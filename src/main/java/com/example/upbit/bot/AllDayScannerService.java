package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.strategy.*;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.upbit.UpbitAccount;
import com.example.upbit.upbit.UpbitPrivateClient;

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

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

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
                                TickerService tickerService) {
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
        scheduleTick();
        startQuickTpTicker();
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
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        return true;
    }

    @PreDestroy
    public void destroy() {
        stopQuickTpTicker();
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
                .withTimeStop(cfg.getTimeStopCandles(), cfg.getTimeStopMinPnl().doubleValue());

        int candleUnit = cfg.getCandleUnitMin();

        // 기존 보유 코인 제외 — 모든 포지션을 제외 (PK collision 방지)
        Set<String> ownedMarkets = new HashSet<String>();
        List<PositionEntity> allPositions = positionRepo.findAll();
        int scannerPosCount = 0;
        for (PositionEntity pe : allPositions) {
            if (pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
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

        // BTC 방향 필터
        boolean btcAllowLong = true;
        if (cfg.isBtcFilterEnabled()) {
            btcAllowLong = checkBtcFilter(candleUnit, cfg.getBtcEmaPeriod());
            if (!btcAllowLong) {
                addDecision("*", "BUY", "BLOCKED", "BTC_FILTER",
                        "BTC가 EMA" + cfg.getBtcEmaPeriod() + " 아래에 있어 모든 진입이 차단되었습니다.");
            }
        }

        // ========== Phase 1: SELL (exit checks) - Parallel candle fetch + sequential sell ==========
        List<PositionEntity> scannerPositions = new ArrayList<PositionEntity>();
        for (PositionEntity pe : allPositions) {
            if (!"HIGH_CONFIDENCE_BREAKOUT".equals(pe.getEntryStrategy())) continue;
            if (pe.getQty() == null || pe.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;
            scannerPositions.add(pe);
        }

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
                        executeSell(pe, candles.get(candles.size() - 1), signal, cfg);
                        addDecision(pe.getMarket(), "SELL", "EXECUTED", "SIGNAL",
                                signal.reason);
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

        // ========== Phase 2: BUY signal detection - Parallel candle fetch + evaluation ==========
        boolean canEnter = btcAllowLong && scannerPosCount < cfg.getMaxPositions() && inEntryWindow;

        if (!canEnter && !btcAllowLong) {
            // BTC 필터 때문에 진입 불가 (이미 위에서 로깅)
        } else if (!canEnter && !inEntryWindow) {
            // 엔트리 윈도우 밖 — 매수 차단, 매도만 처리
        } else if (!canEnter) {
            addDecision("*", "BUY", "BLOCKED", "MAX_POSITIONS",
                    String.format("최대 포지션 수(%d) 도달로 신규 진입 차단", cfg.getMaxPositions()));
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
            List<String> entryCandidates = new ArrayList<String>();
            for (String market : topMarkets) {
                boolean alreadyHas = false;
                for (PositionEntity pe : allPositions) {
                    if (market.equals(pe.getMarket()) && pe.getQty() != null
                            && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
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

                    StrategyContext ctx = new StrategyContext(market, candleUnit, candles, null, 0);
                    Signal signal = evalStrategy.evaluate(ctx);

                    entryAttempts++;
                    if (signal.action == SignalAction.BUY) {
                        // Service-level min confidence filter
                        if (signal.confidence < cfgMinConfidence) {
                            addDecision(market, "BUY", "BLOCKED", "LOW_CONFIDENCE",
                                    "score " + signal.confidence + " < min " + cfgMinConfidence);
                            continue;
                        }
                        buySignals.add(new BuySignal(market, candles.get(candles.size() - 1), signal, candles));
                    } else {
                        String rejectReason = signal.reason != null ? signal.reason : "UNKNOWN";
                        addDecision(market, "BUY", "SKIPPED", "NO_SIGNAL", rejectReason);
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

                try {
                    executeBuy(bs.market, bs.candle, bs.signal, cfg);
                    spentKrw += orderKrw.doubleValue();
                    scannerPosCount++;
                    entrySuccess++;
                    addDecision(bs.market, "BUY", "EXECUTED", "SIGNAL", bs.signal.reason);
                } catch (Exception e) {
                    log.error("[AllDayScanner] buy execution failed for {}", bs.market, e);
                    addDecision(bs.market, "BUY", "ERROR", "EXECUTION_FAIL",
                            "매수 실행 오류: " + e.getMessage());
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
        double price = candle.trade_price;
        BigDecimal orderKrw = calcOrderSize(cfg);
        if (orderKrw.compareTo(BigDecimal.valueOf(5000)) < 0) {
            log.warn("[AllDayScanner] order too small: {} KRW for {}", orderKrw, market);
            addDecision(market, "BUY", "BLOCKED", "ORDER_TOO_SMALL",
                    String.format("주문 금액 %s원이 최소 5,000원 미만", orderKrw.toPlainString()));
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
                tradeLogRepo.save(tl);

                positionRepo.deleteById(peMarket);
            }
        });

        log.info("[AllDayScanner] SELL {} price={} pnl={} roi={}% reason={}",
                pe.getMarket(), fillPrice, String.format("%.0f", pnlKrw),
                String.format("%.2f", roiPct), signal.reason);
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

            if (pnlPct >= quickTpPct) {
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
                tradeLogRepo.save(tl);
                positionRepo.deleteById(market);
            }
        });

        log.info("[AllDayScanner] {} {} price={} pnl={} roi={}%",
                fSellType, market, fFillPrice, Math.round(pnlKrw), String.format("%.2f", roiPct));

        addDecision(market, "SELL", "EXECUTED", fSellType, fReason);
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
                sum += pe.getQty().doubleValue() * pe.getAvgPrice().doubleValue();
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
}
