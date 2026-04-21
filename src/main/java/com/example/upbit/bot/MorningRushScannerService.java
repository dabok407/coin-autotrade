package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.upbit.UpbitAccount;
import com.example.upbit.upbit.UpbitPrivateClient;

import com.example.upbit.market.NewMarketListener;
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
    // ┌─────────────────────────────────────────────────────────────────────────┐
    // │ positionCache value: double[7]                                          │
    // │ [0]=avgPrice, [1]=qty, [2]=openedAtEpochMs, [3]=peakPrice,              │
    // │ [4]=troughPrice, [5]=splitPhase(0/1/2), [6]=armed(0/1)                  │
    // │                                                                         │
    // │ ⚠️ 주의: AllDayScannerService.tpPositionCache와 필드 순서 다름!         │
    // │   - AllDay: [avg, peak, activated, trough, openedAt, splitPhase, armed] │
    // │   - OpeningBreakoutDetector.positionCache: 3-element [avg, openedAt, volumeRank] │
    // │ 리팩토링 시 스캐너별 인덱스 확인 필수. 향후 통일 작업 대기(#3 별건).   │
    // └─────────────────────────────────────────────────────────────────────────┘
    private static final int PC_AVG = 0;
    private static final int PC_QTY = 1;
    private static final int PC_OPENED_AT = 2;
    private static final int PC_PEAK = 3;
    private static final int PC_TROUGH = 4;
    private static final int PC_SPLIT_PHASE = 5;
    private static final int PC_ARMED = 6;
    private final ConcurrentHashMap<String, double[]> positionCache = new ConcurrentHashMap<String, double[]>();
    // SL 종합안 캐시 (DB 설정값, mainLoop에서 갱신)
    private volatile long cachedGracePeriodMs = 60_000L;       // 매수 후 그레이스 (DB grace_period_sec)
    private volatile long cachedWidePeriodMs = 5 * 60_000L;    // SL_WIDE 지속 시간 (DB wide_period_min)
    private volatile double cachedWideSlPct = 5.0;             // SL_WIDE 값 (DB wide_sl_pct)
    private volatile double cachedTpPct = 2.0;
    private volatile double cachedSlPct = 3.0;                 // SL_TIGHT (DB sl_pct)

    // ════════════════════════════════════════════════════════════════
    // TP 트레일링 설정 (2026-04-09 도입)
    // ════════════════════════════════════════════════════════════════
    // 기존: +2.3% 도달 → 즉시 매도 (큰 추세 못 먹음, KRW-ONT 사례)
    // 변경: +2.3% 도달 → peak 추적 → peak에서 -1% 떨어지면 매도
    //
    // 동작:
    //   1. 가격이 한 번이라도 +cachedTpPct(2.3%) 도달 → trail 활성화
    //   2. peak 계속 추적 (더 오르면 peak 갱신)
    //   3. peak에서 -cachedTpTrailDropPct(1.0%) 떨어지면 매도
    //   4. 단 pnl > 0 인 경우에만 trail 매도 (마이너스면 SL에 위임)
    //
    // 안전장치: SL (Grace/Wide/Tight)는 trail 미발동 시 그대로 동작
    //
    // 백테스트 결과에 따라 값 조정 예정. 현재 1.0% 기본값.
    // ════════════════════════════════════════════════════════════════
    // V110: 하드코딩 제거 → DB cached 변수 (tp_trail_drop_pct)
    private volatile double cachedTpTrailDropPct = 1.5;
    // V111: Split-Exit cached 변수
    private volatile boolean cachedSplitExitEnabled = false;
    private volatile double cachedSplitTpPct = 1.5;
    private volatile double cachedSplitRatio = 0.60;
    private volatile double cachedTrailDropAfterSplit = 1.2;
    // V115: 1차 매도 TRAIL drop % (split_tp_pct 도달 후 peak 대비 drop 시 1차 매도)
    private volatile double cachedSplit1stTrailDrop = 0.5;
    // V129: SPLIT_1ST 체결 후 SPLIT_2ND_TRAIL 매도 쿨다운 (초단위 DB → ms cache).
    //   peak 갱신은 유지(엣지 추적). SL은 별도 경로로 영향 없음(급락 방어).
    private volatile long cachedSplit1stCooldownMs = 60_000L;
    // V129: SPLIT_1ST 체결 시점 in-memory 맵 (market → epochMs). 재시작 시 DB에서 복원.
    private final ConcurrentHashMap<String, Long> split1stExecutedAtMap = new ConcurrentHashMap<String, Long>();
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
    private volatile NewMarketListener newMarketListener;

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
    // 2026-04-19 P0 root fix (A안): realtime 진입 in-flight marker.
    // positionCache에 qty=0 placeholder를 넣는 anti-pattern 제거 — 대신 별도 Set으로
    // "같은 마켓 중복 realtime 진입" 을 원자 차단. 매수 완료 후 DB 재조회로 정확한 캐시 put.
    private final java.util.Set<String> pendingBuyMarkets = ConcurrentHashMap.newKeySet();

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

        // Entry phase 시간대 등록 (DB 설정값 → SharedPriceService 10초 갱신 활성화)
        MorningRushConfigEntity initCfg = configRepo.findById(1).orElse(null);
        if (initCfg != null) {
            sharedPriceService.registerEntryPhase(
                    initCfg.getEntryStartHour(), initCfg.getEntryStartMin(),
                    initCfg.getEntryEndHour(), initCfg.getEntryEndMin());
        }

        // 신규 TOP-N 마켓 콜백: entry phase 중 rangeHighMap에 즉시 등록
        newMarketListener = new NewMarketListener() {
            @Override
            public void onNewMarketsAdded(List<String> newMarkets) {
                if (entryPhaseComplete || !rangeCollected) return; // entry phase가 아니면 무시

                for (String market : newMarkets) {
                    if (rangeHighMap.containsKey(market)) continue; // 이미 등록됨

                    // 현재가를 rangeHigh baseline으로 설정
                    Double price = sharedPriceService.getPrice(market);
                    if (price == null || price <= 0) continue;

                    rangeHighMap.put(market, price);
                    priceHistory.putIfAbsent(market, new ConcurrentLinkedDeque<long[]>());
                    log.info("[MorningRush] 신규 TOP-N 동적 추가: {} rangeHigh={} (entry phase 중 감지)",
                            market, price);
                }
            }
        };
        sharedPriceService.addNewMarketListener(newMarketListener);

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
        if (newMarketListener != null) {
            sharedPriceService.removeNewMarketListener(newMarketListener);
            newMarketListener = null;
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

        // 이미 확인된 마켓 스킵 (보유 중) 또는 실시간 매수 in-flight 중복 차단
        // 2026-04-19 P0 root fix: positionCache placeholder 대신 pendingBuyMarkets set으로 분리
        if (positionCache.containsKey(code) || pendingBuyMarkets.contains(code)) return;

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

        // 진입 신호: gap OR surge, BUT surge 단독이면 최소 gap 1.5% 필수 (2026-04-13)
        // FF 사고(-4.12%): surge만으로 진입, gap 1.19% → 모멘텀 부족 → 손절
        boolean entrySignal;
        if (gapCondition) {
            entrySignal = true;  // gap 2.6%+ 단독 OK
        } else if (surgeCondition) {
            // surge 단독: rangeHigh 대비 최소 1.5% 갭도 있어야 함
            double surgeGapPct = (price - rangeHigh) / rangeHigh * 100.0;
            if (surgeGapPct >= 1.5) {
                entrySignal = true;
            } else {
                entrySignal = false;
                log.debug("[MorningRush] SURGE but gap too small: {} gap=+{}% < 1.5%",
                        code, String.format(Locale.ROOT, "%.2f", surgeGapPct));
            }
        } else {
            entrySignal = false;
        }
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
        // 2026-04-19 P0 root fix (A안): qty=0 positionCache placeholder 제거.
        //   구 버그: placeholder[qty=0] → checkRealtimeTpSl의 qty<=0 가드에서 영구 무효화.
        //   대신 pendingBuyMarkets CAS로 realtime 중복 진입만 차단. 매수 완료 후 DB 재조회로
        //   정확한 avg/qty/openedAt을 캐시에 저장 (아래 scheduler Runnable 내부 참고).
        if (!pendingBuyMarkets.add(code)) {
            log.debug("[MorningRush] realtime already in-flight: {}", code);
            return;
        }

        final String fCode = code;
        final double fPrice = price;
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
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
                            // 2026-04-19 P0 root fix: executeBuy는 void이지만 DB에 포지션 저장함.
                            // DB에서 실제 qty/avgPrice 읽어와 캐시 확정 (qty=0으로 put하면
                            // WS realtime checkRealtimeTpSl의 qty<=0 가드에 막혀 영구 무효화됨)
                            long realtimeOpenedMs = System.currentTimeMillis();
                            double realtimeCacheAvg = fPrice;
                            double realtimeCacheQty = 0;
                            try {
                                PositionEntity savedPe = positionRepo.findById(fCode).orElse(null);
                                if (savedPe != null && savedPe.getQty() != null
                                        && savedPe.getQty().signum() > 0
                                        && savedPe.getAvgPrice() != null
                                        && savedPe.getAvgPrice().signum() > 0) {
                                    realtimeCacheAvg = savedPe.getAvgPrice().doubleValue();
                                    realtimeCacheQty = savedPe.getQty().doubleValue();
                                    if (savedPe.getOpenedAt() != null) {
                                        realtimeOpenedMs = savedPe.getOpenedAt().toEpochMilli();
                                    }
                                }
                            } catch (Exception dbEx) {
                                log.warn("[MorningRush] realtime BUY 직후 DB 재조회 실패 {}: {}", fCode, dbEx.getMessage());
                            }
                            // [avgPrice, qty, openedAtMs, peakPrice, troughPrice, splitPhase, split1stTrailArmed]
                            positionCache.put(fCode, new double[]{realtimeCacheAvg, realtimeCacheQty, realtimeOpenedMs, realtimeCacheAvg, realtimeCacheAvg, 0, 0});
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
                        // 2026-04-19 P0 root fix: pendingBuyMarkets로 in-flight 차단 일원화.
                        // buyingMarkets는 다른 경로에서도 안전하게 제거(absent-on-remove는 no-op).
                        pendingBuyMarkets.remove(fCode);
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

        double[] pos = positionCache.get(market);
        if (pos == null) return;

        double avgPrice = pos[0];
        // 2026-04-19 P0 root fix: qty<=0 가드 제거 — placeholder 제거로 stale qty=0 엔트리 존재 불가.
        if (avgPrice <= 0) return;
        long openedAtMs = (long) pos[2];
        int splitPhase = pos.length >= 6 ? (int) pos[5] : 0;

        // peak 업데이트
        double peakPrice = pos[3];
        if (price > peakPrice) {
            pos[3] = price;
            peakPrice = price;
        }

        // trough 업데이트
        double troughPrice = pos.length >= 5 && pos[4] > 0 ? pos[4] : avgPrice;
        if (price < troughPrice) {
            pos[4] = price;
            troughPrice = price;
        }

        double pnlPct = (price - avgPrice) / avgPrice * 100.0;
        long elapsedMs = System.currentTimeMillis() - openedAtMs;

        // ━━━ V129: Grace 기간이면 모든 매도 판정 생략 ━━━
        // 매수 직후 꼬리 흡수용 — SL/SPLIT/TP_TRAIL 모두 차단.
        // peak/trough는 위에서 이미 갱신됨 → Grace 이후 정상 판정으로 이어짐.
        if (elapsedMs < cachedGracePeriodMs) {
            return;
        }

        String sellType = null;
        String reason = null;
        boolean isSplitFirst = false;

        // ━━━ V115: Split-Exit 1차 매도 TRAIL 방식 (splitPhase=0) ━━━
        // (1) +splitTpPct 도달 → armed (매도 안 함, peak 추적 시작)
        // (2) armed 상태에서 peak 대비 drop >= split_1st_trail_drop → SPLIT_1ST 매도
        if (cachedSplitExitEnabled && splitPhase == 0 && pos.length >= 7) {
            boolean armed = pos[6] > 0;
            if (!armed && pnlPct >= cachedSplitTpPct) {
                pos[6] = 1.0;  // 1차 trail armed
                log.info("[MorningRush] SPLIT_1ST trail armed: {} pnl=+{}% peak={}",
                        market, String.format(Locale.ROOT, "%.2f", pnlPct),
                        String.format(Locale.ROOT, "%.2f", peakPrice));
            } else if (armed && peakPrice > avgPrice) {
                double dropFromPeakPct = (peakPrice - price) / peakPrice * 100.0;
                if (dropFromPeakPct >= cachedSplit1stTrailDrop) {
                    double troughPnl = (troughPrice - avgPrice) / avgPrice * 100.0;
                    sellType = "SPLIT_1ST";
                    isSplitFirst = true;
                    reason = String.format(Locale.ROOT,
                            "SPLIT_1ST_TRAIL avg=%.2f peak=%.2f now=%.2f drop=%.2f%% >= %.2f%% pnl=+%.2f%% ratio=%.0f%% trough=%.2f troughPnl=%.2f%% (realtime)",
                            avgPrice, peakPrice, price, dropFromPeakPct, cachedSplit1stTrailDrop,
                            pnlPct, cachedSplitRatio * 100, troughPrice, troughPnl);
                }
            }
        }
        // ━━━ V111/V129: Split 2차 관리 (splitPhase=1) — BEV 제거 + 쿨다운 추가 ━━━
        // V129: SPLIT_2ND_BEV 조건 완전 제거 (오늘 AXL/FLOCK 본전매도 방지).
        // V129: SPLIT_1ST 체결 후 cachedSplit1stCooldownMs 동안 SPLIT_2ND_TRAIL 차단 (Opening V126 포팅).
        //       쿨다운 중 SL은 별도 경로로 허용(아래 SL 블록 참조).
        else if (cachedSplitExitEnabled && splitPhase == 1) {
            Long execAt = split1stExecutedAtMap.get(market);
            boolean cooldownActive = false;
            if (execAt != null && cachedSplit1stCooldownMs > 0) {
                long sinceMs = System.currentTimeMillis() - execAt;
                if (sinceMs < cachedSplit1stCooldownMs) {
                    cooldownActive = true;
                }
            }
            if (!cooldownActive && peakPrice > avgPrice) {
                double dropFromPeakPct = (peakPrice - price) / peakPrice * 100.0;
                if (dropFromPeakPct >= cachedTrailDropAfterSplit) {
                    double troughPnl = (troughPrice - avgPrice) / avgPrice * 100.0;
                    sellType = "SPLIT_2ND_TRAIL";
                    reason = String.format(Locale.ROOT,
                            "SPLIT_2ND_TRAIL avg=%.2f peak=%.2f now=%.2f drop=%.2f%% >= %.2f%% pnl=%.2f%% trough=%.2f troughPnl=%.2f%% (realtime)",
                            avgPrice, peakPrice, price, dropFromPeakPct, cachedTrailDropAfterSplit, pnlPct, troughPrice, troughPnl);
                }
            }
        }
        // ━━━ TP_TRAIL (splitPhase=1 이 아닐 때만 — 2차 관리 중이면 불필요) ━━━
        if (sellType == null && splitPhase != 1) {
            double peakPnlPct = (peakPrice - avgPrice) / avgPrice * 100.0;
            boolean trailActivated = peakPnlPct >= cachedTpPct;

            if (trailActivated && pnlPct > 0) {
                double dropFromPeakPct = (peakPrice - price) / peakPrice * 100.0;
                if (dropFromPeakPct >= cachedTpTrailDropPct) {
                    double troughPnl = (troughPrice - avgPrice) / avgPrice * 100.0;
                    sellType = "TP_TRAIL";
                    reason = String.format(Locale.ROOT,
                            "TP_TRAIL avg=%.2f peak=%.2f now=%.2f drop=%.2f%% pnl=%.2f%% trough=%.2f troughPnl=%.2f%% (realtime)",
                            avgPrice, peakPrice, price, dropFromPeakPct, pnlPct, troughPrice, troughPnl);
                }
            }
        }

        // ━━━ SL 종합안 (V129: splitPhase 무관 — 쿨다운 중이어도 SL은 허용) ━━━
        // Grace는 메서드 상단에서 이미 걸러짐 → 여기서는 Grace 외 구간만 처리.
        if (sellType == null) {
            if (elapsedMs < cachedWidePeriodMs) {
                if (pnlPct <= -cachedWideSlPct) {
                    double troughPnl = (troughPrice - avgPrice) / avgPrice * 100.0;
                    sellType = "SL";
                    reason = String.format(Locale.ROOT,
                            "SL_WIDE pnl=%.2f%% <= -%.2f%% price=%.2f avg=%.2f trough=%.2f troughPnl=%.2f%% (realtime)",
                            pnlPct, cachedWideSlPct, price, avgPrice, troughPrice, troughPnl);
                }
            } else {
                if (pnlPct <= -cachedSlPct) {
                    double troughPnl = (troughPrice - avgPrice) / avgPrice * 100.0;
                    sellType = "SL";
                    reason = String.format(Locale.ROOT,
                            "SL_TIGHT pnl=%.2f%% <= -%.2f%% price=%.2f avg=%.2f trough=%.2f troughPnl=%.2f%% (realtime)",
                            pnlPct, cachedSlPct, price, avgPrice, troughPrice, troughPnl);
                }
            }
        }

        if (sellType == null) return;

        if (!sellingMarkets.add(market)) {
            log.debug("[MorningRush] SELL in progress, skip duplicate trigger: {}", market);
            return;
        }

        // V128: 매도 시점 armed/peak 스냅샷 — 아래 cache 리셋/제거 전에 캡처 (로깅용, 매매 로직 영향 없음)
        final boolean fCapturedArmed = pos.length >= 7 && pos[6] > 0;
        final double fCapturedPeak = peakPrice;

        if (isSplitFirst) {
            // ★ V111: 1차 매도 — 캐시 유지, splitPhase=1로 갱신
            pos[5] = 1.0;    // splitPhase=1
            pos[3] = price;  // peak 리셋 (2차 TRAIL 기준점)
            if (pos.length >= 7) pos[6] = 0.0;  // V115: 1차 trail armed 리셋 (D)
            // V129: 쿨다운 기준점 기록 (in-memory). DB 영속화는 executeSplitFirstSell() 내부에서 수행.
            split1stExecutedAtMap.put(market, System.currentTimeMillis());
        } else {
            positionCache.remove(market);
            // V129: 최종 매도(2차/SL/TP)면 쿨다운 상태 제거.
            split1stExecutedAtMap.remove(market);
        }

        final String fSellType = sellType;
        final String fReason = reason;
        final boolean fIsSplitFirst = isSplitFirst;
        log.info("[MorningRush] realtime {} detected | {} | {}", fSellType, market, fReason);

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
                    if (fIsSplitFirst) {
                        executeSplitFirstSell(fresh, fPrice, fReason, cfg,
                                Boolean.valueOf(fCapturedArmed), Double.valueOf(fCapturedPeak));
                    } else {
                        double freshPnl = (fPrice - fresh.getAvgPrice().doubleValue())
                                / fresh.getAvgPrice().doubleValue() * 100.0;
                        executeSell(fresh, fPrice, freshPnl, fReason, fSellType, cfg,
                                Boolean.valueOf(fCapturedArmed), Double.valueOf(fCapturedPeak));
                    }
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
        cachedTpTrailDropPct = cfg.getTpTrailDropPct().doubleValue();  // V110
        cachedMode = cfg.getMode();

        // V118: positionCache.clear() 제거 — WebSocket 스레드가 pos[3](peak)/pos[6](armed)을
        //   실시간 갱신하므로 tick마다 clear하면 peak이 매 tick 초기화되는 버그가 있었다.
        //   대신 "신규 추가 + 제거된 포지션만 삭제" 방식으로 전환. 재시작/신규 진입 시만 DB에서 복원.
        try {
            List<PositionEntity> allPos = positionRepo.findAll();
            Set<String> currentMarkets = new HashSet<String>();
            for (PositionEntity pe : allPos) {
                if (ENTRY_STRATEGY.equals(pe.getEntryStrategy())
                        && pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0
                        && pe.getAvgPrice() != null) {
                    String market = pe.getMarket();
                    currentMarkets.add(market);
                    // 2026-04-19 P0 root fix: placeholder 제거로 qty=0 stale 엔트리는 존재하지 않음.
                    // 따라서 needsRefresh는 null/짧은 배열(이전 버전 호환)만 갱신.
                    double[] existingCache = positionCache.get(market);
                    boolean needsRefresh = existingCache == null
                            || existingCache.length < 7;
                    if (needsRefresh) {
                        // 복구 시 openedAt 사용 (없으면 현재 시각 - 그레이스 효과는 없음)
                        long openedAtMs = pe.getOpenedAt() != null
                                ? pe.getOpenedAt().toEpochMilli()
                                : System.currentTimeMillis();
                        double avg = pe.getAvgPrice().doubleValue();
                        // [avgPrice, qty, openedAtMs, peakPrice, troughPrice, splitPhase, split1stTrailArmed]
                        Integer pePhase = pe.getSplitPhase();
                        int splitPhase = pePhase != null ? pePhase : 0;

                        // V118: DB에 저장된 실제 peak/armed 우선 복원
                        BigDecimal dbPeak = pe.getPeakPrice();
                        double recPeak;
                        double recArmed;
                        if (dbPeak != null && dbPeak.signum() > 0) {
                            recPeak = dbPeak.doubleValue();
                            recArmed = (pe.getArmedAt() != null) ? 1.0 : 0.0;
                            log.info("[MorningRush] V118 재시작 복구(DB): {} peak={} armed={} phase={}",
                                    market, String.format(Locale.ROOT, "%.2f", recPeak),
                                    recArmed > 0, splitPhase);
                        } else {
                            // V115/P0 fix 2026-04-19 fallback: 오늘 high_price까지 반영해 peak 재판정
                            //  - DB peak이 아직 저장되지 않은 포지션(재시작/구버전 데이터) 복구용
                            //  - (과거 qty=0 placeholder 버그 영향을 받았던 포지션 복구에도 유효)
                            double recPrice = sharedPriceService.getPrice(market);
                            double todayHigh = 0;
                            try {
                                todayHigh = tickerService.getTodayHighPrice(market);
                            } catch (Exception thEx) {
                                log.debug("[MorningRush] today high 조회 실패 {}: {}", market, thEx.getMessage());
                            }
                            recPeak = avg;
                            recArmed = 0;
                            if (splitPhase == 0) {
                                // peak 후보: avg, 현재가, 오늘 고가 중 최대
                                double candPeak = Math.max(avg, Math.max(recPrice, todayHigh));
                                recPeak = candPeak;
                                double peakPnl = (recPeak - avg) / avg * 100.0;
                                if (peakPnl >= cachedSplitTpPct) {
                                    recArmed = 1.0;
                                }
                                log.info("[MorningRush] P0 fallback 복구: {} peak={} (cur={} todayHigh={}) peakPnl=+{}% armed={}",
                                        market, String.format(Locale.ROOT, "%.2f", recPeak),
                                        String.format(Locale.ROOT, "%.2f", recPrice),
                                        String.format(Locale.ROOT, "%.2f", todayHigh),
                                        String.format(Locale.ROOT, "%.2f", peakPnl),
                                        recArmed > 0);
                            }
                        }
                        // 기존 캐시에 추적된 trough가 있으면 보존, 없으면 avg
                        double preservedTrough = (existingCache != null && existingCache.length >= 5 && existingCache[4] > 0)
                                ? existingCache[4] : avg;
                        positionCache.put(market,
                                new double[]{avg, pe.getQty().doubleValue(), openedAtMs, recPeak, preservedTrough, splitPhase, recArmed});
                        // V129: splitPhase==1 포지션의 쿨다운 기준점 복구 (재시작/신규 캐시 진입 시)
                        if (splitPhase == 1 && pe.getSplit1stExecutedAt() != null) {
                            split1stExecutedAtMap.putIfAbsent(market, pe.getSplit1stExecutedAt().toEpochMilli());
                        }
                    }
                }
            }
            // V118: 제거된 포지션(청산 완료)은 캐시에서 제거
            for (String market : new ArrayList<String>(positionCache.keySet())) {
                if (!currentMarkets.contains(market)) {
                    positionCache.remove(market);
                }
            }

            // V118: 메모리 peak/armed → DB 영속화 (peak 0.3% 이상 상승 또는 armed 전환 시)
            for (PositionEntity pe : allPos) {
                if (!ENTRY_STRATEGY.equals(pe.getEntryStrategy())) continue;
                String market = pe.getMarket();
                double[] pos = positionCache.get(market);
                if (pos == null || pos.length < 7) continue;
                double avgPrice = pos[0];
                if (avgPrice <= 0) continue;
                double currentPeak = pos[3];
                boolean currentArmed = pos[6] > 0;

                BigDecimal dbPeak = pe.getPeakPrice();
                boolean peakDirty;
                if (dbPeak == null) {
                    peakDirty = (currentPeak - avgPrice) / avgPrice * 100.0 >= 0.3;
                } else {
                    peakDirty = (currentPeak - dbPeak.doubleValue()) / avgPrice * 100.0 >= 0.3;
                }
                boolean armedDirty = currentArmed && pe.getArmedAt() == null;

                if (peakDirty || armedDirty) {
                    if (peakDirty) pe.setPeakPrice(BigDecimal.valueOf(currentPeak));
                    if (armedDirty) pe.setArmedAt(Instant.now());
                    try {
                        positionRepo.save(pe);
                    } catch (Exception ex) {
                        log.warn("[MorningRush] V118 peak/armed save failed for {}", market, ex);
                    }
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
        cachedTpTrailDropPct = cfg.getTpTrailDropPct().doubleValue();  // V110
        // V111: Split-Exit cached 갱신
        cachedSplitExitEnabled = cfg.isSplitExitEnabled();
        cachedSplitTpPct = cfg.getSplitTpPct().doubleValue();
        cachedSplitRatio = cfg.getSplitRatio().doubleValue();
        cachedTrailDropAfterSplit = cfg.getTrailDropAfterSplit().doubleValue();
        cachedSplit1stTrailDrop = cfg.getSplit1stTrailDrop().doubleValue();  // V115
        cachedSplit1stCooldownMs = cfg.getSplit1stCooldownSec() * 1000L;  // V129
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

            // 진입 신호: gap OR surge, BUT surge 단독이면 최소 gap 1.5% 필수 (2026-04-13)
            boolean entrySignal;
            if (gapCondition) {
                entrySignal = true;
            } else if (surgeCondition) {
                double surgeGapPct = (price - rangeHigh) / rangeHigh * 100.0;
                if (surgeGapPct >= 1.5) {
                    entrySignal = true;
                } else {
                    entrySignal = false;
                    log.debug("[MorningRush] SURGE but gap too small: {} gap=+{}% < 1.5%",
                            market, String.format(Locale.ROOT, "%.2f", surgeGapPct));
                }
            } else {
                entrySignal = false;
            }

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
                        // P0 fix 2026-04-19: executeBuy는 void이지만 DB에 포지션 저장함.
                        // DB에서 실제 qty/avgPrice 읽어와 캐시 확정 (qty=0으로 put하면
                        // WS realtime checkRealtimeTpSl의 qty<=0 가드에 막혀 영구 무효화됨)
                        long openedAtMs = System.currentTimeMillis();
                        double cacheAvg = price;
                        double cacheQty = 0;
                        try {
                            PositionEntity savedPe = positionRepo.findById(market).orElse(null);
                            if (savedPe != null && savedPe.getQty() != null
                                    && savedPe.getQty().signum() > 0
                                    && savedPe.getAvgPrice() != null
                                    && savedPe.getAvgPrice().signum() > 0) {
                                cacheAvg = savedPe.getAvgPrice().doubleValue();
                                cacheQty = savedPe.getQty().doubleValue();
                                if (savedPe.getOpenedAt() != null) {
                                    openedAtMs = savedPe.getOpenedAt().toEpochMilli();
                                }
                            }
                        } catch (Exception dbEx) {
                            log.warn("[MorningRush] BUY 직후 DB 재조회 실패 {}: {}", market, dbEx.getMessage());
                        }
                        // [avgPrice, qty, openedAtMs, peakPrice, troughPrice, splitPhase, split1stTrailArmed]
                        positionCache.put(market, new double[]{cacheAvg, cacheQty, openedAtMs, cacheAvg, cacheAvg, 0, 0});
                        cachedTpPct = cfg.getTpPct().doubleValue();
                        cachedSlPct = cfg.getSlPct().doubleValue();
                        cachedTpTrailDropPct = cfg.getTpTrailDropPct().doubleValue();  // V110
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
            // V118: splitExitEnabled=true면 고정 TP 전면 skip (splitPhase 무관)
            //  - 고정 TP 즉시매도가 TRAIL armed를 선점해 Split-Exit 무효화하는 버그 차단
            //  - 1차/2차 매도 모두 realtime checkRealtimeTpSl의 TRAIL(armed → peak → drop)에 위임
            //  - tpPct는 이제 WS realtime에서 "TRAIL arm 임계값" 의미로만 사용
            if (!cfg.isSplitExitEnabled() && pnlPct >= tpPct) {
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

            // V128: cache 제거 전 armed/peak 캡처 (로깅용)
            double[] mainLoopCache = positionCache.get(pe.getMarket());
            boolean mainLoopArmed = mainLoopCache != null && mainLoopCache.length >= 7 && mainLoopCache[6] > 0;
            double mainLoopPeak = mainLoopCache != null && mainLoopCache.length >= 4 ? mainLoopCache[3] : 0;

            // 캐시에서 제거 (다음 WS tick의 중복 매도 방지)
            positionCache.remove(pe.getMarket());

            try {
                // Re-check position (race condition protection)
                PositionEntity fresh = positionRepo.findById(pe.getMarket()).orElse(null);
                if (fresh == null || fresh.getQty() == null || fresh.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;

                executeSell(fresh, currentPrice, pnlPct, sellReason, sellType, cfg,
                        Boolean.valueOf(mainLoopArmed), Double.valueOf(mainLoopPeak));
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
            // V128: cache 제거 전 armed/peak 캡처 (로깅용)
            double[] forceCache = positionCache.get(pe.getMarket());
            boolean forceArmed = forceCache != null && forceCache.length >= 7 && forceCache[6] > 0;
            double forcePeak = forceCache != null && forceCache.length >= 4 ? forceCache[3] : 0;
            positionCache.remove(pe.getMarket());

            try {
                // P4-21: splitPhase=1이면 SPLIT_SESSION_END note 구분
                String sessType = pe.getSplitPhase() == 1 ? "SPLIT_SESSION_END" : "SESSION_END";
                executeSell(pe, currentPrice, pnlPct, reason, sessType, cfg,
                        Boolean.valueOf(forceArmed), Double.valueOf(forcePeak));
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
                tl.setEntrySignal(fReason); // V128
                tl.setCandleUnitMin(5); // 5-sec polling, use 5 as nominal
                tradeLogRepo.save(tl);
            }
        });

        log.info("[MorningRush] BUY {} mode={} price={} qty={} reason={}",
                market, cfg.getMode(), fillPrice, qty, reason);
    }

    private void executeSell(PositionEntity pe, double price, double pnlPct,
                              String reason, String sellType, MorningRushConfigEntity cfg) {
        executeSell(pe, price, pnlPct, reason, sellType, cfg, null, null);
    }

    private void executeSell(PositionEntity pe, double price, double pnlPct,
                              String reason, String sellType, MorningRushConfigEntity cfg,
                              Boolean capturedArmed, Double capturedPeak) {
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

        // V128: 매도 시점 peak/armed 스냅샷 (매매 로직에 영향 없음, 로깅용)
        // B안: 호출자가 cache/armed 상태 리셋 전 캡처한 값을 우선 사용. 없으면 cache fallback.
        double resolvedPeak;
        boolean resolvedArmed;
        if (capturedPeak != null && capturedArmed != null) {
            resolvedPeak = capturedPeak.doubleValue();
            resolvedArmed = capturedArmed.booleanValue();
        } else {
            double[] cachePos = positionCache.get(market);
            resolvedPeak = cachePos != null && cachePos.length >= 4 ? cachePos[3] : 0;
            resolvedArmed = cachePos != null && cachePos.length >= 7 && cachePos[6] > 0;
        }
        final double fPeakPrice = resolvedPeak;
        final boolean fArmed = resolvedArmed;

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
                if (fPeakPrice > 0 && peAvgPrice != null && peAvgPrice.signum() > 0) {
                    double peakRoi = (fPeakPrice - peAvgPrice.doubleValue()) / peAvgPrice.doubleValue() * 100.0;
                    tl.setPeakPrice(fPeakPrice);
                    tl.setPeakRoiPct(peakRoi);
                }
                tl.setArmedFlag(fArmed ? "Y" : "N");
                tradeLogRepo.save(tl);

                positionRepo.deleteById(market);
            }
        });

        log.info("[MorningRush] {} {} price={} pnl={} roi={}% reason={}",
                sellType, market, fFillPrice, Math.round(pnlKrw),
                String.format("%.2f", roiPct), reason);

        addDecision(market, "SELL", "EXECUTED", fReason);
    }

    // ━━━ V111: Split-Exit 1차 분할 매도 ━━━

    private void executeSplitFirstSell(PositionEntity pe, double price, String reason,
                                        MorningRushConfigEntity cfg) {
        executeSplitFirstSell(pe, price, reason, cfg, null, null);
    }

    private void executeSplitFirstSell(PositionEntity pe, double price, String reason,
                                        MorningRushConfigEntity cfg,
                                        Boolean capturedArmed, Double capturedPeak) {
        final String market = pe.getMarket();
        if (pe.getSplitPhase() != 0) {
            log.debug("[MorningRush] SPLIT_1ST: already split for {} phase={}", market, pe.getSplitPhase());
            return;
        }

        double totalQty = pe.getQty().doubleValue();
        double avgPrice = pe.getAvgPrice().doubleValue();
        double sellRatio = cfg.getSplitRatio().doubleValue();
        double sellQty = totalQty * sellRatio;
        double remainQty = totalQty - sellQty;

        boolean isDust = remainQty * price < 5000;
        double actualSellQty = isDust ? totalQty : sellQty;

        double fillPrice;
        if ("PAPER".equalsIgnoreCase(cfg.getMode())) {
            fillPrice = price * 0.999;
        } else {
            if (!liveOrders.isConfigured()) {
                addDecision(market, "SELL", "BLOCKED", "SPLIT_1ST LIVE 모드 API 키 미설정");
                return;
            }
            try {
                LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(market, actualSellQty);
                boolean filled = r.isFilled() || r.executedVolume > 0;
                if (!filled) {
                    addDecision(market, "SELL", "ERROR",
                            String.format("SPLIT_1ST 매도 미체결 state=%s vol=%.8f", r.state, r.executedVolume));
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
                // P3-16: LIVE 부분 체결 시 executedVolume 반영
                if (r.executedVolume > 0 && r.executedVolume != actualSellQty) {
                    actualSellQty = r.executedVolume;
                    remainQty = totalQty - actualSellQty;
                    isDust = remainQty * fillPrice < 5000;
                }
            } catch (Exception e) {
                log.error("[MorningRush] SPLIT_1ST sell failed for {}", market, e);
                addDecision(market, "SELL", "ERROR", "SPLIT_1ST 매도 실패: " + e.getMessage());
                return;
            }
        }

        double pnlKrw = (fillPrice - avgPrice) * actualSellQty;
        double fee = fillPrice * actualSellQty * 0.0005;
        pnlKrw -= fee;
        double roiPct = avgPrice > 0 ? ((fillPrice - avgPrice) / avgPrice) * 100.0 : 0;

        final double fFillPrice = fillPrice;
        final double fPnlKrw = pnlKrw;
        final double fRoiPct = roiPct;
        final String fReason = reason;
        final String fMode = cfg.getMode();
        final BigDecimal peAvgPrice = pe.getAvgPrice();
        final boolean fIsDust = isDust;
        final double fActualSellQty = actualSellQty;
        final double fRemainQty = remainQty;

        // V128: 매도 시점 peak/armed 스냅샷
        // B안: 호출자가 cache 리셋(pos[6]=0) 전 캡처한 값을 우선 사용. 없으면 cache fallback.
        double resolvedPeak;
        boolean resolvedArmed;
        if (capturedPeak != null && capturedArmed != null) {
            resolvedPeak = capturedPeak.doubleValue();
            resolvedArmed = capturedArmed.booleanValue();
        } else {
            double[] cachePos = positionCache.get(market);
            resolvedPeak = cachePos != null && cachePos.length >= 4 ? cachePos[3] : 0;
            resolvedArmed = cachePos != null && cachePos.length >= 7 && cachePos[6] > 0;
        }
        final double fPeakPrice = resolvedPeak;
        final boolean fArmed = resolvedArmed;

        try {
            txTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    TradeEntity tl = new TradeEntity();
                    tl.setTsEpochMs(System.currentTimeMillis());
                    tl.setMarket(market);
                    tl.setAction("SELL");
                    tl.setPrice(BigDecimal.valueOf(fFillPrice));
                    tl.setQty(BigDecimal.valueOf(fActualSellQty));
                    tl.setPnlKrw(BigDecimal.valueOf(fPnlKrw));
                    tl.setRoiPercent(BigDecimal.valueOf(fRoiPct));
                    tl.setMode(fMode);
                    tl.setPatternType(ENTRY_STRATEGY);
                    tl.setPatternReason(fReason);
                    tl.setAvgBuyPrice(peAvgPrice);
                    tl.setNote(fIsDust ? "SPLIT_1ST_DUST" : "SPLIT_1ST");
                    tl.setCandleUnitMin(5);
                    if (fPeakPrice > 0 && peAvgPrice != null && peAvgPrice.signum() > 0) {
                        double peakRoi = (fPeakPrice - peAvgPrice.doubleValue()) / peAvgPrice.doubleValue() * 100.0;
                        tl.setPeakPrice(fPeakPrice);
                        tl.setPeakRoiPct(peakRoi);
                    }
                    tl.setArmedFlag(fArmed ? "Y" : "N");
                    tradeLogRepo.save(tl);

                    if (fIsDust) {
                        positionRepo.deleteById(market);
                    } else {
                        pe.setQty(BigDecimal.valueOf(fRemainQty));
                        pe.setSplitPhase(1);
                        pe.setSplitOriginalQty(BigDecimal.valueOf(totalQty));
                        // V118: 1차 매도 후 peak/armed 리셋 — 2차 TRAIL은 매도가를 새 기준점으로 추적
                        pe.setPeakPrice(BigDecimal.valueOf(fFillPrice));
                        pe.setArmedAt(null);
                        // V129: 쿨다운 기준점 DB 영속화 (재시작 후 복구용)
                        pe.setSplit1stExecutedAt(java.time.Instant.now());
                        positionRepo.save(pe);
                    }
                }
            });
        } catch (Exception e) {
            // P1-4: DB 실패 시 cache 롤백
            // V129 #9 fix: split1stExecutedAtMap도 함께 제거 — 원자성 보장.
            //   checkRealtimeTp에서 매도 판정 직후 메모리 맵에 put해두는데, DB commit이 실패하면 그 put만 남아
            //   "쿨다운만 살아있고 splitPhase는 0"인 불일치 상태가 발생한다. 이 catch가 유일한 보정 지점.
            log.error("[MorningRush] SPLIT_1ST DB commit failed for {} — cache rollback", market, e);
            double[] rollback = positionCache.get(market);
            if (rollback != null && rollback.length >= 6) {
                rollback[5] = 0;
            }
            split1stExecutedAtMap.remove(market);
            addDecision(market, "SELL", "ERROR", "SPLIT_1ST DB 실패, cache 롤백: " + e.getMessage());
            return;
        }

        if (isDust) {
            positionCache.remove(market);
            log.info("[MorningRush] SPLIT_1ST_DUST {} (전량, 잔량<5000원) price={} pnl={} roi={}%",
                    market, fFillPrice, Math.round(pnlKrw), String.format("%.2f", roiPct));
        } else {
            // 캐시 splitPhase는 checkRealtimeTpSl에서 이미 1로 갱신됨
            log.info("[MorningRush] SPLIT_1ST {} qty={}/{} price={} pnl={} roi={}%",
                    market, String.format("%.8f", actualSellQty), String.format("%.8f", totalQty),
                    fFillPrice, Math.round(pnlKrw), String.format("%.2f", roiPct));
        }

        addDecision(market, "SELL", "EXECUTED", fReason);
        // ★ 1차 매도 시 sellCooldownMap 미등록 (2차에서만)
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
                // V111: splitPhase=1이면 splitOriginalQty 기준 (자본 슬롯 유지)
                double qty = (pe.getSplitPhase() == 1 && pe.getSplitOriginalQty() != null)
                        ? pe.getSplitOriginalQty().doubleValue()
                        : pe.getQty().doubleValue();
                sum += qty * pe.getAvgPrice().doubleValue();
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
