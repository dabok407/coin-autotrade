package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.NewMarketListener;
import com.example.upbit.market.SharedPriceService;
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

    // ════════════════════════════════════════════════════════════════
    // 5분 boundary BUY path 비활성화 플래그
    // ════════════════════════════════════════════════════════════════
    // 비활성화 일시: 2026-04-09 (사용자 요청)
    // 비활성화 이유:
    //   - 5분 boundary path는 옵션 B (실시간 WS path) 도입 전 원래 매수 path였음
    //   - 옵션 B 도입 후 두 path 병행 운영 → 옵션 B가 못 잡은 시그널을
    //     5분 boundary path가 5분 후 매수 → 사용자가 며칠째 "5분단위 진입" 불만
    //   - KRW-FLOCK 09:10:13 케이스가 대표 사례 (옵션 B vol 0.2x SKIP →
    //     5분 후 OPEN_BREAK 매수, +1.35%p 손해)
    //   - 사용자 결정: 옵션 B 단독 사용, 5분 boundary BUY path 비활성화
    //
    // 영향 범위:
    //   - tick() Phase 2 (BUY 시그널 감지) + Phase 3 (BUY 실행) 만 비활성화
    //   - tick() Phase 1 (SELL 체크)는 그대로 동작 — 매도는 5분 boundary path도 사용
    //   - 옵션 B (tryWsBreakoutBuy)는 그대로 동작 — 메인 매수 path
    //
    // 나중에 다시 활성화하려면:
    //   - 이 플래그를 true로 변경 + 재배포
    //   - 그 전에 옵션 B의 한계를 다시 검토 + 사용자 동의 필수
    //
    // ⚠️⚠️⚠️ Claude 자동 작업 시 이 플래그를 무심코 true로 바꾸지 말 것 ⚠️⚠️⚠️
    // 사용자 명시 동의 없이 변경 금지. 변경 시 운영 사고 재발 가능.
    // ════════════════════════════════════════════════════════════════
    private static final boolean BOUNDARY_BUY_ENABLED = false;

    /**
     * V127: 3분봉 vol 비율 계산 (순수함수, 단위테스트 대상).
     * 직전 3개 1분봉 평균 vs 그 이전 20개 1분봉 평균.
     * candles.size() &lt; 23 → -1 (데이터 부족 신호, 게이트는 bypass).
     * avgVol20 &lt;= 0 → 0 (나누기 방어).
     */
    public static double computeVol3Ratio(java.util.List<com.example.upbit.market.UpbitCandle> candles) {
        if (candles == null) return -1;
        int size = candles.size();
        if (size < 23) return -1;
        double avgVol20 = 0;
        for (int i = size - 23; i < size - 3; i++) {
            avgVol20 += candles.get(i).candle_acc_trade_volume;
        }
        avgVol20 /= 20.0;
        double avgVol3 = 0;
        for (int i = size - 3; i < size; i++) {
            avgVol3 += candles.get(i).candle_acc_trade_volume;
        }
        avgVol3 /= 3.0;
        return avgVol20 > 0 ? avgVol3 / avgVol20 : 0;
    }

    /**
     * V127: vol3 게이트 통과 여부 (순수함수, 단위테스트 대상).
     * - vol3Ratio &lt; 0 (데이터 부족) → true (기존 동작 유지, 필터 bypass)
     * - vol3Ratio &gt;= threshold → true
     * - vol3Ratio &lt; threshold → false (진입 차단)
     */
    public static boolean vol3GatePass(double vol3Ratio, double threshold) {
        if (vol3Ratio < 0) return true;
        return vol3Ratio >= threshold;
    }

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

    // Hourly trade throttle: 3개 스캐너가 공유하는 단일 인스턴스
    // (2026-04-08 KRW-TREE 사고: 분리된 throttle로 같은 코인 동시 매수 발생)
    private final SharedTradeThrottle hourlyThrottle;

    // 진행 중인 매수/매도 마켓 (race condition 차단용 in-flight set)
    // (2026-04-09 KRW-CBK 사고: 모닝러쉬에서 발생한 thread race가 오프닝에서도 가능)
    private final java.util.Set<String> buyingMarkets = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> sellingMarkets = ConcurrentHashMap.newKeySet();

    // 매도 후 재매수 쿨다운: 동일 마켓 매도 후 5분간 재매수 차단 (BERA 반복매매 방지)
    // 5분봉 1주기 = 300초, 같은 캔들 데이터로 재진입 방지
    private static final long SELL_COOLDOWN_MS = 300_000L;
    private final ConcurrentHashMap<String, Long> sellCooldownMap = new ConcurrentHashMap<String, Long>();

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
    private final SharedPriceService sharedPriceService;
    private final ScannerLockService scannerLockService;
    private volatile NewMarketListener newMarketListener;

    // V130 ②: L1 지연 진입용 단일 스레드 스케줄러 (지연 후 현재가 확인 → executeBuy)
    private final ScheduledExecutorService l1DelayScheduler = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "op-l1-delay");
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    // 레인지 고점 맵 (range 수집 후 저장, detector에 전달)
    private final ConcurrentHashMap<String, Double> rangeHighCache = new ConcurrentHashMap<String, Double>();
    private volatile boolean breakoutDetectorConnected = false;

    // 옵션 B: 1분봉 사전 캐시 (매분 0~5초에 갱신, WS 돌파 시 즉시 사용)
    // 키: market, 값: 최근 60개 1분봉 (지표 계산용)
    private final ConcurrentHashMap<String, List<UpbitCandle>> oneMinCandleCache = new ConcurrentHashMap<String, List<UpbitCandle>>();
    private volatile long lastPrecacheEpochMs = 0;
    // WS 돌파 처리 중복 방지 (중복 매수 방지)
    private final Set<String> wsBreakoutProcessing = ConcurrentHashMap.newKeySet();

    public OpeningScannerService(OpeningScannerConfigRepository configRepo,
                                  BotConfigRepository botConfigRepo,
                                  PositionRepository positionRepo,
                                  TradeRepository tradeLogRepo,
                                  CandleService candleService,
                                  UpbitMarketCatalogService catalogService,
                                  LiveOrderService liveOrders,
                                  UpbitPrivateClient privateClient,
                                  TransactionTemplate txTemplate,
                                  OpeningBreakoutDetector breakoutDetector,
                                  SharedTradeThrottle hourlyThrottle,
                                  SharedPriceService sharedPriceService,
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
        this.breakoutDetector = breakoutDetector;
        this.hourlyThrottle = hourlyThrottle;
        this.sharedPriceService = sharedPriceService;
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
        breakoutDetector.setBreakoutPct(1.5);  // 2026-04-13: 1.0→1.5%
        breakoutDetector.setRequiredConfirm(4);
        // TP_TRAIL 설정 (2026-04-10 백테스트 최적화: A9→A1)
        // 변경 전: activate=2.3%, trail=1.0% → 총PnL +144% (12위)
        // 변경 후: activate=1.5%, trail=0.5% → 총PnL +190% (1위, 14일 109건 시뮬레이션)
        // 이유: activate 2.3%가 너무 높아 24건 SL → 1.5%로 낮추면 15건으로 감소
        //       trail 0.5%로 peak 근처 매도 → 오프닝 돌파의 짧은 peak 패턴에 적합
        // V110: 하드코딩 제거 → DB값 사용 (tp_trail_activate_pct, tp_trail_drop_pct)
        OpeningScannerConfigEntity tpCfg = configRepo.findById(1).orElse(null);
        double tpActivate = tpCfg != null ? tpCfg.getTpTrailActivatePct().doubleValue() : 1.5;
        double tpDrop = tpCfg != null ? tpCfg.getTpTrailDropPct().doubleValue() : 1.0;
        breakoutDetector.setTpActivatePct(tpActivate);
        breakoutDetector.setTrailFromPeakPct(tpDrop);
        breakoutDetector.setListener(new OpeningBreakoutDetector.BreakoutListener() {
            @Override
            public void onBreakoutConfirmed(String market, double price, double rangeHigh, double breakoutPctActual) {
                log.info("[OpeningScanner] WS breakout detected: {} price={} rH={} bo=+{}%",
                        market, price, rangeHigh, String.format(Locale.ROOT, "%.2f", breakoutPctActual));
                addDecision(market, "BUY", "WS_BREAKOUT", "BREAKOUT_DETECTED",
                        String.format(Locale.ROOT, "실시간 돌파 감지: price=%.2f rH=%.2f bo=+%.2f%%",
                                price, rangeHigh, breakoutPctActual));
                // 옵션 B: WS 돌파 감지 즉시 1분봉 캐시 기반 빠른 매수 시도
                final String fMarket = market;
                final double fPrice = price;
                final double fRangeHigh = rangeHigh;
                final double fBreakoutPctActual = breakoutPctActual;
                if (scheduler != null && !scheduler.isShutdown()) {
                    scheduler.schedule(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                tryWsBreakoutBuy(fMarket, fPrice, fRangeHigh, fBreakoutPctActual);
                            } catch (Exception e) {
                                log.error("[OpeningScanner] WS breakout buy failed for {}", fMarket, e);
                            }
                        }
                    }, 0, TimeUnit.MILLISECONDS);
                }
            }

            @Override
            public void onTpSlTriggered(String market, double price, String sellType, String reason) {
                log.info("[OpeningScanner] realtime {} triggered: {} | {}", sellType, market, reason);
                addDecision(market, "SELL", "REALTIME_TP", sellType, reason);
                // V128: WS 스레드에서 detector의 armed/peak 캡처 (스케줄러 실행 전, DB 영속 전 시점)
                final boolean capturedArmed = breakoutDetector.isArmed(market);
                Double memPeak = breakoutDetector.getPeak(market);
                final double capturedPeak = memPeak != null ? memPeak.doubleValue() : 0;
                // scheduler에서 매도 실행 (DB 접근)
                if (scheduler != null && !scheduler.isShutdown()) {
                    final String fMarket = market;
                    final double fPrice = price;
                    final String fReason = reason;
                    final String fSellType = sellType;
                    scheduler.schedule(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                PositionEntity pe = positionRepo.findById(fMarket).orElse(null);
                                if (pe == null || pe.getQty() == null
                                        || pe.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) return;
                                // V111: Split-Exit 분기
                                if ("SPLIT_1ST".equals(fSellType)) {
                                    executeSplitFirstSellForTp(pe, fPrice, fReason,
                                            Boolean.valueOf(capturedArmed), Double.valueOf(capturedPeak));
                                } else {
                                    String note = fSellType.startsWith("SPLIT_2ND") ? "SPLIT_2ND" : null;
                                    executeSellForTp(pe, fPrice, fReason, note,
                                            Boolean.valueOf(capturedArmed), Double.valueOf(capturedPeak));
                                }
                            } catch (Exception e) {
                                log.error("[OpeningScanner] realtime TP sell failed for {}", fMarket, e);
                            }
                        }
                    }, 0, TimeUnit.MILLISECONDS);
                }
            }
        });

        scheduleTick();

        // Entry phase 시간대 등록 (DB 설정값 → SharedPriceService 10초 갱신 활성화)
        OpeningScannerConfigEntity initCfg = configRepo.findById(1).orElse(null);
        if (initCfg != null) {
            sharedPriceService.registerEntryPhase(
                    initCfg.getEntryStartHour(), initCfg.getEntryStartMin(),
                    initCfg.getEntryEndHour(), initCfg.getEntryEndMin());
        }

        // 신규 TOP-N 마켓 콜백: entry phase 중 rangeHighCache + breakoutDetector에 즉시 등록
        newMarketListener = new NewMarketListener() {
            @Override
            public void onNewMarketsAdded(List<String> newMarkets) {
                if (!breakoutDetectorConnected) return; // range 수집 전이면 무시

                for (String market : newMarkets) {
                    if (rangeHighCache.containsKey(market)) continue; // 이미 등록됨

                    // 현재가를 rangeHigh baseline으로 설정
                    Double price = sharedPriceService.getPrice(market);
                    if (price == null || price <= 0) continue;

                    rangeHighCache.put(market, price);
                    breakoutDetector.addRangeHigh(market, price);
                    log.info("[OpeningScanner] 신규 TOP-N 동적 추가: {} rangeHigh={} (entry phase 중 감지)",
                            market, price);
                }
            }
        };
        sharedPriceService.addNewMarketListener(newMarketListener);

        // 옵션 B: 1분봉 사전 캐시 스케줄러 (10초 주기로 갱신)
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    precacheOneMinCandles();
                } catch (Exception e) {
                    log.debug("[OpeningScanner] precache 1min candles failed: {}", e.getMessage());
                }
            }
        }, 5, 10, TimeUnit.SECONDS);

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

        // SL 종합안 + TOP-N 차등 설정 캐시 갱신 (DB → BreakoutDetector)
        breakoutDetector.updateSlConfig(
                cfg.getGracePeriodSec(),
                cfg.getWidePeriodMin(),
                cfg.getWideSlTop10Pct().doubleValue(),
                cfg.getWideSlTop20Pct().doubleValue(),
                cfg.getWideSlTop50Pct().doubleValue(),
                cfg.getWideSlOtherPct().doubleValue(),
                cfg.getTightSlPct().doubleValue()
        );
        // V110: TP_TRAIL DB값 갱신
        breakoutDetector.setTpActivatePct(cfg.getTpTrailActivatePct().doubleValue());
        breakoutDetector.setTrailFromPeakPct(cfg.getTpTrailDropPct().doubleValue());
        // V111: Split-Exit DB값 갱신
        breakoutDetector.setSplitExitEnabled(cfg.isSplitExitEnabled());
        breakoutDetector.setSplitTpPct(cfg.getSplitTpPct().doubleValue());
        breakoutDetector.setSplitRatio(cfg.getSplitRatio().doubleValue());
        breakoutDetector.setTrailDropAfterSplit(cfg.getTrailDropAfterSplit().doubleValue());
        breakoutDetector.setSplit1stTrailDropPct(cfg.getSplit1stTrailDrop().doubleValue());  // V115
        breakoutDetector.setSplit1stCooldownSec(cfg.getSplit1stCooldownSec());  // V126
        // V130 ①: Trail Ladder A — detector 캐시 갱신
        breakoutDetector.setTrailLadder(
                cfg.isTrailLadderEnabled(),
                cfg.getSplit1stDropUnder2().doubleValue(),
                cfg.getSplit1stDropUnder3().doubleValue(),
                cfg.getSplit1stDropUnder5().doubleValue(),
                cfg.getSplit1stDropAbove5().doubleValue(),
                cfg.getTrailAfterDropUnder2().doubleValue(),
                cfg.getTrailAfterDropUnder3().doubleValue(),
                cfg.getTrailAfterDropUnder5().doubleValue(),
                cfg.getTrailAfterDropAbove5().doubleValue()
        );
        // V130 ④: SPLIT_1ST roi 하한선
        breakoutDetector.setSplit1stRoiFloorPct(cfg.getSplit1stRoiFloorPct().doubleValue());

        // KST 현재 시각 확인
        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        int nowMinOfDay = nowKst.getHour() * 60 + nowKst.getMinute();
        int rangeStart = cfg.getRangeStartHour() * 60 + cfg.getRangeStartMin();
        int sessionEnd = cfg.getSessionEndHour() * 60 + cfg.getSessionEndMin();

        // 활성 시간 밖이면 스킵
        // sessionEnd < 12:00이면 오버나잇 세션 → 익일 새벽까지 보유 → 항상 active 유지
        // 단 02:00~07:00은 IDLE (포지션 모니터링은 별도 WebSocket으로)
        boolean isOvernight = sessionEnd < 12 * 60;
        boolean inActiveHours;
        if (isOvernight) {
            // 오버나잇: rangeStart ~ 23:59 OR 00:00 ~ sessionEnd+30 OR 다음날 영역
            // 02:00~07:00 IDLE 시간만 제외
            inActiveHours = !(nowMinOfDay >= 2 * 60 && nowMinOfDay < 7 * 60);
        } else {
            inActiveHours = nowMinOfDay >= rangeStart && nowMinOfDay <= sessionEnd + 30;
        }
        if (!inActiveHours) {
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
        // SL 종합안: tight = cfg.getSlPct() (V100=3.0%), wide = cfg.getWideSlOtherPct() (V103=6.0%)
        ScalpOpeningBreakStrategy strategy = new ScalpOpeningBreakStrategy()
                .withTiming(cfg.getRangeStartHour(), cfg.getRangeStartMin(),
                        cfg.getRangeEndHour(), cfg.getRangeEndMin(),
                        cfg.getEntryStartHour(), cfg.getEntryStartMin(),
                        cfg.getEntryEndHour(), cfg.getEntryEndMin(),
                        cfg.getSessionEndHour(), cfg.getSessionEndMin())
                .withRisk(cfg.getTpAtrMult().doubleValue(),
                        cfg.getSlPct().doubleValue(),
                        cfg.getTrailAtrMult().doubleValue())
                .withSlAdvanced(cfg.getGracePeriodSec(),
                        cfg.getWidePeriodMin(),
                        cfg.getWideSlOtherPct().doubleValue())
                .withFilters(cfg.getVolumeMult().doubleValue(),
                        cfg.getMinBodyRatio().doubleValue())
                .withOpenFailedEnabled(cfg.isOpenFailedEnabled());

        int candleUnit = cfg.getCandleUnitMin();

        // 기존 보유 코인 제외 (entry_strategy != SCALP_OPENING_BREAK)
        // V130 ⑤: dust 포지션은 보유로 간주하지 않음
        Set<String> ownedMarkets = new HashSet<String>();
        List<PositionEntity> allPositions = positionRepo.findAll();
        int scannerPosCount = 0;
        for (PositionEntity pe : allPositions) {
            if (!scannerLockService.isDustPosition(pe) && pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
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

        // WebSocket 돌파 감지기 — 2026-04-09 변경: listener 미리 등록 + entry window check
        // 기존: entry window(09:05) 진입 시점에야 connect → 09:05:00 첫 가격 update 놓침
        // 변경: entry window 5분 전(09:00)부터 connect (range 수집도 미리)
        //       BreakoutDetector에 entry window 시각 전달 → 윈도우 안에서만 confirm 카운트
        int entryStart = cfg.getEntryStartHour() * 60 + cfg.getEntryStartMin();
        int entryEnd = cfg.getEntryEndHour() * 60 + cfg.getEntryEndMin();
        boolean inEntryWindow = nowMinOfDay >= entryStart && nowMinOfDay <= entryEnd;
        boolean nearEntryWindow = nowMinOfDay >= (entryStart - 5) && nowMinOfDay <= entryEnd;

        if (nearEntryWindow && !breakoutDetectorConnected && !topMarkets.isEmpty()) {
            // rangeHigh 계산 (서비스 레벨에서 직접)
            collectRangeHighForDetector(topMarkets, candleUnit, cfg);
            if (!rangeHighCache.isEmpty()) {
                breakoutDetector.setRangeHighMap(new HashMap<String, Double>(rangeHighCache));
                // ★ entry window 시각 전달 — checkBreakout이 윈도우 안에서만 confirm 카운트
                breakoutDetector.setEntryWindow(entryStart, entryEnd);
                breakoutDetector.connect(new ArrayList<String>(rangeHighCache.keySet()));
                breakoutDetectorConnected = true;
                log.info("[OpeningScanner] WebSocket breakout detector connected: {} markets, entryWindow={}~{}",
                        rangeHighCache.size(), entryStart, entryEnd);
            }
        }
        // entry window 종료 후: 포지션 없으면 WebSocket 해제, 있으면 유지 (실시간 TP용)
        if (nowMinOfDay > entryEnd && breakoutDetectorConnected && scannerPosCount == 0) {
            breakoutDetector.disconnect();
            breakoutDetectorConnected = false;
            log.info("[OpeningScanner] WebSocket disconnected (no positions)");
        }
        // 포지션 있는데 WebSocket 해제된 경우 재연결 (세션 종료 전까지)
        // 오버나잇 세션이면 항상 재연결 (포지션이 있는 한)
        int sessionEndForWs = cfg.getSessionEndHour() * 60 + cfg.getSessionEndMin();
        boolean isOvernightWs = sessionEndForWs < 12 * 60;
        boolean wsAllowed = isOvernightWs || nowMinOfDay < sessionEndForWs;
        if (!breakoutDetectorConnected && scannerPosCount > 0 && wsAllowed) {
            List<String> posMarkets = new ArrayList<String>();
            for (PositionEntity pe : allPositions) {
                if ("SCALP_OPENING_BREAK".equals(pe.getEntryStrategy())
                        && !scannerLockService.isDustPosition(pe)
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
            Map<String, Long> openedAtMap = new LinkedHashMap<String, Long>();
            Map<String, Integer> splitPhaseMap = new LinkedHashMap<String, Integer>();
            // V118: DB peak/armed 맵 전달 (재시작 복구 시 실제 peak/armed 유지)
            Map<String, Double> dbPeakMap = new LinkedHashMap<String, Double>();
            Map<String, Boolean> dbArmedMap = new LinkedHashMap<String, Boolean>();
            // V126: SPLIT_1ST 체결 시점 복구 (재시작 시 쿨다운 이어가기)
            Map<String, Long> dbSplit1stExecMap = new LinkedHashMap<String, Long>();
            for (PositionEntity pe : scannerPositions) {
                if (pe.getAvgPrice() != null) {
                    posMap.put(pe.getMarket(), pe.getAvgPrice().doubleValue());
                    if (pe.getOpenedAt() != null) {
                        openedAtMap.put(pe.getMarket(), pe.getOpenedAt().toEpochMilli());
                    }
                    splitPhaseMap.put(pe.getMarket(), pe.getSplitPhase());
                    if (pe.getPeakPrice() != null) {
                        dbPeakMap.put(pe.getMarket(), pe.getPeakPrice().doubleValue());
                    }
                    dbArmedMap.put(pe.getMarket(), pe.getArmedAt() != null);
                    if (pe.getSplit1stExecutedAt() != null) {
                        dbSplit1stExecMap.put(pe.getMarket(), pe.getSplit1stExecutedAt().toEpochMilli());
                    }
                }
            }
            breakoutDetector.updatePositionCache(posMap, openedAtMap, splitPhaseMap, dbPeakMap, dbArmedMap, dbSplit1stExecMap);

            // V118: detector 메모리 peak/armed → DB 영속화 (0.3% 이상 상승 또는 armed 전환 시)
            for (PositionEntity pe : scannerPositions) {
                String market = pe.getMarket();
                if (pe.getAvgPrice() == null) continue;
                double avgPrice = pe.getAvgPrice().doubleValue();
                if (avgPrice <= 0) continue;

                Double memPeak = breakoutDetector.getPeak(market);
                if (memPeak == null) continue;
                boolean memArmed = breakoutDetector.isArmed(market);

                BigDecimal dbPeak = pe.getPeakPrice();
                boolean peakDirty;
                if (dbPeak == null) {
                    peakDirty = (memPeak - avgPrice) / avgPrice * 100.0 >= 0.3;
                } else {
                    peakDirty = (memPeak - dbPeak.doubleValue()) / avgPrice * 100.0 >= 0.3;
                }
                boolean armedDirty = memArmed && pe.getArmedAt() == null;

                if (peakDirty || armedDirty) {
                    if (peakDirty) pe.setPeakPrice(BigDecimal.valueOf(memPeak));
                    if (armedDirty) pe.setArmedAt(Instant.now());
                    try {
                        positionRepo.save(pe);
                    } catch (Exception ex) {
                        log.warn("[OpeningScanner] V118 peak/armed save failed for {}", market, ex);
                    }
                }
            }
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
                        // ※ OPEN_TIME_EXIT(12:00 강제청산 등) candle-based 경로는
                        //   OpeningBreakoutDetector.checkRealtimeTp(WebSocket)의 Grace 60s/쿨다운 60s와 물리적으로 분리됨.
                        //   12:00 청산은 진입(09:05~10:30) 후 최소 90분 경과 → Grace 60s와 겹침 불가.
                        //   분리 구조를 깨뜨리려면(예: candle exit에도 Grace 적용) 반드시 충돌 테스트 추가할 것.
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
        // ★ 2026-04-09: 5분 boundary BUY path 비활성화 (BOUNDARY_BUY_ENABLED 플래그 참조)
        // 옵션 B (tryWsBreakoutBuy) 단독 매수. SELL phase는 위에서 그대로 동작했음.
        if (!BOUNDARY_BUY_ENABLED) {
            // 매수 phase 전체 skip. tick 종료.
            activePositions = scannerPosCount;
            return;
        }

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
                    if (market.equals(pe.getMarket()) && !scannerLockService.isDustPosition(pe)
                            && pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                        alreadyHas = true;
                        break;
                    }
                }
                if (!alreadyHas) {
                    // 매도 후 재매수 쿨다운 체크 (60초)
                    Long lastSellTime = sellCooldownMap.get(market);
                    if (lastSellTime != null && System.currentTimeMillis() - lastSellTime < SELL_COOLDOWN_MS) {
                        long remainSec = (SELL_COOLDOWN_MS - (System.currentTimeMillis() - lastSellTime)) / 1000;
                        addDecision(market, "BUY", "BLOCKED", "SELL_COOLDOWN",
                                String.format("매도 후 %d초 쿨다운 남음", remainSec));
                        continue;
                    }
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

                // 결함 4: throttle 소모 전 canEnter 사전 차단 (throttle 낭비 방지)
                if (!scannerLockService.canEnter(bs.market, "OP")) {
                    addDecision(bs.market, "BUY", "BLOCKED", "CROSS_SCANNER_COOLDOWN",
                            "다른 스캐너 보유 또는 손실 후 쿨다운 (tick 사전 차단)");
                    continue;
                }

                // ★ atomic throttle claim (race fix)
                if (!hourlyThrottle.tryClaim(bs.market)) {
                    addDecision(bs.market, "BUY", "BLOCKED", "HOURLY_LIMIT",
                            String.format("1시간 내 최대 2회 매매 제한 (남은 대기: %ds)", hourlyThrottle.remainingWaitMs(bs.market) / 1000));
                    continue;
                }

                // 결함 2: tick path L1 지연 진입 (delaySec초 후 현재가 >= 시그널가 확인)
                int opL1Sec = cfg.getL1DelaySec();
                if (opL1Sec > 0) {
                    final String fMarket = bs.market;
                    final double fSignalPrice = bs.candle.trade_price;
                    final UpbitCandle fCandle = bs.candle;
                    final Signal fSignal = bs.signal;
                    final OpeningScannerConfigEntity fCfg = cfg;
                    addDecision(bs.market, "BUY", "PENDING", "L1_DELAY",
                            String.format(Locale.ROOT, "L1 지연 %d초 후 가격 재확인 (tick)", opL1Sec));
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
                                log.info("[OpeningScanner] tick L1_OK: {} currentPrice={} >= signalPrice={}",
                                        fMarket, currentPrice, fSignalPrice);
                                try {
                                    // V131: fillPrice 반환 → breakoutDetector.addPosition에 실 체결가 전달
                                    double opFillPrice = executeBuy(fMarket, fCandle, fSignal, fCfg);
                                    if (opFillPrice > 0) {
                                        addDecision(fMarket, "BUY", "EXECUTED", "SIGNAL", fSignal.reason);
                                    }
                                } catch (Exception e) {
                                    hourlyThrottle.releaseClaim(fMarket);
                                    log.error("[OpeningScanner] tick L1 delayed buy failed for {}", fMarket, e);
                                    addDecision(fMarket, "BUY", "ERROR", "EXECUTION_FAIL",
                                            "tick L1 매수 오류: " + e.getMessage());
                                }
                            } catch (Exception e) {
                                log.warn("[OpeningScanner] tick L1 스케줄러 오류 {}: {}", fMarket, e.getMessage());
                                hourlyThrottle.releaseClaim(fMarket);
                            }
                        }
                    }, opL1Sec, TimeUnit.SECONDS);
                    spentKrw += orderKrw.doubleValue();
                    scannerPosCount++;
                    entrySuccess++;
                } else {
                    try {
                        // V131: fillPrice 반환 → breakoutDetector.addPosition에 실 체결가 전달
                        double opFillPrice = executeBuy(bs.market, bs.candle, bs.signal, cfg);
                        if (opFillPrice > 0) {
                            spentKrw += orderKrw.doubleValue();
                            scannerPosCount++;
                            entrySuccess++;
                            // 실시간 TP/SL 종합안 캐시에 등록 (openedAt = 현재, volumeRank 포함)
                            int volumeRank = breakoutDetector.getSharedPriceService() != null
                                    ? breakoutDetector.getSharedPriceService().getVolumeRank(bs.market) : 999;
                            breakoutDetector.addPosition(bs.market, opFillPrice, System.currentTimeMillis(), volumeRank);
                            addDecision(bs.market, "BUY", "EXECUTED", "SIGNAL", bs.signal.reason);
                        } else {
                            hourlyThrottle.releaseClaim(bs.market);
                        }
                    } catch (Exception e) {
                        // 실패 시 throttle 권한 반환
                        hourlyThrottle.releaseClaim(bs.market);
                        log.error("[OpeningScanner] buy execution failed for {}", bs.market, e);
                        addDecision(bs.market, "BUY", "ERROR", "EXECUTION_FAIL",
                                "매수 실행 오류: " + e.getMessage());
                    }
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

    /** @return fillPrice (실 체결가). 매수 미실행 시 -1.0 */
    private double executeBuy(String market, UpbitCandle candle, Signal signal,
                               OpeningScannerConfigEntity cfg) {
        // ★ race 방어: in-flight 매수 차단 (KRW-CBK 사고 패턴 재발 방지)
        if (!buyingMarkets.add(market)) {
            log.info("[OpeningScanner] BUY in progress, skip duplicate: {}", market);
            return -1.0;
        }
        try {
            return executeBuyInner(market, candle, signal, cfg);
        } finally {
            buyingMarkets.remove(market);
        }
    }

    /** @return fillPrice (실 체결가). 매수 미실행/실패 시 -1.0 */
    private double executeBuyInner(String market, UpbitCandle candle, Signal signal,
                                  OpeningScannerConfigEntity cfg) {
        double price = candle.trade_price;
        BigDecimal orderKrw = calcOrderSize(cfg);
        if (orderKrw.compareTo(BigDecimal.valueOf(5000)) < 0) {
            log.warn("[OpeningScanner] order too small: {} KRW for {}", orderKrw, market);
            addDecision(market, "BUY", "BLOCKED", "ORDER_TOO_SMALL",
                    String.format("주문 금액 %s원이 최소 5,000원 미만", orderKrw.toPlainString()));
            return -1.0;
        }

        // V130 ③: 스캐너 간 동일종목 재진입 차단 (dust 제외, 손실 쿨다운)
        if (!scannerLockService.canEnter(market, "OP")) {
            addDecision(market, "BUY", "BLOCKED", "CROSS_SCANNER_COOLDOWN",
                    "크로스 스캐너 락: 다른 스캐너 보유 또는 손실 쿨다운 중");
            return -1.0;
        }

        // 사전 중복 포지션 차단 (KRW-TREE orphan 사고 재발 방지)
        // 다른 스캐너가 이미 매수한 코인인지 DB에서 한 번 더 확인
        PositionEntity existing = positionRepo.findById(market).orElse(null);
        if (existing != null && !scannerLockService.isDustPosition(existing)
                && existing.getQty() != null && existing.getQty().compareTo(BigDecimal.ZERO) > 0) {
            log.warn("[OpeningScanner] DUPLICATE_POSITION blocked: {} already held by {} qty={}",
                    market, existing.getEntryStrategy(), existing.getQty());
            addDecision(market, "BUY", "BLOCKED", "DUPLICATE_POSITION",
                    String.format("이미 보유 중 (전략=%s qty=%s) — 중복 매수 차단",
                            existing.getEntryStrategy(), existing.getQty().toPlainString()));
            return -1.0;
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
                return -1.0;
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
                    return -1.0;
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
                    return -1.0;
                }
                log.info("[OpeningScanner] LIVE buy filled: market={} state={} price={} qty={}",
                        market, r.state, fillPrice, qty);
            } catch (Exception e) {
                log.error("[OpeningScanner] LIVE buy order failed for {}", market, e);
                addDecision(market, "BUY", "ERROR", "ORDER_EXCEPTION",
                        "주문 실패: " + e.getMessage());
                return -1.0;
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
                pe.setPeakPrice(BigDecimal.valueOf(fFillPrice));
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
                tl.setEntrySignal(signal.reason); // V128
                tl.setConfidence(signal.confidence);
                tl.setCandleUnitMin(cfg.getCandleUnitMin());
                tradeLogRepo.save(tl);
            }
        });

        log.info("[OpeningScanner] BUY {} mode={} price={} qty={} conf={} reason={}",
                market, cfg.getMode(), fillPrice, qty, signal.confidence, signal.reason);
        return fillPrice;
    }

    private void executeSell(PositionEntity pe, UpbitCandle candle, Signal signal,
                              OpeningScannerConfigEntity cfg) {
        // ★ race 방어: 동시 매도 차단 (KRW-CBK 패턴)
        if (!sellingMarkets.add(pe.getMarket())) {
            log.debug("[OpeningScanner] SELL in progress, skip duplicate: {}", pe.getMarket());
            return;
        }
        try {
            executeSellInner(pe, candle, signal, cfg);
        } finally {
            sellingMarkets.remove(pe.getMarket());
        }
    }

    private void executeSellInner(PositionEntity pe, UpbitCandle candle, Signal signal,
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
        // V128 B안: detector 메모리의 armed/peak가 더 정확 (DB는 영속화 주기 전까지 stale).
        final Double detectorPeak = breakoutDetector.getPeak(peMarket);
        final boolean detectorArmed = breakoutDetector.isArmed(peMarket);
        final BigDecimal pePeakPrice = pe.getPeakPrice(); // V118 DB 영속화 peak (fallback)
        final boolean peArmed = detectorArmed || pe.getArmedAt() != null;
        final double memPeakVal = detectorPeak != null ? detectorPeak.doubleValue() : 0;
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
                // V128: peak/armed 로그 기록 — detector 메모리 peak 우선, DB 영속 peak fallback
                if (memPeakVal > 0 && peAvgPrice != null && peAvgPrice.signum() > 0) {
                    tl.setPeakPrice(memPeakVal);
                    double peakRoi = (memPeakVal - peAvgPrice.doubleValue())
                            / peAvgPrice.doubleValue() * 100.0;
                    tl.setPeakRoiPct(BigDecimal.valueOf(peakRoi));
                } else if (pePeakPrice != null && pePeakPrice.signum() > 0
                        && peAvgPrice != null && peAvgPrice.signum() > 0) {
                    tl.setPeakPrice(pePeakPrice);
                    double peakRoi = (pePeakPrice.doubleValue() - peAvgPrice.doubleValue())
                            / peAvgPrice.doubleValue() * 100.0;
                    tl.setPeakRoiPct(BigDecimal.valueOf(peakRoi));
                }
                tl.setArmedFlag(peArmed ? "Y" : "N");
                tradeLogRepo.save(tl);

                positionRepo.deleteById(peMarket);
            }
        });

        // 매도 후 재매수 쿨다운 기록 (BERA 반복매매 방지)
        sellCooldownMap.put(pe.getMarket(), System.currentTimeMillis());

        // BREAKOUT 재감지 허용 (DRIFT 사고 방지) — 즉시 재매수는 sellCooldownMap이 차단
        breakoutDetector.releaseMarket(pe.getMarket());

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
                // V111: splitPhase=1이면 splitOriginalQty 기준 (자본 슬롯 유지)
                double qty = (pe.getSplitPhase() == 1 && pe.getSplitOriginalQty() != null)
                        ? pe.getSplitOriginalQty().doubleValue()
                        : pe.getQty().doubleValue();
                sum += qty * pe.getAvgPrice().doubleValue();
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
        executeSellForTp(pe, price, reason, null, null, null);
    }

    private void executeSellForTp(PositionEntity pe, double price, String reason, String note) {
        executeSellForTp(pe, price, reason, note, null, null);
    }

    private void executeSellForTp(PositionEntity pe, double price, String reason, String note,
                                    Boolean capturedArmed, Double capturedPeak) {
        if (!sellingMarkets.add(pe.getMarket())) {
            log.debug("[OpeningScanner] TP SELL in progress, skip duplicate: {}", pe.getMarket());
            return;
        }
        try {
            executeSellForTpInner(pe, price, reason, note, capturedArmed, capturedPeak);
        } finally {
            sellingMarkets.remove(pe.getMarket());
        }
    }

    private void executeSellForTpInner(PositionEntity pe, double price, String reason, final String note) {
        executeSellForTpInner(pe, price, reason, note, null, null);
    }

    private void executeSellForTpInner(PositionEntity pe, double price, String reason, final String note,
                                        Boolean capturedArmed, Double capturedPeak) {
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
        final BigDecimal pePeakPrice = pe.getPeakPrice(); // V128
        // V128 B안: WS 스레드가 캡처한 detector.isArmed() 우선 사용. 없으면 DB armedAt fallback.
        //  ※ DB armedAt은 V118 mainloop 영속화 주기(수 초) 전까지는 stale일 수 있음.
        final boolean peArmed = capturedArmed != null ? capturedArmed.booleanValue() : (pe.getArmedAt() != null);
        final double capturedPeakVal = capturedPeak != null ? capturedPeak.doubleValue() : 0;

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
                tl.setNote(note != null ? note : "TP_TRAIL_REALTIME");
                tl.setCandleUnitMin(5);
                // V128: peak/armed 로그 기록 — 캡처된 peak(WS)를 우선, 없으면 DB peakPrice 사용
                if (capturedPeakVal > 0 && peAvgPrice != null && peAvgPrice.signum() > 0) {
                    tl.setPeakPrice(capturedPeakVal);
                    double peakRoi = (capturedPeakVal - peAvgPrice.doubleValue())
                            / peAvgPrice.doubleValue() * 100.0;
                    tl.setPeakRoiPct(BigDecimal.valueOf(peakRoi));
                } else if (pePeakPrice != null && pePeakPrice.signum() > 0
                        && peAvgPrice != null && peAvgPrice.signum() > 0) {
                    tl.setPeakPrice(pePeakPrice);
                    double peakRoi = (pePeakPrice.doubleValue() - peAvgPrice.doubleValue())
                            / peAvgPrice.doubleValue() * 100.0;
                    tl.setPeakRoiPct(BigDecimal.valueOf(peakRoi));
                }
                tl.setArmedFlag(peArmed ? "Y" : "N");
                tradeLogRepo.save(tl);
                positionRepo.deleteById(market);
            }
        });

        sellCooldownMap.put(market, System.currentTimeMillis());
        breakoutDetector.releaseMarket(market);

        String logLabel = note != null ? note : "REALTIME TP";
        log.info("[OpeningScanner] {} {} price={} pnl={} roi={}% reason={}",
                logLabel, market, fFillPrice, Math.round(pnlKrw), String.format("%.2f", roiPct), reason);
    }

    // ━━━ V111: Split-Exit 1차 분할 매도 ━━━

    private void executeSplitFirstSellForTp(PositionEntity pe, double price, String reason) {
        executeSplitFirstSellForTp(pe, price, reason, null, null);
    }

    private void executeSplitFirstSellForTp(PositionEntity pe, double price, String reason,
                                             Boolean capturedArmed, Double capturedPeak) {
        if (!sellingMarkets.add(pe.getMarket())) {
            log.debug("[OpeningScanner] SPLIT_1ST in progress, skip duplicate: {}", pe.getMarket());
            return;
        }
        try {
            executeSplitFirstSellForTpInner(pe, price, reason, capturedArmed, capturedPeak);
        } finally {
            sellingMarkets.remove(pe.getMarket());
        }
    }

    private void executeSplitFirstSellForTpInner(PositionEntity pe, double price, String reason) {
        executeSplitFirstSellForTpInner(pe, price, reason, null, null);
    }

    private void executeSplitFirstSellForTpInner(PositionEntity pe, double price, String reason,
                                                   Boolean capturedArmed, Double capturedPeak) {
        String market = pe.getMarket();
        if (pe.getSplitPhase() != 0) {
            log.debug("[OpeningScanner] SPLIT_1ST: already split for {} phase={}", market, pe.getSplitPhase());
            return;
        }

        OpeningScannerConfigEntity cfg = configRepo.loadOrCreate();
        double totalQty = pe.getQty().doubleValue();
        double avgPrice = pe.getAvgPrice().doubleValue();
        double sellRatio = cfg.getSplitRatio().doubleValue();
        double sellQty = totalQty * sellRatio;
        double remainQty = totalQty - sellQty;

        // dust 체크: 잔량 * 현재가 < 5000원이면 전량 매도
        boolean isDust = remainQty * price < 5000;
        double actualSellQty = isDust ? totalQty : sellQty;

        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        double fillPrice;

        if (isPaper) {
            fillPrice = price * 0.999;
        } else {
            if (!liveOrders.isConfigured()) {
                log.error("[OpeningScanner] SPLIT_1ST: API 키 미설정 market={}", market);
                return;
            }
            try {
                LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(market, actualSellQty);
                if (!r.isFilled() && r.executedVolume <= 0) {
                    log.warn("[OpeningScanner] SPLIT_1ST: sell not filled for {}", market);
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
                log.error("[OpeningScanner] SPLIT_1ST: sell failed for {}", market, e);
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
        final BigDecimal pePeakPrice = pe.getPeakPrice(); // V128
        // V128 B안: SPLIT_1ST는 detector 메모리의 armed/peak가 권위 있음 (WS 캡처값 우선).
        //  - DB armedAt은 V118 mainloop 영속화 주기 전까지 stale.
        //  - SPLIT_1ST 감지 순간 detector.isArmed()=true였음을 그대로 반영.
        final boolean peArmed = capturedArmed != null ? capturedArmed.booleanValue() : (pe.getArmedAt() != null);
        final double capturedPeakVal = capturedPeak != null ? capturedPeak.doubleValue() : 0;

        try {
            txTemplate.execute(new org.springframework.transaction.support.TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                    TradeEntity tl = new TradeEntity();
                    tl.setTsEpochMs(System.currentTimeMillis());
                    tl.setMarket(market);
                    tl.setAction("SELL");
                    tl.setPrice(BigDecimal.valueOf(fFillPrice));
                    tl.setQty(BigDecimal.valueOf(fActualSellQty));
                    tl.setPnlKrw(BigDecimal.valueOf(fPnlKrw));
                    tl.setRoiPercent(BigDecimal.valueOf(fRoiPct));
                    tl.setMode(fMode);
                    tl.setPatternType("SCALP_OPENING_BREAK");
                    tl.setPatternReason(fReason);
                    tl.setAvgBuyPrice(peAvgPrice);
                    tl.setNote(fIsDust ? "SPLIT_1ST_DUST" : "SPLIT_1ST");
                    tl.setCandleUnitMin(5);
                    // V128: peak/armed 로그 기록 — 캡처된 WS peak 우선, 없으면 DB peakPrice
                    if (capturedPeakVal > 0 && peAvgPrice != null && peAvgPrice.signum() > 0) {
                        tl.setPeakPrice(capturedPeakVal);
                        double peakRoi = (capturedPeakVal - peAvgPrice.doubleValue())
                                / peAvgPrice.doubleValue() * 100.0;
                        tl.setPeakRoiPct(BigDecimal.valueOf(peakRoi));
                    } else if (pePeakPrice != null && pePeakPrice.signum() > 0
                            && peAvgPrice != null && peAvgPrice.signum() > 0) {
                        tl.setPeakPrice(pePeakPrice);
                        double peakRoi = (pePeakPrice.doubleValue() - peAvgPrice.doubleValue())
                                / peAvgPrice.doubleValue() * 100.0;
                        tl.setPeakRoiPct(BigDecimal.valueOf(peakRoi));
                    }
                    tl.setArmedFlag(peArmed ? "Y" : "N");
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
                        // V126: 쿨다운 기준점 기록
                        pe.setSplit1stExecutedAt(java.time.Instant.now());
                        positionRepo.save(pe);
                    }
                }
            });
        } catch (Exception e) {
            // P1-4: DB 실패 시 detector cache 롤백
            // V129 #9 fix: 쿨다운 기준점도 함께 제거해야 메모리-DB 불일치 제거
            log.error("[OpeningScanner] SPLIT_1ST DB commit failed for {} — detector rollback", market, e);
            breakoutDetector.setSplitPhase(market, 0);
            breakoutDetector.clearSplit1stCooldown(market);
            return;
        }

        if (isDust) {
            sellCooldownMap.put(market, System.currentTimeMillis());
            breakoutDetector.releaseMarket(market);
            log.info("[OpeningScanner] SPLIT_1ST_DUST {} (전량, 잔량<5000원) price={} pnl={} roi={}%",
                    market, fFillPrice, Math.round(pnlKrw), String.format("%.2f", roiPct));
        } else {
            // 1차 매도: 쿨다운 미등록, detector 유지 (splitPhase는 detector에서 이미 1로 설정됨)
            log.info("[OpeningScanner] SPLIT_1ST {} qty={}/{} price={} pnl={} roi={}%",
                    market, String.format("%.8f", actualSellQty), String.format("%.8f", totalQty),
                    fFillPrice, Math.round(pnlKrw), String.format("%.2f", roiPct));
        }
    }

    // ════════════════════════════════════════════════════════════
    //  옵션 B: 1분봉 사전 캐시 + WS 즉시 진입
    // ════════════════════════════════════════════════════════════

    /**
     * 매 10초마다 호출됨. 진입 윈도우(09:05~10:30) 동안만 동작.
     * rangeHighCache의 모든 종목에 대해 1분봉 60개를 사전에 fetch해서 메모리 캐시.
     * WS 돌파 감지 시 이 캐시를 즉시 사용하여 빠른 필터 검사.
     */
    private void precacheOneMinCandles() {
        if (!running.get()) return;

        OpeningScannerConfigEntity cfg;
        try {
            cfg = configRepo.findById(1).orElse(null);
        } catch (Exception e) {
            return;
        }
        if (cfg == null || !cfg.isEnabled()) return;

        // 진입 윈도우 5분 전부터 캐시 갱신 (2026-04-09 변경)
        // 이유: entry window 시작 정각(09:05:00)에 캐시가 채워진 상태로 옵션 B 즉시 매수 가능.
        // 기존엔 09:05 entry start 이후부터 갱신 → 09:05:00~09:05:10 사이 캐시 비어있어
        // 옵션 B가 fallback fetch에 의존했음.
        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        int nowMin = nowKst.getHour() * 60 + nowKst.getMinute();
        int entryStart = cfg.getEntryStartHour() * 60 + cfg.getEntryStartMin();
        int entryEnd = cfg.getEntryEndHour() * 60 + cfg.getEntryEndMin();
        if (nowMin < entryStart - 5 || nowMin > entryEnd) return;

        if (rangeHighCache.isEmpty()) return;

        // 캐시 갱신 (병렬 fetch)
        long startMs = System.currentTimeMillis();
        List<String> markets = new ArrayList<String>(rangeHighCache.keySet());
        int success = 0;
        for (String market : markets) {
            try {
                List<UpbitCandle> candles = candleService.getMinuteCandlesPaged(market, 1, 60);
                if (candles != null && !candles.isEmpty()) {
                    oneMinCandleCache.put(market, candles);
                    success++;
                }
            } catch (Exception e) {
                // 개별 실패는 무시
            }
        }
        lastPrecacheEpochMs = System.currentTimeMillis();
        long elapsed = lastPrecacheEpochMs - startMs;
        log.info("[OpeningScanner] 1min candle cache: {}/{} markets ({}ms)",
                success, markets.size(), elapsed);
    }

    /**
     * WS 돌파 감지 시 호출됨.
     * 1분봉 캐시를 사용해 즉시 필터 검사 + 매수.
     * 검사가 통과하면 ScalpOpeningBreakStrategy 호출 없이 즉시 매수.
     */
    private void tryWsBreakoutBuy(String market, double wsPrice, double rangeHigh, double breakoutPctActual) {
        if (!running.get()) return;

        // 중복 처리 방지 (같은 종목 동시 처리 차단)
        if (!wsBreakoutProcessing.add(market)) {
            log.debug("[OpeningScanner] {} WS buy already in progress", market);
            return;
        }

        try {
            OpeningScannerConfigEntity cfg = configRepo.findById(1).orElse(null);
            if (cfg == null || !cfg.isEnabled()) return;

            // ★ Entry window 체크 (2026-04-09 추가)
            // listener를 entry window 전에 미리 등록했으므로, 윈도우 밖 호출 시 즉시 return.
            // 09:00~09:05 사이 listener가 가격 update 받아도 매수 안 함 (모닝러쉬와 충돌 방지).
            ZonedDateTime nowKst = ZonedDateTime.now(KST);
            int nowMin = nowKst.getHour() * 60 + nowKst.getMinute();
            int entryStartMin = cfg.getEntryStartHour() * 60 + cfg.getEntryStartMin();
            int entryEndMin = cfg.getEntryEndHour() * 60 + cfg.getEntryEndMin();
            if (nowMin < entryStartMin || nowMin > entryEndMin) {
                log.debug("[OpeningScanner] WS buy outside entry window: {} (nowMin={}, window={}~{})",
                        market, nowMin, entryStartMin, entryEndMin);
                return;
            }

            // 매도 쿨다운 체크
            Long lastSell = sellCooldownMap.get(market);
            if (lastSell != null && System.currentTimeMillis() - lastSell < SELL_COOLDOWN_MS) {
                long remainSec = (SELL_COOLDOWN_MS - (System.currentTimeMillis() - lastSell)) / 1000;
                addDecision(market, "BUY", "BLOCKED", "SELL_COOLDOWN",
                        "매도 후 " + remainSec + "초 쿨다운 남음 (WS)");
                return;
            }

            boolean throttleClaimed = false;

            // 활성 마켓 카운트 (이미 보유 중이면 스킵) — V130 ⑤ dust 제외
            List<PositionEntity> allPos = positionRepo.findAll();
            int rushPosCount = 0;
            for (PositionEntity pe : allPos) {
                if ("SCALP_OPENING_BREAK".equals(pe.getEntryStrategy())
                        && !scannerLockService.isDustPosition(pe)
                        && pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                    rushPosCount++;
                }
                if (market.equals(pe.getMarket()) && !scannerLockService.isDustPosition(pe)
                        && pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                    log.debug("[OpeningScanner] {} already held, skip WS buy", market);
                    return;
                }
            }
            if (rushPosCount >= cfg.getMaxPositions()) {
                addDecision(market, "BUY", "BLOCKED", "MAX_POSITIONS",
                        "최대 포지션 수(" + cfg.getMaxPositions() + ") 도달 (WS)");
                return;
            }

            // 1분봉 캐시 확인
            List<UpbitCandle> candles = oneMinCandleCache.get(market);
            if (candles == null || candles.size() < 25) {
                // 캐시 없으면 즉시 fetch (rate limit 영향 최소)
                try {
                    candles = candleService.getMinuteCandlesPaged(market, 1, 60);
                    if (candles != null && !candles.isEmpty()) {
                        oneMinCandleCache.put(market, candles);
                    }
                } catch (Exception e) {
                    log.warn("[OpeningScanner] WS buy: 1min candle fetch failed for {}: {}",
                            market, e.getMessage());
                    addDecision(market, "BUY", "ERROR", "CANDLE_FETCH_FAIL",
                            "1분봉 fetch 실패: " + e.getMessage());
                    return;
                }
            }
            if (candles == null || candles.size() < 25) {
                addDecision(market, "BUY", "BLOCKED", "INSUFFICIENT_CANDLES",
                        "1분봉 부족: " + (candles == null ? 0 : candles.size()) + "/25");
                return;
            }

            // 빠른 필터 검사 (1분봉 기반)
            // BreakoutDetector가 이미 +1.0% 돌파 + 3 tick confirm으로 강한 신호 검증함.
            // SKIP 분기마다 breakoutDetector.releaseMarket() 호출로 재시도 가능 (옵션 B)
            UpbitCandle last = candles.get(candles.size() - 1);

            // 2. RSI 과매수 차단
            double rsi = Indicators.rsi(candles, 14);
            if (rsi >= 75) {
                addDecision(market, "BUY", "SKIPPED", "RSI_OVERBOUGHT",
                        String.format("RSI %.0f >= 75 (1min)", rsi));
                breakoutDetector.releaseMarket(market);
                return;
            }

            // 3. EMA20 위
            double ema20 = Indicators.ema(candles, 20);
            if (!Double.isNaN(ema20) && wsPrice < ema20) {
                addDecision(market, "BUY", "SKIPPED", "BELOW_EMA20",
                        String.format("price=%.2f < ema20=%.2f (1min)", wsPrice, ema20));
                breakoutDetector.releaseMarket(market);
                return;
            }

            // 4. 최소 돌파 강도
            if (breakoutPctActual < 1.5) {
                addDecision(market, "BUY", "SKIPPED", "BREAKOUT_WEAK",
                        String.format("bo=%.2f%% < 1.5%%", breakoutPctActual));
                breakoutDetector.releaseMarket(market);
                return;
            }

            // 5. 볼륨 필터 복원 (3분봉 평균, 2026-04-13)
            // FLOCK 사고 교훈: 단일 1분봉 vol은 부정확 → 3분봉 합산 평균으로 개선
            // 직전 3개 1분봉 평균 vs 20개 1분봉 평균 비교
            // V127: 임계값 하드코딩(1.5x) → DB값(기본 2.50x, UI에서 1.0~5.0 조정)
            int candleSize = candles.size();
            double vol3Ratio = computeVol3Ratio(candles);
            double vol3Threshold = cfg.getVol3RatioThreshold().doubleValue();
            if (candleSize >= 23 && !vol3GatePass(vol3Ratio, vol3Threshold)) {
                addDecision(market, "BUY", "SKIPPED", "VOL_3MIN_WEAK",
                        String.format(Locale.ROOT, "3분봉 vol=%.1fx < %.1fx", vol3Ratio, vol3Threshold));
                breakoutDetector.releaseMarket(market);
                return;
            }

            // volRatio는 로그용 (vol3Ratio 없을 때 fallback)
            double avgVolForLog = 0;
            int volCount = Math.min(20, candles.size());
            for (int i = candles.size() - volCount; i < candles.size(); i++) {
                avgVolForLog += candles.get(i).candle_acc_trade_volume;
            }
            avgVolForLog /= volCount;
            double curVol = last.candle_acc_trade_volume;
            double volRatio = avgVolForLog > 0 ? curVol / avgVolForLog : 0;

            // 6. 간이 3-Factor 스코어 (2026-04-13: 진입 품질 강화)
            // AllDay HCB 9.4점 대비 간이 버전: 돌파강도 + 볼륨 + RSI 복합 체크
            double quickScore = 0;

            // Factor A: 돌파 강도 (0~2.0)
            if (breakoutPctActual >= 3.0) quickScore += 2.0;
            else if (breakoutPctActual >= 2.0) quickScore += 1.5;
            else if (breakoutPctActual >= 1.5) quickScore += 1.0;
            else quickScore += 0.3;

            // Factor B: 볼륨 (0~1.5) — vol3Ratio 사용
            double volForScore = (candleSize >= 23) ? vol3Ratio : volRatio;
            if (volForScore >= 5.0) quickScore += 1.5;
            else if (volForScore >= 3.0) quickScore += 1.0;
            else if (volForScore >= 1.5) quickScore += 0.5;

            // Factor C: RSI 위치 (0~1.0)
            if (rsi >= 50 && rsi < 65) quickScore += 1.0;
            else if (rsi >= 65 && rsi < 75) quickScore += 0.6;
            else if (rsi < 50) quickScore += 0.3;

            if (quickScore < 2.0) {
                addDecision(market, "BUY", "SKIPPED", "LOW_QUICK_SCORE",
                        String.format(Locale.ROOT, "quickScore=%.1f < 2.0 (bo=%.2f%% vol=%.1fx rsi=%.0f)",
                                quickScore, breakoutPctActual, volForScore, rsi));
                breakoutDetector.releaseMarket(market);
                return;
            }

            // 결함 4 WS path: throttle 소모 전 canEnter 사전 차단 (throttle 낭비 방지)
            if (!scannerLockService.canEnter(market, "OP")) {
                addDecision(market, "BUY", "BLOCKED", "CROSS_SCANNER_COOLDOWN",
                        "다른 스캐너 보유 또는 손실 후 쿨다운 (WS 사전 차단)");
                breakoutDetector.releaseMarket(market);
                return;
            }

            // ★ atomic throttle claim — 모든 필터 통과 후, executeBuy 직전 (race fix)
            // 위치를 여기로 둔 이유: 위쪽 차단 분기들이 throttle을 잘못 기록하는 것 방지
            if (!hourlyThrottle.tryClaim(market)) {
                long remainSec = hourlyThrottle.remainingWaitMs(market) / 1000;
                addDecision(market, "BUY", "BLOCKED", "HOURLY_LIMIT",
                        "1시간 내 최대 2회 매매 제한 (남은: " + remainSec + "초, WS)");
                return;
            }
            throttleClaimed = true;

            // 모든 필터 통과
            double logVol = (candleSize >= 23) ? vol3Ratio : volRatio;
            final String fReason = String.format(Locale.ROOT,
                    "WS_BREAK price=%.2f rH=%.2f bo=+%.2f%% vol=%.1fx rsi=%.0f qs=%.1f (1min)",
                    wsPrice, rangeHigh, breakoutPctActual, logVol, rsi, quickScore);
            final Signal fSig = Signal.of(SignalAction.BUY, StrategyType.SCALP_OPENING_BREAK, fReason, 9.0);

            // V130 ②: L1 지연 진입 — delaySec 후 현재가 >= 시그널가 확인 시 매수
            int delaySec = cfg.getL1DelaySec();

            // executeBuy 재사용 위해 가짜 candle/signal 생성 (L1 경로에서도 사용)
            final UpbitCandle synth = new UpbitCandle();
            synth.market = market;
            synth.trade_price = wsPrice;
            synth.opening_price = wsPrice * 0.999;
            synth.high_price = wsPrice;
            synth.low_price = wsPrice * 0.999;
            synth.candle_acc_trade_volume = curVol;
            synth.candle_date_time_utc = last.candle_date_time_utc;

            final double fSignalPrice = wsPrice;
            final OpeningScannerConfigEntity fCfg = cfg;
            final String fMarket = market;
            final boolean fThrottleClaimed = throttleClaimed;

            if (delaySec > 0) {
                // L1 지연: delaySec 후 현재가 확인 후 매수
                log.info("[OpeningScanner] WS_BREAKOUT L1_DELAY: {} price={} bo=+{}% vol={}x rsi={} qs={} delay={}s",
                        market, wsPrice,
                        String.format(Locale.ROOT, "%.2f", breakoutPctActual),
                        String.format(Locale.ROOT, "%.1f", logVol),
                        String.format(Locale.ROOT, "%.0f", rsi),
                        String.format(Locale.ROOT, "%.1f", quickScore), delaySec);
                l1DelayScheduler.schedule(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Double currentPrice = sharedPriceService.getPrice(fMarket);
                            if (currentPrice == null || currentPrice < fSignalPrice) {
                                addDecision(fMarket, "BUY", "BLOCKED", "L1_NO_MOMENTUM",
                                        String.format(Locale.ROOT, "L1 지연 후 현재가 %.2f < 시그널가 %.2f — 꼬리매수 회피",
                                                currentPrice != null ? currentPrice : 0.0, fSignalPrice));
                                if (fThrottleClaimed) hourlyThrottle.releaseClaim(fMarket);
                                wsBreakoutProcessing.remove(fMarket);
                                return;
                            }
                            log.info("[OpeningScanner] WS_BREAKOUT L1_OK: {} currentPrice={} >= signalPrice={}",
                                    fMarket, currentPrice, fSignalPrice);
                            // 현재가로 synth 갱신
                            synth.trade_price = currentPrice;
                            synth.high_price = currentPrice;
                            try {
                                executeBuy(fMarket, synth, fSig, fCfg);
                            } catch (Exception e) {
                                if (fThrottleClaimed) hourlyThrottle.releaseClaim(fMarket);
                                log.error("[OpeningScanner] L1 delayed buy failed for {}", fMarket, e);
                            }
                        } finally {
                            wsBreakoutProcessing.remove(fMarket);
                        }
                    }
                }, delaySec, TimeUnit.SECONDS);
                // L1 경로는 스케줄러가 wsBreakoutProcessing.remove()를 담당
                // finally 블록에서 중복 제거 방지를 위해 return
                return;
            }

            log.info("[OpeningScanner] WS_BREAKOUT BUY: {} price={} bo=+{}% vol={}x rsi={} qs={}",
                    market, wsPrice,
                    String.format(Locale.ROOT, "%.2f", breakoutPctActual),
                    String.format(Locale.ROOT, "%.1f", logVol),
                    String.format(Locale.ROOT, "%.0f", rsi),
                    String.format(Locale.ROOT, "%.1f", quickScore));

            try {
                executeBuy(market, synth, fSig, cfg);
                // WS 경로 positionCache 등록 — tick 경로(line 995)와 대칭. executeBuy는 void이지만
                // 성공 시 DB에 포지션 저장됨. DB 재조회로 실패 경로(ORDER_NOT_FILLED 등)에서
                // phantom cache 생기는 것 방지.
                try {
                    PositionEntity savedPe = positionRepo.findById(market).orElse(null);
                    if (savedPe != null && savedPe.getQty() != null
                            && savedPe.getQty().signum() > 0
                            && savedPe.getAvgPrice() != null
                            && savedPe.getAvgPrice().signum() > 0) {
                        double realAvg = savedPe.getAvgPrice().doubleValue();
                        long realOpenedMs = savedPe.getOpenedAt() != null
                                ? savedPe.getOpenedAt().toEpochMilli()
                                : System.currentTimeMillis();
                        int volumeRank = breakoutDetector.getSharedPriceService() != null
                                ? breakoutDetector.getSharedPriceService().getVolumeRank(market) : 999;
                        breakoutDetector.addPosition(market, realAvg, realOpenedMs, volumeRank);
                    }
                } catch (Exception dbEx) {
                    log.warn("[OpeningScanner] WS buy 직후 DB 재조회 실패 {}: {}", market, dbEx.getMessage());
                }
            } catch (Exception e) {
                // 매수 실패 → throttle 권한 반환
                if (throttleClaimed) {
                    hourlyThrottle.releaseClaim(market);
                    throttleClaimed = false;
                }
                throw e;
            }
        } catch (Exception e) {
            log.error("[OpeningScanner] tryWsBreakoutBuy error for {}", market, e);
        } finally {
            wsBreakoutProcessing.remove(market);
        }
    }
}
