package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
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
import java.util.Locale;

/**
 * 독립 오프닝 레인지 돌파 스캐너.
 * 메인 TradingBotService와 별도로 on/off 운영.
 * 거래대금 상위 N개 코인을 스캔하여 오프닝 돌파 시 매수.
 *
 * v2: Decision Log 추가, KRW 잔고 사전확인, BTC 필터 로깅 강화
 */
@Service
public class OpeningScannerService {

    private static final Logger log = LoggerFactory.getLogger(OpeningScannerService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final OpeningScannerConfigRepository configRepo;
    private final BotConfigRepository botConfigRepo;
    private final PositionRepository positionRepo;
    private final TradeRepository tradeLogRepo;
    private final CandleService candleService;
    private final UpbitMarketCatalogService catalogService;
    private final LiveOrderService liveOrders;
    private final UpbitPrivateClient privateClient;
    private final TransactionTemplate txTemplate;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

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
                    Thread t = new Thread(r, "scanner-parallel-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    // v2: Decision Log (대시보드에서 차단/실행 사유 확인용)
    private static final int MAX_DECISION_LOG = 200;
    private final Deque<ScannerDecision> decisionLog = new ArrayDeque<ScannerDecision>();

    // Hourly trade throttle: 동일 마켓 1시간 내 최대 2회 매수
    private final HourlyTradeThrottle hourlyThrottle = new HourlyTradeThrottle(2);

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

    /** BUY signal holder for Phase 2 → Phase 3 handoff */
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

    private final OpeningBreakoutDetector breakoutDetector;

    // 레인지 고점 맵 (range 수집 후 저장, detector에 전달)
    private final ConcurrentHashMap<String, Double> rangeHighCache = new ConcurrentHashMap<String, Double>();
    private volatile boolean breakoutDetectorConnected = false;

    public OpeningScannerService(OpeningScannerConfigRepository configRepo,
                                  BotConfigRepository botConfigRepo,
                                  PositionRepository positionRepo,
                                  TradeRepository tradeLogRepo,
                                  CandleService candleService,
                                  UpbitMarketCatalogService catalogService,
                                  LiveOrderService liveOrders,
                                  UpbitPrivateClient privateClient,
                                  TransactionTemplate txTemplate,
                                  OpeningBreakoutDetector breakoutDetector) {
        this.configRepo = configRepo;
        this.botConfigRepo = botConfigRepo;
        this.positionRepo = positionRepo;
        this.tradeLogRepo = tradeLogRepo;
        this.candleService = candleService;
        this.catalogService = catalogService;
        this.liveOrders = liveOrders;
        this.privateClient = privateClient;
        this.txTemplate = txTemplate;
        this.breakoutDetector = breakoutDetector;
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
        if ("SKIPPED".equals(result) || "BLOCKED".equals(result) || "ERROR".equals(result)) {
            log.info("[OpeningScanner] {} {} {} {} | {}", market, action, result, reasonCode, reasonKo);
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
            log.info("[OpeningScanner] already running");
            return false;
        }
        log.info("[OpeningScanner] starting...");
        statusText = "RUNNING";
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "opening-scanner");
            t.setDaemon(true);
            return t;
        });

