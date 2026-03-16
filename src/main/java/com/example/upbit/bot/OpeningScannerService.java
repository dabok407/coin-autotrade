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
 */
@Service
public class OpeningScannerService {

    private static final Logger log = LoggerFactory.getLogger(OpeningScannerService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final OpeningScannerConfigRepository configRepo;
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

    public OpeningScannerService(OpeningScannerConfigRepository configRepo,
                                  PositionRepository positionRepo,
                                  TradeRepository tradeLogRepo,
                                  CandleService candleService,
                                  UpbitMarketCatalogService catalogService,
                                  LiveOrderService liveOrders,
                                  UpbitPrivateClient privateClient) {
        this.configRepo = configRepo;
        this.positionRepo = positionRepo;
        this.tradeLogRepo = tradeLogRepo;
        this.candleService = candleService;
        this.catalogService = catalogService;
        this.liveOrders = liveOrders;
        this.privateClient = privateClient;
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

        // LIVE 모드: 업비트 실제 계좌 보유 코인 조회하여 제외
        if ("LIVE".equalsIgnoreCase(cfg.getMode()) && privateClient.isConfigured()) {
            try {
                List<UpbitAccount> accounts = privateClient.getAccounts();
                if (accounts != null) {
                    for (UpbitAccount a : accounts) {
                        if ("KRW".equals(a.currency)) continue; // 원화 제외
                        BigDecimal bal = a.balanceAsBigDecimal().add(a.lockedAsBigDecimal());
                        if (bal.compareTo(BigDecimal.ZERO) > 0) {
                            ownedMarkets.add("KRW-" + a.currency);
                        }
                    }
                }
                log.debug("[OpeningScanner] LIVE 보유코인 제외 목록: {}", ownedMarkets);
            } catch (Exception e) {
                log.warn("[OpeningScanner] 업비트 잔고 조회 실패, position table만 사용", e);
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
        }

        // 스캐너 포지션 먼저 청산 체크 (보유 중인 스캐너 포지션)
        for (PositionEntity pe : allPositions) {
            if (!"SCALP_OPENING_BREAK".equals(pe.getEntryStrategy())) continue;
            if (pe.getQty() == null || pe.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;

            try {
                List<UpbitCandle> candles = candleService.getMinuteCandles(pe.getMarket(), candleUnit, 40, null);
                if (candles == null || candles.isEmpty()) continue;

                StrategyContext ctx = new StrategyContext(pe.getMarket(), candleUnit, candles, pe, 0);
                Signal signal = strategy.evaluate(ctx);

                if (signal.action == SignalAction.SELL) {
                    executeSell(pe, candles.get(candles.size() - 1), signal, cfg);
                }
            } catch (Exception e) {
                log.error("[OpeningScanner] exit check failed for {}", pe.getMarket(), e);
            }
        }

        // 새 진입 체크
        boolean canEnter = btcAllowLong && scannerPosCount < cfg.getMaxPositions();

        if (canEnter) {
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

                    StrategyContext ctx = new StrategyContext(market, candleUnit, candles, null, 0);
                    Signal signal = strategy.evaluate(ctx);

                    if (signal.action == SignalAction.BUY) {
                        executeBuy(market, candles.get(candles.size() - 1), signal, cfg);
                        scannerPosCount++;
                    }
                } catch (Exception e) {
                    log.error("[OpeningScanner] entry check failed for {}", market, e);
                }
            }
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
                return;
            }
            LiveOrderService.OrderResult r = liveOrders.placeBidPriceOrder(market, orderKrw.doubleValue());
            if (!r.isFilled()) {
                log.warn("[OpeningScanner] LIVE buy pending/failed: market={} state={} vol={}",
                        market, r.state, r.executedVolume);
                return;
            }
            fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
            qty = r.executedVolume;
            if (qty <= 0) {
                log.warn("[OpeningScanner] LIVE buy executedVolume=0 for {}", market);
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
                return;
            }
            LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(pe.getMarket(), qty);
            if (!r.isFilled()) {
                log.warn("[OpeningScanner] LIVE sell pending/failed: market={} state={} vol={}",
                        pe.getMarket(), r.state, r.executedVolume);
                return;
            }
            fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
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
        // PCT mode
        BigDecimal pct = cfg.getOrderSizingValue();
        return cfg.getCapitalKrw().multiply(pct).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
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

            double ema = Indicators.ema(btcCandles, emaPeriod);
            double btcClose = btcCandles.get(btcCandles.size() - 1).trade_price;
            boolean allow = btcClose >= ema;
            if (!allow) {
                log.info("[OpeningScanner] BTC filter BLOCKED: close={} < EMA({})={}", btcClose, emaPeriod, ema);
            }
            return allow;
        } catch (Exception e) {
            log.error("[OpeningScanner] BTC filter check failed", e);
            return true; // 에러 시 허용
        }
    }
}
