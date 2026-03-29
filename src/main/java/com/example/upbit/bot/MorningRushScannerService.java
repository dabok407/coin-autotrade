package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.upbit.UpbitAccount;
import com.example.upbit.upbit.UpbitPrivateClient;

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
 * 1. 08:50 ~ 09:00 — 전일 08:00-09:00 레인지(고가) 수집 (Ticker API)
 * 2. 09:00 ~ 09:05 — 5초 간격 Ticker 폴링으로 갭업 확인
 *    - price > rangeHigh × (1 + gapThreshold%) 를 confirmCount 연속 통과 → BUY
 *    - 24시간 거래대금 > 기준 거래대금 × volumeMult
 * 3. 09:05 ~ 10:00 — 10초 간격 TP/SL 모니터링
 * 4. 10:00 — 강제 청산
 *
 * MVP: Ticker API 폴링 방식 (WebSocket은 향후 업그레이드)
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

    // ========== Runtime state ==========

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

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

    // Decision log
    private static final int MAX_DECISION_LOG = 200;
    private final Deque<Map<String, Object>> decisionLog = new ArrayDeque<Map<String, Object>>();

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
                                      TickerService tickerService) {
        this.configRepo = configRepo;
        this.botConfigRepo = botConfigRepo;
        this.positionRepo = positionRepo;
        this.tradeLogRepo = tradeLogRepo;
        this.liveOrders = liveOrders;
        this.privateClient = privateClient;
        this.txTemplate = txTemplate;
        this.catalogService = catalogService;
        this.tickerService = tickerService;
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

        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "morning-rush-scanner");
                t.setDaemon(true);
                return t;
            }
        });

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

    // ========== Main Loop ==========

    /**
     * 1초마다 실행되는 메인 루프. KST 시각에 따라 다른 동작 수행.
     * 실제 ticker 폴링은 checkIntervalSec 간격으로만 실행 (lastTickEpochMs로 스로틀링).
     */
    private void mainLoop() {
        if (!running.get()) return;

        MorningRushConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isEnabled()) {
            statusText = "DISABLED";
            return;
        }

        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        int hour = nowKst.getHour();
        int minute = nowKst.getMinute();
        int nowMinOfDay = hour * 60 + minute;

        int sessionEndMin = cfg.getSessionEndHour() * 60 + cfg.getSessionEndMin();

        // Phase timing (configurable session end, entry fixed 09:00-09:05)
        boolean isRangePhase = (nowMinOfDay >= 8 * 60 + 50) && (nowMinOfDay < 9 * 60);  // 08:50 - 09:00
        boolean isEntryPhase = (nowMinOfDay >= 9 * 60) && (nowMinOfDay < 9 * 60 + 5);    // 09:00 - 09:05
        boolean isHoldPhase = (nowMinOfDay >= 9 * 60 + 5) && (nowMinOfDay < sessionEndMin); // 09:05 - session end
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
            }
            statusText = "IDLE (outside hours)";
            return;
        }

        // Throttle: only poll at checkIntervalSec frequency
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
            statusText = "MONITORING";
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

        // Fetch tickers to get current prices and 24h trade amounts
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

        // If we're in the last minute of range phase, mark collected
        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        if (nowKst.getMinute() >= 59 || (nowKst.getHour() == 8 && nowKst.getMinute() >= 58)) {
            rangeCollected = true;
            addDecision("*", "RANGE", "COLLECTED",
                    String.format("레인지 수집 완료: %d개 마켓, rangeHigh 설정됨", selectedMarkets.size()));
            log.info("[MorningRush] range collection complete: {} markets", selectedMarkets.size());
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

        // Fetch current prices for all tracked markets
        List<String> trackedMarkets = new ArrayList<String>(rangeHighMap.keySet());
        Map<String, Double> currentPrices = tickerService.getTickerPrices(trackedMarkets);
        if (currentPrices.isEmpty()) {
            log.warn("[MorningRush] ticker price fetch returned empty");
            return;
        }

        double gapThreshold = cfg.getGapThresholdPct().doubleValue() / 100.0;
        double volumeMultiplier = cfg.getVolumeMult().doubleValue();
        int requiredConfirm = cfg.getConfirmCount();

        // Check each market for gap-up condition
        for (Map.Entry<String, Double> entry : currentPrices.entrySet()) {
            String market = entry.getKey();
            double price = entry.getValue();

            if (ownedMarkets.contains(market)) continue;
            if (rushPosCount >= cfg.getMaxPositions()) break;

            Double rangeHigh = rangeHighMap.get(market);
            if (rangeHigh == null || rangeHigh <= 0) continue;

            double threshold = rangeHigh * (1.0 + gapThreshold);
            boolean priceBreak = price > threshold;

            // Volume check: we use baseline 24h volume collected during range phase
            // For simplicity, we check if current 24h trade amount exceeds baseline * volumeMult
            // Note: in practice, the 24h volume doesn't change dramatically in 5 minutes,
            // so this serves as a minimum liquidity filter
            Double baseVol = baselineVolume.get(market);
            boolean volumeOk = (baseVol != null && baseVol >= cfg.getMinTradeAmount());

            if (priceBreak && volumeOk) {
                Integer count = confirmCounts.get(market);
                int newCount = (count != null ? count : 0) + 1;
                confirmCounts.put(market, newCount);

                if (newCount >= requiredConfirm) {
                    // Confirmed gap-up! Execute BUY
                    double gapPct = (price - rangeHigh) / rangeHigh * 100.0;
                    String reason = String.format(Locale.ROOT,
                            "GAP_UP price=%.2f rangeHigh=%.2f gap=+%.2f%% confirm=%d/%d",
                            price, rangeHigh, gapPct, newCount, requiredConfirm);

                    log.info("[MorningRush] BUY signal confirmed: {} | {}", market, reason);

                    try {
                        executeBuy(market, price, reason, cfg, orderKrw, isLive);
                        rushPosCount++;
                        addDecision(market, "BUY", "EXECUTED", reason);
                    } catch (Exception e) {
                        log.error("[MorningRush] buy execution failed for {}", market, e);
                        addDecision(market, "BUY", "ERROR", "매수 실행 오류: " + e.getMessage());
                    }

                    // Remove from tracking to prevent duplicate entries
                    confirmCounts.remove(market);
                    ownedMarkets.add(market);
                } else {
                    log.debug("[MorningRush] {} gap confirm {}/{}", market, newCount, requiredConfirm);
                }
            } else {
                // Reset confirm count if condition not met
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

        Map<String, Double> prices = tickerService.getTickerPrices(markets);
        if (prices.isEmpty()) return;

        double tpPct = cfg.getTpPct().doubleValue();
        double slPct = cfg.getSlPct().doubleValue();

        for (PositionEntity pe : rushPositions) {
            Double currentPrice = prices.get(pe.getMarket());
            if (currentPrice == null || currentPrice <= 0) continue;

            double avgPrice = pe.getAvgPrice().doubleValue();
            if (avgPrice <= 0) continue;

            double pnlPct = (currentPrice - avgPrice) / avgPrice * 100.0;

            String sellReason = null;
            String sellType = null;

            if (pnlPct >= tpPct) {
                sellReason = String.format(Locale.ROOT,
                        "TP pnl=%.2f%% >= target=%.2f%% price=%.2f avg=%.2f",
                        pnlPct, tpPct, currentPrice, avgPrice);
                sellType = "TP";
            } else if (pnlPct <= -slPct) {
                sellReason = String.format(Locale.ROOT,
                        "SL pnl=%.2f%% <= -%.2f%% price=%.2f avg=%.2f",
                        pnlPct, slPct, currentPrice, avgPrice);
                sellType = "SL";
            }

            if (sellReason == null) continue;

            log.info("[MorningRush] {} triggered | {} | {}", sellType, pe.getMarket(), sellReason);

            // Re-check position (race condition protection)
            PositionEntity fresh = positionRepo.findById(pe.getMarket()).orElse(null);
            if (fresh == null || fresh.getQty() == null || fresh.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;

            try {
                executeSell(fresh, currentPrice, pnlPct, sellReason, sellType, cfg);
            } catch (Exception e) {
                log.error("[MorningRush] sell failed for {}", pe.getMarket(), e);
                addDecision(pe.getMarket(), "SELL", "ERROR", "매도 실행 오류: " + e.getMessage());
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

        Map<String, Double> prices = tickerService.getTickerPrices(markets);

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

            try {
                executeSell(pe, currentPrice, pnlPct, reason, "SESSION_END", cfg);
            } catch (Exception e) {
                log.error("[MorningRush] session-end sell failed for {}", pe.getMarket(), e);
                addDecision(pe.getMarket(), "SELL", "ERROR", "세션종료 매도 오류: " + e.getMessage());
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
    }
}