        // WebSocket 돌파 감지기 + 실시간 TP 트레일링 콜백 설정
        breakoutDetector.setBreakoutPct(1.0);
        breakoutDetector.setRequiredConfirm(3);
        breakoutDetector.setTpActivatePct(2.3);     // +2.3% 도달 시 트레일링 활성화 (슬리피지 감안)
        breakoutDetector.setTrailFromPeakPct(1.0);   // 피크에서 -1% 떨어지면 매도
        breakoutDetector.setListener(new OpeningBreakoutDetector.BreakoutListener() {
            @Override
            public void onBreakoutConfirmed(String market, double price, double rangeHigh, double breakoutPctActual) {
                log.info("[OpeningScanner] WS breakout detected: {} price={} rH={} bo=+{}%",
                        market, price, rangeHigh, String.format(Locale.ROOT, "%.2f", breakoutPctActual));
                addDecision(market, "BUY", "WS_BREAKOUT", "BREAKOUT_DETECTED",
                        String.format(Locale.ROOT, "실시간 돌파 감지: price=%.2f rH=%.2f bo=+%.2f%%",
                                price, rangeHigh, breakoutPctActual));
                // 즉시 tick 실행하여 전략 평가
                if (scheduler != null && !scheduler.isShutdown()) {
                    scheduler.schedule(new Runnable() {
                        @Override
                        public void run() {
                            tickWrapper();
                        }
                    }, 0, TimeUnit.MILLISECONDS);
                }
            }

            @Override
            public void onTpSlTriggered(String market, double price, String sellType, String reason) {
                log.info("[OpeningScanner] realtime {} triggered: {} | {}", sellType, market, reason);
                addDecision(market, "SELL", "REALTIME_TP", sellType, reason);
                // scheduler에서 매도 실행 (DB 접근)
                if (scheduler != null && !scheduler.isShutdown()) {
                    final String fMarket = market;
                    final double fPrice = price;
                    final String fReason = reason;
                    scheduler.schedule(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                PositionEntity pe = positionRepo.findById(fMarket).orElse(null);
                                if (pe == null || pe.getQty() == null
                                        || pe.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) return;
                                executeSellForTp(pe, fPrice, fReason);
                            } catch (Exception e) {
                                log.error("[OpeningScanner] realtime TP sell failed for {}", fMarket, e);
                            }
                        }
                    }, 0, TimeUnit.MILLISECONDS);
                }
            }
        });

        scheduleTick();
        return true;
    }

    public boolean stop() {
        if (!running.compareAndSet(true, false)) {
            log.info("[OpeningScanner] already stopped");
            return false;
        }
        log.info("[OpeningScanner] stopping...");
        statusText = "STOPPED";
        breakoutDetector.disconnect();
        breakoutDetector.reset();
        breakoutDetectorConnected = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        return true;
    }

    @PreDestroy
    public void destroy() {
        parallelExecutor.shutdownNow();
        log.info("[OpeningScanner] parallel executor shut down");
    }

    public boolean isRunning() { return running.get(); }
    public String getStatusText() { return statusText; }
    public int getScanCount() { return scanCount; }
    public int getActivePositions() { return activePositions; }
    public List<String> getLastScannedMarkets() { return lastScannedMarkets; }
    public long getLastTickEpochMs() { return lastTickEpochMs; }

    // ========== Scheduling ==========

    private void scheduleTick() {
        if (!running.get() || scheduler == null) return;
        OpeningScannerConfigEntity cfg = configRepo.loadOrCreate();
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
            log.debug("[OpeningScanner] next tick in {}s (boundary={}min)", delaySec, nextBoundaryMin);
        } catch (Exception e) {
            log.error("[OpeningScanner] schedule failed", e);
        }
    }

    private void tickWrapper() {
        try {
            tick();
        } catch (Exception e) {
            log.error("[OpeningScanner] tick error", e);
        } finally {
            scheduleTick();
        }
    }

    // ========== Main Tick ==========

    private void tick() {
        if (!running.get()) return;

        OpeningScannerConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isEnabled()) {
            statusText = "DISABLED";
            return;
        }

        // KST 현재 시각 확인
        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        int nowMinOfDay = nowKst.getHour() * 60 + nowKst.getMinute();
        int rangeStart = cfg.getRangeStartHour() * 60 + cfg.getRangeStartMin();
        int sessionEnd = cfg.getSessionEndHour() * 60 + cfg.getSessionEndMin();

        // 활성 시간 밖이면 스킵 (레인지 시작 ~ 세션 종료 + 30분 여유)
        if (nowMinOfDay < rangeStart || nowMinOfDay > sessionEnd + 30) {
            statusText = "IDLE (outside hours)";
            return;
        }

        statusText = "SCANNING";
        lastTickEpochMs = System.currentTimeMillis();

        String mode = cfg.getMode();
        boolean isLive = "LIVE".equalsIgnoreCase(mode);

        // v2: LIVE 모드 API 키 사전 확인
        if (isLive && !liveOrders.isConfigured()) {
            statusText = "ERROR (API key)";
            addDecision("*", "TICK", "BLOCKED", "API_KEY_MISSING",
                    "LIVE 모드인데 업비트 API 키가 설정되지 않았습니다.");
            log.error("[OpeningScanner] LIVE 모드인데 업비트 API 키가 없습니다.");
            return;
        }

        // 전략 인스턴스 생성 (파라미터 오버라이드)
        ScalpOpeningBreakStrategy strategy = new ScalpOpeningBreakStrategy()
                .withTiming(cfg.getRangeStartHour(), cfg.getRangeStartMin(),
                        cfg.getRangeEndHour(), cfg.getRangeEndMin(),
                        cfg.getEntryStartHour(), cfg.getEntryStartMin(),
                        cfg.getEntryEndHour(), cfg.getEntryEndMin(),
                        cfg.getSessionEndHour(), cfg.getSessionEndMin())
                .withRisk(cfg.getTpAtrMult().doubleValue(),
                        cfg.getSlPct().doubleValue(),
                        cfg.getTrailAtrMult().doubleValue())
                .withFilters(cfg.getVolumeMult().doubleValue(),
                        cfg.getMinBodyRatio().doubleValue())
                .withOpenFailedEnabled(cfg.isOpenFailedEnabled());

        int candleUnit = cfg.getCandleUnitMin();

        // 기존 보유 코인 제외 (entry_strategy != SCALP_OPENING_BREAK)
        Set<String> ownedMarkets = new HashSet<String>();
        List<PositionEntity> allPositions = positionRepo.findAll();
        int scannerPosCount = 0;
        for (PositionEntity pe : allPositions) {
            if (pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                if ("SCALP_OPENING_BREAK".equals(pe.getEntryStrategy())) {
                    scannerPosCount++;
                } else {
                    ownedMarkets.add(pe.getMarket());
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
                log.debug("[OpeningScanner] LIVE 보유코인 제외 목록: {}", ownedMarkets);
            } catch (Exception e) {
                log.warn("[OpeningScanner] 업비트 잔고 조회 실패, position table만 사용", e);
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

        // WebSocket 돌파 감지기: entry window 진입 시 한 번만 연결
        int entryStart = cfg.getEntryStartHour() * 60 + cfg.getEntryStartMin();
        int entryEnd = cfg.getEntryEndHour() * 60 + cfg.getEntryEndMin();
        boolean inEntryWindow = nowMinOfDay >= entryStart && nowMinOfDay <= entryEnd;

        if (inEntryWindow && !breakoutDetectorConnected && !topMarkets.isEmpty()) {
            // rangeHigh 계산 (서비스 레벨에서 직접)
            collectRangeHighForDetector(topMarkets, candleUnit, cfg);
            if (!rangeHighCache.isEmpty()) {
                breakoutDetector.setRangeHighMap(new HashMap<String, Double>(rangeHighCache));
                breakoutDetector.connect(new ArrayList<String>(rangeHighCache.keySet()));
                breakoutDetectorConnected = true;
                log.info("[OpeningScanner] WebSocket breakout detector connected: {} markets", rangeHighCache.size());
            }
        }
        // entry window 종료 후: 포지션 없으면 WebSocket 해제, 있으면 유지 (실시간 TP용)
        if (nowMinOfDay > entryEnd && breakoutDetectorConnected && scannerPosCount == 0) {
            breakoutDetector.disconnect();
            breakoutDetectorConnected = false;
            log.info("[OpeningScanner] WebSocket disconnected (no positions)");
        }
        // 포지션 있는데 WebSocket 해제된 경우 재연결 (세션 종료 전까지)
        int sessionEndForWs = cfg.getSessionEndHour() * 60 + cfg.getSessionEndMin();
        if (!breakoutDetectorConnected && scannerPosCount > 0 && nowMinOfDay < sessionEndForWs) {
            List<String> posMarkets = new ArrayList<String>();
            for (PositionEntity pe : allPositions) {
                if ("SCALP_OPENING_BREAK".equals(pe.getEntryStrategy())
                        && pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                    posMarkets.add(pe.getMarket());
                }
            }
            if (!posMarkets.isEmpty()) {
                breakoutDetector.connect(posMarkets);
                breakoutDetectorConnected = true;
                log.info("[OpeningScanner] WebSocket reconnected for TP monitoring: {} markets", posMarkets.size());
            }
        }

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
            if (!"SCALP_OPENING_BREAK".equals(pe.getEntryStrategy())) continue;
            if (pe.getQty() == null || pe.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;
            scannerPositions.add(pe);
        }

        // 기존 포지션을 detector TP 캐시에 등록 (재시작 후 복구)
        if (!scannerPositions.isEmpty() && breakoutDetectorConnected) {
            Map<String, Double> posMap = new LinkedHashMap<String, Double>();
            for (PositionEntity pe : scannerPositions) {
                if (pe.getAvgPrice() != null) {
                    posMap.put(pe.getMarket(), pe.getAvgPrice().doubleValue());
                }
            }
            breakoutDetector.updatePositionCache(posMap);
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
                            List<UpbitCandle> candles = candleService.getMinuteCandles(pe.getMarket(), sellCandleUnit, 40, null);
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
                        log.error("[OpeningScanner] exit candle fetch failed for {}", pe.getMarket(), result.error);
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
                    log.error("[OpeningScanner] exit candle fetch timeout for {}", pe.getMarket());
                    addDecision(pe.getMarket(), "SELL", "ERROR", "EXIT_CHECK_FAIL",
                            "청산 캔들 조회 타임아웃 (30초)");
                } catch (Exception e) {
                    log.error("[OpeningScanner] exit check failed for {}", pe.getMarket(), e);
                    addDecision(pe.getMarket(), "SELL", "ERROR", "EXIT_CHECK_FAIL",
                            "청산 체크 오류: " + e.getMessage());
                }
            }
        }

        // ========== Phase 2: BUY signal detection - Parallel candle fetch + evaluation ==========
        boolean canEnter = btcAllowLong && scannerPosCount < cfg.getMaxPositions();

        if (!canEnter && !btcAllowLong) {
            // BTC 필터 때문에 진입 불가 (이미 위에서 로깅)
        } else if (!canEnter) {
            addDecision("*", "BUY", "BLOCKED", "MAX_POSITIONS",
                    String.format("최대 포지션 수(%d) 도달로 신규 진입 차단", cfg.getMaxPositions()));
        }

        // v2: LIVE 모드에서 가용 KRW 잔고 확인 (cachedAccounts 재사용, 이중 API 호출 방지)
        BigDecimal orderKrw = calcOrderSize(cfg);
        if (canEnter && isLive && availableKrw < orderKrw.doubleValue()) {
            addDecision("*", "BUY", "BLOCKED", "INSUFFICIENT_KRW",
                    String.format("KRW 잔고 부족: 필요 %s원, 가용 %.0f원",
                            orderKrw.toPlainString(), availableKrw));
            log.warn("[OpeningScanner] KRW 잔고 부족: need={} available={}",
                    orderKrw, availableKrw);
            canEnter = false;
        }

        // v3: Global Capital 한도 체크 (기본 전략 + 오프닝 전략 공유 풀)
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
            // Filter entry candidates (exclude already-held markets)
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
            final ScalpOpeningBreakStrategy evalStrategy = strategy;
            Map<String, Future<CandleFetchResult>> buyFutures = new LinkedHashMap<String, Future<CandleFetchResult>>();
            for (final String market : entryCandidates) {
                buyFutures.put(market, parallelExecutor.submit(new Callable<CandleFetchResult>() {
                    @Override
                    public CandleFetchResult call() {
                        try {
                            List<UpbitCandle> candles = candleService.getMinuteCandles(market, buyCandleUnit, 40, null);
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
                        log.error("[OpeningScanner] entry candle fetch failed for {}", market, result.error);
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
                        buySignals.add(new BuySignal(market, candles.get(candles.size() - 1), signal, candles));
                    } else {
                        String rejectReason = signal.reason != null ? signal.reason : "UNKNOWN";
                        addDecision(market, "BUY", "SKIPPED", "NO_SIGNAL", rejectReason);
                    }
                } catch (TimeoutException e) {
                    log.error("[OpeningScanner] entry candle fetch timeout for {}", market);
                    addDecision(market, "BUY", "ERROR", "ENTRY_CHECK_FAIL",
                            "진입 캔들 조회 타임아웃 (30초)");
                } catch (Exception e) {
                    log.error("[OpeningScanner] entry check failed for {}", market, e);
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
                    log.warn("[OpeningScanner] {} KRW 잔고 부족(누적): need={} available={} spent={}",
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

                // Hourly throttle check
                if (!hourlyThrottle.canBuy(bs.market)) {
                    addDecision(bs.market, "BUY", "BLOCKED", "HOURLY_LIMIT",
                            String.format("1시간 내 최대 2회 매매 제한 (남은 대기: %ds)", hourlyThrottle.remainingWaitMs(bs.market) / 1000));
                    continue;
                }

                try {
                    executeBuy(bs.market, bs.candle, bs.signal, cfg);
                    hourlyThrottle.recordBuy(bs.market);
                    spentKrw += orderKrw.doubleValue();
                    scannerPosCount++;
                    entrySuccess++;
                    // 실시간 TP 트레일링 캐시에 등록
                    breakoutDetector.addPosition(bs.market, bs.candle.trade_price);
                    addDecision(bs.market, "BUY", "EXECUTED", "SIGNAL", bs.signal.reason);
                } catch (Exception e) {
                    log.error("[OpeningScanner] buy execution failed for {}", bs.market, e);
                    addDecision(bs.market, "BUY", "ERROR", "EXECUTION_FAIL",
                            "매수 실행 오류: " + e.getMessage());
                }
            }

            // v2: 틱 요약 로그
            log.info("[OpeningScanner] tick완료 mode={} markets={} attempts={} signals={} entries={} positions={}",
                    mode, topMarkets.size(), entryAttempts, buySignals.size(), entrySuccess, scannerPosCount);
        }

        activePositions = scannerPosCount;
        statusText = "SCANNING";
    }

    // ========== Order Execution ==========

    private void executeBuy(String market, UpbitCandle candle, Signal signal,
                             OpeningScannerConfigEntity cfg) {
        double price = candle.trade_price;
        BigDecimal orderKrw = calcOrderSize(cfg);
        if (orderKrw.compareTo(BigDecimal.valueOf(5000)) < 0) {
            log.warn("[OpeningScanner] order too small: {} KRW for {}", orderKrw, market);
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
                log.error("[OpeningScanner] LIVE 모드인데 업비트 키가 없습니다. market={}", market);
                addDecision(market, "BUY", "BLOCKED", "API_KEY_MISSING",
                        "LIVE 모드 API 키 미설정");
                return;
            }
            try {
                LiveOrderService.OrderResult r = liveOrders.placeBidPriceOrder(market, orderKrw.doubleValue());
                // isFilled(): done 또는 (cancel + executedVolume>0)
                // 추가: timeout/wait 상태라도 executedVolume>0 이면 실제 체결된 것
                boolean actuallyFilled = r.isFilled() || r.executedVolume > 0;
                if (!actuallyFilled) {
                    log.warn("[OpeningScanner] LIVE buy pending/failed: market={} state={} vol={}",
                            market, r.state, r.executedVolume);
                    // trade_log에도 BUY_PENDING 기록 (sync 복구 대상이 될 수 있도록)
                    TradeEntity pendingLog = new TradeEntity();
                    pendingLog.setTsEpochMs(System.currentTimeMillis());
                    pendingLog.setMarket(market);
                    pendingLog.setAction("BUY_PENDING");
                    pendingLog.setPrice(BigDecimal.valueOf(price));
                    pendingLog.setQty(BigDecimal.ZERO);
                    pendingLog.setPnlKrw(BigDecimal.ZERO);
                    pendingLog.setRoiPercent(BigDecimal.ZERO);
                    pendingLog.setMode(cfg.getMode());
                    pendingLog.setPatternType("SCALP_OPENING_BREAK");
                    pendingLog.setNote("state=" + r.state + " vol=" + r.executedVolume);
                    pendingLog.setCandleUnitMin(cfg.getCandleUnitMin());
                    tradeLogRepo.save(pendingLog);
                    addDecision(market, "BUY", "ERROR", "ORDER_NOT_FILLED",
                            String.format("주문 미체결 state=%s vol=%.8f", r.state, r.executedVolume));
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
                qty = r.executedVolume;
                // 404 즉시체결: executedVolume 조회 불가 → 주문금액/가격으로 추정
                if (qty <= 0 && "order_not_found".equalsIgnoreCase(r.state)) {
                    qty = orderKrw.doubleValue() / fillPrice;
                    log.info("[OpeningScanner] BUY {} — 404 즉시체결 추정, qty≈{} ({}원/{})",
                            market, String.format("%.8f", qty), orderKrw, fillPrice);
                } else if (qty <= 0) {
                    log.warn("[OpeningScanner] LIVE buy executedVolume=0 for {}", market);
                    addDecision(market, "BUY", "ERROR", "ZERO_VOLUME",
                            "체결 수량 0");
                    return;
                }
                log.info("[OpeningScanner] LIVE buy filled: market={} state={} price={} qty={}",
                        market, r.state, fillPrice, qty);
            } catch (Exception e) {
                log.error("[OpeningScanner] LIVE buy order failed for {}", market, e);
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
                pe.setEntryStrategy("SCALP_OPENING_BREAK");
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
                tl.setPatternType("SCALP_OPENING_BREAK");
                tl.setPatternReason(signal.reason);
                tl.setConfidence(signal.confidence);
                tl.setCandleUnitMin(cfg.getCandleUnitMin());
                tradeLogRepo.save(tl);
            }
        });

        log.info("[OpeningScanner] BUY {} mode={} price={} qty={} conf={} reason={}",
                market, cfg.getMode(), fillPrice, qty, signal.confidence, signal.reason);
    }

    private void executeSell(PositionEntity pe, UpbitCandle candle, Signal signal,
                              OpeningScannerConfigEntity cfg) {
        double price = candle.trade_price;
        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        double fillPrice;
        double qty = pe.getQty().doubleValue();

        if (isPaper) {
            fillPrice = price * 0.999; // 슬리피지 0.1%
        } else {
            // LIVE: 실제 업비트 시장가 매도
            if (!liveOrders.isConfigured()) {
                log.error("[OpeningScanner] LIVE 모드인데 업비트 키가 없습니다. market={}", pe.getMarket());
                addDecision(pe.getMarket(), "SELL", "BLOCKED", "API_KEY_MISSING",
                        "LIVE 모드 API 키 미설정");
                return;
            }
            try {
                LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(pe.getMarket(), qty);
                boolean actuallyFilled = r.isFilled() || r.executedVolume > 0;
                if (!actuallyFilled) {
                    log.warn("[OpeningScanner] LIVE sell pending/failed: market={} state={} vol={}",
                            pe.getMarket(), r.state, r.executedVolume);
                    addDecision(pe.getMarket(), "SELL", "ERROR", "ORDER_NOT_FILLED",
                            String.format("매도 미체결 state=%s vol=%.8f", r.state, r.executedVolume));
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
                if ("order_not_found".equalsIgnoreCase(r.state)) {
                    log.info("[OpeningScanner] SELL {} — 404 즉시체결 추정, 캔들가격 사용: {}",
                            pe.getMarket(), price);
                }
            } catch (Exception e) {
                log.error("[OpeningScanner] LIVE sell order failed for {}", pe.getMarket(), e);
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
                tl.setPatternType("SCALP_OPENING_BREAK");
                tl.setPatternReason(signal.reason);
                tl.setAvgBuyPrice(peAvgPrice);
                tl.setConfidence(signal.confidence);
                tl.setCandleUnitMin(cfg.getCandleUnitMin());
                tradeLogRepo.save(tl);

                positionRepo.deleteById(peMarket);
            }
        });

        log.info("[OpeningScanner] SELL {} price={} pnl={} roi={}% reason={}",
                pe.getMarket(), fillPrice, String.format("%.0f", pnlKrw),
                String.format("%.2f", roiPct), signal.reason);
    }

    // ========== Helpers ==========

    private BigDecimal calcOrderSize(OpeningScannerConfigEntity cfg) {
        if ("FIXED".equalsIgnoreCase(cfg.getOrderSizingMode())) {
            return cfg.getOrderSizingValue();
        }
        // PCT mode — Global Capital 사용
        BigDecimal pct = cfg.getOrderSizingValue();
        BigDecimal globalCapital = getGlobalCapitalKrw();
        return globalCapital.multiply(pct).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
    }

    /** Global Capital(bot_config.capital_krw) 조회. 기본 전략과 오프닝 전략이 공유하는 단일 풀. */
    private BigDecimal getGlobalCapitalKrw() {
        List<BotConfigEntity> configs = botConfigRepo.findAll();
        if (configs.isEmpty()) return BigDecimal.valueOf(100000);
        BigDecimal cap = configs.get(0).getCapitalKrw();
        return cap != null && cap.compareTo(BigDecimal.ZERO) > 0 ? cap : BigDecimal.valueOf(100000);
    }

    /** 전체 포지션(기본 전략 + 오프닝 스캐너)의 총 투입금 계산 */
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
     * 업비트 API가 반환한 캔들 목록(최신→오래된 순)에서 현재 진행 중인 미완성 캔들을 제거한다.
     * 업비트 API는 현재 형성 중인 캔들을 첫 번째 결과로 포함하는데,
     * 이 캔들은 거래량/body가 불완전하여 전략 평가에 부적합하다.
     *
     * 백테스트와의 동작 일관성: 백테스트는 완성된 캔들만 사용하므로,
     * LIVE에서도 완성된 캔들만 평가해야 동일한 결과가 나온다.
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
                // 아직 완성되지 않은 캔들 → 제거
                log.debug("[OpeningScanner] 미완성 캔들 제거: {} (완성까지 {}초 남음)",
                        newest.candle_date_time_utc, candleEndEpochSec - nowEpochSec);
                return descCandles.subList(1, descCandles.size());
            }
        } catch (DateTimeParseException e) {
            log.warn("[OpeningScanner] 캔들 시각 파싱 실패: {}", newest.candle_date_time_utc);
        }
        return descCandles;
    }

    /**
     * 거래대금 상위 N개 KRW 마켓 조회 (ownedMarkets 제외).
     * 업비트 ticker API로 24시간 거래대금 조회.
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
                log.info("[OpeningScanner] 저가 필터({}원 미만) 제외: {}", minPriceKrw, lowPriceMarkets);
                krwMarkets.removeAll(lowPriceMarkets);
            }

            krwMarkets.sort(new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    double va = volumeMap.containsKey(a) ? volumeMap.get(a) : 0;
                    double vb = volumeMap.containsKey(b) ? volumeMap.get(b) : 0;
                    return Double.compare(vb, va); // 내림차순
                }
            });

            return krwMarkets.subList(0, Math.min(topN, krwMarkets.size()));
        } catch (Exception e) {
            log.error("[OpeningScanner] failed to get top markets", e);
            return Collections.emptyList();
        }
    }

    /**
     * BTC 방향 필터: BTC close >= EMA(period) → true (롱 허용)
     */
    private boolean checkBtcFilter(int candleUnit, int emaPeriod) {
        try {
            List<UpbitCandle> btcCandles = candleService.getMinuteCandles("KRW-BTC", candleUnit, emaPeriod + 10, null);
            if (btcCandles == null || btcCandles.size() < emaPeriod) return true; // 데이터 부족 시 허용
            // v3: 미완성 캔들 제거 후 역순 정렬
            btcCandles = new ArrayList<UpbitCandle>(stripIncompleteCandle(btcCandles, candleUnit));
            if (btcCandles.size() < emaPeriod) return true;
            Collections.reverse(btcCandles);

            double ema = Indicators.ema(btcCandles, emaPeriod);
            double btcClose = btcCandles.get(btcCandles.size() - 1).trade_price;
            boolean allow = btcClose >= ema;
            if (!allow) {
                log.info("[OpeningScanner] BTC filter BLOCKED: close={} < EMA({})={}", btcClose, emaPeriod, ema);
            } else {
                log.debug("[OpeningScanner] BTC filter PASSED: close={} >= EMA({})={}", btcClose, emaPeriod, ema);
            }
            return allow;
        } catch (Exception e) {
            log.error("[OpeningScanner] BTC filter check failed", e);
            return true; // 에러 시 허용
        }
    }

    /**
     * WebSocket 돌파 감지용 rangeHigh 수집.
     * 각 코인의 08:00~08:59 캔들에서 최고가를 추출하여 rangeHighCache에 저장.
     */
    private void collectRangeHighForDetector(List<String> markets, int candleUnit,
                                              OpeningScannerConfigEntity cfg) {
        rangeHighCache.clear();
        ZonedDateTime now = ZonedDateTime.now(KST);

        for (String market : markets) {
            try {
                List<UpbitCandle> candles = candleService.getMinuteCandles(market, candleUnit, 80, null);
                if (candles == null || candles.isEmpty()) continue;

                // desc → asc
                List<UpbitCandle> asc = new ArrayList<UpbitCandle>(candles);
                Collections.reverse(asc);

                double rangeHigh = Double.MIN_VALUE;
                int rangeCount = 0;
                for (UpbitCandle c : asc) {
                    String utcStr = c.candle_date_time_utc;
                    if (utcStr == null) continue;
                    try {
                        LocalDateTime utcLdt = LocalDateTime.parse(utcStr);
                        ZonedDateTime kst = utcLdt.atZone(ZoneOffset.UTC).withZoneSameInstant(KST);
                        if (kst.toLocalDate().equals(now.toLocalDate())
                                && kst.getHour() >= cfg.getRangeStartHour()
                                && kst.getHour() <= cfg.getRangeEndHour()) {
                            if (kst.getHour() == cfg.getRangeEndHour() && kst.getMinute() > cfg.getRangeEndMin())
                                continue;
                            if (c.high_price > rangeHigh) rangeHigh = c.high_price;
                            rangeCount++;
                        }
                    } catch (Exception e) { /* skip */ }
                }

                if (rangeCount >= 4 && rangeHigh > 0 && rangeHigh < Double.MAX_VALUE) {
                    rangeHighCache.put(market, rangeHigh);
                }
            } catch (Exception e) {
                log.debug("[OpeningScanner] rangeHigh fetch failed for {}: {}", market, e.getMessage());
            }
        }
        log.info("[OpeningScanner] rangeHigh collected for {} markets (detector)", rangeHighCache.size());
    }

    /**
     * 실시간 TP 트레일링 매도 실행.
     * WebSocket 콜백에서 scheduler를 통해 호출됨.
     */
    private void executeSellForTp(PositionEntity pe, double price, String reason) {
        String market = pe.getMarket();
        double qty = pe.getQty().doubleValue();
        double avgPrice = pe.getAvgPrice().doubleValue();

        OpeningScannerConfigEntity cfg = configRepo.loadOrCreate();
        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        double fillPrice;

        if (isPaper) {
            fillPrice = price * 0.999;
        } else {
            if (!liveOrders.isConfigured()) {
                log.error("[OpeningScanner] realtime TP: API 키 미설정 market={}", market);
                return;
            }
            try {
                LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(market, qty);
                if (!r.isFilled() && r.executedVolume <= 0) {
                    log.warn("[OpeningScanner] realtime TP sell not filled: {}", market);
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
            } catch (Exception e) {
                log.error("[OpeningScanner] realtime TP sell failed for {}", market, e);
                return;
            }
        }

        double pnlKrw = (fillPrice - avgPrice) * qty;
        double fee = fillPrice * qty * 0.0005;
        pnlKrw -= fee;
        double roiPct = avgPrice > 0 ? ((fillPrice - avgPrice) / avgPrice) * 100.0 : 0;

        final double fFillPrice = fillPrice;
        final double fPnlKrw = pnlKrw;
        final double fRoiPct = roiPct;
        final String fReason = reason;
        final String fMode = cfg.getMode();
        final BigDecimal peAvgPrice = pe.getAvgPrice();

        txTemplate.execute(new org.springframework.transaction.support.TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                TradeEntity tl = new TradeEntity();
                tl.setTsEpochMs(System.currentTimeMillis());
                tl.setMarket(market);
                tl.setAction("SELL");
                tl.setPrice(BigDecimal.valueOf(fFillPrice));
                tl.setQty(BigDecimal.valueOf(qty));
                tl.setPnlKrw(BigDecimal.valueOf(fPnlKrw));
                tl.setRoiPercent(BigDecimal.valueOf(fRoiPct));
                tl.setMode(fMode);
                tl.setPatternType("SCALP_OPENING_BREAK");
                tl.setPatternReason(fReason);
                tl.setAvgBuyPrice(peAvgPrice);
                tl.setNote("TP_TRAIL_REALTIME");
                tl.setCandleUnitMin(5);
                tradeLogRepo.save(tl);
                positionRepo.deleteById(market);
            }
        });

        log.info("[OpeningScanner] REALTIME TP {} price={} pnl={} roi={}% reason={}",
                market, fFillPrice, Math.round(pnlKrw), String.format("%.2f", roiPct), reason);
    }
}
