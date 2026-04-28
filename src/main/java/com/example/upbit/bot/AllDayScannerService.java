package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.strategy.*;
import com.example.upbit.strategy.Indicators;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.upbit.UpbitAccount;
import com.example.upbit.upbit.UpbitPrivateClient;

import com.example.upbit.market.NewMarketListener;
import com.example.upbit.market.PriceUpdateListener;
import com.example.upbit.market.SharedPriceService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PreDestroy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 종일 고확신 돌파 스캐너.
 * 메인 TradingBotService와 별도로 on/off 운영.
 * 거래대금 상위 N개 코인을 스캔하여 고확신 돌파 시 매수.
 *
 * HighConfidenceBreakoutStrategy 기반, 오프닝 레인지 제약 없이 종일 운영.
 */
@Service
public class AllDayScannerService {

    private static final Logger log = LoggerFactory.getLogger(AllDayScannerService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final AllDayScannerConfigRepository configRepo;
    private final BotConfigRepository botConfigRepo;
    private final PositionRepository positionRepo;
    private final TradeRepository tradeLogRepo;
    private final CandleService candleService;
    private final UpbitMarketCatalogService catalogService;
    private final LiveOrderService liveOrders;
    private final UpbitPrivateClient privateClient;
    private final TransactionTemplate txTemplate;
    private final TickerService tickerService;
    private final SharedPriceService sharedPriceService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

    // ── 실시간 WebSocket TP (매도 전용 스레드, tick()과 독립) ──
    private final ExecutorService wsTpExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "allday-ws-tp-sell");
            t.setDaemon(true);
            return t;
        }
    });
    // ┌─────────────────────────────────────────────────────────────────────────┐
    // │ tpPositionCache value: double[7]                                        │
    // │ [0]=avgPrice, [1]=peakPrice, [2]=trailActivated(0/1), [3]=troughPrice,  │
    // │ [4]=openedAtEpochMs, [5]=splitPhase(0/1/2), [6]=armed(0/1)              │
    // │                                                                         │
    // │ ⚠️ 주의: MorningRushScannerService.positionCache와 필드 순서 완전히 다름!│
    // │   - MR:  [avg, qty, openedAt, peak, trough, splitPhase, armed]          │
    // │ 리팩토링 시 스캐너별 인덱스 확인 필수. 향후 통일 작업 대기(#3 별건).   │
    // └─────────────────────────────────────────────────────────────────────────┘
    private static final int TPC_AVG = 0;
    private static final int TPC_PEAK = 1;
    private static final int TPC_ACTIVATED = 2;
    private static final int TPC_TROUGH = 3;
    private static final int TPC_OPENED_AT = 4;
    private static final int TPC_SPLIT_PHASE = 5;
    private static final int TPC_ARMED = 6;
    private final ConcurrentHashMap<String, double[]> tpPositionCache = new ConcurrentHashMap<String, double[]>();
    // V109: 하드코딩 제거 → DB cached 변수로 전환
    private volatile double cachedTpTrailActivatePct = 2.0;
    private volatile double cachedTpTrailDropPct = 1.0;
    // V109: 실시간 티어드 SL 캐시 (mainLoop에서 갱신, WebSocket에서 읽기)
    // V129: 기본값 30→60s (Grace 확장). mainLoop DB 갱신 전까지의 첫 tick 안전망.
    private volatile long cachedGracePeriodMs = 60_000L;
    private volatile long cachedWidePeriodMs = 15 * 60_000L;
    private volatile double cachedWideSlPct = 3.0;
    private volatile double cachedTightSlPct = 1.5;

    // V111: Split-Exit cached 변수 (WebSocket 스레드에서 읽기)
    private volatile boolean cachedSplitExitEnabled = false;
    private volatile double cachedSplitTpPct = 1.5;
    private volatile double cachedSplitRatio = 0.60;
    private volatile double cachedTrailDropAfterSplit = 1.2;
    // V115: 1차 매도 TRAIL drop %
    private volatile double cachedSplit1stTrailDrop = 0.5;
    // V129: SPLIT_1ST 체결 후 SPLIT_2ND_TRAIL 매도 쿨다운(ms). SL은 허용 (진짜 급락 방어).
    private volatile long cachedSplit1stCooldownMs = 60_000L;
    // V129: market → epochMs of SPLIT_1ST execution (쿨다운 기준점, 재시작 시 DB에서 복구)
    private final ConcurrentHashMap<String, Long> split1stExecutedAtMap = new ConcurrentHashMap<String, Long>();
    private volatile PriceUpdateListener priceListener;

    // 당일 09:00 시가 캐시 (Daily Change 계산용)
    private final ConcurrentHashMap<String, Double> dailyOpenPriceCache = new ConcurrentHashMap<String, Double>();
    private volatile int dailyOpenCacheDay = -1; // 캐시된 날짜(dayOfYear), 날짜 변경 시 초기화

    // Quick TP ticker
    private volatile ScheduledExecutorService tickerExec;
    private volatile ScheduledFuture<?> tickerFuture;

    // 스캐너 상태 (대시보드 폴링용)
    private volatile String statusText = "STOPPED";
    private volatile int scanCount = 0;
    private volatile int activePositions = 0;
    private volatile List<String> lastScannedMarkets = Collections.emptyList();
    private volatile long lastTickEpochMs = 0;

    // Parallel executor for candle fetching and strategy evaluation
    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(
            Math.min(Runtime.getRuntime().availableProcessors(), 8),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "allday-scanner-parallel-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    // Decision Log (대시보드에서 차단/실행 사유 확인용)
    private static final int MAX_DECISION_LOG = 200;
    private final Deque<ScannerDecision> decisionLog = new ArrayDeque<ScannerDecision>();

    // Hourly trade throttle: 3개 스캐너가 공유하는 단일 인스턴스
    // (2026-04-08 KRW-TREE 사고: 분리된 throttle로 같은 코인 동시 매수 발생)
    private final SharedTradeThrottle hourlyThrottle;

    // 진행 중인 매수/매도 마켓 (race condition 차단용 in-flight set)
    // (2026-04-09 KRW-CBK 사고: thread race fix 일관 적용)
    private final java.util.Set<String> buyingMarkets = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> sellingMarkets = ConcurrentHashMap.newKeySet();

    // 매도 후 재매수 쿨다운: 동일 마켓 매도 후 5분간 재매수 차단
    // (2026-04-11 추가: 오프닝 스캐너와 동일 방어. SUPER/BARD 재매수 사고 방지)
    private static final long SELL_COOLDOWN_MS = 300_000L;
    private final ConcurrentHashMap<String, Long> sellCooldownMap = new ConcurrentHashMap<String, Long>();

    // ── 실시간 WS 매수 감지용 가격 추적 (Change 1: 2026-04-10, 2026-04-11 rolling window 방식으로 변경) ──
    // market → Deque of [epochMs, price] — 최근 3분간 10초 간격 가격 스냅샷 유지
    private final ConcurrentHashMap<String, java.util.Deque<double[]>> realtimePriceHistory =
            new ConcurrentHashMap<String, java.util.Deque<double[]>>();
    private static final long PRICE_SNAPSHOT_INTERVAL_MS = 10_000L; // 10초마다 스냅샷
    private static final long PRICE_HISTORY_MAX_MS = 180_000L;      // 3분 보관
    private final ConcurrentHashMap<String, Long> surgeCheckCooldownMap = new ConcurrentHashMap<String, Long>();
    private final Set<String> wsBreakoutProcessing = ConcurrentHashMap.newKeySet();
    // 5분봉 tick() 매수 비활성화 (2026-04-11): 실시간 WS surge 매수만 사용
    private static final boolean TICK_BUY_ENABLED = false;

    private static final double SURGE_DETECT_PCT = 2.0;
    private static final double SURGE_MIN_DAILY_PCT = 1.5;
    private static final long SURGE_WINDOW_MS = 120_000L;   // 2분
    private static final long SURGE_COOLDOWN_MS = 30_000L;  // 같은 코인 30초 쿨다운

    // ── NewMarketListener (Change 3: 2026-04-10) ──
    private volatile NewMarketListener newMarketListener;

    // V130 ①: Trail Ladder cached 변수 (WebSocket 스레드에서 읽기)
    private volatile boolean cachedTrailLadderEnabled = true;
    private volatile double cachedSplit1stDropUnder2 = 0.50;
    private volatile double cachedSplit1stDropUnder3 = 1.00;
    private volatile double cachedSplit1stDropUnder5 = 1.50;
    private volatile double cachedSplit1stDropAbove5 = 2.00;
    private volatile double cachedTrailAfterDropUnder2 = 1.00;
    private volatile double cachedTrailAfterDropUnder3 = 1.20;
    private volatile double cachedTrailAfterDropUnder5 = 1.50;
    private volatile double cachedTrailAfterDropAbove5 = 2.00;

    // V130 ④: SPLIT_1ST roi 하한선 (0=비활성)
    private volatile double cachedSplit1stRoiFloorPct = 0.30;

    // V130 ②: L1 지연 진입용 단일 스레드 스케줄러
    private final ScheduledExecutorService l1DelayScheduler = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "ad-l1-delay");
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    private ScannerLockService scannerLockService;

    /** Result holder for parallel candle fetching */
    private static class CandleFetchResult {
        final String market;
        final List<UpbitCandle> candles;
        final Exception error;

        CandleFetchResult(String market, List<UpbitCandle> candles, Exception error) {
            this.market = market;
            this.candles = candles;
            this.error = error;
        }
    }

    /** BUY signal holder for Phase 2 -> Phase 3 handoff */
    private static class BuySignal {
        final String market;
        final UpbitCandle candle;
        final Signal signal;
        final List<UpbitCandle> candles;

        BuySignal(String market, UpbitCandle candle, Signal signal, List<UpbitCandle> candles) {
            this.market = market;
            this.candle = candle;
            this.signal = signal;
            this.candles = candles;
        }
    }

    public AllDayScannerService(AllDayScannerConfigRepository configRepo,
                                BotConfigRepository botConfigRepo,
                                PositionRepository positionRepo,
                                TradeRepository tradeLogRepo,
                                CandleService candleService,
                                UpbitMarketCatalogService catalogService,
                                LiveOrderService liveOrders,
                                UpbitPrivateClient privateClient,
                                TransactionTemplate txTemplate,
                                TickerService tickerService,
                                SharedPriceService sharedPriceService,
                                SharedTradeThrottle hourlyThrottle,
                                ScannerLockService scannerLockService) {
        this.configRepo = configRepo;
        this.botConfigRepo = botConfigRepo;
        this.positionRepo = positionRepo;
        this.tradeLogRepo = tradeLogRepo;
        this.candleService = candleService;
        this.catalogService = catalogService;
        this.liveOrders = liveOrders;
        this.privateClient = privateClient;
        this.txTemplate = txTemplate;
        this.tickerService = tickerService;
        this.sharedPriceService = sharedPriceService;
        this.hourlyThrottle = hourlyThrottle;
        this.scannerLockService = scannerLockService;
    }

    // ========== Decision Log ==========

    public static class ScannerDecision {
        public final long tsEpochMs;
        public final String market;
        public final String action;     // BUY, SELL, SKIP, BLOCKED
        public final String result;     // EXECUTED, BLOCKED, SKIPPED, ERROR
        public final String reasonCode; // BTC_FILTER, NO_SIGNAL, MAX_POS, INSUFFICIENT_KRW, etc.
        public final String reasonKo;   // 한글 설명

        public ScannerDecision(long ts, String market, String action, String result,
                               String reasonCode, String reasonKo) {
            this.tsEpochMs = ts;
            this.market = market;
            this.action = action;
            this.result = result;
            this.reasonCode = reasonCode;
            this.reasonKo = reasonKo;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("tsEpochMs", tsEpochMs);
            m.put("market", market);
            m.put("action", action);
            m.put("result", result);
            m.put("reasonCode", reasonCode);
            m.put("reasonKo", reasonKo);
            return m;
        }
    }

    private void addDecision(String market, String action, String result,
                              String reasonCode, String reasonKo) {
        ScannerDecision d = new ScannerDecision(
                System.currentTimeMillis(), market, action, result, reasonCode, reasonKo);
        synchronized (decisionLog) {
            decisionLog.addFirst(d);
            while (decisionLog.size() > MAX_DECISION_LOG) decisionLog.removeLast();
        }
        // 파일 로그 기록 (EXECUTED/ERROR는 이미 별도 로그가 있으므로 SKIP/BLOCKED만)
        if ("SKIPPED".equals(result) || "BLOCKED".equals(result) || "ERROR".equals(result)) {
            log.info("[AllDayScanner] {} {} {} {} | {}", market, action, result, reasonCode, reasonKo);
        }
    }

    public List<Map<String, Object>> getRecentDecisions(int limit) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        synchronized (decisionLog) {
            int count = 0;
            for (ScannerDecision d : decisionLog) {
                if (count >= limit) break;
                list.add(d.toMap());
                count++;
            }
        }
        return list;
    }

    // ========== Start / Stop ==========

    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            log.info("[AllDayScanner] already running");
            return false;
        }
        log.info("[AllDayScanner] starting...");
        statusText = "RUNNING";
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "allday-scanner");
                t.setDaemon(true);
                return t;
            }
        });

        // Register SharedPriceService listener for realtime TP + realtime BUY surge detection
        priceListener = new PriceUpdateListener() {
            @Override
            public void onPriceUpdate(String market, double price) {
                // V109: TP_TRAIL + 티어드 SL 통합 실시간 체크
                checkRealtimeTpSl(market, price);
                // Rolling window 가격 기록 + surge 감지 (2026-04-11 전면 재설계)
                long nowMs = System.currentTimeMillis();
                java.util.Deque<double[]> history = realtimePriceHistory.get(market);
                if (history == null) {
                    history = new java.util.concurrent.ConcurrentLinkedDeque<double[]>();
                    realtimePriceHistory.put(market, history);
                }
                // 10초 간격으로 스냅샷 추가
                if (history.isEmpty() || nowMs - (long) history.peekLast()[0] >= PRICE_SNAPSHOT_INTERVAL_MS) {
                    history.addLast(new double[]{nowMs, price});
                    // 3분 초과 데이터 제거
                    while (!history.isEmpty() && nowMs - (long) history.peekFirst()[0] > PRICE_HISTORY_MAX_MS) {
                        history.pollFirst();
                    }
                }
                // surge 감지
                checkRealtimeBuy(market, price);
            }
        };
        sharedPriceService.addGlobalListener(priceListener);

        scheduleTick();
        // Quick TP disabled — replaced by TP_TRAIL (2026-04-10)
        // startQuickTpTicker();

        // Change 3: Entry phase 시간대 등록 (DB 설정값 → SharedPriceService 10초 갱신 활성화)
        AllDayScannerConfigEntity initCfg = configRepo.loadOrCreate();
        sharedPriceService.registerEntryPhase(
                initCfg.getEntryStartHour(), initCfg.getEntryStartMin(),
                initCfg.getEntryEndHour(), initCfg.getEntryEndMin());

        // Change 3: 신규 TOP-N 마켓 콜백 — entry window 중 신규 마켓 즉시 추적
        newMarketListener = new NewMarketListener() {
            @Override
            public void onNewMarketsAdded(List<String> newMarkets) {
                if (!running.get()) return;
                AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();
                ZonedDateTime nowKst = ZonedDateTime.now(KST);
                int nowMinOfDay = nowKst.getHour() * 60 + nowKst.getMinute();
                int entryStart = cfg.getEntryStartHour() * 60 + cfg.getEntryStartMin();
                int entryEnd = cfg.getEntryEndHour() * 60 + cfg.getEntryEndMin();
                boolean inEntry;
                if (entryStart <= entryEnd) {
                    inEntry = nowMinOfDay >= entryStart && nowMinOfDay <= entryEnd;
                } else {
                    inEntry = nowMinOfDay >= entryStart || nowMinOfDay <= entryEnd;
                }
                if (!inEntry) return;

                for (String market : newMarkets) {
                    Double price = sharedPriceService.getPrice(market);
                    if (price != null && price > 0) {
                        java.util.Deque<double[]> h = new java.util.concurrent.ConcurrentLinkedDeque<double[]>();
                        h.addLast(new double[]{System.currentTimeMillis(), price});
                        realtimePriceHistory.put(market, h);
                        log.info("[AllDayScanner] 신규 TOP-N 동적 추가 추적: {} price={} (entry phase 중 감지)",
                                market, price);
                    }
                }
            }
        };
        sharedPriceService.addNewMarketListener(newMarketListener);

        return true;
    }

    public boolean stop() {
        if (!running.compareAndSet(true, false)) {
            log.info("[AllDayScanner] already stopped");
            return false;
        }
        log.info("[AllDayScanner] stopping...");
        statusText = "STOPPED";
        stopQuickTpTicker();
        if (priceListener != null) {
            sharedPriceService.removeGlobalListener(priceListener);
            priceListener = null;
        }
        if (newMarketListener != null) {
            sharedPriceService.removeNewMarketListener(newMarketListener);
            newMarketListener = null;
        }
        tpPositionCache.clear();
        realtimePriceHistory.clear();
        surgeCheckCooldownMap.clear();
        wsBreakoutProcessing.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        return true;
    }

    @PreDestroy
    public void destroy() {
        stopQuickTpTicker();
        if (priceListener != null) {
            sharedPriceService.removeGlobalListener(priceListener);
            priceListener = null;
        }
        if (newMarketListener != null) {
            sharedPriceService.removeNewMarketListener(newMarketListener);
            newMarketListener = null;
        }
        wsTpExecutor.shutdownNow();
        parallelExecutor.shutdownNow();
        log.info("[AllDayScanner] parallel executor shut down");
    }

    public boolean isRunning() { return running.get(); }
    public String getStatusText() { return statusText; }
    public int getScanCount() { return scanCount; }
    public int getActivePositions() { return activePositions; }
    public List<String> getLastScannedMarkets() { return lastScannedMarkets; }
    public long getLastTickEpochMs() { return lastTickEpochMs; }

    /** Status map for dashboard polling */
    public Map<String, Object> getStatus() {
        AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("statusText", statusText);
        status.put("scanCount", scanCount);
        status.put("lastTickEpochMs", lastTickEpochMs);
        status.put("activePositions", activePositions);
        status.put("lastScannedMarkets", lastScannedMarkets);
        status.put("positionCount", activePositions);
        status.put("maxPositions", cfg.getMaxPositions());
        return status;
    }

    /** Decision log for dashboard */
    public List<Map<String, Object>> getDecisions(int limit) {
        return getRecentDecisions(limit);
    }

    // ========== Scheduling ==========

    private void scheduleTick() {
        if (!running.get() || scheduler == null) return;
        AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();
        int unitMin = cfg.getCandleUnitMin();
        if (unitMin <= 0) unitMin = 5;

        // 다음 캔들 경계까지 대기
        long nowEpochSec = Instant.now().getEpochSecond();
        long epochMin = nowEpochSec / 60;
        long nextBoundaryMin = ((epochMin / unitMin) + 1) * unitMin;
        long delaySec = (nextBoundaryMin * 60) - nowEpochSec + 2; // 2초 버퍼
        if (delaySec <= 0) delaySec = 1;

        try {
            scheduler.schedule(this::tickWrapper, delaySec, TimeUnit.SECONDS);
            log.debug("[AllDayScanner] next tick in {}s (boundary={}min)", delaySec, nextBoundaryMin);
        } catch (Exception e) {
            log.error("[AllDayScanner] schedule failed", e);
        }
    }

    private void tickWrapper() {
        try {
            tick();
        } catch (Exception e) {
            log.error("[AllDayScanner] tick error", e);
        } finally {
            scheduleTick();
        }
    }

    // ========== Main Tick ==========

    private void tick() {
        if (!running.get()) return;

        AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isEnabled()) {
            statusText = "DISABLED";
            return;
        }

        // KST 현재 시각 확인
        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        int nowMinOfDay = nowKst.getHour() * 60 + nowKst.getMinute();
        int entryStart = cfg.getEntryStartHour() * 60 + cfg.getEntryStartMin();
        int entryEnd = cfg.getEntryEndHour() * 60 + cfg.getEntryEndMin();
        int sessionEnd = cfg.getSessionEndHour() * 60 + cfg.getSessionEndMin();

        // 엔트리 윈도우 체크 (overnight wrapping 지원)
        boolean inEntryWindow;
        if (entryStart <= entryEnd) {
            // 같은 날: 10:35 ~ 22:00
            inEntryWindow = nowMinOfDay >= entryStart && nowMinOfDay <= entryEnd;
        } else {
            // 자정 넘김: e.g. 10:35 ~ 08:00 next day
            inEntryWindow = nowMinOfDay >= entryStart || nowMinOfDay <= entryEnd;
        }

        // 세션 활성 여부 체크 (엔트리 시작 ~ 세션 종료 + 30분 여유)
        boolean inSession;
        if (entryStart <= sessionEnd) {
            inSession = nowMinOfDay >= entryStart && nowMinOfDay <= sessionEnd + 30;
        } else {
            // overnight session
            inSession = nowMinOfDay >= entryStart || nowMinOfDay <= sessionEnd + 30;
        }

        if (!inSession) {
            statusText = "IDLE (outside hours)";
            return;
        }

        statusText = "SCANNING";
        lastTickEpochMs = System.currentTimeMillis();

        // V109: 실시간 TP/SL cached 변수 갱신 (WebSocket 스레드에서 읽기)
        cachedTpTrailActivatePct = cfg.getTpTrailActivatePct().doubleValue();
        cachedTpTrailDropPct = cfg.getTpTrailDropPct().doubleValue();
        cachedGracePeriodMs = cfg.getGracePeriodSec() * 1000L;
        cachedWidePeriodMs = cfg.getWidePeriodMin() * 60_000L;
        cachedWideSlPct = cfg.getWideSlPct().doubleValue();
        cachedTightSlPct = cfg.getSlPct().doubleValue();

        // V111: Split-Exit cached 변수 갱신
        cachedSplitExitEnabled = cfg.isSplitExitEnabled();
        cachedSplitTpPct = cfg.getSplitTpPct().doubleValue();
        cachedSplitRatio = cfg.getSplitRatio().doubleValue();
        cachedTrailDropAfterSplit = cfg.getTrailDropAfterSplit().doubleValue();
        cachedSplit1stTrailDrop = cfg.getSplit1stTrailDrop().doubleValue();  // V115
        cachedSplit1stCooldownMs = cfg.getSplit1stCooldownSec() * 1000L;  // V129
        // V130 ①: Trail Ladder A
        cachedTrailLadderEnabled = cfg.isTrailLadderEnabled();
        cachedSplit1stDropUnder2 = cfg.getSplit1stDropUnder2().doubleValue();
        cachedSplit1stDropUnder3 = cfg.getSplit1stDropUnder3().doubleValue();
        cachedSplit1stDropUnder5 = cfg.getSplit1stDropUnder5().doubleValue();
        cachedSplit1stDropAbove5 = cfg.getSplit1stDropAbove5().doubleValue();
        cachedTrailAfterDropUnder2 = cfg.getTrailAfterDropUnder2().doubleValue();
        cachedTrailAfterDropUnder3 = cfg.getTrailAfterDropUnder3().doubleValue();
        cachedTrailAfterDropUnder5 = cfg.getTrailAfterDropUnder5().doubleValue();
        cachedTrailAfterDropAbove5 = cfg.getTrailAfterDropAbove5().doubleValue();
        // V130 ④: SPLIT_1ST roi 하한선
        cachedSplit1stRoiFloorPct = cfg.getSplit1stRoiFloorPct().doubleValue();

        String mode = cfg.getMode();
        boolean isLive = "LIVE".equalsIgnoreCase(mode);

        // LIVE 모드 API 키 사전 확인
        if (isLive && !liveOrders.isConfigured()) {
            statusText = "ERROR (API key)";
            addDecision("*", "TICK", "BLOCKED", "API_KEY_MISSING",
                    "LIVE 모드인데 업비트 API 키가 설정되지 않았습니다.");
            log.error("[AllDayScanner] LIVE 모드인데 업비트 API 키가 없습니다.");
            return;
        }

        // 전략 인스턴스 생성 (파라미터 오버라이드)
        HighConfidenceBreakoutStrategy strategy = new HighConfidenceBreakoutStrategy()
                .withRisk(cfg.getSlPct().doubleValue(), cfg.getTrailAtrMult().doubleValue())
                .withFilters(cfg.getVolumeSurgeMult().doubleValue(),
                        cfg.getMinBodyRatio().doubleValue(),
                        cfg.getMinConfidence().doubleValue())
                .withTiming(cfg.getSessionEndHour(), cfg.getSessionEndMin())
                .withTimeStop(cfg.getTimeStopCandles(), cfg.getTimeStopMinPnl().doubleValue())
                // HC_TRAIL 비활성화: 실시간 TP_TRAIL에 위임 (2026-04-11)
                // 999%로 설정하면 캔들 기반 trail은 절대 활성화 안 됨
                .withTrailActivate(TICK_BUY_ENABLED ? cfg.getTrailActivatePct() : 999.0)
                .withGracePeriod(cfg.getGracePeriodCandles())
                .withExitFlags(cfg.isEmaExitEnabled(), cfg.isMacdExitEnabled());

        int candleUnit = cfg.getCandleUnitMin();

        // 기존 보유 코인 제외 — 모든 포지션을 제외 (PK collision 방지)
        // V130 ⑤: dust 포지션은 보유로 간주하지 않음
        Set<String> ownedMarkets = new HashSet<String>();
        List<PositionEntity> allPositions = positionRepo.findAll();
        int scannerPosCount = 0;
        for (PositionEntity pe : allPositions) {
            if (!scannerLockService.isDustPosition(pe) && pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                ownedMarkets.add(pe.getMarket());
                if ("HIGH_CONFIDENCE_BREAKOUT".equals(pe.getEntryStrategy())) {
                    scannerPosCount++;
                }
            }
        }
        activePositions = scannerPosCount;

        // LIVE 모드: 업비트 실제 계좌 조회 (1회만 호출, 보유코인 제외 + KRW 잔고 확인에 재사용)
        List<UpbitAccount> cachedAccounts = null;
        double availableKrw = Double.MAX_VALUE;
        if (isLive && privateClient.isConfigured()) {
            try {
                cachedAccounts = privateClient.getAccounts();
                if (cachedAccounts != null) {
                    for (UpbitAccount a : cachedAccounts) {
                        if ("KRW".equals(a.currency)) {
                            availableKrw = a.balanceAsBigDecimal().doubleValue();
                            continue;
                        }
                        BigDecimal bal = a.balanceAsBigDecimal().add(a.lockedAsBigDecimal());
                        if (bal.compareTo(BigDecimal.ZERO) > 0) {
                            ownedMarkets.add("KRW-" + a.currency);
                        }
                    }
                }
                log.debug("[AllDayScanner] LIVE 보유코인 제외 목록: {}", ownedMarkets);
            } catch (Exception e) {
                log.warn("[AllDayScanner] 업비트 잔고 조회 실패, position table만 사용", e);
                addDecision("*", "TICK", "WARN", "ACCOUNT_QUERY_FAIL",
                        "업비트 계좌 조회 실패: " + e.getMessage());
            }
        }

        // 설정에서 수동 제외 마켓 추가 (추가 안전장치)
        ownedMarkets.addAll(cfg.getExcludeMarketsSet());

        // 거래대금 상위 N개 마켓 조회 (기존 보유 코인 + 제외 마켓 제외 + 저가 코인 필터)
        List<String> topMarkets = getTopMarketsByVolume(cfg.getTopN(), ownedMarkets, cfg.getMinPriceKrw());
        lastScannedMarkets = topMarkets;
        scanCount = topMarkets.size();

        // 당일 09:00 시가 캐시 수집 (날짜 변경 시 또는 첫 틱)
        int todayDoy = nowKst.getDayOfYear();
        if (dailyOpenCacheDay != todayDoy) {
            dailyOpenPriceCache.clear();
            dailyOpenCacheDay = todayDoy;
            log.info("[AllDayScanner] 당일 시가 캐시 초기화 (day={})", todayDoy);
        }
        if (dailyOpenPriceCache.isEmpty() && !topMarkets.isEmpty()) {
            collectDailyOpenPrices(topMarkets, candleUnit);
        }

        // BTC 방향 필터 (C안: 고확신 시그널은 바이패스)
        boolean btcAllowLong = true;
        if (cfg.isBtcFilterEnabled()) {
            btcAllowLong = checkBtcFilter(candleUnit, cfg.getBtcEmaPeriod());
            if (!btcAllowLong) {
                log.info("[AllDayScanner] BTC filter: close < EMA({}), 고확신 시그널만 진입 허용",
                        cfg.getBtcEmaPeriod());
            }
        }

        // ========== Phase 1: SELL (exit checks) - Parallel candle fetch + sequential sell ==========
        List<PositionEntity> scannerPositions = new ArrayList<PositionEntity>();
        for (PositionEntity pe : allPositions) {
            if (!"HIGH_CONFIDENCE_BREAKOUT".equals(pe.getEntryStrategy())) continue;
            if (pe.getQty() == null || pe.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;
            scannerPositions.add(pe);
        }

        // 실시간 TP WebSocket 동기화 (포지션 캐시 + 연결 관리)
        syncTpWebSocket(scannerPositions);

        if (!scannerPositions.isEmpty()) {
            // Submit parallel candle fetches for all scanner positions
            final int sellCandleUnit = candleUnit;
            Map<String, Future<CandleFetchResult>> sellFutures = new LinkedHashMap<String, Future<CandleFetchResult>>();
            for (final PositionEntity pe : scannerPositions) {
                sellFutures.put(pe.getMarket(), parallelExecutor.submit(new Callable<CandleFetchResult>() {
                    @Override
                    public CandleFetchResult call() {
                        try {
                            List<UpbitCandle> candles = candleService.getMinuteCandles(pe.getMarket(), sellCandleUnit, 80, null);
                            return new CandleFetchResult(pe.getMarket(), candles, null);
                        } catch (Exception e) {
                            return new CandleFetchResult(pe.getMarket(), null, e);
                        }
                    }
                }));
            }

            // Collect results and execute sells SEQUENTIALLY
            for (PositionEntity pe : scannerPositions) {
                try {
                    CandleFetchResult result = sellFutures.get(pe.getMarket()).get(30, TimeUnit.SECONDS);
                    if (result.error != null) {
                        log.error("[AllDayScanner] exit candle fetch failed for {}", pe.getMarket(), result.error);
                        addDecision(pe.getMarket(), "SELL", "ERROR", "EXIT_CHECK_FAIL",
                                "청산 캔들 조회 오류: " + result.error.getMessage());
                        continue;
                    }
                    List<UpbitCandle> candles = result.candles;
                    if (candles == null || candles.isEmpty()) continue;
                    candles = new ArrayList<UpbitCandle>(stripIncompleteCandle(candles, candleUnit));
                    if (candles.isEmpty()) continue;
                    Collections.reverse(candles);

                    StrategyContext ctx = new StrategyContext(pe.getMarket(), candleUnit, candles, pe, 0);
                    Signal signal = strategy.evaluate(ctx);

                    if (signal.action == SignalAction.SELL) {
                        // V111/V129: splitPhase=1이면 TIME_STOP 무시 (1차 수익 확보, 2차는 realtime SPLIT_2ND_TRAIL 또는 SL만)
                        // ※ TIME_STOP은 candle-based 경로(이 블록)로 처리되며, Grace 60s/쿨다운 60s는 WebSocket checkRealtimeTpSl에만 적용.
                        //   TIME_STOP 최소 발동(timeStopCandles × candleUnit, 기본 60분) ≫ Grace 60s ⇒ 물리적 겹침 없음.
                        //   위 splitPhase=1 스킵이 유일한 분기. 이 코멘트를 수정해야 하면 그 시점에 테스트도 함께 추가할 것.
                        if (pe.getSplitPhase() == 1 && signal.reason != null
                                && signal.reason.contains("TIME_STOP")) {
                            addDecision(pe.getMarket(), "SELL", "BLOCKED", "SPLIT_TIME_STOP_SKIP",
                                    "splitPhase=1 → TIME_STOP 비활성 (2차는 realtime SPLIT_2ND_TRAIL 또는 SL)");
                        } else {
                            executeSell(pe, candles.get(candles.size() - 1), signal, cfg);
                            addDecision(pe.getMarket(), "SELL", "EXECUTED", "SIGNAL",
                                    signal.reason);
                        }
                    }
                } catch (TimeoutException e) {
                    log.error("[AllDayScanner] exit candle fetch timeout for {}", pe.getMarket());
                    addDecision(pe.getMarket(), "SELL", "ERROR", "EXIT_CHECK_FAIL",
                            "청산 캔들 조회 타임아웃 (30초)");
                } catch (Exception e) {
                    log.error("[AllDayScanner] exit check failed for {}", pe.getMarket(), e);
                    addDecision(pe.getMarket(), "SELL", "ERROR", "EXIT_CHECK_FAIL",
                            "청산 체크 오류: " + e.getMessage());
                }
            }
        }

        // ════════════════════════════════════════════════════════════════
        // Phase 2,3 (5분봉 매수) 비활성화 — 실시간 WS surge 매수만 사용
        // ════════════════════════════════════════════════════════════════
        // 비활성화 일시: 2026-04-11 (사용자 요청)
        // 비활성화 이유:
        //   - 5분봉 tick()이 실시간 surge 감지보다 늦게 매수 → 고점 진입
        //   - AWE 케이스: surge 감지 전 5분봉이 고점에서 매수 → -4.06% 손실
        //   - MMT/ONT 케이스: 5분봉이 먼저 매수 → surge는 "이미 보유" 차단
        //   - 느린 상승(2분 내 2% 미달)은 돌파 모멘텀 약해 승률 낮음
        //   - 매수는 실시간 checkRealtimeBuy → processSurgeBuy 경로만 사용
        //
        // Phase 1(매도)은 유지: HC_SL(손절) + HC_SESSION_END(강제 청산)
        // HC_TRAIL(캔들 기반 익절)은 실시간 TP_TRAIL과 중복 → 비활성화
        //
        // 재활성화 조건:
        //   - 실시간 surge가 충분한 거래를 잡지 못하는 케이스 다수 발견 시
        //   - 재활성화 전 반드시 사용자 동의 필수
        // ════════════════════════════════════════════════════════════════
        // TICK_BUY_ENABLED는 클래스 상수 참조

        // ========== Phase 2: BUY signal detection - Parallel candle fetch + evaluation ==========
        // C안: BTC 필터는 per-signal로 바이패스하므로 canEnter 조건에서 제외
        boolean canEnter = TICK_BUY_ENABLED && scannerPosCount < cfg.getMaxPositions() && inEntryWindow;

        if (!canEnter && !inEntryWindow) {
            // 엔트리 윈도우 밖 — 매수 차단, 매도만 처리
        } else if (!canEnter) {
            if (!TICK_BUY_ENABLED) {
                addDecision("*", "BUY", "BLOCKED", "TICK_BUY_DISABLED",
                        "5분봉 tick 매수 비활성 — 실시간 WS surge 경로만 사용");
            } else {
                addDecision("*", "BUY", "BLOCKED", "MAX_POSITIONS",
                        String.format("최대 포지션 수(%d) 도달로 신규 진입 차단 (현재 %d)",
                                cfg.getMaxPositions(), scannerPosCount));
            }
        }

        // LIVE 모드에서 가용 KRW 잔고 확인
        BigDecimal orderKrw = calcOrderSize(cfg);
        if (canEnter && isLive && availableKrw < orderKrw.doubleValue()) {
            addDecision("*", "BUY", "BLOCKED", "INSUFFICIENT_KRW",
                    String.format("KRW 잔고 부족: 필요 %s원, 가용 %.0f원",
                            orderKrw.toPlainString(), availableKrw));
            log.warn("[AllDayScanner] KRW 잔고 부족: need={} available={}",
                    orderKrw, availableKrw);
            canEnter = false;
        }

        // Global Capital 한도 체크 (기본 전략 + 스캐너 전략 공유 풀)
        if (canEnter) {
            BigDecimal globalCap = getGlobalCapitalKrw();
            double totalInvested = calcTotalInvestedAllPositions();
            double remainingBudget = Math.max(0, globalCap.doubleValue() - totalInvested);

            if (orderKrw.doubleValue() > remainingBudget) {
                if (remainingBudget >= 5000) {
                    orderKrw = BigDecimal.valueOf(remainingBudget).setScale(0, RoundingMode.DOWN);
                    addDecision("*", "BUY", "PARTIAL", "CAPITAL_PARTIAL",
                            String.format("Global Capital 한도 내 부분 매수: 잔여 %.0f원 / 한도 %s원",
                                    remainingBudget, globalCap.toPlainString()));
                } else {
                    addDecision("*", "BUY", "BLOCKED", "CAPITAL_LIMIT",
                            String.format("Global Capital 한도 초과: 총 투입 %.0f원 / 한도 %s원",
                                    totalInvested, globalCap.toPlainString()));
                    canEnter = false;
                }
            }
        }

        if (canEnter) {
            // Filter entry candidates (exclude ALL already-held markets to prevent PK collision)
            // V130 ⑤: dust 포지션은 보유로 간주하지 않음
            List<String> entryCandidates = new ArrayList<String>();
            for (String market : topMarkets) {
                boolean alreadyHas = false;
                for (PositionEntity pe : allPositions) {
                    if (market.equals(pe.getMarket()) && !scannerLockService.isDustPosition(pe)
                            && pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                        alreadyHas = true;
                        break;
                    }
                }
                if (!alreadyHas) {
                    entryCandidates.add(market);
                }
            }

            // Submit parallel candle fetches + strategy evaluation for all candidates
            final int buyCandleUnit = candleUnit;
            final HighConfidenceBreakoutStrategy evalStrategy = strategy;
            final double cfgMinConfidence = cfg.getMinConfidence().doubleValue();
            Map<String, Future<CandleFetchResult>> buyFutures = new LinkedHashMap<String, Future<CandleFetchResult>>();
            for (final String market : entryCandidates) {
                buyFutures.put(market, parallelExecutor.submit(new Callable<CandleFetchResult>() {
                    @Override
                    public CandleFetchResult call() {
                        try {
                            List<UpbitCandle> candles = candleService.getMinuteCandles(market, buyCandleUnit, 80, null);
                            return new CandleFetchResult(market, candles, null);
                        } catch (Exception e) {
                            return new CandleFetchResult(market, null, e);
                        }
                    }
                }));
            }

            // Collect candle results and evaluate strategies — build BuySignal list
            List<BuySignal> buySignals = new ArrayList<BuySignal>();
            int entryAttempts = 0;

            for (String market : entryCandidates) {
                try {
                    CandleFetchResult result = buyFutures.get(market).get(30, TimeUnit.SECONDS);
                    if (result.error != null) {
                        log.error("[AllDayScanner] entry candle fetch failed for {}", market, result.error);
                        addDecision(market, "BUY", "ERROR", "ENTRY_CHECK_FAIL",
                                "진입 캔들 조회 오류: " + result.error.getMessage());
                        continue;
                    }
                    List<UpbitCandle> candles = result.candles;
                    if (candles == null || candles.isEmpty()) continue;
                    candles = new ArrayList<UpbitCandle>(stripIncompleteCandle(candles, candleUnit));
                    if (candles.isEmpty()) continue;
                    Collections.reverse(candles);

                    Double dop = dailyOpenPriceCache.get(market);
                    StrategyContext ctx = new StrategyContext(market, candleUnit, candles, null, 0,
                            Collections.<String, Integer>emptyMap(), dop != null ? dop : 0);
                    Signal signal = evalStrategy.evaluate(ctx);

                    entryAttempts++;
                    if (signal.action == SignalAction.BUY) {
                        // Service-level min confidence filter
                        if (signal.confidence < cfgMinConfidence) {
                            addDecision(market, "BUY", "BLOCKED", "LOW_CONFIDENCE",
                                    "score " + signal.confidence + " < min " + cfgMinConfidence);
                            continue;
                        }
                        // BTC filter: 고확신 시그널은 바이패스
                        if (!btcAllowLong) {
                            addDecision(market, "BUY", "BTC_BYPASS", "BTC_FILTER_BYPASS",
                                    String.format("BTC 약세지만 고확신(%.1f) 바이패스 진입", signal.confidence));
                        }
                        buySignals.add(new BuySignal(market, candles.get(candles.size() - 1), signal, candles));
                    } else {
                        // Mode 2: Surge Catcher — Mode 1 미통과 시 급등 조건 체크
                        // 조건: vol≥10x + body≥60% + bo≥0.3% + 시간 10:35~19:30 (양봉 필수 조건 제거됨)
                        boolean surgeEntry = false;
                        if (nowMinOfDay <= 21 * 60) { // 21:00 이전만
                            UpbitCandle lastCandle = candles.get(candles.size() - 1);
                            {
                                // Mode2 하락세 필터: EMA50 기울기 -0.3% 이상 하락이면 차단
                                boolean downtrend = false;
                                if (candles.size() >= 60) {
                                    double ema50now = Indicators.ema(candles, 50);
                                    List<UpbitCandle> prevCandles = candles.subList(0, candles.size() - 10);
                                    double ema50prev = Indicators.ema(prevCandles, 50);
                                    if (ema50now > 0 && ema50prev > 0) {
                                        double slopePct = (ema50now - ema50prev) / ema50prev * 100;
                                        if (slopePct < -0.3) {
                                            downtrend = true;
                                            addDecision(market, "BUY", "SKIPPED", "M2_DOWNTREND",
                                                    String.format(Locale.ROOT, "M2 하락세 차단 ema50slope=%.3f%%", slopePct));
                                        }
                                    }
                                }
                                if (downtrend) { /* skip */ }
                                else {
                                double avgVolM2 = Indicators.smaVolume(candles, 20);
                                double volRatioM2 = avgVolM2 > 0 ? lastCandle.candle_acc_trade_volume / avgVolM2 : 0;
                                double rangeM2 = lastCandle.high_price - lastCandle.low_price;
                                double bodyRatioM2 = rangeM2 > 0 ? Math.abs(lastCandle.trade_price - lastCandle.opening_price) / rangeM2 : 0;
                                double recentHighM2 = Indicators.recentHigh(candles, 20);
                                double boM2 = recentHighM2 > 0 ? (lastCandle.trade_price - recentHighM2) / recentHighM2 * 100 : 0;

                                // M2 day% 필터: 당일 2.8% 이상 상승한 코인만 진입
                                double dayPctM2 = 0;
                                Double dopM2 = dailyOpenPriceCache.get(market);
                                if (dopM2 != null && dopM2 > 0) {
                                    dayPctM2 = (lastCandle.trade_price - dopM2) / dopM2 * 100;
                                }
                                if (volRatioM2 >= 10.0 && bodyRatioM2 >= 0.60 && boM2 >= 0.3 && dayPctM2 >= 2.8) {
                                    surgeEntry = true;
                                    String surgeReason = String.format(Locale.ROOT,
                                            "[M2_SURGE] close=%.2f vol=%.1fx body=%.0f%% bo=%.2f%% day=%+.1f%%",
                                            lastCandle.trade_price, volRatioM2, bodyRatioM2 * 100, boM2, dayPctM2);
                                    log.info("[AllDayScanner] Mode2 surge detected: {} | {}", market, surgeReason);
                                    Signal surgeSignal = Signal.of(SignalAction.BUY,
                                            StrategyType.HIGH_CONFIDENCE_BREAKOUT, surgeReason, 10.0);
                                    buySignals.add(new BuySignal(market, lastCandle, surgeSignal, candles));
                                    addDecision(market, "BUY", "M2_SURGE", "SURGE_DETECTED", surgeReason);
                                }
                                } // else (not downtrend)
                            }
                        }
                        if (!surgeEntry) {
                            String rejectReason = signal.reason != null ? signal.reason : "UNKNOWN";
                            addDecision(market, "BUY", "SKIPPED", "NO_SIGNAL", rejectReason);
                        }
                    }
                } catch (TimeoutException e) {
                    log.error("[AllDayScanner] entry candle fetch timeout for {}", market);
                    addDecision(market, "BUY", "ERROR", "ENTRY_CHECK_FAIL",
                            "진입 캔들 조회 타임아웃 (30초)");
                } catch (Exception e) {
                    log.error("[AllDayScanner] entry check failed for {}", market, e);
                    addDecision(market, "BUY", "ERROR", "ENTRY_CHECK_FAIL",
                            "진입 체크 오류: " + e.getMessage());
                }
            }

            // ========== Phase 3: BUY execution - Sequential with capital tracking ==========
            // Sort by confidence (highest first)
            Collections.sort(buySignals, new Comparator<BuySignal>() {
                @Override
                public int compare(BuySignal a, BuySignal b) {
                    return Double.compare(b.signal.confidence, a.signal.confidence);
                }
            });

            int entrySuccess = 0;
            double spentKrw = 0;

            for (BuySignal bs : buySignals) {
                // 매도 후 재매수 쿨다운 체크 (SUPER/BARD 재매수 사고 방지, 2026-04-11)
                Long lastSellTime = sellCooldownMap.get(bs.market);
                if (lastSellTime != null && System.currentTimeMillis() - lastSellTime < SELL_COOLDOWN_MS) {
                    long remainSec = (SELL_COOLDOWN_MS - (System.currentTimeMillis() - lastSellTime)) / 1000;
                    addDecision(bs.market, "BUY", "BLOCKED", "SELL_COOLDOWN",
                            String.format("매도 후 %d초 쿨다운 남음", remainSec));
                    continue;
                }

                // 포지션 수 재확인
                if (scannerPosCount >= cfg.getMaxPositions()) {
                    addDecision(bs.market, "BUY", "BLOCKED", "MAX_POSITIONS",
                            String.format("최대 포지션 수(%d) 도달", cfg.getMaxPositions()));
                    break;
                }

                // Per-order capital checks: availableKrw (live wallet) and global capital limit
                if (isLive && availableKrw - spentKrw < orderKrw.doubleValue()) {
                    addDecision(bs.market, "BUY", "BLOCKED", "INSUFFICIENT_KRW",
                            String.format("KRW 잔고 부족(누적 차감): 필요 %s원, 가용 %.0f원 (이번 틱 사용 %.0f원)",
                                    orderKrw.toPlainString(), availableKrw - spentKrw, spentKrw));
                    log.warn("[AllDayScanner] {} KRW 잔고 부족(누적): need={} available={} spent={}",
                            bs.market, orderKrw, availableKrw - spentKrw, spentKrw);
                    continue;
                }
                BigDecimal globalCap2 = getGlobalCapitalKrw();
                double totalInvested2 = calcTotalInvestedAllPositions() + spentKrw;
                double remainingBudget2 = Math.max(0, globalCap2.doubleValue() - totalInvested2);
                if (orderKrw.doubleValue() > remainingBudget2) {
                    if (remainingBudget2 < 5000) {
                        addDecision(bs.market, "BUY", "BLOCKED", "CAPITAL_LIMIT",
                                String.format("Global Capital 한도 초과(누적): 투입 %.0f원+소비 %.0f원 / 한도 %s원",
                                        totalInvested2, spentKrw, globalCap2.toPlainString()));
                        continue;
                    }
                    orderKrw = BigDecimal.valueOf(remainingBudget2).setScale(0, RoundingMode.DOWN);
                }

                // ★ atomic throttle claim (race fix)
                if (!hourlyThrottle.tryClaim(bs.market)) {
                    addDecision(bs.market, "BUY", "BLOCKED", "HOURLY_LIMIT",
                            String.format("1시간 내 최대 2회 매매 제한 (남은 대기: %ds)", hourlyThrottle.remainingWaitMs(bs.market) / 1000));
                    continue;
                }

                // 결함 3: tick path L1 지연 진입 (delaySec초 후 현재가 >= 시그널가 확인)
                int adL1Sec = cfg.getL1DelaySec();
                if (adL1Sec > 0) {
                    final String fMarket = bs.market;
                    final double fSignalPrice = bs.candle.trade_price;
                    final UpbitCandle fCandle = bs.candle;
                    final Signal fSignal = bs.signal;
                    final AllDayScannerConfigEntity fCfg = cfg;
                    addDecision(bs.market, "BUY", "PENDING", "L1_DELAY",
                            String.format(Locale.ROOT, "L1 지연 %d초 후 가격 재확인 (tick)", adL1Sec));
                    l1DelayScheduler.schedule(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Double currentPrice = sharedPriceService.getPrice(fMarket);
                                if (currentPrice == null || currentPrice < fSignalPrice) {
                                    addDecision(fMarket, "BUY", "BLOCKED", "L1_NO_MOMENTUM",
                                            String.format(Locale.ROOT, "L1 지연 후 현재가 %s < 시그널가 %.2f — tick 꼬리매수 회피",
                                                    currentPrice != null ? String.format(Locale.ROOT, "%.2f", currentPrice) : "null", fSignalPrice));
                                    hourlyThrottle.releaseClaim(fMarket);
                                    return;
                                }
                                log.info("[AllDayScanner] tick L1_OK: {} currentPrice={} >= signalPrice={}",
                                        fMarket, currentPrice, fSignalPrice);
                                try {
                                    executeBuy(fMarket, fCandle, fSignal, fCfg);
                                    tpPositionCache.put(fMarket, new double[]{fSignalPrice, fSignalPrice, 0, fSignalPrice, System.currentTimeMillis(), 0, 0});
                                    addDecision(fMarket, "BUY", "EXECUTED", "SIGNAL", fSignal.reason);
                                } catch (Exception e) {
                                    hourlyThrottle.releaseClaim(fMarket);
                                    log.error("[AllDayScanner] tick L1 delayed buy failed for {}", fMarket, e);
                                    addDecision(fMarket, "BUY", "ERROR", "EXECUTION_FAIL",
                                            "tick L1 매수 오류: " + e.getMessage());
                                }
                            } catch (Exception e) {
                                log.warn("[AllDayScanner] tick L1 스케줄러 오류 {}: {}", fMarket, e.getMessage());
                                hourlyThrottle.releaseClaim(fMarket);
                            }
                        }
                    }, adL1Sec, TimeUnit.SECONDS);
                    spentKrw += orderKrw.doubleValue();
                    scannerPosCount++;
                    entrySuccess++;
                } else {
                    try {
                        executeBuy(bs.market, bs.candle, bs.signal, cfg);
                        spentKrw += orderKrw.doubleValue();
                        scannerPosCount++;
                        entrySuccess++;
                        // 실시간 TP_TRAIL 캐시에 즉시 등록 [avgPrice, peakPrice, activated, troughPrice, openedAtMs, splitPhase, split1stTrailArmed]
                        tpPositionCache.put(bs.market, new double[]{bs.candle.trade_price, bs.candle.trade_price, 0, bs.candle.trade_price, System.currentTimeMillis(), 0, 0});
                        addDecision(bs.market, "BUY", "EXECUTED", "SIGNAL", bs.signal.reason);
                    } catch (Exception e) {
                        // 매수 실패 → throttle 권한 반환
                        hourlyThrottle.releaseClaim(bs.market);
                        log.error("[AllDayScanner] buy execution failed for {}", bs.market, e);
                        addDecision(bs.market, "BUY", "ERROR", "EXECUTION_FAIL",
                                "매수 실행 오류: " + e.getMessage());
                    }
                }
            }

            // 틱 요약 로그
            log.info("[AllDayScanner] tick완료 mode={} markets={} attempts={} signals={} entries={} positions={}",
                    mode, topMarkets.size(), entryAttempts, buySignals.size(), entrySuccess, scannerPosCount);
        }

        activePositions = scannerPosCount;
        statusText = "SCANNING";
    }

    // ========== Order Execution ==========

    private void executeBuy(String market, UpbitCandle candle, Signal signal,
                             AllDayScannerConfigEntity cfg) {
        // ★ race 방어: in-flight 매수 차단
        if (!buyingMarkets.add(market)) {
            log.info("[AllDayScanner] BUY in progress, skip duplicate: {}", market);
            return;
        }
        try {
            executeBuyInner(market, candle, signal, cfg);
        } finally {
            buyingMarkets.remove(market);
        }
    }

    private void executeBuyInner(String market, UpbitCandle candle, Signal signal,
                                  AllDayScannerConfigEntity cfg) {
        double price = candle.trade_price;
        BigDecimal orderKrw = calcOrderSize(cfg);
        if (orderKrw.compareTo(BigDecimal.valueOf(5000)) < 0) {
            log.warn("[AllDayScanner] order too small: {} KRW for {}", orderKrw, market);
            addDecision(market, "BUY", "BLOCKED", "ORDER_TOO_SMALL",
                    String.format("주문 금액 %s원이 최소 5,000원 미만", orderKrw.toPlainString()));
            return;
        }

        // V130 ③: 스캐너 간 동일종목 재진입 차단 (executeBuyInner 최종 방어선)
        if (!scannerLockService.canEnter(market, "AD")) {
            addDecision(market, "BUY", "BLOCKED", "CROSS_SCANNER_COOLDOWN",
                    "타 스캐너 보유 또는 손실 쿨다운 — 중복 매수 차단");
            return;
        }

        // 사전 중복 포지션 차단 (KRW-TREE orphan 사고 재발 방지)
        // V130 ⑤: dust 포지션은 보유로 간주하지 않음
        PositionEntity existing = positionRepo.findById(market).orElse(null);
        if (existing != null && !scannerLockService.isDustPosition(existing)
                && existing.getQty() != null && existing.getQty().compareTo(BigDecimal.ZERO) > 0) {
            log.warn("[AllDayScanner] DUPLICATE_POSITION blocked: {} already held by {} qty={}",
                    market, existing.getEntryStrategy(), existing.getQty());
            addDecision(market, "BUY", "BLOCKED", "DUPLICATE_POSITION",
                    String.format("이미 보유 중 (전략=%s qty=%s) — 중복 매수 차단",
                            existing.getEntryStrategy(), existing.getQty().toPlainString()));
            return;
        }

        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        double qty;
        double fillPrice;

        if (isPaper) {
            // Paper: 슬리피지 0.1%, 수수료 0.05%
            fillPrice = price * 1.001;
            double fee = orderKrw.doubleValue() * 0.0005;
            qty = (orderKrw.doubleValue() - fee) / fillPrice;
        } else {
            // LIVE: 실제 업비트 주문
            if (!liveOrders.isConfigured()) {
                log.error("[AllDayScanner] LIVE 모드인데 업비트 키가 없습니다. market={}", market);
                addDecision(market, "BUY", "BLOCKED", "API_KEY_MISSING",
                        "LIVE 모드 API 키 미설정");
                return;
            }
            try {
                LiveOrderService.OrderResult r = liveOrders.placeBidPriceOrder(market, orderKrw.doubleValue());
                boolean actuallyFilled = r.isFilled() || r.executedVolume > 0;
                if (!actuallyFilled) {
                    log.warn("[AllDayScanner] LIVE buy pending/failed: market={} state={} vol={}",
                            market, r.state, r.executedVolume);
                    TradeEntity pendingLog = new TradeEntity();
                    pendingLog.setTsEpochMs(System.currentTimeMillis());
                    pendingLog.setMarket(market);
                    pendingLog.setAction("BUY_PENDING");
                    pendingLog.setPrice(BigDecimal.valueOf(price));
                    pendingLog.setQty(BigDecimal.ZERO);
                    pendingLog.setPnlKrw(BigDecimal.ZERO);
                    pendingLog.setRoiPercent(BigDecimal.ZERO);
                    pendingLog.setMode(cfg.getMode());
                    pendingLog.setPatternType("HIGH_CONFIDENCE_BREAKOUT");
                    pendingLog.setNote("state=" + r.state + " vol=" + r.executedVolume);
                    pendingLog.setCandleUnitMin(cfg.getCandleUnitMin());
                    tradeLogRepo.save(pendingLog);
                    addDecision(market, "BUY", "ERROR", "ORDER_NOT_FILLED",
                            String.format("주문 미체결 state=%s vol=%.8f", r.state, r.executedVolume));
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
                qty = r.executedVolume;
                // 404 즉시체결: executedVolume 조회 불가 -> 주문금액/가격으로 추정
                if (qty <= 0 && "order_not_found".equalsIgnoreCase(r.state)) {
                    qty = orderKrw.doubleValue() / fillPrice;
                    log.info("[AllDayScanner] BUY {} — 404 즉시체결 추정, qty~{} ({}원/{})",
                            market, String.format("%.8f", qty), orderKrw, fillPrice);
                } else if (qty <= 0) {
                    log.warn("[AllDayScanner] LIVE buy executedVolume=0 for {}", market);
                    addDecision(market, "BUY", "ERROR", "ZERO_VOLUME",
                            "체결 수량 0");
                    return;
                }
                log.info("[AllDayScanner] LIVE buy filled: market={} state={} price={} qty={}",
                        market, r.state, fillPrice, qty);
            } catch (Exception e) {
                log.error("[AllDayScanner] LIVE buy order failed for {}", market, e);
                addDecision(market, "BUY", "ERROR", "ORDER_EXCEPTION",
                        "주문 실패: " + e.getMessage());
                return;
            }
        }

        // 포지션 + 거래 로그를 원자적으로 저장
        final double fFillPrice = fillPrice;
        final double fQty = qty;
        txTemplate.execute(new org.springframework.transaction.support.TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                PositionEntity pe = new PositionEntity();
                pe.setMarket(market);
                pe.setQty(fQty);
                pe.setAvgPrice(fFillPrice);
                pe.setAddBuys(0);
                pe.setOpenedAt(Instant.now());
                pe.setEntryStrategy("HIGH_CONFIDENCE_BREAKOUT");
                positionRepo.save(pe);

                TradeEntity tl = new TradeEntity();
                tl.setTsEpochMs(System.currentTimeMillis());
                tl.setMarket(market);
                tl.setAction("BUY");
                tl.setPrice(BigDecimal.valueOf(fFillPrice));
                tl.setQty(BigDecimal.valueOf(fQty));
                tl.setPnlKrw(BigDecimal.ZERO);
                tl.setRoiPercent(BigDecimal.ZERO);
                tl.setMode(cfg.getMode());
                tl.setPatternType("HIGH_CONFIDENCE_BREAKOUT");
                tl.setPatternReason(signal.reason);
                tl.setEntrySignal(signal.reason); // V128
                tl.setConfidence(signal.confidence);
                tl.setCandleUnitMin(cfg.getCandleUnitMin());
                tradeLogRepo.save(tl);
            }
        });

        log.info("[AllDayScanner] BUY {} mode={} price={} qty={} conf={} reason={}",
                market, cfg.getMode(), fillPrice, qty, signal.confidence, signal.reason);
    }

    private void executeSell(PositionEntity pe, UpbitCandle candle, Signal signal,
                              AllDayScannerConfigEntity cfg) {
        // ★ race 방어: in-flight 매도 차단
        if (!sellingMarkets.add(pe.getMarket())) {
            log.debug("[AllDayScanner] SELL in progress, skip duplicate: {}", pe.getMarket());
            return;
        }
        try {
            executeSellInner(pe, candle, signal, cfg);
        } finally {
            sellingMarkets.remove(pe.getMarket());
        }
    }

    private void executeSellInner(PositionEntity pe, UpbitCandle candle, Signal signal,
                                   AllDayScannerConfigEntity cfg) {
        double price = candle.trade_price;
        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        double fillPrice;
        double qty = pe.getQty().doubleValue();

        if (isPaper) {
            fillPrice = price * 0.999; // 슬리피지 0.1%
        } else {
            // LIVE: 실제 업비트 시장가 매도
            if (!liveOrders.isConfigured()) {
                log.error("[AllDayScanner] LIVE 모드인데 업비트 키가 없습니다. market={}", pe.getMarket());
                addDecision(pe.getMarket(), "SELL", "BLOCKED", "API_KEY_MISSING",
                        "LIVE 모드 API 키 미설정");
                return;
            }
            try {
                LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(pe.getMarket(), qty);
                boolean actuallyFilled = r.isFilled() || r.executedVolume > 0;
                if (!actuallyFilled) {
                    log.warn("[AllDayScanner] LIVE sell pending/failed: market={} state={} vol={}",
                            pe.getMarket(), r.state, r.executedVolume);
                    addDecision(pe.getMarket(), "SELL", "ERROR", "ORDER_NOT_FILLED",
                            String.format("매도 미체결 state=%s vol=%.8f", r.state, r.executedVolume));
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
                if ("order_not_found".equalsIgnoreCase(r.state)) {
                    log.info("[AllDayScanner] SELL {} — 404 즉시체결 추정, 캔들가격 사용: {}",
                            pe.getMarket(), price);
                }
            } catch (Exception e) {
                log.error("[AllDayScanner] LIVE sell order failed for {}", pe.getMarket(), e);
                addDecision(pe.getMarket(), "SELL", "ERROR", "ORDER_EXCEPTION",
                        "매도 실패: " + e.getMessage());
                return;
            }
        }

        double avgPrice = pe.getAvgPrice().doubleValue();
        double pnlKrw = (fillPrice - avgPrice) * qty;
        double fee = fillPrice * qty * 0.0005;
        pnlKrw -= fee;
        double roiPct = avgPrice > 0 ? ((fillPrice - avgPrice) / avgPrice) * 100.0 : 0;

        // 거래 로그 + 포지션 삭제를 원자적으로 처리
        final double fFillPrice = fillPrice;
        final double fQty = qty;
        final double fPnlKrw = pnlKrw;
        final double fRoiPct = roiPct;
        final BigDecimal peAvgPrice = pe.getAvgPrice();
        final String peMarket = pe.getMarket();
        // P4-21: SESSION_END + splitPhase=1이면 note 구분
        final String sellNote = (pe.getSplitPhase() == 1 && signal.reason != null
                && signal.reason.contains("SESSION_END")) ? "SPLIT_SESSION_END" : null;
        // V128: tpPositionCache [1]=peakPrice, [6]=split1stTrailArmed
        final double[] cachePos = tpPositionCache.get(peMarket);
        final double fPeakPrice = cachePos != null && cachePos.length >= 2 ? cachePos[1] : 0;
        final boolean fArmed = cachePos != null && cachePos.length >= 7 && cachePos[6] > 0;
        txTemplate.execute(new org.springframework.transaction.support.TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                TradeEntity tl = new TradeEntity();
                tl.setTsEpochMs(System.currentTimeMillis());
                tl.setMarket(peMarket);
                tl.setAction("SELL");
                tl.setPrice(BigDecimal.valueOf(fFillPrice));
                tl.setQty(BigDecimal.valueOf(fQty));
                tl.setPnlKrw(BigDecimal.valueOf(fPnlKrw));
                tl.setRoiPercent(BigDecimal.valueOf(fRoiPct));
                tl.setMode(cfg.getMode());
                tl.setPatternType("HIGH_CONFIDENCE_BREAKOUT");
                tl.setPatternReason(signal.reason);
                tl.setAvgBuyPrice(peAvgPrice);
                tl.setConfidence(signal.confidence);
                tl.setCandleUnitMin(cfg.getCandleUnitMin());
                if (sellNote != null) tl.setNote(sellNote);
                // V128: peak/armed 로그 기록
                if (fPeakPrice > 0 && peAvgPrice != null && peAvgPrice.signum() > 0) {
                    tl.setPeakPrice(fPeakPrice);
                    double peakRoi = (fPeakPrice - peAvgPrice.doubleValue())
                            / peAvgPrice.doubleValue() * 100.0;
                    tl.setPeakRoiPct(BigDecimal.valueOf(peakRoi));
                }
                tl.setArmedFlag(fArmed ? "Y" : "N");
                tradeLogRepo.save(tl);

                positionRepo.deleteById(peMarket);
            }
        });

        log.info("[AllDayScanner] SELL {} price={} pnl={} roi={}% reason={}",
                pe.getMarket(), fillPrice, String.format("%.0f", pnlKrw),
                String.format("%.2f", roiPct), signal.reason);
        sellCooldownMap.put(pe.getMarket(), System.currentTimeMillis());
        split1stExecutedAtMap.remove(pe.getMarket());  // V129: 최종 매도 — 쿨다운 맵 제거
    }

    // ========== Quick TP Ticker ==========

    private void startQuickTpTicker() {
        AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isQuickTpEnabled()) {
            log.info("[AllDayScanner] Quick TP ticker disabled");
            return;
        }
        int intervalSec = Math.max(3, cfg.getQuickTpIntervalSec());
        tickerExec = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "allday-quick-tp-ticker");
                t.setDaemon(true);
                return t;
            }
        });
        tickerFuture = tickerExec.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    tickQuickTpFromTicker();
                } catch (Exception e) {
                    log.error("[AllDayScanner] Quick TP ticker error", e);
                }
            }
        }, intervalSec, intervalSec, TimeUnit.SECONDS);
        log.info("[AllDayScanner] Quick TP ticker started -- {}s interval, target {}%",
                intervalSec, cfg.getQuickTpPct());
    }

    private void stopQuickTpTicker() {
        if (tickerFuture != null) {
            tickerFuture.cancel(false);
            tickerFuture = null;
        }
        if (tickerExec != null) {
            tickerExec.shutdownNow();
            tickerExec = null;
        }
    }

    private void tickQuickTpFromTicker() {
        if (!running.get()) return;

        AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isQuickTpEnabled()) return;

        // 1. Get all HIGH_CONFIDENCE_BREAKOUT positions
        List<PositionEntity> allPos = positionRepo.findAll();
        List<PositionEntity> scannerPos = new ArrayList<PositionEntity>();
        List<String> markets = new ArrayList<String>();
        for (PositionEntity p : allPos) {
            if ("HIGH_CONFIDENCE_BREAKOUT".equals(p.getEntryStrategy())
                    && p.getQty() != null && p.getQty().compareTo(BigDecimal.ZERO) > 0) {
                scannerPos.add(p);
                markets.add(p.getMarket());
            }
        }
        if (scannerPos.isEmpty()) return;

        // 2. Batch fetch current prices
        Map<String, Double> prices = tickerService.getTickerPrices(markets);
        if (prices.isEmpty()) return;

        double baseQuickTpPct = cfg.getQuickTpPct();
        double slPct = cfg.getSlPct().doubleValue();

        // 3. Check each position
        for (PositionEntity pe : scannerPos) {
            Double currentPrice = prices.get(pe.getMarket());
            if (currentPrice == null || currentPrice <= 0) continue;

            double avgPrice = pe.getAvgPrice().doubleValue();
            if (avgPrice <= 0) continue;

            double pnlPct = (currentPrice - avgPrice) / avgPrice * 100.0;

            // 고신뢰(confidence >= 9.7) 포지션은 Quick TP 목표 상향 (최소 1.0%)
            double quickTpPct = baseQuickTpPct;
            try {
                TradeEntity buyTrade = tradeLogRepo.findTop1ByMarketAndActionOrderByTsEpochMsDesc(pe.getMarket(), "BUY");
                if (buyTrade != null && buyTrade.getConfidence() != null && buyTrade.getConfidence() >= 9.7) {
                    quickTpPct = Math.max(baseQuickTpPct, 1.0);
                }
            } catch (Exception e) {
                log.debug("[AllDayScanner] Failed to lookup confidence for {}: {}", pe.getMarket(), e.getMessage());
            }

            String sellReason = null;
            String sellType = null;

            // V118: splitExitEnabled=true면 QUICK_TP 전면 skip (splitPhase 무관)
            //  - QUICK_TP 고정목표 즉시매도가 TRAIL armed를 선점해 Split-Exit 무효화하는 버그 차단
            //  - 1차/2차 매도 모두 realtime checkRealtimeTpSl의 TRAIL(armed → peak → drop)에 위임
            if (!cfg.isSplitExitEnabled() && pnlPct >= quickTpPct) {
                sellReason = String.format(java.util.Locale.ROOT,
                        "QUICK_TP pnl=%.2f%% >= target=%.2f%% price=%.2f avg=%.2f",
                        pnlPct, quickTpPct, currentPrice, avgPrice);
                sellType = "QUICK_TP";
            }

            if (sellReason == null) continue;

            log.info("[AllDayScanner] {} triggered | {} | {}", sellType, pe.getMarket(), sellReason);

            // Re-check position (race condition protection)
            PositionEntity fresh = positionRepo.findById(pe.getMarket()).orElse(null);
            if (fresh == null || fresh.getQty() == null || fresh.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;

            // Execute sell
            try {
                executeQuickSell(fresh, currentPrice, pnlPct, sellReason, sellType, cfg);
            } catch (Exception e) {
                log.error("[AllDayScanner] Quick sell failed for {}", pe.getMarket(), e);
            }
        }
    }

    private void executeQuickSell(PositionEntity pe, double price, double pnlPct,
                                   String reason, String sellType, AllDayScannerConfigEntity cfg) {
        final String market = pe.getMarket();
        final double qty = pe.getQty().doubleValue();
        final double avgPrice = pe.getAvgPrice().doubleValue();

        double fillPrice;
        double fillQty;

        if ("PAPER".equalsIgnoreCase(cfg.getMode())) {
            fillPrice = price * 0.999; // 0.1% slippage
            fillQty = qty;
        } else {
            // LIVE mode
            if (!liveOrders.isConfigured()) {
                log.error("[AllDayScanner] Quick sell: LIVE mode but no API key for {}", market);
                return;
            }
            LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(market, qty);
            boolean filled = r.isFilled() || r.executedVolume > 0;
            if (!filled) {
                log.warn("[AllDayScanner] Quick sell order not filled for {}", market);
                return;
            }
            fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
            fillQty = r.executedVolume > 0 ? r.executedVolume : qty;
        }

        final double fFillPrice = fillPrice;
        final double fFillQty = fillQty;
        final double pnlKrw = (fFillPrice - avgPrice) * fFillQty;
        final double roiPct = avgPrice > 0 ? (fFillPrice - avgPrice) / avgPrice * 100.0 : 0;
        final String fReason = reason;
        final String fSellType = sellType;

        // V128: tpPositionCache [1]=peakPrice, [6]=split1stTrailArmed
        final double[] cachePos = tpPositionCache.get(market);
        final double fPeakPrice = cachePos != null && cachePos.length >= 2 ? cachePos[1] : 0;
        final boolean fArmed = cachePos != null && cachePos.length >= 7 && cachePos[6] > 0;

        // Atomic: trade_log + position delete
        txTemplate.execute(new org.springframework.transaction.support.TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                TradeEntity tl = new TradeEntity();
                tl.setTsEpochMs(System.currentTimeMillis());
                tl.setMarket(market);
                tl.setAction("SELL");
                tl.setPrice(BigDecimal.valueOf(fFillPrice));
                tl.setQty(BigDecimal.valueOf(fFillQty));
                tl.setPnlKrw(BigDecimal.valueOf(Math.round(pnlKrw)));
                tl.setRoiPercent(BigDecimal.valueOf(roiPct).setScale(2, RoundingMode.HALF_UP));
                tl.setMode(cfg.getMode());
                tl.setPatternType("HIGH_CONFIDENCE_BREAKOUT");
                tl.setPatternReason(fReason);
                tl.setAvgBuyPrice(BigDecimal.valueOf(avgPrice));
                tl.setCandleUnitMin(cfg.getCandleUnitMin());
                // V128: peak/armed 로그 기록
                if (fPeakPrice > 0 && avgPrice > 0) {
                    tl.setPeakPrice(fPeakPrice);
                    double peakRoi = (fPeakPrice - avgPrice) / avgPrice * 100.0;
                    tl.setPeakRoiPct(BigDecimal.valueOf(peakRoi));
                }
                tl.setArmedFlag(fArmed ? "Y" : "N");
                tradeLogRepo.save(tl);
                positionRepo.deleteById(market);
            }
        });

        log.info("[AllDayScanner] {} {} price={} pnl={} roi={}%",
                fSellType, market, fFillPrice, Math.round(pnlKrw), String.format("%.2f", roiPct));

        addDecision(market, "SELL", "EXECUTED", fSellType, fReason);
        sellCooldownMap.put(market, System.currentTimeMillis());
        split1stExecutedAtMap.remove(market);  // V129: 최종 매도 — 쿨다운 맵 제거
    }

    // ========== WebSocket Real-time TP_TRAIL + Tiered SL (V109: 2026-04-13) ==========

    /**
     * 실시간 TP/SL 통합 체크 (모닝러쉬 패턴):
     *
     * TP_TRAIL 활성화 후 (수익 +2% 도달한 적 있음):
     *   → TP_TRAIL 단독 관리 (피크 추적, 피크 대비 -1% drop 시 매도)
     *   → SL 비활성 (돌파 성공 확인됨)
     *
     * TP_TRAIL 미활성 (수익 +2% 미도달):
     *   Phase 1 Grace (0~60초, V129): SL/SPLIT/TP 포함 모든 매도 차단 (꼬리 흡수)
     *   Phase 2 Wide  (60초~15분):    SL_WIDE -3.0%
     *   Phase 3 Tight (15분~):        SL_TIGHT -1.5%
     *
     * WebSocket 스레드에서 호출 → DB 접근 금지 → wsTpExecutor에서 매도 실행.
     * positionCache: [avgPrice, peakPrice, trailActivated(0/1), troughPrice, openedAtEpochMs]
     */

    /**
     * V130 ①: Trail Ladder A — peak% 구간별 drop 임계값 반환.
     * cachedTrailLadderEnabled=false이면 기존 단일값 fallback.
     *
     * @param avgPrice   진입 평균가
     * @param peakPrice  현재까지의 최고가
     * @param isAfterSplit true=SPLIT_2ND_TRAIL, false=SPLIT_1ST
     */
    private double getDropForPeak(double avgPrice, double peakPrice, boolean isAfterSplit) {
        if (!cachedTrailLadderEnabled) {
            return isAfterSplit ? cachedTrailDropAfterSplit : cachedSplit1stTrailDrop;
        }
        double peakPct = avgPrice > 0 ? (peakPrice - avgPrice) / avgPrice * 100.0 : 0;
        if (isAfterSplit) {
            if (peakPct < 2) return cachedTrailAfterDropUnder2;
            if (peakPct < 3) return cachedTrailAfterDropUnder3;
            if (peakPct < 5) return cachedTrailAfterDropUnder5;
            return cachedTrailAfterDropAbove5;
        } else {
            if (peakPct < 2) return cachedSplit1stDropUnder2;
            if (peakPct < 3) return cachedSplit1stDropUnder3;
            if (peakPct < 5) return cachedSplit1stDropUnder5;
            return cachedSplit1stDropAbove5;
        }
    }

    private void checkRealtimeTpSl(final String market, final double price) {
        double[] pos = tpPositionCache.get(market);
        if (pos == null) return;

        double avgPrice = pos[0];
        if (avgPrice <= 0) return;
        double peakPrice = pos[1];
        boolean activated = pos[2] > 0;
        double troughPrice = pos.length >= 4 ? pos[3] : avgPrice;
        long openedAtMs = pos.length >= 5 ? (long) pos[4] : 0;
        int splitPhase = pos.length >= 6 ? (int) pos[5] : 0;

        double pnlPct = (price - avgPrice) / avgPrice * 100.0;
        long elapsedMs = openedAtMs > 0 ? System.currentTimeMillis() - openedAtMs : Long.MAX_VALUE;

        // 항상: 피크/트로프 업데이트
        if (price > peakPrice) {
            pos[1] = price;
            peakPrice = price;
        }
        if (price < troughPrice) {
            pos[3] = price;
            troughPrice = price;
        }

        // V129: Grace 가드 최상단 — SL/SPLIT/TP 포함 모든 매도 차단 (매수 직후 꼬리 흡수)
        if (elapsedMs < cachedGracePeriodMs) return;

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
                log.info("[AllDayScanner] SPLIT_1ST trail armed: {} pnl=+{}% peak={}",
                        market, String.format(Locale.ROOT, "%.2f", pnlPct),
                        String.format(Locale.ROOT, "%.2f", peakPrice));
            } else if (armed && peakPrice > avgPrice) {
                double dropFromPeakPct = (peakPrice - price) / peakPrice * 100.0;
                // V130 ①: Trail Ladder A — peak% 구간별 drop 임계값
                double dropThreshold1st = getDropForPeak(avgPrice, peakPrice, false);
                // V130 ④: roi 하한선 — current_roi < floor이면 SPLIT_1ST 차단
                boolean roiFloorOk = (cachedSplit1stRoiFloorPct <= 0) || (pnlPct >= cachedSplit1stRoiFloorPct);
                if (dropFromPeakPct >= dropThreshold1st && roiFloorOk) {
                    double troughPnl = (troughPrice - avgPrice) / avgPrice * 100.0;
                    sellType = "SPLIT_1ST";
                    isSplitFirst = true;
                    reason = String.format(Locale.ROOT,
                            "SPLIT_1ST_TRAIL avg=%.2f peak=%.2f now=%.2f drop=%.2f%% >= %.2f%% pnl=+%.2f%% ratio=%.0f%% trough=%.2f troughPnl=%.2f%% (realtime)",
                            avgPrice, peakPrice, price, dropFromPeakPct, dropThreshold1st,
                            pnlPct, cachedSplitRatio * 100, troughPrice, troughPnl);
                } else if (!roiFloorOk) {
                    log.debug("[AllDayScanner] SPLIT_1ST roi_floor blocked: {} pnl={}% < floor={}%",
                            market, String.format(Locale.ROOT, "%.2f", pnlPct), cachedSplit1stRoiFloorPct);
                }
            }
        }
        // ━━━ V129: Split-Exit 2차 관리 (splitPhase=1) — BEV 제거 + 쿨다운 게이트 ━━━
        else if (cachedSplitExitEnabled && splitPhase == 1) {
            // V129 쿨다운: SPLIT_1ST 체결 후 60초 동안 SPLIT_2ND_TRAIL 차단. SL은 아래 블록이 처리.
            Long execAt = split1stExecutedAtMap.get(market);
            boolean cooldownActive = false;
            if (execAt != null && cachedSplit1stCooldownMs > 0) {
                long sinceMs = System.currentTimeMillis() - execAt;
                if (sinceMs < cachedSplit1stCooldownMs) {
                    cooldownActive = true;
                    log.debug("[AllDayScanner] SPLIT_2ND cooldown active: {} since={}ms < {}ms",
                            market, sinceMs, cachedSplit1stCooldownMs);
                }
            }
            if (!cooldownActive && peakPrice > avgPrice) {
                // V129: SPLIT_2ND_BEV(pnlPct<=0 본전) 제거 — 꼬리 본전매도 후 반등 놓침 원인.
                // V130 ①: Trail Ladder A — 2차도 peak% 구간별 drop 임계값 적용.
                double dropFromPeak = (peakPrice - price) / peakPrice * 100.0;
                double dropThreshold2nd = getDropForPeak(avgPrice, peakPrice, true);
                if (dropFromPeak >= dropThreshold2nd) {
                    double troughPnl = (troughPrice - avgPrice) / avgPrice * 100.0;
                    sellType = "SPLIT_2ND_TRAIL";
                    reason = String.format(Locale.ROOT,
                            "SPLIT_2ND_TRAIL avg=%.2f peak=%.2f now=%.2f drop=%.2f%% >= %.2f%% pnl=%.2f%% trough=%.2f troughPnl=%.2f%% (realtime)",
                            avgPrice, peakPrice, price, dropFromPeak, dropThreshold2nd, pnlPct, troughPrice, troughPnl);
                }
            }
        }
        // ━━━ TP_TRAIL 활성화 + drop 판정 (splitPhase=0 전용) ━━━
        // splitPhase=1은 위에서 SPLIT_2ND_TRAIL로 이미 판정됨. splitPhase=-1(분할 불가) 시도 여기에 해당.
        if (sellType == null && splitPhase != 1) {
            if (!activated && pnlPct >= cachedTpTrailActivatePct) {
                pos[2] = 1.0;
                activated = true;
                log.info("[AllDayScanner] TP_TRAIL activated: {} pnl=+{}% peak={} (realtime)",
                        market, String.format(Locale.ROOT, "%.2f", pnlPct), peakPrice);
            }
            if (activated) {
                double dropFromPeak = (peakPrice - price) / peakPrice * 100.0;
                if (dropFromPeak >= cachedTpTrailDropPct) {
                    double troughPnl = (troughPrice - avgPrice) / avgPrice * 100.0;
                    sellType = "TP_TRAIL";
                    reason = String.format(Locale.ROOT,
                            "TP_TRAIL avg=%.2f peak=%.2f now=%.2f drop=%.2f%% pnl=%.2f%% trough=%.2f troughPnl=%.2f%% (realtime)",
                            avgPrice, peakPrice, price, dropFromPeak, pnlPct, troughPrice, troughPnl);
                }
            }
        }

        // ━━━ V129: 티어드 SL (splitPhase 무관 — 쿨다운 중이어도 SL은 허용) ━━━
        // Grace는 메서드 상단에서 이미 return 처리됨 → 여기서는 Wide/Tight만 처리.
        // splitPhase=0에서 TP_TRAIL activated이면 수익 보존 위해 SL 비활성.
        if (sellType == null && !(splitPhase == 0 && activated)) {
            if (elapsedMs < cachedWidePeriodMs) {
                if (pnlPct <= -cachedWideSlPct) {
                    double troughPnl = (troughPrice - avgPrice) / avgPrice * 100.0;
                    sellType = "SL_WIDE";
                    reason = String.format(Locale.ROOT,
                            "SL_WIDE pnl=%.2f%% <= -%.2f%% price=%.2f avg=%.2f trough=%.2f troughPnl=%.2f%% (realtime)",
                            pnlPct, cachedWideSlPct, price, avgPrice, troughPrice, troughPnl);
                }
            } else {
                if (pnlPct <= -cachedTightSlPct) {
                    double troughPnl = (troughPrice - avgPrice) / avgPrice * 100.0;
                    sellType = "SL_TIGHT";
                    reason = String.format(Locale.ROOT,
                            "SL_TIGHT pnl=%.2f%% <= -%.2f%% price=%.2f avg=%.2f trough=%.2f troughPnl=%.2f%% (realtime)",
                            pnlPct, cachedTightSlPct, price, avgPrice, troughPrice, troughPnl);
                }
            }
        }

        // 매도 실행
        if (sellType != null) {
            // V128: 매도 시점 armed/peak 스냅샷 — cache 리셋/제거 전에 캡처 (로깅용)
            final boolean fCapturedArmed = pos.length >= 7 && pos[6] > 0;
            final double fCapturedPeak = peakPrice;

            if (isSplitFirst) {
                // ★ V111: 1차 분할 매도 — 캐시 유지, splitPhase=1로 갱신
                final String fReason = reason;
                final double fPnlPct = pnlPct;
                final double fPrice = price;
                log.info("[AllDayScanner] {} triggered: {} | {}", sellType, market, fReason);

                try {
                    wsTpExecutor.submit(new Runnable() {
                        @Override
                        public void run() {
                            executeSplitFirstSellForWsTp(market, fPrice, fPnlPct, fReason,
                                    Boolean.valueOf(fCapturedArmed), Double.valueOf(fCapturedPeak));
                        }
                    });
                } catch (Exception e) {
                    log.error("[AllDayScanner] SPLIT_1ST schedule failed for {}", market, e);
                }
            } else {
                // ★ 기존 전량 매도 (2차 분할 매도 포함)
                if (tpPositionCache.remove(market) == null) return;  // 중복 방지

                final String fSellType = sellType;
                final String fReason = reason;
                final double fPnlPct = pnlPct;
                final double fTroughPrice = troughPrice;
                final double fTroughPnl = (troughPrice - avgPrice) / avgPrice * 100.0;
                final String fNote = sellType.startsWith("SPLIT_2ND") ? "SPLIT_2ND" : null;
                log.info("[AllDayScanner] {} triggered: {} | {}", fSellType, market, fReason);

                try {
                    wsTpExecutor.submit(new Runnable() {
                        @Override
                        public void run() {
                            executeSellForWsTp(market, price, fPnlPct, fTroughPrice, fTroughPnl, fNote,
                                    Boolean.valueOf(fCapturedArmed), Double.valueOf(fCapturedPeak));
                        }
                    });
                } catch (Exception e) {
                    log.error("[AllDayScanner] {} schedule failed for {}", fSellType, market, e);
                    tpPositionCache.put(market, new double[]{avgPrice, peakPrice, activated ? 1.0 : 0, troughPrice, openedAtMs, splitPhase, 0});
                }
            }
        }
    }

    // ========== WebSocket Real-time BUY Surge Detection (Change 1: 2026-04-10) ==========

    /**
     * 실시간 매수 감지: 2분 내 +1.5% 급등 시 즉시 캔들 페치 + HCB 스코어링.
     * WebSocket 스레드에서 호출 → 무거운 로직은 scheduler 스레드로 위임.
     */
    private void checkRealtimeBuy(final String market, final double price) {
        if (!running.get()) return;

        // 당일 시가 대비 최소 +1.5% 이상 체크 (2026-04-11 추가)
        Double dailyOpen = dailyOpenPriceCache.get(market);
        if (dailyOpen != null && dailyOpen > 0) {
            double dailyPct = (price - dailyOpen) / dailyOpen * 100.0;
            if (dailyPct < SURGE_MIN_DAILY_PCT) return;
        }

        // 쿨다운 체크
        Long lastCheck = surgeCheckCooldownMap.get(market);
        long now = System.currentTimeMillis();
        if (lastCheck != null && now - lastCheck < SURGE_COOLDOWN_MS) return;

        // Rolling window에서 2분 전 가격 조회
        java.util.Deque<double[]> history = realtimePriceHistory.get(market);
        if (history == null || history.size() < 2) return;

        // 2분 전(±30초) 스냅샷 찾기
        double prevPrice = 0;
        long prevTime = 0;
        long targetTime = now - SURGE_WINDOW_MS; // 2분 전
        for (double[] snap : history) {
            long snapTime = (long) snap[0];
            // targetTime 이전의 가장 가까운 스냅샷
            if (snapTime <= targetTime) {
                prevPrice = snap[1];
                prevTime = snapTime;
            }
        }
        if (prevPrice <= 0 || prevTime == 0) return;

        long elapsed = now - prevTime;
        if (elapsed < 30_000L) return; // 최소 30초 이상 경과

        double changePct = (price - prevPrice) / prevPrice * 100.0;
        if (changePct < SURGE_DETECT_PCT) return;

        // 중복 처리 방지
        if (!wsBreakoutProcessing.add(market)) return;

        // 쿨다운 등록
        surgeCheckCooldownMap.put(market, now);

        log.info("[AllDayScanner] WS surge detected: {} +{}% in {}s ({}→{})",
                market, String.format(Locale.ROOT, "%.2f", changePct),
                elapsed / 1000, prevPrice, price);

        // scheduler 스레드에서 캔들 페치 + 스코어링 실행 (WS 스레드에서 DB 접근 금지)
        final double fChangePct = changePct;
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        processSurgeBuy(market, price, fChangePct);
                    } catch (Exception e) {
                        log.error("[AllDayScanner] WS surge buy failed for {}", market, e);
                    } finally {
                        wsBreakoutProcessing.remove(market);
                    }
                }
            }, 0, TimeUnit.MILLISECONDS);
        } else {
            wsBreakoutProcessing.remove(market);
        }
    }

    /**
     * WS 급등 감지 후 실제 매수 프로세스 (scheduler 스레드에서 실행).
     * DB 접근 안전, 캔들 페치 + HCB 평가 + Mode 2 체크.
     */
    private void processSurgeBuy(String market, double wsPrice, double changePct) {
        AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isEnabled()) return;

        // 1. 엔트리 윈도우 체크
        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        int nowMinOfDay = nowKst.getHour() * 60 + nowKst.getMinute();
        int entryStart = cfg.getEntryStartHour() * 60 + cfg.getEntryStartMin();
        int entryEnd = cfg.getEntryEndHour() * 60 + cfg.getEntryEndMin();
        boolean inEntryWindow;
        if (entryStart <= entryEnd) {
            inEntryWindow = nowMinOfDay >= entryStart && nowMinOfDay <= entryEnd;
        } else {
            inEntryWindow = nowMinOfDay >= entryStart || nowMinOfDay <= entryEnd;
        }
        if (!inEntryWindow) {
            addDecision(market, "BUY", "BLOCKED", "WS_SURGE_OUTSIDE_ENTRY",
                    String.format(Locale.ROOT, "급등 감지(+%.2f%%)했지만 entry window 밖", changePct));
            return;
        }

        // 2. 포지션 수 체크 (V130 ⑤: dust 포지션은 보유로 간주하지 않음)
        int scannerPosCount = 0;
        List<PositionEntity> allPositions = positionRepo.findAll();
        Set<String> ownedMarkets = new HashSet<String>();
        for (PositionEntity pe : allPositions) {
            if (!scannerLockService.isDustPosition(pe) && pe.getQty() != null
                    && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                ownedMarkets.add(pe.getMarket());
                if ("HIGH_CONFIDENCE_BREAKOUT".equals(pe.getEntryStrategy())) {
                    scannerPosCount++;
                }
            }
        }
        if (scannerPosCount >= cfg.getMaxPositions()) {
            addDecision(market, "BUY", "BLOCKED", "WS_SURGE_MAX_POS",
                    String.format(Locale.ROOT, "급등 감지(+%.2f%%)했지만 최대 포지션(%d) 도달",
                            changePct, cfg.getMaxPositions()));
            return;
        }

        // 이미 보유 중이면 스킵
        if (ownedMarkets.contains(market)) {
            addDecision(market, "BUY", "BLOCKED", "WS_SURGE_ALREADY_HELD",
                    String.format(Locale.ROOT, "급등 감지(+%.2f%%)했지만 이미 보유 중", changePct));
            return;
        }

        // 매도 후 재매수 쿨다운 (2026-04-11)
        Long lastSellTime = sellCooldownMap.get(market);
        if (lastSellTime != null && System.currentTimeMillis() - lastSellTime < SELL_COOLDOWN_MS) {
            long remainSec = (SELL_COOLDOWN_MS - (System.currentTimeMillis() - lastSellTime)) / 1000;
            addDecision(market, "BUY", "BLOCKED", "WS_SURGE_SELL_COOLDOWN",
                    String.format(Locale.ROOT, "급등 감지(+%.2f%%)했지만 매도 후 %d초 쿨다운", changePct, remainSec));
            return;
        }

        // 제외 마켓 체크
        if (cfg.getExcludeMarketsSet().contains(market)) return;

        // V130 ③: 스캐너 간 동일종목 재진입 차단
        if (!scannerLockService.canEnter(market, "AD")) {
            addDecision(market, "BUY", "BLOCKED", "CROSS_SCANNER_COOLDOWN",
                    String.format(Locale.ROOT, "급등 감지(+%.2f%%)했지만 타 스캐너 보유 또는 손실 쿨다운", changePct));
            return;
        }

        // 3. Hourly throttle
        if (!hourlyThrottle.tryClaim(market)) {
            addDecision(market, "BUY", "BLOCKED", "WS_SURGE_HOURLY_LIMIT",
                    String.format(Locale.ROOT, "급등 감지(+%.2f%%)했지만 시간당 매매 제한",
                            changePct));
            return;
        }

        try {
            // 4. 캔들 페치 (80개 5분봉 — 미완성 캔들 포함, WS 급등 path의 핵심 차이)
            int candleUnit = cfg.getCandleUnitMin();
            List<UpbitCandle> candles = candleService.getMinuteCandles(market, candleUnit, 80, null);
            if (candles == null || candles.isEmpty()) {
                addDecision(market, "BUY", "ERROR", "WS_SURGE_NO_CANDLES",
                        "급등 감지 후 캔들 조회 실패");
                hourlyThrottle.releaseClaim(market);
                return;
            }
            // ★ WS path는 미완성 캔들을 제거하지 않음 (실시간 데이터 반영)
            Collections.reverse(candles);

            // 5. HCB 전략 평가
            HighConfidenceBreakoutStrategy strategy = new HighConfidenceBreakoutStrategy()
                    .withRisk(cfg.getSlPct().doubleValue(), cfg.getTrailAtrMult().doubleValue())
                    .withFilters(cfg.getVolumeSurgeMult().doubleValue(),
                            cfg.getMinBodyRatio().doubleValue(),
                            cfg.getMinConfidence().doubleValue())
                    .withTiming(cfg.getSessionEndHour(), cfg.getSessionEndMin())
                    .withTimeStop(cfg.getTimeStopCandles(), cfg.getTimeStopMinPnl().doubleValue())
                    .withTrailActivate(cfg.getTrailActivatePct())
                    .withGracePeriod(cfg.getGracePeriodCandles())
                    .withExitFlags(cfg.isEmaExitEnabled(), cfg.isMacdExitEnabled());

            Double dop = dailyOpenPriceCache.get(market);
            StrategyContext ctx = new StrategyContext(market, candleUnit, candles, null, 0,
                    Collections.<String, Integer>emptyMap(), dop != null ? dop : 0);
            Signal signal = strategy.evaluate(ctx);

            boolean bought = false;

            if (signal.action == SignalAction.BUY && signal.confidence >= cfg.getMinConfidence().doubleValue()) {
                // V109: 추가 진입 필터 (RSI / 거래량 / EMA21 방향 / 급등 방향 확인)
                UpbitCandle lastC = candles.get(candles.size() - 1);

                // (a) RSI 과매수 차단 — 손실 7건 전부 RSI 80+ (평균 -5.1%)
                double rsi14 = Indicators.rsi(candles, 14);
                int maxRsi = cfg.getMaxEntryRsi();
                if (rsi14 >= maxRsi) {
                    addDecision(market, "BUY", "SKIPPED", "RSI_OVERBOUGHT",
                            String.format(Locale.ROOT, "RSI %.0f >= %d", rsi14, maxRsi));
                    hourlyThrottle.releaseClaim(market);
                    return;
                }

                // (b) 최소 거래량 강화 — vol 2~4x 거래 19건 수익 0건
                double avgVol = Indicators.smaVolume(candles, 20);
                double curVol = lastC.candle_acc_trade_volume;
                double volRatio = avgVol > 0 ? curVol / avgVol : 0;
                double minVol = cfg.getMinVolumeMult().doubleValue();
                if (volRatio < minVol) {
                    addDecision(market, "BUY", "SKIPPED", "VOL_WEAK",
                            String.format(Locale.ROOT, "vol %.1fx < %.1fx", volRatio, minVol));
                    hourlyThrottle.releaseClaim(market);
                    return;
                }

                // (c) EMA21 방향 UP 필수
                if (candles.size() >= 26) {
                    double ema21now = Indicators.ema(candles, 21);
                    List<UpbitCandle> prevCandles5 = candles.subList(0, candles.size() - 5);
                    double ema21prev = Indicators.ema(prevCandles5, 21);
                    if (!Double.isNaN(ema21now) && !Double.isNaN(ema21prev) && ema21now < ema21prev) {
                        addDecision(market, "BUY", "SKIPPED", "EMA21_DOWN",
                                String.format(Locale.ROOT, "ema21 %.2f < prev %.2f (하락 추세)", ema21now, ema21prev));
                        hourlyThrottle.releaseClaim(market);
                        return;
                    }
                }

                // (d) 급등 방향 확인: 캔들 종가 대비 -0.2% 이내
                if (wsPrice > lastC.trade_price * 0.998) {
                    // 가격이 캔들 종가 대비 -0.2% 이내 = 아직 유효
                } else {
                    addDecision(market, "BUY", "SKIPPED", "SURGE_FADING",
                            String.format(Locale.ROOT, "wsPrice=%.2f < candle=%.2f (급등 꺾임)", wsPrice, lastC.trade_price));
                    hourlyThrottle.releaseClaim(market);
                    return;
                }

                // 모든 필터 통과 → V130 ②: L1 지연 진입 (delaySec초 후 가격 재확인)
                int delaySec = cfg.getL1DelaySec();
                final UpbitCandle fLastC = lastC;
                final Signal fSignal = signal;
                final double fWsPrice = wsPrice;
                if (delaySec > 0) {
                    log.info("[AllDayScanner] WS surge → L1 지연 {}초: {}", delaySec, market);
                    addDecision(market, "BUY", "PENDING", "L1_DELAY",
                            String.format(Locale.ROOT, "L1 지연 %d초 후 가격 재확인: %s", delaySec, market));
                    try {
                        Thread.sleep((long) delaySec * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        hourlyThrottle.releaseClaim(market);
                        return;
                    }
                    Double currentPrice = sharedPriceService.getPrice(market);
                    if (currentPrice == null || currentPrice < fWsPrice) {
                        addDecision(market, "BUY", "BLOCKED", "L1_NO_MOMENTUM",
                                String.format(Locale.ROOT, "L1 가격 하락 — 시그널가=%.2f 현재가=%s",
                                        fWsPrice, currentPrice == null ? "null" : String.format(Locale.ROOT, "%.2f", currentPrice)));
                        log.info("[AllDayScanner] L1 차단: {} signalPrice={} currentPrice={}",
                                market, fWsPrice, currentPrice);
                        hourlyThrottle.releaseClaim(market);
                        return;
                    }
                }
                log.info("[AllDayScanner] WS surge → HCB BUY: {} conf={} rsi={} vol={}x | {}",
                        market, fSignal.confidence,
                        String.format(Locale.ROOT, "%.0f", rsi14),
                        String.format(Locale.ROOT, "%.1f", volRatio),
                        fSignal.reason);
                executeBuy(market, fLastC, fSignal, cfg);
                bought = true;
                // V109: TP/SL 캐시 즉시 등록 [avgPrice, peakPrice, activated, troughPrice, openedAtMs, splitPhase, split1stTrailArmed]
                tpPositionCache.put(market, new double[]{fWsPrice, fWsPrice, 0, fWsPrice, System.currentTimeMillis(), 0, 0});
                addDecision(market, "BUY", "EXECUTED", "WS_SURGE_HCB", fSignal.reason);
            }

            // 6. Mode 2 Surge Catcher (HCB 미통과 시) — V109: RSI/EMA 필터 동일 적용
            // 양봉 필수 조건 제거됨 (2026-04-22): BEARISH 무조건 skip 철폐 방침에 따라
            if (!bought && nowMinOfDay <= 21 * 60) {
                UpbitCandle lastCandle = candles.get(candles.size() - 1);
                {
                    // Mode2 하락세 필터
                    boolean downtrend = false;
                    if (candles.size() >= 60) {
                        double ema50now = Indicators.ema(candles, 50);
                        List<UpbitCandle> prevCandles = candles.subList(0, candles.size() - 10);
                        double ema50prev = Indicators.ema(prevCandles, 50);
                        if (ema50now > 0 && ema50prev > 0) {
                            double slopePct = (ema50now - ema50prev) / ema50prev * 100;
                            if (slopePct < -0.3) {
                                downtrend = true;
                                addDecision(market, "BUY", "SKIPPED", "WS_M2_DOWNTREND",
                                        String.format(Locale.ROOT, "WS M2 하락세 차단 ema50slope=%.3f%%", slopePct));
                            }
                        }
                    }
                    // V109: Mode2에도 RSI/EMA 필터 동일 적용
                    if (!downtrend) {
                        double rsiM2 = Indicators.rsi(candles, 14);
                        if (rsiM2 >= cfg.getMaxEntryRsi()) {
                            downtrend = true;  // RSI 과매수 → 차단
                            addDecision(market, "BUY", "SKIPPED", "WS_M2_RSI",
                                    String.format(Locale.ROOT, "M2 RSI %.0f >= %d", rsiM2, cfg.getMaxEntryRsi()));
                        }
                    }
                    if (!downtrend && candles.size() >= 26) {
                        double ema21M2 = Indicators.ema(candles, 21);
                        double ema21PrevM2 = Indicators.ema(candles.subList(0, candles.size() - 5), 21);
                        if (!Double.isNaN(ema21M2) && !Double.isNaN(ema21PrevM2) && ema21M2 < ema21PrevM2) {
                            downtrend = true;
                            addDecision(market, "BUY", "SKIPPED", "WS_M2_EMA21_DOWN",
                                    String.format(Locale.ROOT, "M2 ema21 %.2f < prev %.2f", ema21M2, ema21PrevM2));
                        }
                    }
                    if (!downtrend) {
                        double avgVolM2 = Indicators.smaVolume(candles, 20);
                        double volRatioM2 = avgVolM2 > 0 ? lastCandle.candle_acc_trade_volume / avgVolM2 : 0;
                        double rangeM2 = lastCandle.high_price - lastCandle.low_price;
                        double bodyRatioM2 = rangeM2 > 0 ? Math.abs(lastCandle.trade_price - lastCandle.opening_price) / rangeM2 : 0;
                        double recentHighM2 = Indicators.recentHigh(candles, 20);
                        double boM2 = recentHighM2 > 0 ? (lastCandle.trade_price - recentHighM2) / recentHighM2 * 100 : 0;

                        double dayPctM2 = 0;
                        Double dopM2 = dailyOpenPriceCache.get(market);
                        if (dopM2 != null && dopM2 > 0) {
                            dayPctM2 = (lastCandle.trade_price - dopM2) / dopM2 * 100;
                        }
                        if (volRatioM2 >= 10.0 && bodyRatioM2 >= 0.60 && boM2 >= 0.3 && dayPctM2 >= 2.8) {
                            String surgeReason = String.format(Locale.ROOT,
                                    "[WS_M2_SURGE] close=%.2f vol=%.1fx body=%.0f%% bo=%.2f%% day=%+.1f%%",
                                    lastCandle.trade_price, volRatioM2, bodyRatioM2 * 100, boM2, dayPctM2);
                            log.info("[AllDayScanner] WS Mode2 surge: {} | {}", market, surgeReason);
                            Signal surgeSignal = Signal.of(SignalAction.BUY,
                                    StrategyType.HIGH_CONFIDENCE_BREAKOUT, surgeReason, 10.0);
                            // V130 ②: L1 지연 진입 (M2도 동일 적용)
                            int m2DelaySec = cfg.getL1DelaySec();
                            if (m2DelaySec > 0) {
                                addDecision(market, "BUY", "PENDING", "L1_DELAY_M2",
                                        String.format(Locale.ROOT, "M2 L1 지연 %d초 후 가격 재확인", m2DelaySec));
                                try {
                                    Thread.sleep((long) m2DelaySec * 1000L);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    hourlyThrottle.releaseClaim(market);
                                    return;
                                }
                                Double currentPriceM2 = sharedPriceService.getPrice(market);
                                if (currentPriceM2 == null || currentPriceM2 < wsPrice) {
                                    addDecision(market, "BUY", "BLOCKED", "L1_NO_MOMENTUM_M2",
                                            String.format(Locale.ROOT, "M2 L1 가격 하락 — 시그널가=%.2f 현재가=%s",
                                                    wsPrice, currentPriceM2 == null ? "null" : String.format(Locale.ROOT, "%.2f", currentPriceM2)));
                                    hourlyThrottle.releaseClaim(market);
                                    return;
                                }
                            }
                            executeBuy(market, lastCandle, surgeSignal, cfg);
                            bought = true;
                            tpPositionCache.put(market, new double[]{wsPrice, wsPrice, 0, wsPrice, System.currentTimeMillis(), 0, 0});
                            addDecision(market, "BUY", "EXECUTED", "WS_M2_SURGE", surgeReason);
                        }
                    }
                }
            }

            if (!bought) {
                // 매수 안 됨 → throttle 반환
                hourlyThrottle.releaseClaim(market);
                String rejectReason = signal.reason != null ? signal.reason : "NO_SIGNAL";
                addDecision(market, "BUY", "SKIPPED", "WS_SURGE_NO_SIGNAL",
                        String.format(Locale.ROOT, "급등(+%.2f%%) 감지 후 HCB/M2 미통과: %s",
                                changePct, rejectReason));
            }
        } catch (Exception e) {
            hourlyThrottle.releaseClaim(market);
            log.error("[AllDayScanner] WS surge processing failed for {}", market, e);
            addDecision(market, "BUY", "ERROR", "WS_SURGE_ERROR",
                    "급등 처리 오류: " + e.getMessage());
        }
    }

    /**
     * WebSocket TP 매도 실행 (wsTpExecutor 스레드에서 호출, DB 접근 안전).
     */
    private void executeSellForWsTp(String market, double wsPrice, double pnlPct,
                                     double troughPrice, double troughPnl, String note) {
        executeSellForWsTp(market, wsPrice, pnlPct, troughPrice, troughPnl, note, null, null);
    }

    private void executeSellForWsTp(String market, double wsPrice, double pnlPct,
                                     double troughPrice, double troughPnl, String note,
                                     Boolean capturedArmed, Double capturedPeak) {
        // ★ race 방어: in-flight 매도 차단 (WS TP path와 mainLoop monitorPositions 동시 매도 가능)
        if (!sellingMarkets.add(market)) {
            log.debug("[AllDayScanner] WS_TP SELL in progress, skip duplicate: {}", market);
            return;
        }
        try {
            executeSellForWsTpInner(market, wsPrice, pnlPct, troughPrice, troughPnl, note, capturedArmed, capturedPeak);
        } finally {
            sellingMarkets.remove(market);
        }
    }

    private void executeSellForWsTpInner(String market, double wsPrice, double pnlPct,
                                          double troughPrice, double troughPnl, final String note) {
        executeSellForWsTpInner(market, wsPrice, pnlPct, troughPrice, troughPnl, note, null, null);
    }

    private void executeSellForWsTpInner(String market, double wsPrice, double pnlPct,
                                          double troughPrice, double troughPnl, final String note,
                                          Boolean capturedArmed, Double capturedPeak) {
        PositionEntity pe = positionRepo.findById(market).orElse(null);
        if (pe == null || pe.getQty() == null || pe.getQty().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[AllDayScanner] WS_TP: position not found for {} (already sold?)", market);
            return;
        }

        AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();
        double qty = pe.getQty().doubleValue();
        double avgPrice = pe.getAvgPrice().doubleValue();
        double fillPrice;

        if ("PAPER".equalsIgnoreCase(cfg.getMode())) {
            fillPrice = wsPrice * 0.999; // 0.1% slippage
        } else {
            if (!liveOrders.isConfigured()) {
                log.error("[AllDayScanner] WS_TP: LIVE mode but no API key for {}", market);
                return;
            }
            try {
                LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(market, qty);
                boolean filled = r.isFilled() || r.executedVolume > 0;
                if (!filled) {
                    log.warn("[AllDayScanner] WS_TP: sell not filled for {}", market);
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : wsPrice;
            } catch (Exception e) {
                log.error("[AllDayScanner] WS_TP: sell order failed for {}", market, e);
                return;
            }
        }

        final double fFillPrice = fillPrice;
        final double fQty = qty;
        final double pnlKrw = (fFillPrice - avgPrice) * fQty;
        final double roiPct = avgPrice > 0 ? (fFillPrice - avgPrice) / avgPrice * 100.0 : 0;
        final String reason = String.format(Locale.ROOT,
                "TP_TRAIL pnl=%.2f%% price=%.2f avg=%.2f trough=%.2f troughPnl=%.2f%% (realtime)",
                pnlPct, fFillPrice, avgPrice, troughPrice, troughPnl);

        // V128 B안: 호출자가 cache 제거 전 캡처한 armed/peak 우선 사용. 없으면 cache fallback.
        double resolvedPeak;
        boolean resolvedArmed;
        if (capturedPeak != null && capturedArmed != null) {
            resolvedPeak = capturedPeak.doubleValue();
            resolvedArmed = capturedArmed.booleanValue();
        } else {
            double[] cachePos = tpPositionCache.get(market);
            resolvedPeak = cachePos != null && cachePos.length >= 2 ? cachePos[1] : 0;
            resolvedArmed = cachePos != null && cachePos.length >= 7 && cachePos[6] > 0;
        }
        final double fPeakPrice = resolvedPeak;
        final boolean fArmed = resolvedArmed;

        txTemplate.execute(new org.springframework.transaction.support.TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                TradeEntity tl = new TradeEntity();
                tl.setTsEpochMs(System.currentTimeMillis());
                tl.setMarket(market);
                tl.setAction("SELL");
                tl.setPrice(BigDecimal.valueOf(fFillPrice));
                tl.setQty(BigDecimal.valueOf(fQty));
                tl.setPnlKrw(BigDecimal.valueOf(Math.round(pnlKrw)));
                tl.setRoiPercent(BigDecimal.valueOf(roiPct).setScale(2, RoundingMode.HALF_UP));
                tl.setMode(cfg.getMode());
                tl.setPatternType("HIGH_CONFIDENCE_BREAKOUT");
                tl.setPatternReason(reason);
                tl.setAvgBuyPrice(BigDecimal.valueOf(avgPrice));
                tl.setCandleUnitMin(cfg.getCandleUnitMin());
                if (note != null) tl.setNote(note);
                // V128: peak/armed 로그 기록
                if (fPeakPrice > 0 && avgPrice > 0) {
                    tl.setPeakPrice(fPeakPrice);
                    double peakRoi = (fPeakPrice - avgPrice) / avgPrice * 100.0;
                    tl.setPeakRoiPct(BigDecimal.valueOf(peakRoi));
                }
                tl.setArmedFlag(fArmed ? "Y" : "N");
                tradeLogRepo.save(tl);
                positionRepo.deleteById(market);
            }
        });

        String logLabel = note != null ? note : "TP_TRAIL";
        log.info("[AllDayScanner] {} SELL {} price={} pnl={} roi={}%",
                logLabel, market, fFillPrice, Math.round(pnlKrw), String.format("%.2f", roiPct));
        addDecision(market, "SELL", "EXECUTED", logLabel, reason);
        sellCooldownMap.put(market, System.currentTimeMillis());
        split1stExecutedAtMap.remove(market);  // V129: 최종 매도(2차/SL/TP) — 쿨다운 맵 제거
    }

    // ━━━ V111: Split-Exit 1차 분할 매도 (60%) ━━━

    private void executeSplitFirstSellForWsTp(String market, double wsPrice, double pnlPct, String splitReason) {
        executeSplitFirstSellForWsTp(market, wsPrice, pnlPct, splitReason, null, null);
    }

    private void executeSplitFirstSellForWsTp(String market, double wsPrice, double pnlPct, String splitReason,
                                                Boolean capturedArmed, Double capturedPeak) {
        if (!sellingMarkets.add(market)) {
            log.debug("[AllDayScanner] SPLIT_1ST in progress, skip duplicate: {}", market);
            return;
        }
        try {
            executeSplitFirstSellInner(market, wsPrice, pnlPct, splitReason, capturedArmed, capturedPeak);
        } finally {
            sellingMarkets.remove(market);
        }
    }

    private void executeSplitFirstSellInner(String market, double wsPrice, double pnlPct, String splitReason) {
        executeSplitFirstSellInner(market, wsPrice, pnlPct, splitReason, null, null);
    }

    private void executeSplitFirstSellInner(String market, double wsPrice, double pnlPct, String splitReason,
                                              Boolean capturedArmed, Double capturedPeak) {
        PositionEntity pe = positionRepo.findById(market).orElse(null);
        if (pe == null || pe.getQty() == null || pe.getQty().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[AllDayScanner] SPLIT_1ST: position not found for {} (already sold?)", market);
            return;
        }
        // splitPhase가 이미 1이면 중복 방지
        if (pe.getSplitPhase() != 0) {
            log.debug("[AllDayScanner] SPLIT_1ST: already split for {} phase={}", market, pe.getSplitPhase());
            return;
        }

        AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();
        double totalQty = pe.getQty().doubleValue();
        double avgPrice = pe.getAvgPrice().doubleValue();
        double sellRatio = cfg.getSplitRatio().doubleValue();
        double sellQty = totalQty * sellRatio;
        double remainQty = totalQty - sellQty;

        // dust 체크: 잔량 * 현재가 < 5000원이면 전량 매도
        double remainValueKrw = remainQty * wsPrice;
        boolean isDust = remainValueKrw < 5000;

        double actualSellQty = isDust ? totalQty : sellQty;
        double fillPrice;

        if ("PAPER".equalsIgnoreCase(cfg.getMode())) {
            fillPrice = wsPrice * 0.999;
        } else {
            if (!liveOrders.isConfigured()) {
                log.error("[AllDayScanner] SPLIT_1ST: LIVE mode but no API key for {}", market);
                return;
            }
            try {
                LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(market, actualSellQty);
                boolean filled = r.isFilled() || r.executedVolume > 0;
                if (!filled) {
                    log.warn("[AllDayScanner] SPLIT_1ST: sell not filled for {}", market);
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : wsPrice;
                // P3-16: LIVE 부분 체결 시 executedVolume으로 실제 매도량 반영
                if (r.executedVolume > 0 && r.executedVolume != actualSellQty) {
                    actualSellQty = r.executedVolume;
                    remainQty = totalQty - actualSellQty;
                    isDust = remainQty * fillPrice < 5000;
                    log.info("[AllDayScanner] SPLIT_1ST partial fill: requested={} filled={}", sellQty, actualSellQty);
                }
            } catch (Exception e) {
                log.error("[AllDayScanner] SPLIT_1ST: sell order failed for {}", market, e);
                return;
            }
        }

        final double fFillPrice = fillPrice;
        final double fSellQty = actualSellQty;
        final double pnlKrw = (fFillPrice - avgPrice) * fSellQty;
        final double roiPct = avgPrice > 0 ? (fFillPrice - avgPrice) / avgPrice * 100.0 : 0;
        final BigDecimal peAvgPrice = pe.getAvgPrice();
        final boolean fIsDust = isDust;
        final double fRemainQty = remainQty;

        // V128 B안: 호출자가 cache 리셋(pos[6]=0) 전 캡처한 armed/peak 우선. 없으면 cache fallback.
        double resolvedPeak;
        boolean resolvedArmed;
        if (capturedPeak != null && capturedArmed != null) {
            resolvedPeak = capturedPeak.doubleValue();
            resolvedArmed = capturedArmed.booleanValue();
        } else {
            double[] cachePos = tpPositionCache.get(market);
            resolvedPeak = cachePos != null && cachePos.length >= 2 ? cachePos[1] : 0;
            resolvedArmed = cachePos != null && cachePos.length >= 7 && cachePos[6] > 0;
        }
        final double fPeakPrice = resolvedPeak;
        final boolean fArmed = resolvedArmed;

        try {
            txTemplate.execute(new org.springframework.transaction.support.TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                    TradeEntity tl = new TradeEntity();
                    tl.setTsEpochMs(System.currentTimeMillis());
                    tl.setMarket(market);
                    tl.setAction("SELL");
                    tl.setPrice(BigDecimal.valueOf(fFillPrice));
                    tl.setQty(BigDecimal.valueOf(fSellQty));
                    tl.setPnlKrw(BigDecimal.valueOf(Math.round(pnlKrw)));
                    tl.setRoiPercent(BigDecimal.valueOf(roiPct).setScale(2, RoundingMode.HALF_UP));
                    tl.setMode(cfg.getMode());
                    tl.setPatternType("HIGH_CONFIDENCE_BREAKOUT");
                    tl.setPatternReason(splitReason);
                    tl.setAvgBuyPrice(peAvgPrice);
                    tl.setCandleUnitMin(cfg.getCandleUnitMin());
                    tl.setNote(fIsDust ? "SPLIT_1ST_DUST" : "SPLIT_1ST");
                    // V128: peak/armed 로그 기록
                    if (fPeakPrice > 0 && avgPrice > 0) {
                        tl.setPeakPrice(fPeakPrice);
                        double peakRoi = (fPeakPrice - avgPrice) / avgPrice * 100.0;
                        tl.setPeakRoiPct(BigDecimal.valueOf(peakRoi));
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
            // P1-4: DB 실패 시 cache 롤백 (실잔고와 동기화)
            log.error("[AllDayScanner] SPLIT_1ST DB commit failed for {} — cache rollback", market, e);
            double[] rollback = tpPositionCache.get(market);
            if (rollback != null) {
                rollback[5] = 0; // splitPhase → 0으로 복원
                // V115: armed 상태는 유지 (drop 조건으로 armed 되었던 상태 복원)
                // DB commit 실패라 armed 상태는 그대로 두면 다음 tick에 재시도 가능
            }
            addDecision(market, "SELL", "ERROR", "SPLIT_1ST_DB_FAIL",
                    "1차 매도 DB 실패, cache 롤백: " + e.getMessage());
            return;
        }

        // V129: DB commit 성공 후 쿨다운 기준점 메모리 맵에도 등록 (즉시 체크용)
        if (!fIsDust) {
            split1stExecutedAtMap.put(market, System.currentTimeMillis());
        }

        // 캐시 갱신
        double[] pos = tpPositionCache.get(market);
        if (fIsDust) {
            // dust → 캐시 제거
            tpPositionCache.remove(market);
            sellCooldownMap.put(market, System.currentTimeMillis());
            split1stExecutedAtMap.remove(market);  // V129: dust 전량 매도 시 쿨다운 맵도 제거
            log.info("[AllDayScanner] SPLIT_1ST_DUST SELL {} (전량, 잔량<5000원) price={} pnl={} roi={}%",
                    market, fFillPrice, Math.round(pnlKrw), String.format("%.2f", roiPct));
        } else if (pos != null) {
            // 1차 완료: splitPhase=1, peak 리셋, trail 재초기화
            pos[1] = wsPrice;  // peak → 현재가 리셋
            pos[2] = 0;        // trail activated → 재초기화
            pos[5] = 1.0;      // splitPhase = 1
            if (pos.length >= 7) pos[6] = 0.0;  // V115: 1차 trail armed 리셋
            log.info("[AllDayScanner] SPLIT_1ST SELL {} qty={}/{} price={} pnl={} roi={}% (잔량 {} 대기)",
                    market, String.format("%.8f", fSellQty), String.format("%.8f", totalQty),
                    fFillPrice, Math.round(pnlKrw), String.format("%.2f", roiPct),
                    String.format("%.8f", fRemainQty));
        }

        addDecision(market, "SELL", "EXECUTED", fIsDust ? "SPLIT_1ST_DUST" : "SPLIT_1ST", splitReason);
        // ★ 1차 매도 시 sellCooldownMap 미등록 (2차에서만 등록)
    }

    /**
     * tick()에서 호출: 포지션 캐시 동기화 + SharedPriceService 구독 관리.
     */
    private void syncTpWebSocket(List<PositionEntity> scannerPositions) {
        // 포지션 캐시 동기화 (TP_TRAIL: [avgPrice, peakPrice, activated, troughPrice, openedAtMs, splitPhase, armed])
        Set<String> currentMarkets = new HashSet<String>();
        for (PositionEntity pe : scannerPositions) {
            String market = pe.getMarket();
            currentMarkets.add(market);
            if (!tpPositionCache.containsKey(market)) {
                double avg = pe.getAvgPrice().doubleValue();
                long openedAtMs = pe.getOpenedAt() != null ? pe.getOpenedAt().toEpochMilli() : System.currentTimeMillis();
                Integer pePhase = pe.getSplitPhase();
                int splitPhase = pePhase != null ? pePhase : 0;

                // V118: DB에 저장된 실제 peak/armed 우선 복원
                BigDecimal dbPeak = pe.getPeakPrice();
                double recPeak;
                double recArmed;
                if (dbPeak != null && dbPeak.signum() > 0) {
                    recPeak = dbPeak.doubleValue();
                    recArmed = (pe.getArmedAt() != null) ? 1.0 : 0.0;
                    log.info("[AllDayScanner] V118 재시작 복구(DB): {} peak={} armed={} phase={}",
                            market, String.format(Locale.ROOT, "%.2f", recPeak), recArmed > 0, splitPhase);
                } else {
                    // V115 fallback: 현재가 기준 armed/peak 재판정 (구 데이터 or 신규 진입)
                    double recPrice = sharedPriceService.getPrice(market);
                    recPeak = avg;
                    recArmed = 0;
                    if (recPrice > 0 && splitPhase == 0) {
                        double recPnl = (recPrice - avg) / avg * 100.0;
                        recPeak = Math.max(avg, recPrice);
                        if (recPnl >= cachedSplitTpPct) {
                            recArmed = 1.0;
                            log.info("[AllDayScanner] V115 fallback 복구: {} armed=1 pnl=+{}% peak={}",
                                    market, String.format(Locale.ROOT, "%.2f", recPnl),
                                    String.format(Locale.ROOT, "%.2f", recPeak));
                        }
                    }
                }
                tpPositionCache.put(market, new double[]{avg, recPeak, 0, avg, openedAtMs, splitPhase, recArmed});
                // V129: splitPhase==1 포지션의 쿨다운 기준점 복구 (재시작 시 DB → 메모리)
                if (splitPhase == 1 && pe.getSplit1stExecutedAt() != null) {
                    split1stExecutedAtMap.putIfAbsent(market, pe.getSplit1stExecutedAt().toEpochMilli());
                }
            }
        }
        // 없어진 포지션 제거
        for (String market : new ArrayList<String>(tpPositionCache.keySet())) {
            if (!currentMarkets.contains(market)) {
                tpPositionCache.remove(market);
                split1stExecutedAtMap.remove(market);  // V129: 캐시에서 제거 시 쿨다운 맵도 제거
            }
        }

        // V118: 메모리 peak/armed → DB 영속화 (Option B: peak 0.3% 이상 상승 또는 armed 전환 시)
        for (PositionEntity pe : scannerPositions) {
            String market = pe.getMarket();
            double[] pos = tpPositionCache.get(market);
            if (pos == null || pos.length < 7) continue;
            double avgPrice = pos[0];
            if (avgPrice <= 0) continue;
            double currentPeak = pos[1];
            boolean currentArmed = pos[6] > 0;

            BigDecimal dbPeak = pe.getPeakPrice();
            boolean peakDirty;
            if (dbPeak == null) {
                // 최초 저장: avgPrice 대비 0.3% 이상 peak 상승 시
                peakDirty = (currentPeak - avgPrice) / avgPrice * 100.0 >= 0.3;
            } else {
                // 증분 저장: 기존 DB peak 대비 0.3% 이상 추가 상승 시
                peakDirty = (currentPeak - dbPeak.doubleValue()) / avgPrice * 100.0 >= 0.3;
            }
            boolean armedDirty = currentArmed && pe.getArmedAt() == null;

            if (peakDirty || armedDirty) {
                if (peakDirty) pe.setPeakPrice(BigDecimal.valueOf(currentPeak));
                if (armedDirty) pe.setArmedAt(Instant.now());
                try {
                    positionRepo.save(pe);
                } catch (Exception e) {
                    log.warn("[AllDayScanner] V118 peak/armed save failed for {}", market, e);
                }
            }
        }

        // 3. SharedPriceService에 포지션 마켓 구독 요청
        if (!currentMarkets.isEmpty()) {
            sharedPriceService.ensureMarketsSubscribed(currentMarkets);
        }
    }

    // ========== Helpers ==========

    private BigDecimal calcOrderSize(AllDayScannerConfigEntity cfg) {
        if ("FIXED".equalsIgnoreCase(cfg.getOrderSizingMode())) {
            return cfg.getOrderSizingValue();
        }
        // PCT mode — Global Capital 사용
        BigDecimal pct = cfg.getOrderSizingValue();
        BigDecimal globalCapital = getGlobalCapitalKrw();
        return globalCapital.multiply(pct).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
    }

    /** Global Capital(bot_config.capital_krw) 조회. 기본 전략과 스캐너 전략이 공유하는 단일 풀. */
    private BigDecimal getGlobalCapitalKrw() {
        List<BotConfigEntity> configs = botConfigRepo.findAll();
        if (configs.isEmpty()) return BigDecimal.valueOf(100000);
        BigDecimal cap = configs.get(0).getCapitalKrw();
        return cap != null && cap.compareTo(BigDecimal.ZERO) > 0 ? cap : BigDecimal.valueOf(100000);
    }

    /** 전체 포지션(기본 전략 + 스캐너)의 총 투입금 계산 */
    private double calcTotalInvestedAllPositions() {
        double sum = 0.0;
        List<PositionEntity> all = positionRepo.findAll();
        for (PositionEntity pe : all) {
            if (pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0
                    && pe.getAvgPrice() != null) {
                // V111: splitPhase=1이면 splitOriginalQty 기준 (1차 매도 수익은 이미 확보했지만 자본 슬롯은 유지)
                double qty = (pe.getSplitPhase() == 1 && pe.getSplitOriginalQty() != null)
                        ? pe.getSplitOriginalQty().doubleValue()
                        : pe.getQty().doubleValue();
                sum += qty * pe.getAvgPrice().doubleValue();
            }
        }
        return sum;
    }

    /**
     * 업비트 API가 반환한 캔들 목록(최신->오래된 순)에서 현재 진행 중인 미완성 캔들을 제거한다.
     */
    private List<UpbitCandle> stripIncompleteCandle(List<UpbitCandle> descCandles, int candleUnitMin) {
        if (descCandles == null || descCandles.isEmpty()) return descCandles;
        UpbitCandle newest = descCandles.get(0);
        if (newest.candle_date_time_utc == null) return descCandles;
        try {
            LocalDateTime candleStartUtc = LocalDateTime.parse(newest.candle_date_time_utc);
            long candleEndEpochSec = candleStartUtc.toEpochSecond(ZoneOffset.UTC) + candleUnitMin * 60L;
            long nowEpochSec = Instant.now().getEpochSecond();
            if (nowEpochSec < candleEndEpochSec) {
                log.debug("[AllDayScanner] 미완성 캔들 제거: {} (완성까지 {}초 남음)",
                        newest.candle_date_time_utc, candleEndEpochSec - nowEpochSec);
                return descCandles.subList(1, descCandles.size());
            }
        } catch (DateTimeParseException e) {
            log.warn("[AllDayScanner] 캔들 시각 파싱 실패: {}", newest.candle_date_time_utc);
        }
        return descCandles;
    }

    /**
     * 거래대금 상위 N개 KRW 마켓 조회 (ownedMarkets 제외).
     */
    private List<String> getTopMarketsByVolume(int topN, Set<String> excludeMarkets) {
        return getTopMarketsByVolume(topN, excludeMarkets, 0);
    }

    private List<String> getTopMarketsByVolume(int topN, Set<String> excludeMarkets, int minPriceKrw) {
        try {
            Set<String> allKrwMarkets = catalogService.getAllMarketCodes();
            if (allKrwMarkets == null || allKrwMarkets.isEmpty()) return Collections.emptyList();

            // KRW 마켓만 필터
            List<String> krwMarkets = new ArrayList<String>();
            for (String m : allKrwMarkets) {
                if (m.startsWith("KRW-") && !excludeMarkets.contains(m)) {
                    krwMarkets.add(m);
                }
            }
            if (krwMarkets.isEmpty()) return Collections.emptyList();

            // ticker API로 거래대금 + 현재가 일괄 조회
            List<com.example.upbit.market.UpbitMarketCatalogService.TickerItem> tickers = catalogService.fetchTickers(krwMarkets);
            final Map<String, Double> volumeMap = new HashMap<String, Double>();
            // 저가 코인 필터: Top N 선별 전에 최소 가격 미만 코인 제거
            Set<String> lowPriceMarkets = new HashSet<String>();
            for (com.example.upbit.market.UpbitMarketCatalogService.TickerItem t : tickers) {
                volumeMap.put(t.market, t.acc_trade_price_24h);
                if (minPriceKrw > 0 && t.trade_price < minPriceKrw) {
                    lowPriceMarkets.add(t.market);
                }
            }
            if (!lowPriceMarkets.isEmpty()) {
                log.info("[AllDayScanner] 저가 필터({}원 미만) 제외: {}", minPriceKrw, lowPriceMarkets);
                krwMarkets.removeAll(lowPriceMarkets);
            }

            Collections.sort(krwMarkets, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    double va = volumeMap.containsKey(a) ? volumeMap.get(a) : 0;
                    double vb = volumeMap.containsKey(b) ? volumeMap.get(b) : 0;
                    return Double.compare(vb, va); // 내림차순
                }
            });

            return krwMarkets.subList(0, Math.min(topN, krwMarkets.size()));
        } catch (Exception e) {
            log.error("[AllDayScanner] failed to get top markets", e);
            return Collections.emptyList();
        }
    }

    /**
     * BTC 방향 필터: BTC close >= EMA(period) -> true (롱 허용)
     */
    private boolean checkBtcFilter(int candleUnit, int emaPeriod) {
        try {
            List<UpbitCandle> btcCandles = candleService.getMinuteCandles("KRW-BTC", candleUnit, emaPeriod + 10, null);
            if (btcCandles == null || btcCandles.size() < emaPeriod) return true;
            btcCandles = new ArrayList<UpbitCandle>(stripIncompleteCandle(btcCandles, candleUnit));
            if (btcCandles.size() < emaPeriod) return true;
            Collections.reverse(btcCandles);

            double ema = Indicators.ema(btcCandles, emaPeriod);
            double btcClose = btcCandles.get(btcCandles.size() - 1).trade_price;
            boolean allow = btcClose >= ema;
            if (!allow) {
                log.info("[AllDayScanner] BTC filter BLOCKED: close={} < EMA({})={}", btcClose, emaPeriod, ema);
            } else {
                log.debug("[AllDayScanner] BTC filter PASSED: close={} >= EMA({})={}", btcClose, emaPeriod, ema);
            }
            return allow;
        } catch (Exception e) {
            log.error("[AllDayScanner] BTC filter check failed", e);
            return true; // 에러 시 허용
        }
    }

    /**
     * 당일 09:00 시가 수집 (Daily Change 계산용).
     * 각 코인의 09:00 KST 캔들 시가를 조회하여 캐시.
     */
    /**
     * 당일 09:00 시가 수집. 09:05 UTC 기준으로 캔들 1개만 조회하여 시가 확보.
     * 80개 캔들 의존 없이, 직접 09:00 캔들을 타겟 조회.
     */
    private void collectDailyOpenPrices(List<String> markets, int candleUnit) {
        ZonedDateTime now = ZonedDateTime.now(KST);
        // 09:05 KST = 00:05 UTC → to 파라미터로 사용
        String toUtc = now.toLocalDate() + "T00:05:00";
        for (String market : markets) {
            try {
                List<UpbitCandle> candles = candleService.getMinuteCandles(market, candleUnit, 2, toUtc);
                if (candles == null || candles.isEmpty()) continue;
                // desc 순서로 반환되므로, 09:00 캔들이 첫 번째 또는 두 번째
                for (UpbitCandle c : candles) {
                    ZonedDateTime kst = null;
                    if (c.candle_date_time_utc != null) {
                        try {
                            java.time.LocalDateTime utcLdt = java.time.LocalDateTime.parse(c.candle_date_time_utc);
                            kst = utcLdt.atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(KST);
                        } catch (Exception e) { continue; }
                    }
                    if (kst != null && kst.toLocalDate().equals(now.toLocalDate())
                            && kst.getHour() == 9 && kst.getMinute() < candleUnit) {
                        dailyOpenPriceCache.put(market, c.opening_price);
                        break;
                    }
                }
            } catch (Exception e) {
                log.debug("[AllDayScanner] dailyOpen fetch failed for {}: {}", market, e.getMessage());
            }
        }
        log.info("[AllDayScanner] 당일 09:00 시가 캐시: {}개 마켓", dailyOpenPriceCache.size());
    }
}
