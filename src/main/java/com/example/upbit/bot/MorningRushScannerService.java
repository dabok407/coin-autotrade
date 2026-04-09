package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.upbit.UpbitAccount;
import com.example.upbit.upbit.UpbitPrivateClient;

import com.example.upbit.market.PriceUpdateListener;
import com.example.upbit.market.SharedPriceService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;

import javax.annotation.PreDestroy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 모닝 러쉬 스캐너 — 09:00 KST 갭업 스파이크를 잡는 단기 스캐너.
 *
 * 흐름:
 * 1. 08:50 ~ 09:00 — Ticker API를 1초마다 폴링하며 현재가(trade_price)의 최대값을
 *                    rangeHigh로 추적. (10분 prelude window)
 * 2. 09:00 ~ 09:05 — WebSocket 실시간 가격 피드로 갭업 확인
 *    - price > rangeHigh × (1 + gapThreshold%) 를 confirmCount 연속 통과 → BUY
 *    - (또는 surge 윈도우 내 최저가 대비 surgeThreshold% 이상 상승)
 *    - 24시간 거래대금 > 기준 거래대금 × volumeMult
 * 3. 09:05 ~ session_end — WebSocket 실시간 TP/SL 모니터링
 * 4. session_end (DB 컬럼, V104 기준 11:30) — 강제 청산
 *
 * 페이즈 타이밍은 V105부터 DB 컬럼화 (range/entry start/end 컬럼 참조).
 * WebSocket: SharedPriceService 글로벌 리스너 (Upbit ticker)
 * Fallback: SharedPriceService에 가격 데이터 없으면 Ticker REST 폴링
 */
@Service
public class MorningRushScannerService {

