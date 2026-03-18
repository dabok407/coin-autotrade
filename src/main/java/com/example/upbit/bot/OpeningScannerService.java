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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

    // 스캐너 상태 (대시보드 폴링용)
    private volatile String statusText = "STOPPED";
    private volatile int scanCount = 0;
    private volatile int activePositions = 0;
    private volatile List<String> lastScannedMarkets = Collections.emptyList();
    private volatile long lastTickEpochMs = 0;

    // v2: Decision Log (대시보드에서 차단/실행 사유 확인용)
    private static final int MAX_DECISION_LOG = 200;
    private final Deque<ScannerDecision> decisionLog = new ArrayDeque<ScannerDecision>();

    public OpeningScannerService(OpeningScannerConfigRepository configRepo,
                                  BotConfigRepository botConfigRepo,
                                  PositionRepository positionRepo,
                                  TradeRepository tradeLogRepo,
                                  CandleService candleService,
                                  UpbitMarketCatalogService catalogService,
                                  LiveOrderService liveOrders,
                                  UpbitPrivateClient privateClient) {
        this.configRepo = configRepo;
        this.botConfigRepo = botConfigRepo;
        this.positionRepo = positionRepo;
        this.tradeLogRepo = tradeLogRepo;
        this.candleService = candleService;
        this.catalogService = catalogService;
        this.liveOrders = liveOrders;
        this.privateClient = privateClient;
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
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        return true;
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
                        cfg.getMinBodyRatio().doubleValue());

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

        // 거래대금 상위 N개 마켓 조회 (기존 보유 코인 + 제외 마켓 제외)
        List<String> topMarkets = getTopMarketsByVolume(cfg.getTopN(), ownedMarkets);
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

        // 스캐너 포지션 먼저 청산 체크 (보유 중인 스캐너 포지션)
        for (PositionEntity pe : allPositions) {
            if (!"SCALP_OPENING_BREAK".equals(pe.getEntryStrategy())) continue;
            if (pe.getQty() == null || pe.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;

            try {
                List<UpbitCandle> candles = candleService.getMinuteCandles(pe.getMarket(), candleUnit, 40, null);
                if (candles == null || candles.isEmpty()) continue;
                // 업비트 API는 최신→오래된 순 반환 → 전략은 오래된→최신 순 기대
                candles = new ArrayList<UpbitCandle>(candles);
                Collections.reverse(candles);

                StrategyContext ctx = new StrategyContext(pe.getMarket(), candleUnit, candles, pe, 0);
                Signal signal = strategy.evaluate(ctx);

                if (signal.action == SignalAction.SELL) {
                    executeSell(pe, candles.get(candles.size() - 1), signal, cfg);
                    addDecision(pe.getMarket(), "SELL", "EXECUTED", "SIGNAL",
                            signal.reason);
                }
            } catch (Exception e) {
                log.error("[OpeningScanner] exit check failed for {}", pe.getMarket(), e);
                addDecision(pe.getMarket(), "SELL", "ERROR", "EXIT_CHECK_FAIL",
                        "청산 체크 오류: " + e.getMessage());
            }
        }

        // 새 진입 체크
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
            int entryAttempts = 0;
            int entrySuccess = 0;
            for (String market : topMarkets) {
                // 이미 포지션 보유 중이면 스킵
                boolean alreadyHas = false;
                for (PositionEntity pe : allPositions) {
                    if (market.equals(pe.getMarket()) && pe.getQty() != null
                            && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                        alreadyHas = true;
                        break;
                    }
                }
                if (alreadyHas) continue;

                // 포지션 수 재확인
                if (scannerPosCount >= cfg.getMaxPositions()) break;

                try {
                    List<UpbitCandle> candles = candleService.getMinuteCandles(market, candleUnit, 40, null);
                    if (candles == null || candles.isEmpty()) continue;
                    // 업비트 API는 최신→오래된 순 반환 → 전략은 오래된→최신 순 기대
                    candles = new ArrayList<UpbitCandle>(candles);
                    Collections.reverse(candles);

                    StrategyContext ctx = new StrategyContext(market, candleUnit, candles, null, 0);
                    Signal signal = strategy.evaluate(ctx);

                    entryAttempts++;
                    if (signal.action == SignalAction.BUY) {
                        executeBuy(market, candles.get(candles.size() - 1), signal, cfg);
                        scannerPosCount++;
                        entrySuccess++;
                        addDecision(market, "BUY", "EXECUTED", "SIGNAL", signal.reason);
                    } else {
                        // v2: 시그널이 없는 마켓도 기록 (디버깅용, 최근 것만)
                        if (entryAttempts <= 3) {
                            addDecision(market, "BUY", "SKIPPED", "NO_SIGNAL",
                                    "전략 조건 미충족");
                        }
                    }
                } catch (Exception e) {
                    log.error("[OpeningScanner] entry check failed for {}", market, e);
                    addDecision(market, "BUY", "ERROR", "ENTRY_CHECK_FAIL",
                            "진입 체크 오류: " + e.getMessage());
                }
            }

            // v2: 틱 요약 로그
            log.info("[OpeningScanner] tick완료 mode={} markets={} attempts={} entries={} positions={}",
                    mode, topMarkets.size(), entryAttempts, entrySuccess, scannerPosCount);
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
                if (!r.isFilled()) {
                    log.warn("[OpeningScanner] LIVE buy pending/failed: market={} state={} vol={}",
                            market, r.state, r.executedVolume);
                    addDecision(market, "BUY", "ERROR", "ORDER_NOT_FILLED",
                            String.format("주문 미체결 state=%s vol=%.8f", r.state, r.executedVolume));
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
                qty = r.executedVolume;
                if (qty <= 0) {
                    log.warn("[OpeningScanner] LIVE buy executedVolume=0 for {}", market);
                    addDecision(market, "BUY", "ERROR", "ZERO_VOLUME",
                            "체결 수량 0");
                    return;
                }
            } catch (Exception e) {
                log.error("[OpeningScanner] LIVE buy order failed for {}", market, e);
                addDecision(market, "BUY", "ERROR", "ORDER_EXCEPTION",
                        "주문 실패: " + e.getMessage());
                return;
            }
        }

        // 포지션 생성
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setQty(qty);
        pe.setAvgPrice(fillPrice);
        pe.setAddBuys(0);
        pe.setOpenedAt(Instant.now());
        pe.setEntryStrategy("SCALP_OPENING_BREAK");
        positionRepo.save(pe);

        // 거래 로그
        TradeEntity tl = new TradeEntity();
        tl.setTsEpochMs(System.currentTimeMillis());
        tl.setMarket(market);
        tl.setAction("BUY");
        tl.setPrice(BigDecimal.valueOf(fillPrice));
        tl.setQty(BigDecimal.valueOf(qty));
        tl.setMode(cfg.getMode());
        tl.setPatternType("SCALP_OPENING_BREAK");
        tl.setPatternReason(signal.reason);
        tl.setConfidence(signal.confidence);
        tl.setCandleUnitMin(cfg.getCandleUnitMin());
        tradeLogRepo.save(tl);

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
                if (!r.isFilled()) {
                    log.warn("[OpeningScanner] LIVE sell pending/failed: market={} state={} vol={}",
                            pe.getMarket(), r.state, r.executedVolume);
                    addDecision(pe.getMarket(), "SELL", "ERROR", "ORDER_NOT_FILLED",
                            String.format("매도 미체결 state=%s vol=%.8f", r.state, r.executedVolume));
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
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

        // 거래 로그
        TradeEntity tl = new TradeEntity();
        tl.setTsEpochMs(System.currentTimeMillis());
        tl.setMarket(pe.getMarket());
        tl.setAction("SELL");
        tl.setPrice(BigDecimal.valueOf(fillPrice));
        tl.setQty(BigDecimal.valueOf(qty));
        tl.setPnlKrw(BigDecimal.valueOf(pnlKrw));
        tl.setRoiPercent(BigDecimal.valueOf(roiPct));
        tl.setMode(cfg.getMode());
        tl.setPatternType("SCALP_OPENING_BREAK");
        tl.setPatternReason(signal.reason);
        tl.setAvgBuyPrice(pe.getAvgPrice());
        tl.setConfidence(signal.confidence);
        tl.setCandleUnitMin(cfg.getCandleUnitMin());
        tradeLogRepo.save(tl);

        // 포지션 삭제
        positionRepo.deleteById(pe.getMarket());

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
     * 거래대금 상위 N개 KRW 마켓 조회 (ownedMarkets 제외).
     * 업비트 ticker API로 24시간 거래대금 조회.
     */
    private List<String> getTopMarketsByVolume(int topN, Set<String> excludeMarkets) {
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

            // 거래대금으로 정렬 (ticker API 사용)
            Map<String, Double> volumeMap = catalogService.get24hTradePrice(krwMarkets);
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
            // 업비트 API는 최신→오래된 순 반환 → EMA/close 계산에 오래된→최신 순 필요
            btcCandles = new ArrayList<UpbitCandle>(btcCandles);
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
}