    private static final Logger log = LoggerFactory.getLogger(MorningRushScannerService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String ENTRY_STRATEGY = "MORNING_RUSH";

    // ========== Dependencies ==========

    private final MorningRushConfigRepository configRepo;
    private final BotConfigRepository botConfigRepo;
    private final PositionRepository positionRepo;
    private final TradeRepository tradeLogRepo;
    private final LiveOrderService liveOrders;
    private final UpbitPrivateClient privateClient;
    private final TransactionTemplate txTemplate;
    private final UpbitMarketCatalogService catalogService;
    private final TickerService tickerService;
    private final SharedPriceService sharedPriceService;

    // ========== Runtime state ==========

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

    // 실시간 TP/SL 매도 전용 스레드 (mainLoop과 독립)
    private final ExecutorService tpSlExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "morning-rush-tp-sell");
            t.setDaemon(true);
            return t;
        }
    });

    // Dashboard state
    private volatile String statusText = "STOPPED";
    private volatile int scanCount = 0;
    private volatile int activePositions = 0;
    private volatile List<String> lastScannedMarkets = Collections.emptyList();
    private volatile long lastTickEpochMs = 0;

    // Price tracking for gap-up confirmation
    private final ConcurrentHashMap<String, Double> rangeHighMap = new ConcurrentHashMap<String, Double>();
    private final ConcurrentHashMap<String, Double> baselineVolume = new ConcurrentHashMap<String, Double>();
    private final ConcurrentHashMap<String, Integer> confirmCounts = new ConcurrentHashMap<String, Integer>();

    // WebSocket real-time prices
    private final ConcurrentHashMap<String, Double> latestPrices = new ConcurrentHashMap<String, Double>();
    // 실시간 TP/SL 체크용 캐시 (mainLoop에서 업데이트, WebSocket 스레드에서 읽기)
    // positionCache value: [avgPrice, qty, openedAtEpochMs, _, _]
    private final ConcurrentHashMap<String, double[]> positionCache = new ConcurrentHashMap<String, double[]>();
    // SL 종합안 캐시 (DB 설정값, mainLoop에서 갱신)
    private volatile long cachedGracePeriodMs = 60_000L;       // 매수 후 그레이스 (DB grace_period_sec)
    private volatile long cachedWidePeriodMs = 5 * 60_000L;    // SL_WIDE 지속 시간 (DB wide_period_min)
    private volatile double cachedWideSlPct = 5.0;             // SL_WIDE 값 (DB wide_sl_pct)
    private volatile double cachedTpPct = 2.0;
    private volatile double cachedSlPct = 3.0;                 // SL_TIGHT (DB sl_pct)
    private volatile String cachedMode = "PAPER";
    private volatile double cachedGapPct = 2.0;
    private volatile double cachedSurgePct = 3.0;
    private volatile int cachedSurgeWindowSec = 30;
    private volatile int cachedConfirmCount = 3;
    // V105: 페이즈 타이밍 캐시 (DB 설정값, mainLoop에서 갱신)
    private volatile int cachedRangeStartMin = 8 * 60 + 50;   // range 수집 시작 (분 단위)
    private volatile int cachedEntryStartMin = 9 * 60;         // 진입 시작 (분 단위)
    private volatile int cachedEntryEndMin = 9 * 60 + 5;       // 진입 종료 (분 단위, exclusive)
    // Surge detection: market → deque of (epochMs, price) for rolling window
    private final ConcurrentHashMap<String, Deque<long[]>> priceHistory = new ConcurrentHashMap<String, Deque<long[]>>();
    private volatile PriceUpdateListener priceListener;

    // Decision log
    private static final int MAX_DECISION_LOG = 200;
    private final Deque<Map<String, Object>> decisionLog = new ArrayDeque<Map<String, Object>>();

    // Hourly trade throttle: 3개 스캐너가 공유하는 단일 인스턴스
    // (2026-04-08 KRW-TREE 사고: 분리된 throttle로 같은 코인 동시 매수 발생)
    private final SharedTradeThrottle hourlyThrottle;

    // 진행 중인 매수/매도 마켓 (race condition 차단용 in-flight set)
    // (2026-04-09 KRW-CBK 사고: 같은 스캐너의 두 thread morning-rush-0/1 동시 매수)
    // ConcurrentHashMap.newKeySet() = lock-free atomic add (CAS 기반)
    private final java.util.Set<String> buyingMarkets = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> sellingMarkets = ConcurrentHashMap.newKeySet();

    // Session phase tracking
    private volatile boolean rangeCollected = false;
    private volatile boolean entryPhaseComplete = false;

    public MorningRushScannerService(MorningRushConfigRepository configRepo,
                                      BotConfigRepository botConfigRepo,
                                      PositionRepository positionRepo,
                                      TradeRepository tradeLogRepo,
                                      LiveOrderService liveOrders,
                                      UpbitPrivateClient privateClient,
                                      TransactionTemplate txTemplate,
                                      UpbitMarketCatalogService catalogService,
                                      TickerService tickerService,
                                      SharedPriceService sharedPriceService,
                                      SharedTradeThrottle hourlyThrottle) {
        this.configRepo = configRepo;
        this.botConfigRepo = botConfigRepo;
        this.positionRepo = positionRepo;
        this.tradeLogRepo = tradeLogRepo;
        this.liveOrders = liveOrders;
        this.privateClient = privateClient;
        this.txTemplate = txTemplate;
        this.catalogService = catalogService;
        this.tickerService = tickerService;
        this.sharedPriceService = sharedPriceService;
        this.hourlyThrottle = hourlyThrottle;
    }

    // ========== Lifecycle ==========

    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            log.info("[MorningRush] already running");
            return false;
        }
        log.info("[MorningRush] starting...");
        statusText = "RUNNING";
        rangeCollected = false;
        entryPhaseComplete = false;
        confirmCounts.clear();
        rangeHighMap.clear();
        baselineVolume.clear();
        latestPrices.clear();
        priceHistory.clear();

        scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory() {
            private int seq = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "morning-rush-" + (seq++));
                t.setDaemon(true);
                return t;
            }
        });

        // Register SharedPriceService listener for realtime price updates
        priceListener = new PriceUpdateListener() {
            @Override
            public void onPriceUpdate(String market, double price) {
                long tsMs = System.currentTimeMillis();
                latestPrices.put(market, price);

                // Record price for surge detection (only if tracking this market)
                Deque<long[]> history = priceHistory.get(market);
                if (history != null) {
                    history.addLast(new long[]{tsMs, Double.doubleToLongBits(price)});
                    // Keep max 120 seconds of data
                    long cutoff = tsMs - 120_000L;
                    while (!history.isEmpty() && history.peekFirst()[0] < cutoff) {
                        history.pollFirst();
                    }
                }

                // Real-time entry check (entry phase, memory only)
                checkRealtimeEntry(market, price, tsMs);

                // Real-time TP/SL check (hold phase, memory cache only)
                checkRealtimeTpSl(market, price);
            }
        };
        sharedPriceService.addGlobalListener(priceListener);

        // Schedule the main loop at 1-second resolution to detect time phases
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    mainLoop();
                } catch (Exception e) {
                    log.error("[MorningRush] main loop error", e);
                }
            }
        }, 0, 1, TimeUnit.SECONDS);

        return true;
    }

    public boolean stop() {
        if (!running.compareAndSet(true, false)) {
            log.info("[MorningRush] already stopped");
            return false;
        }
        log.info("[MorningRush] stopping...");
        statusText = "STOPPED";
        if (priceListener != null) {
            sharedPriceService.removeGlobalListener(priceListener);
            priceListener = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        return true;
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    // ========== Status accessors ==========

    public boolean isRunning() { return running.get(); }
    public String getStatusText() { return statusText; }
    public int getScanCount() { return scanCount; }
    public int getActivePositions() { return activePositions; }
    public List<String> getLastScannedMarkets() { return lastScannedMarkets; }
    public long getLastTickEpochMs() { return lastTickEpochMs; }

    public List<Map<String, Object>> getRecentDecisions(int limit) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        synchronized (decisionLog) {
            int count = 0;
            for (Map<String, Object> d : decisionLog) {
                if (count >= limit) break;
                list.add(d);
                count++;
            }
        }
        return list;
    }

    // ========== Price Service Integration ==========

    /**
     * WebSocket 가격 수신 시 즉시 갭/급등 진입 체크 (entry phase).
     * DB 접근 없이 메모리(rangeHighMap, priceHistory, confirmCounts)만 사용.
     * 조건 충족 시 scheduler에 매수 스케줄링.
     */
    private void checkRealtimeEntry(String code, double price, long tsMs) {
        if (!running.get() || rangeHighMap.isEmpty()) return;
        // entry phase 체크 (V105: DB 설정값 사용, mainLoop에서 캐시 갱신됨)
        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        int nowMin = nowKst.getHour() * 60 + nowKst.getMinute();
        if (nowMin < cachedEntryStartMin || nowMin >= cachedEntryEndMin) return;
        if (entryPhaseComplete) return;

        // 이미 확인된 마켓 스킵
        if (positionCache.containsKey(code)) return;

        Double rangeHigh = rangeHighMap.get(code);
        if (rangeHigh == null || rangeHigh <= 0) return;

        // 거래대금 체크 (메모리)
        Double baseVol = baselineVolume.get(code);
        if (baseVol == null || baseVol < 1000000000L) return; // minTradeAmount

        double gapThreshold = cachedGapPct / 100.0;
        double surgeThreshold = cachedSurgePct / 100.0;
        double threshold = rangeHigh * (1.0 + gapThreshold);
        boolean gapCondition = price > threshold;

        // Surge condition (priceHistory 기반)
        boolean surgeCondition = false;
        if (cachedSurgePct > 0) {
            Deque<long[]> history = priceHistory.get(code);
            if (history != null && !history.isEmpty()) {
                long cutoff = tsMs - cachedSurgeWindowSec * 1000L;
                double minInWindow = Double.MAX_VALUE;
                for (long[] entry : history) {
                    if (entry[0] >= cutoff) {
                        double p = Double.longBitsToDouble(entry[1]);
                        if (p < minInWindow) minInWindow = p;
                    }
                }
                if (minInWindow < Double.MAX_VALUE && minInWindow > 0) {
                    double surgePct = (price - minInWindow) / minInWindow;
                    if (surgePct >= surgeThreshold) surgeCondition = true;
                }
            }
        }

        boolean entrySignal = gapCondition || surgeCondition;
        if (!entrySignal) {
            if (confirmCounts.containsKey(code)) confirmCounts.put(code, 0);
            return;
        }

        Integer count = confirmCounts.get(code);
        int newCount = (count != null ? count : 0) + 1;
        confirmCounts.put(code, newCount);

        int requiredConfirm = cachedConfirmCount;
        if (newCount < requiredConfirm) {
            log.debug("[MorningRush] realtime {} confirm {}/{} price={} gap={} surge={}",
                    code, newCount, requiredConfirm, price, gapCondition, surgeCondition);
            return;
        }

        // Confirmed! scheduler에 매수 스케줄링
        double gapPct = (price - rangeHigh) / rangeHigh * 100.0;
        final String triggerType = (gapCondition && surgeCondition) ? "GAP+SURGE" :
                (gapCondition ? "GAP_UP" : "SURGE");
        final String reason = String.format(Locale.ROOT,
                "%s price=%.2f rangeHigh=%.2f gap=+%.2f%% confirm=%d/%d (realtime)",
                triggerType, price, rangeHigh, gapPct, newCount, requiredConfirm);

        log.info("[MorningRush] realtime BUY signal: {} | {}", code, reason);

        confirmCounts.remove(code);
        // 중복 진입 방지용 placeholder (실제 매수는 실패해도 OK)
        // 매수 성공 시 920행에서 정확한 데이터로 갱신됨
        positionCache.put(code, new double[]{price, 0, System.currentTimeMillis(), price, 0});

        final String fCode = code;
        final double fPrice = price;
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    // ★ 1차 race 방어: in-flight 매수 차단 (lock-free atomic add)
                    // 2026-04-09 KRW-CBK 사고 재발 방지 — 두 thread morning-rush-0/1 동시 매수
                    if (!buyingMarkets.add(fCode)) {
                        log.info("[MorningRush] BUY in progress, skip duplicate task: {}", fCode);
                        return;
                    }
                    boolean throttleClaimed = false;
                    try {
                        MorningRushConfigEntity cfg = configRepo.loadOrCreate();
                        if (!cfg.isEnabled()) return;
                        String mode = cfg.getMode();
                        boolean isLive = "LIVE".equalsIgnoreCase(mode);

                        // 포지션 수 체크
                        int rushPosCount = 0;
                        List<PositionEntity> allPos = positionRepo.findAll();
                        for (PositionEntity pe : allPos) {
                            if (ENTRY_STRATEGY.equals(pe.getEntryStrategy())
                                    && pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                                rushPosCount++;
                            }
                            if (fCode.equals(pe.getMarket()) && pe.getQty() != null
                                    && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                                log.info("[MorningRush] realtime skip: {} already held", fCode);
                                return;
                            }
                        }
                        if (rushPosCount >= cfg.getMaxPositions()) {
                            log.info("[MorningRush] realtime skip: max positions {} reached", cfg.getMaxPositions());
                            addDecision(fCode, "BUY", "BLOCKED", "MAX_POSITIONS",
                                    String.format("최대 포지션 수(%d) 도달 (realtime)", cfg.getMaxPositions()));
                            return;
                        }

                        BigDecimal orderKrw = calcOrderSize(cfg);
                        // ★ 2차 race 방어: tryClaim atomic (canBuy + recordBuy를 synchronized로 한 번에)
                        if (!hourlyThrottle.tryClaim(fCode)) {
                            addDecision(fCode, "BUY", "BLOCKED", "HOURLY_LIMIT",
                                    String.format("1시간 내 최대 2회 매매 제한 (남은 대기: %ds)", hourlyThrottle.remainingWaitMs(fCode) / 1000));
                            return;
                        }
                        throttleClaimed = true;
                        try {
                            executeBuy(fCode, fPrice, reason, cfg, orderKrw, isLive);
                            addDecision(fCode, "BUY", "EXECUTED", reason);
                        } catch (Exception e) {
                            // 매수 실패 → throttle 권한 반환 (다음 시도 가능하도록)
                            hourlyThrottle.releaseClaim(fCode);
                            throttleClaimed = false;
                            throw e;
                        }
                    } catch (Exception e) {
                        log.error("[MorningRush] realtime buy failed for {}", fCode, e);
                    } finally {
                        buyingMarkets.remove(fCode);
                    }
                }
            }, 0, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * WebSocket 가격 수신 시 즉시 TP/SL 체크 (지연 0초).
     * DB 접근 없이 메모리 캐시만 사용. mainLoop에서 캐시 업데이트.
     * TP/SL 도달 감지 시 mainLoop에 매도 스케줄링.
     *
     * 매도 로직:
     *  - TP: 도달 즉시 매도 (트레일링 없음 — 변동성 급락 위험 + 슬리피지 고려)
     *  - SL 종합안:
     *    · 0~60초:   1분 그레이스 (SL 무시)
     *    · 60s~5분:  SL 5% (흔들기 보호)
     *    · 5분 이후: SL 3% (타이트닝)
     */
    private void checkRealtimeTpSl(String market, double price) {
        if (!running.get()) return;

        // 메모리 캐시에서 포지션 확인 (DB 접근 없음)
        double[] pos = positionCache.get(market);
        if (pos == null) return;

        double avgPrice = pos[0];
        if (avgPrice <= 0) return;
        long openedAtMs = (long) pos[2];

        double pnlPct = (price - avgPrice) / avgPrice * 100.0;
        long elapsedMs = System.currentTimeMillis() - openedAtMs;

        String sellType = null;
        String reason = null;

        // 1. TP 체크 — 도달 즉시 매도 (단순 TP, 트레일링 없음)
        if (pnlPct >= cachedTpPct) {
            sellType = "TP";
            reason = String.format(Locale.ROOT,
                    "TP pnl=%.2f%% >= %.2f%% price=%.2f avg=%.2f (realtime)",
                    pnlPct, cachedTpPct, price, avgPrice);
        }
        // 2. SL 종합안 (TP 미달 시) — DB 설정값 사용
        else if (elapsedMs < cachedGracePeriodMs) {
            // 그레이스 — SL 무시
        } else if (elapsedMs < cachedWidePeriodMs) {
            // 그레이스 후 ~ wide_period: SL_WIDE (흔들기 보호)
            if (pnlPct <= -cachedWideSlPct) {
                sellType = "SL";
                reason = String.format(Locale.ROOT,
                        "SL_WIDE pnl=%.2f%% <= -%.2f%% price=%.2f avg=%.2f (realtime)",
                        pnlPct, cachedWideSlPct, price, avgPrice);
            }
        } else {
            // wide_period 이후: SL_TIGHT
            if (pnlPct <= -cachedSlPct) {
                sellType = "SL";
                reason = String.format(Locale.ROOT,
                        "SL_TIGHT pnl=%.2f%% <= -%.2f%% price=%.2f avg=%.2f (realtime)",
                        pnlPct, cachedSlPct, price, avgPrice);
            }
        }

        if (sellType == null) return;

        // ★ 1차 race 방어: in-flight 매도 차단 (lock-free atomic add)
        // 2026-04-09 KRW-CBK 사고 — mainLoop SL 체크와 WS realtime SL 체크 두 path 동시 매도
        if (!sellingMarkets.add(market)) {
            log.debug("[MorningRush] SELL in progress, skip duplicate trigger: {}", market);
            return;
        }

        // 캐시에서 제거 (중복 매도 방지)
        positionCache.remove(market);

        final String fSellType = sellType;
        final String fReason = reason;
        log.info("[MorningRush] realtime {} detected | {} | {}", fSellType, market, fReason);

        // 매도 전용 스레드에서 즉시 실행 (mainLoop과 독립, DB 접근 안전)
        final String fMarket = market;
        final double fPrice = price;
        tpSlExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    PositionEntity fresh = positionRepo.findById(fMarket).orElse(null);
                    if (fresh == null || fresh.getQty() == null
                            || fresh.getQty().compareTo(BigDecimal.ZERO) <= 0) return;
                    if (!ENTRY_STRATEGY.equals(fresh.getEntryStrategy())) return;
                    MorningRushConfigEntity cfg = configRepo.loadOrCreate();
                    double freshPnl = (fPrice - fresh.getAvgPrice().doubleValue())
                            / fresh.getAvgPrice().doubleValue() * 100.0;
                    executeSell(fresh, fPrice, freshPnl, fReason, fSellType, cfg);
                } catch (Exception e) {
                    log.error("[MorningRush] realtime sell failed for {}", fMarket, e);
                } finally {
                    sellingMarkets.remove(fMarket);
                }
            }
        });
    }

    /**
     * mainLoop에서 호출: 포지션+설정 캐시 업데이트.
     */
    private void updatePositionCache(MorningRushConfigEntity cfg) {
        cachedTpPct = cfg.getTpPct().doubleValue();
        cachedSlPct = cfg.getSlPct().doubleValue();
        cachedGracePeriodMs = cfg.getGracePeriodSec() * 1000L;
        cachedWidePeriodMs = cfg.getWidePeriodMin() * 60_000L;
        cachedWideSlPct = cfg.getWideSlPct().doubleValue();
        cachedMode = cfg.getMode();

        positionCache.clear();
        try {
            List<PositionEntity> allPos = positionRepo.findAll();
            for (PositionEntity pe : allPos) {
                if (ENTRY_STRATEGY.equals(pe.getEntryStrategy())
                        && pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0
                        && pe.getAvgPrice() != null) {
                    // 복구 시 openedAt 사용 (없으면 현재 시각 - 그레이스 효과는 없음)
                    long openedAtMs = pe.getOpenedAt() != null
                            ? pe.getOpenedAt().toEpochMilli()
                            : System.currentTimeMillis();
                    double avg = pe.getAvgPrice().doubleValue();
                    positionCache.put(pe.getMarket(),
                            new double[]{avg, pe.getQty().doubleValue(), openedAtMs, avg, 0});
                }
            }
        } catch (Exception e) {
            log.debug("[MorningRush] position cache update failed: {}", e.getMessage());
        }
    }

    /**
     * SharedPriceService 또는 REST API에서 실시간 가격을 가져온다.
     */
    private Map<String, Double> getCurrentPrices(List<String> markets) {
        // SharedPriceService의 latestPrices에서 우선 조회
        Map<String, Double> result = new LinkedHashMap<String, Double>();
        for (String market : markets) {
            Double price = latestPrices.get(market);
            if (price != null && price > 0) {
                result.put(market, price);
            }
        }

        // SharedPriceService에 아직 데이터가 없으면 REST fallback
        if (result.isEmpty()) {
            log.debug("[MorningRush] no cached prices yet, using REST fallback");
            return tickerService.getTickerPrices(markets);
        }

        return result;
    }

    // ========== Main Loop ==========

    /**
     * 1초마다 실행되는 메인 루프. KST 시각에 따라 다른 동작 수행.
     * Entry/Hold 페이즈에서는 WebSocket 가격 피드를 사용하며,
     * checkIntervalSec 간격으로만 매매 판단을 실행 (스로틀링).
     */
    private void mainLoop() {
        if (!running.get()) return;

        MorningRushConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isEnabled()) {
            statusText = "DISABLED";
            return;
        }
        // 실시간 체크용 config 캐시 업데이트
        cachedTpPct = cfg.getTpPct().doubleValue();
        cachedSlPct = cfg.getSlPct().doubleValue();
        cachedGracePeriodMs = cfg.getGracePeriodSec() * 1000L;
        cachedWidePeriodMs = cfg.getWidePeriodMin() * 60_000L;
        cachedWideSlPct = cfg.getWideSlPct().doubleValue();
        cachedGapPct = cfg.getGapThresholdPct().doubleValue();
        cachedSurgePct = cfg.getSurgeThresholdPct().doubleValue();
        cachedSurgeWindowSec = cfg.getSurgeWindowSec();
        cachedConfirmCount = cfg.getConfirmCount();
        cachedMode = cfg.getMode();
        // V105: 페이즈 타이밍 캐시 갱신 (DB 설정값)
        cachedRangeStartMin = cfg.getRangeStartHour() * 60 + cfg.getRangeStartMin();
        cachedEntryStartMin = cfg.getEntryStartHour() * 60 + cfg.getEntryStartMin();
        cachedEntryEndMin = cfg.getEntryEndHour() * 60 + cfg.getEntryEndMin();

        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        int hour = nowKst.getHour();
        int minute = nowKst.getMinute();
        int nowMinOfDay = hour * 60 + minute;

        int sessionEndMin = cfg.getSessionEndHour() * 60 + cfg.getSessionEndMin();

        // Phase timing (V105: DB 설정값 사용)
        boolean isRangePhase = (nowMinOfDay >= cachedRangeStartMin) && (nowMinOfDay < cachedEntryStartMin);
        boolean isEntryPhase = (nowMinOfDay >= cachedEntryStartMin) && (nowMinOfDay < cachedEntryEndMin);
        boolean isHoldPhase = (nowMinOfDay >= cachedEntryEndMin) && (nowMinOfDay < sessionEndMin);
        boolean isSessionEnd = (nowMinOfDay >= sessionEndMin);

        // Update active position count
        int rushPosCount = 0;
        List<PositionEntity> allPos = positionRepo.findAll();
        for (PositionEntity pe : allPos) {
            if (ENTRY_STRATEGY.equals(pe.getEntryStrategy())
                    && pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                rushPosCount++;
            }
        }
        activePositions = rushPosCount;

        // Session end: force exit all morning rush positions
        if (isSessionEnd && rushPosCount > 0) {
            statusText = "SESSION_END";
            forceExitAll(cfg);
            return;
        }

        if (!isRangePhase && !isEntryPhase && !isHoldPhase) {
            // Outside operating hours — reset for next day
            if (rangeCollected || entryPhaseComplete) {
                rangeCollected = false;
                entryPhaseComplete = false;
                confirmCounts.clear();
                rangeHighMap.clear();
                baselineVolume.clear();
                latestPrices.clear();
            }
            statusText = "IDLE (outside hours)";
            return;
        }

        // Throttle: only run trade logic at checkIntervalSec frequency
        long nowMs = System.currentTimeMillis();
        int intervalSec = cfg.getCheckIntervalSec();
        if (nowMs - lastTickEpochMs < intervalSec * 1000L) {
            return; // skip this second
        }
        lastTickEpochMs = nowMs;

        // ---- Range Phase: collect baseline prices ----
        if (isRangePhase) {
            statusText = "COLLECTING_RANGE";
            collectRange(cfg);
            return;
        }

        // ---- Entry Phase: scan for gap-up spikes ----
        if (isEntryPhase && !entryPhaseComplete) {
            statusText = "SCANNING";
            scanForEntry(cfg);
            return;
        }

        // ---- Hold Phase: TP/SL monitoring ----
        if (isHoldPhase) {
            if (!entryPhaseComplete) {
                entryPhaseComplete = true;
                log.info("[MorningRush] Entry phase complete, switching to MONITORING");
            }
            statusText = "MONITORING";
            // 포지션+설정 캐시 업데이트 (실시간 TP/SL 체크용)
            updatePositionCache(cfg);
            monitorPositions(cfg);
            return;
        }
    }


    // ========== Phase 1: Range Collection ==========

    private void collectRange(MorningRushConfigEntity cfg) {
        if (rangeCollected) return;

        Set<String> excludeSet = cfg.getExcludeMarketsSet();

        // Get all KRW markets
        Set<String> allMarkets = catalogService.getAllMarketCodes();
        List<String> krwMarkets = new ArrayList<String>();
        for (String m : allMarkets) {
            if (m.startsWith("KRW-") && !excludeSet.contains(m)) {
                krwMarkets.add(m);
            }
        }

        if (krwMarkets.isEmpty()) {
            log.warn("[MorningRush] no KRW markets found");
            return;
        }

        // Fetch tickers to get current prices and 24h trade amounts (REST — range phase)
        List<UpbitMarketCatalogService.TickerItem> tickers = catalogService.fetchTickers(krwMarkets);

        // Filter by min trade amount and min price, then select top N
        List<UpbitMarketCatalogService.TickerItem> filtered = new ArrayList<UpbitMarketCatalogService.TickerItem>();
        for (UpbitMarketCatalogService.TickerItem t : tickers) {
            if (t.acc_trade_price_24h < cfg.getMinTradeAmount()) continue;
            if (cfg.getMinPriceKrw() > 0 && t.trade_price < cfg.getMinPriceKrw()) continue;
            if (excludeSet.contains(t.market)) continue;
            filtered.add(t);
        }

        // Sort by 24h trade amount descending
        Collections.sort(filtered, new Comparator<UpbitMarketCatalogService.TickerItem>() {
            @Override
            public int compare(UpbitMarketCatalogService.TickerItem a, UpbitMarketCatalogService.TickerItem b) {
                return Double.compare(b.acc_trade_price_24h, a.acc_trade_price_24h);
            }
        });

        int topN = cfg.getTopN();
        List<String> selectedMarkets = new ArrayList<String>();
        for (int i = 0; i < Math.min(topN, filtered.size()); i++) {
            UpbitMarketCatalogService.TickerItem t = filtered.get(i);
            selectedMarkets.add(t.market);
            // Use current price as rangeHigh baseline; during 08:50-09:00 we update to max seen
            Double existing = rangeHighMap.get(t.market);
            if (existing == null || t.trade_price > existing) {
                rangeHighMap.put(t.market, t.trade_price);
            }
            baselineVolume.put(t.market, t.acc_trade_price_24h);
        }

        lastScannedMarkets = selectedMarkets;
        scanCount = selectedMarkets.size();

        log.info("[MorningRush] range collection: {} markets, top={}", selectedMarkets.size(),
                selectedMarkets.isEmpty() ? "none" : selectedMarkets.get(0));

        // If we're in the last minute of range phase, mark collected and start WebSocket
        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        if (nowKst.getMinute() >= 59 || (nowKst.getHour() == 8 && nowKst.getMinute() >= 58)) {
            rangeCollected = true;
            addDecision("*", "RANGE", "COLLECTED",
                    String.format("레인지 수집 완료: %d개 마켓, rangeHigh 설정됨", selectedMarkets.size()));
            log.info("[MorningRush] range collection complete: {} markets, subscribing to SharedPriceService", selectedMarkets.size());

            // Ensure SharedPriceService subscribes to our selected markets
            sharedPriceService.ensureMarketsSubscribed(selectedMarkets);
        }
    }

    // ========== Phase 2: Entry Scanning ==========

    private void scanForEntry(MorningRushConfigEntity cfg) {
        if (rangeHighMap.isEmpty()) {
            // Fallback: if range wasn't collected (scanner started after 08:50), do it now
            collectRange(cfg);
            rangeCollected = true;
            if (rangeHighMap.isEmpty()) {
                addDecision("*", "BUY", "BLOCKED", "NO_RANGE",
                        "레인지 데이터 수집 실패 — 스캔 불가");
                return;
            }
        }

        String mode = cfg.getMode();
        boolean isLive = "LIVE".equalsIgnoreCase(mode);

        // LIVE mode: check API key
        if (isLive && !liveOrders.isConfigured()) {
            statusText = "ERROR (API key)";
            addDecision("*", "BUY", "BLOCKED", "API_KEY_MISSING",
                    "LIVE 모드인데 업비트 API 키가 설정되지 않았습니다.");
            return;
        }

        // Count existing morning rush positions
        int rushPosCount = 0;
        Set<String> ownedMarkets = new HashSet<String>();
        List<PositionEntity> allPos = positionRepo.findAll();
        for (PositionEntity pe : allPos) {
            if (pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                ownedMarkets.add(pe.getMarket());
                if (ENTRY_STRATEGY.equals(pe.getEntryStrategy())) {
                    rushPosCount++;
                }
            }
        }

        if (rushPosCount >= cfg.getMaxPositions()) {
            addDecision("*", "BUY", "BLOCKED", "MAX_POSITIONS",
                    String.format("최대 포지션 수(%d) 도달", cfg.getMaxPositions()));
            return;
        }

        // LIVE: check available KRW
        double availableKrw = Double.MAX_VALUE;
        if (isLive && privateClient.isConfigured()) {
            try {
                List<UpbitAccount> accounts = privateClient.getAccounts();
                if (accounts != null) {
                    for (UpbitAccount a : accounts) {
                        if ("KRW".equals(a.currency)) {
                            availableKrw = a.balanceAsBigDecimal().doubleValue();
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[MorningRush] 잔고 조회 실패", e);
            }
        }

        BigDecimal orderKrw = calcOrderSize(cfg);

        // Global Capital check
        BigDecimal globalCap = getGlobalCapitalKrw();
        double totalInvested = calcTotalInvestedAllPositions();
        double remainingBudget = Math.max(0, globalCap.doubleValue() - totalInvested);
        if (orderKrw.doubleValue() > remainingBudget) {
            if (remainingBudget >= 5000) {
                orderKrw = BigDecimal.valueOf(remainingBudget).setScale(0, RoundingMode.DOWN);
            } else {
                addDecision("*", "BUY", "BLOCKED", "CAPITAL_LIMIT",
                        String.format("Global Capital 한도 초과: 투입 %.0f원 / 한도 %s원",
                                totalInvested, globalCap.toPlainString()));
                return;
            }
        }

        // Initialize surge history deques for all tracked markets (idempotent)
        for (String m : rangeHighMap.keySet()) {
            if (!priceHistory.containsKey(m)) {
                priceHistory.put(m, new ArrayDeque<long[]>());
            }
        }

        // Fetch current prices via SharedPriceService (or REST fallback)
        List<String> trackedMarkets = new ArrayList<String>(rangeHighMap.keySet());
        Map<String, Double> currentPrices = getCurrentPrices(trackedMarkets);
        if (currentPrices.isEmpty()) {
            log.warn("[MorningRush] price fetch returned empty (shared={})",
                    sharedPriceService.isConnected());
            return;
        }

        double gapThreshold = cfg.getGapThresholdPct().doubleValue() / 100.0;
        double volumeMultiplier = cfg.getVolumeMult().doubleValue();
        int requiredConfirm = cfg.getConfirmCount();
        double surgeThreshold = cfg.getSurgeThresholdPct().doubleValue() / 100.0;
        int surgeWindowSec = cfg.getSurgeWindowSec();
        boolean surgeEnabled = surgeThreshold > 0;
        long nowMs2 = System.currentTimeMillis();

        // Check each market for gap-up OR mid-candle surge
        for (Map.Entry<String, Double> entry : currentPrices.entrySet()) {
            String market = entry.getKey();
            double price = entry.getValue();

            if (ownedMarkets.contains(market)) continue;
            if (rushPosCount >= cfg.getMaxPositions()) break;

            Double rangeHigh = rangeHighMap.get(market);
            if (rangeHigh == null || rangeHigh <= 0) continue;

            // Volume check: we use baseline 24h volume collected during range phase
            Double baseVol = baselineVolume.get(market);
            boolean volumeOk = (baseVol != null && baseVol >= cfg.getMinTradeAmount());
            if (!volumeOk) continue;

            // --- Condition 1: Gap-up (price > rangeHigh × (1 + gapThreshold)) ---
            double threshold = rangeHigh * (1.0 + gapThreshold);
            boolean gapCondition = price > threshold;

            // --- Condition 2: Mid-candle surge (price rose surgeThreshold% within surgeWindowSec) ---
            boolean surgeCondition = false;
            double surgeFromPrice = 0;
            if (surgeEnabled) {
                Deque<long[]> history = priceHistory.get(market);
                if (history == null) {
                    history = new ArrayDeque<long[]>();
                    priceHistory.put(market, history);
                }
                // Record current price
                history.addLast(new long[]{nowMs2, Double.doubleToLongBits(price)});
                // Trim entries outside the window
                long cutoff = nowMs2 - surgeWindowSec * 1000L;
                while (!history.isEmpty() && history.peekFirst()[0] < cutoff) {
                    history.pollFirst();
                }
                // Find min price in the window
                double minInWindow = Double.MAX_VALUE;
                for (long[] entry2 : history) {
                    double p = Double.longBitsToDouble(entry2[1]);
                    if (p < minInWindow) minInWindow = p;
                }
                if (minInWindow < Double.MAX_VALUE && minInWindow > 0) {
                    double surgePct = (price - minInWindow) / minInWindow;
                    if (surgePct >= surgeThreshold) {
                        surgeCondition = true;
                        surgeFromPrice = minInWindow;
                    }
                }
            }

            // Hybrid: either gap OR surge triggers entry
            boolean entrySignal = gapCondition || surgeCondition;

            if (entrySignal) {
                Integer count = confirmCounts.get(market);
                int newCount = (count != null ? count : 0) + 1;
                confirmCounts.put(market, newCount);

                if (newCount >= requiredConfirm) {
                    // Confirmed! Execute BUY
                    String triggerType;
                    String reason;
                    if (gapCondition && surgeCondition) {
                        double gapPct = (price - rangeHigh) / rangeHigh * 100.0;
                        double surgePct2 = (price - surgeFromPrice) / surgeFromPrice * 100.0;
                        triggerType = "GAP+SURGE";
                        reason = String.format(Locale.ROOT,
                                "%s price=%.2f rangeHigh=%.2f gap=+%.2f%% surge=+%.2f%%(%ds) confirm=%d/%d",
                                triggerType, price, rangeHigh, gapPct, surgePct2, surgeWindowSec, newCount, requiredConfirm);
                    } else if (gapCondition) {
                        double gapPct = (price - rangeHigh) / rangeHigh * 100.0;
                        triggerType = "GAP_UP";
                        reason = String.format(Locale.ROOT,
                                "%s price=%.2f rangeHigh=%.2f gap=+%.2f%% confirm=%d/%d",
                                triggerType, price, rangeHigh, gapPct, newCount, requiredConfirm);
                    } else {
                        double surgePct2 = (price - surgeFromPrice) / surgeFromPrice * 100.0;
                        triggerType = "SURGE";
                        reason = String.format(Locale.ROOT,
                                "%s price=%.2f from=%.2f surge=+%.2f%%(%ds) confirm=%d/%d",
                                triggerType, price, surgeFromPrice, surgePct2, surgeWindowSec, newCount, requiredConfirm);
                    }

                    log.info("[MorningRush] BUY signal confirmed: {} | {}", market, reason);

                    // Hourly throttle check
                    if (!hourlyThrottle.canBuy(market)) {
                        addDecision(market, "BUY", "BLOCKED", "HOURLY_LIMIT",
                                String.format("1시간 내 최대 2회 매매 제한 (남은 대기: %ds)", hourlyThrottle.remainingWaitMs(market) / 1000));
                        confirmCounts.remove(market);
                        priceHistory.remove(market);
                        continue;
                    }

                    try {
                        executeBuy(market, price, reason, cfg, orderKrw, isLive);
                        hourlyThrottle.recordBuy(market);
                        rushPosCount++;
                        // 실시간 TP/SL 캐시에 추가 (SL 종합안: openedAt + peak)
                        long openedAtMs = System.currentTimeMillis();
                        positionCache.put(market, new double[]{price, 0, openedAtMs, price, 0});
                        cachedTpPct = cfg.getTpPct().doubleValue();
                        cachedSlPct = cfg.getSlPct().doubleValue();
                        addDecision(market, "BUY", "EXECUTED", reason);
                    } catch (Exception e) {
                        log.error("[MorningRush] buy execution failed for {}", market, e);
                        addDecision(market, "BUY", "ERROR", "매수 실행 오류: " + e.getMessage());
                    }

                    // Remove from tracking to prevent duplicate entries
                    confirmCounts.remove(market);
                    priceHistory.remove(market);
                    ownedMarkets.add(market);
                } else {
                    log.debug("[MorningRush] {} entry confirm {}/{} (gap={} surge={})",
                            market, newCount, requiredConfirm, gapCondition, surgeCondition);
                }
            } else {
                // Reset confirm count if neither condition met
                if (confirmCounts.containsKey(market)) {
                    confirmCounts.put(market, 0);
                }
            }
        }
    }

    // ========== Phase 3: TP/SL Monitoring ==========

    private void monitorPositions(MorningRushConfigEntity cfg) {
        List<PositionEntity> allPos = positionRepo.findAll();
        List<PositionEntity> rushPositions = new ArrayList<PositionEntity>();
        List<String> markets = new ArrayList<String>();

        for (PositionEntity pe : allPos) {
            if (ENTRY_STRATEGY.equals(pe.getEntryStrategy())
                    && pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                rushPositions.add(pe);
                markets.add(pe.getMarket());
            }
        }

        if (rushPositions.isEmpty()) return;

        // Get prices via WebSocket (or REST fallback)
        Map<String, Double> prices = getCurrentPrices(markets);
        if (prices.isEmpty()) return;

        double tpPct = cfg.getTpPct().doubleValue();
        // SL 종합안 적용: realtime checkRealtimeTpSl과 동일한 grace+wide+tight 로직
        long gracePeriodMs = cfg.getGracePeriodSec() * 1000L;
        long widePeriodMs = cfg.getWidePeriodMin() * 60_000L;
        double wideSlPct = cfg.getWideSlPct().doubleValue();
        double tightSlPct = cfg.getSlPct().doubleValue();

        for (PositionEntity pe : rushPositions) {
            Double currentPrice = prices.get(pe.getMarket());
            if (currentPrice == null || currentPrice <= 0) continue;

            double avgPrice = pe.getAvgPrice().doubleValue();
            if (avgPrice <= 0) continue;

            double pnlPct = (currentPrice - avgPrice) / avgPrice * 100.0;

            // 진입 후 경과 시간 (SL 종합안 단계 판정용)
            long openedAtMs = pe.getOpenedAt() != null
                    ? pe.getOpenedAt().toEpochMilli()
                    : System.currentTimeMillis();
            long elapsedMs = System.currentTimeMillis() - openedAtMs;

            String sellReason = null;
            String sellType = null;

            // 1. TP 우선
            if (pnlPct >= tpPct) {
                sellReason = String.format(Locale.ROOT,
                        "TP pnl=%.2f%% >= target=%.2f%% price=%.2f avg=%.2f",
                        pnlPct, tpPct, currentPrice, avgPrice);
                sellType = "TP";
            }
            // 2. SL 종합안 — 단계별
            else if (elapsedMs < gracePeriodMs) {
                // 그레이스 — SL 무시
            } else if (elapsedMs < widePeriodMs) {
                // 그레이스 후 ~ wide_period: SL_WIDE (흔들기 보호)
                if (pnlPct <= -wideSlPct) {
                    sellReason = String.format(Locale.ROOT,
                            "SL_WIDE pnl=%.2f%% <= -%.2f%% price=%.2f avg=%.2f elapsed=%ds",
                            pnlPct, wideSlPct, currentPrice, avgPrice, elapsedMs / 1000);
                    sellType = "SL";
                }
            } else {
                // wide_period 이후: SL_TIGHT
                if (pnlPct <= -tightSlPct) {
                    sellReason = String.format(Locale.ROOT,
                            "SL_TIGHT pnl=%.2f%% <= -%.2f%% price=%.2f avg=%.2f elapsed=%ds",
                            pnlPct, tightSlPct, currentPrice, avgPrice, elapsedMs / 1000);
                    sellType = "SL";
                }
            }

            if (sellReason == null) continue;

            // ★ race 방어: WS realtime checkRealtimeTpSl과 mainLoop monitorPositions 둘 다 매도 시도 가능
            // 같은 sellingMarkets Set으로 in-flight 차단 (KRW-CBK 09:30 이중 매도 사고 재발 방지)
            if (!sellingMarkets.add(pe.getMarket())) {
                log.debug("[MorningRush] mainLoop SELL skip, already in progress: {}", pe.getMarket());
                continue;
            }

            log.info("[MorningRush] {} triggered | {} | {}", sellType, pe.getMarket(), sellReason);

            // 캐시에서 제거 (다음 WS tick의 중복 매도 방지)
            positionCache.remove(pe.getMarket());

            try {
                // Re-check position (race condition protection)
                PositionEntity fresh = positionRepo.findById(pe.getMarket()).orElse(null);
                if (fresh == null || fresh.getQty() == null || fresh.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;

                executeSell(fresh, currentPrice, pnlPct, sellReason, sellType, cfg);
            } catch (Exception e) {
                log.error("[MorningRush] sell failed for {}", pe.getMarket(), e);
                addDecision(pe.getMarket(), "SELL", "ERROR", "매도 실행 오류: " + e.getMessage());
            } finally {
                sellingMarkets.remove(pe.getMarket());
            }
        }
    }

    // ========== Session End: Force Exit ==========

    private void forceExitAll(MorningRushConfigEntity cfg) {
        List<PositionEntity> allPos = positionRepo.findAll();
        List<PositionEntity> rushPositions = new ArrayList<PositionEntity>();
        List<String> markets = new ArrayList<String>();

        for (PositionEntity pe : allPos) {
            if (ENTRY_STRATEGY.equals(pe.getEntryStrategy())
                    && pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                rushPositions.add(pe);
                markets.add(pe.getMarket());
            }
        }

        if (rushPositions.isEmpty()) {
            statusText = "IDLE (session ended)";
            return;
        }

        // Try WebSocket prices first, then REST fallback
        Map<String, Double> prices = getCurrentPrices(markets);

        for (PositionEntity pe : rushPositions) {
            Double currentPrice = prices.get(pe.getMarket());
            if (currentPrice == null || currentPrice <= 0) {
                // Use avg price as fallback
                currentPrice = pe.getAvgPrice().doubleValue();
            }

            double avgPrice = pe.getAvgPrice().doubleValue();
            double pnlPct = avgPrice > 0 ? (currentPrice - avgPrice) / avgPrice * 100.0 : 0;

            String reason = String.format(Locale.ROOT,
                    "SESSION_END forced exit at %02d:%02d, pnl=%.2f%%",
                    cfg.getSessionEndHour(), cfg.getSessionEndMin(), pnlPct);

            log.info("[MorningRush] SESSION_END | {} | {}", pe.getMarket(), reason);

            // ★ race 방어
            if (!sellingMarkets.add(pe.getMarket())) {
                log.debug("[MorningRush] forceExit skip, already in progress: {}", pe.getMarket());
                continue;
            }
            positionCache.remove(pe.getMarket());

            try {
                executeSell(pe, currentPrice, pnlPct, reason, "SESSION_END", cfg);
            } catch (Exception e) {
                log.error("[MorningRush] session-end sell failed for {}", pe.getMarket(), e);
                addDecision(pe.getMarket(), "SELL", "ERROR", "세션종료 매도 오류: " + e.getMessage());
            } finally {
                sellingMarkets.remove(pe.getMarket());
            }
        }

        statusText = "IDLE (session ended)";
    }

    // ========== Order Execution ==========

    private void executeBuy(String market, double price, String reason,
                             MorningRushConfigEntity cfg, BigDecimal orderKrw, boolean isLive) {
        if (orderKrw.compareTo(BigDecimal.valueOf(5000)) < 0) {
            log.warn("[MorningRush] order too small: {} KRW for {}", orderKrw, market);
            addDecision(market, "BUY", "BLOCKED",
                    String.format("주문 금액 %s원이 최소 5,000원 미만", orderKrw.toPlainString()));
            return;
        }

        // 사전 중복 포지션 차단 (KRW-TREE orphan 사고 재발 방지)
        // 다른 스캐너가 이미 매수한 코인인지 DB에서 한 번 더 확인
        PositionEntity existing = positionRepo.findById(market).orElse(null);
        if (existing != null && existing.getQty() != null
                && existing.getQty().compareTo(BigDecimal.ZERO) > 0) {
            log.warn("[MorningRush] DUPLICATE_POSITION blocked: {} already held by {} qty={}",
                    market, existing.getEntryStrategy(), existing.getQty());
            addDecision(market, "BUY", "BLOCKED",
                    String.format("이미 보유 중 (전략=%s qty=%s) — 중복 매수 차단",
                            existing.getEntryStrategy(), existing.getQty().toPlainString()));
            return;
        }

        double qty;
        double fillPrice;

        if (!isLive) {
            // Paper: slippage 0.1%, fee 0.05%
            fillPrice = price * 1.001;
            double fee = orderKrw.doubleValue() * 0.0005;
            qty = (orderKrw.doubleValue() - fee) / fillPrice;
        } else {
            // LIVE: actual Upbit order
            try {
                LiveOrderService.OrderResult r = liveOrders.placeBidPriceOrder(market, orderKrw.doubleValue());
                boolean filled = r.isFilled() || r.executedVolume > 0;
                if (!filled) {
                    log.warn("[MorningRush] LIVE buy pending/failed: market={} state={} vol={}",
                            market, r.state, r.executedVolume);
                    addDecision(market, "BUY", "ERROR",
                            String.format("주문 미체결 state=%s vol=%.8f", r.state, r.executedVolume));
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
                qty = r.executedVolume;
                if (qty <= 0 && "order_not_found".equalsIgnoreCase(r.state)) {
                    qty = orderKrw.doubleValue() / fillPrice;
                } else if (qty <= 0) {
                    addDecision(market, "BUY", "ERROR", "체결 수량 0");
                    return;
                }
                log.info("[MorningRush] LIVE buy filled: market={} price={} qty={}", market, fillPrice, qty);
            } catch (Exception e) {
                log.error("[MorningRush] LIVE buy order failed for {}", market, e);
                addDecision(market, "BUY", "ERROR", "주문 실패: " + e.getMessage());
                return;
            }
        }

        // Save position + trade log atomically
        final String fMarket = market;
        final double fFillPrice = fillPrice;
        final double fQty = qty;
        final String fReason = reason;
        final String fMode = cfg.getMode();
        txTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                PositionEntity pe = new PositionEntity();
                pe.setMarket(fMarket);
                pe.setQty(fQty);
                pe.setAvgPrice(fFillPrice);
                pe.setAddBuys(0);
                pe.setOpenedAt(Instant.now());
                pe.setEntryStrategy(ENTRY_STRATEGY);
                positionRepo.save(pe);

                TradeEntity tl = new TradeEntity();
                tl.setTsEpochMs(System.currentTimeMillis());
                tl.setMarket(fMarket);
                tl.setAction("BUY");
                tl.setPrice(BigDecimal.valueOf(fFillPrice));
                tl.setQty(BigDecimal.valueOf(fQty));
                tl.setPnlKrw(BigDecimal.ZERO);
                tl.setRoiPercent(BigDecimal.ZERO);
                tl.setMode(fMode);
                tl.setPatternType(ENTRY_STRATEGY);
                tl.setPatternReason(fReason);
                tl.setCandleUnitMin(5); // 5-sec polling, use 5 as nominal
                tradeLogRepo.save(tl);
            }
        });

        log.info("[MorningRush] BUY {} mode={} price={} qty={} reason={}",
                market, cfg.getMode(), fillPrice, qty, reason);
    }

    private void executeSell(PositionEntity pe, double price, double pnlPct,
                              String reason, String sellType, MorningRushConfigEntity cfg) {
        final String market = pe.getMarket();
        final double qty = pe.getQty().doubleValue();
        final double avgPrice = pe.getAvgPrice().doubleValue();

        double fillPrice;

        if ("PAPER".equalsIgnoreCase(cfg.getMode())) {
            fillPrice = price * 0.999; // slippage 0.1%
        } else {
            // LIVE: market sell
            if (!liveOrders.isConfigured()) {
                addDecision(market, "SELL", "BLOCKED", "LIVE 모드 API 키 미설정");
                return;
            }
            try {
                LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(market, qty);
                boolean filled = r.isFilled() || r.executedVolume > 0;
                if (!filled) {
                    addDecision(market, "SELL", "ERROR",
                            String.format("매도 미체결 state=%s vol=%.8f", r.state, r.executedVolume));
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
            } catch (Exception e) {
                log.error("[MorningRush] LIVE sell failed for {}", market, e);
                addDecision(market, "SELL", "ERROR", "매도 실패: " + e.getMessage());
                return;
            }
        }

        double pnlKrw = (fillPrice - avgPrice) * qty;
        double fee = fillPrice * qty * 0.0005;
        pnlKrw -= fee;
        double roiPct = avgPrice > 0 ? ((fillPrice - avgPrice) / avgPrice) * 100.0 : 0;

        final double fFillPrice = fillPrice;
        final double fQty = qty;
        final double fPnlKrw = pnlKrw;
        final double fRoiPct = roiPct;
        final BigDecimal peAvgPrice = pe.getAvgPrice();
        final String fReason = reason;
        final String fSellType = sellType;
        final String fMode = cfg.getMode();

        txTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                TradeEntity tl = new TradeEntity();
                tl.setTsEpochMs(System.currentTimeMillis());
                tl.setMarket(market);
                tl.setAction("SELL");
                tl.setPrice(BigDecimal.valueOf(fFillPrice));
                tl.setQty(BigDecimal.valueOf(fQty));
                tl.setPnlKrw(BigDecimal.valueOf(fPnlKrw));
                tl.setRoiPercent(BigDecimal.valueOf(fRoiPct));
                tl.setMode(fMode);
                tl.setPatternType(ENTRY_STRATEGY);
                tl.setPatternReason(fReason);
                tl.setAvgBuyPrice(peAvgPrice);
                tl.setNote(fSellType);
                tl.setCandleUnitMin(5);
                tradeLogRepo.save(tl);

                positionRepo.deleteById(market);
            }
        });

        log.info("[MorningRush] {} {} price={} pnl={} roi={}% reason={}",
                sellType, market, fFillPrice, Math.round(pnlKrw),
                String.format("%.2f", roiPct), reason);

        addDecision(market, "SELL", "EXECUTED", fReason);
    }

    // ========== Helpers ==========

    private BigDecimal calcOrderSize(MorningRushConfigEntity cfg) {
        if ("FIXED".equalsIgnoreCase(cfg.getOrderSizingMode())) {
            return cfg.getOrderSizingValue();
        }
        // PCT mode — Global Capital
        BigDecimal pct = cfg.getOrderSizingValue();
        BigDecimal globalCapital = getGlobalCapitalKrw();
        return globalCapital.multiply(pct).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
    }

    private BigDecimal getGlobalCapitalKrw() {
        List<BotConfigEntity> configs = botConfigRepo.findAll();
        if (configs.isEmpty()) return BigDecimal.valueOf(100000);
        BigDecimal cap = configs.get(0).getCapitalKrw();
        return cap != null && cap.compareTo(BigDecimal.ZERO) > 0 ? cap : BigDecimal.valueOf(100000);
    }

    private double calcTotalInvestedAllPositions() {
        double sum = 0.0;
        List<PositionEntity> all = positionRepo.findAll();
        for (PositionEntity pe : all) {
            if (pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0
                    && pe.getAvgPrice() != null) {
                sum += pe.getQty().doubleValue() * pe.getAvgPrice().doubleValue();
            }
        }
        return sum;
    }

    private void addDecision(String market, String action, String result, String reason) {
        addDecision(market, action, result, "", reason);
    }

    private void addDecision(String market, String action, String result,
                              String reasonCode, String reasonKo) {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("tsEpochMs", System.currentTimeMillis());
        d.put("market", market);
        d.put("action", action);
        d.put("result", result);
        d.put("reasonCode", reasonCode);
        d.put("reasonKo", reasonKo);
        synchronized (decisionLog) {
            decisionLog.addFirst(d);
            while (decisionLog.size() > MAX_DECISION_LOG) decisionLog.removeLast();
        }
        if ("SKIPPED".equals(result) || "BLOCKED".equals(result) || "ERROR".equals(result)) {
            log.info("[MorningRush] {} {} {} {} | {}", market, action, result, reasonCode, reasonKo);
        }
    }
}
