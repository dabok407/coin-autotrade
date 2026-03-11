package com.example.upbit.bot;

import com.example.upbit.config.BotProperties;
import com.example.upbit.config.StrategyProperties;
import com.example.upbit.config.TradeProperties;
import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.strategy.*;
import com.example.upbit.upbit.UpbitAccount;
import com.example.upbit.upbit.UpbitPrivateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 자동매매 핵심 서비스.
 *
 * 정책:
 * - UI에서 start() 해야 tick이 돌며 자동매매 실행
 * - 설정(mode/분봉/코인/투입금)은 DB 저장 -> 다음 tick부터 즉시 반영
 * - PAPER 모드: 가상 체결 (슬리피지+수수료 반영)
 * - LIVE 모드: 업비트 주문 생성 + /v1/order 폴링으로 체결(done) 확정 후 포지션/손익 반영
 *
 * 중요 안전장치:
 * - LIVE 모드에서 "pending 주문"이 있으면 해당 마켓의 추가 주문/청산을 막습니다(중복주문 방지).
 * - 재시작 시 wait 상태 주문을 1회 동기화(syncPendingOrders).
 *
 * 주의:
 * - 본 프로젝트는 학습/테스트 목적이며, 실매매 사용 시 반드시 소액 + 모니터링 권장.
 */
@Service
public class TradingBotService {

    private static final Logger log = LoggerFactory.getLogger(TradingBotService.class);

    private static double bd(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }

    private final CandleService candleService;
    private final BotProperties botProps;
    private final StrategyProperties cfg;
    private final TradeProperties tradeProps;

    private final BotConfigRepository botConfigRepo;
    private final MarketConfigRepository marketRepo;
    private final StrategyStateRepository stateRepo;
    private final PositionRepository positionRepo;
    private final TradeRepository tradeRepo;

    private final LiveOrderService liveOrders;
    private final UpbitPrivateClient privateClient;

    private final StrategyFactory strategyFactory;
    private final TickerService tickerService;
    private final StrategyGroupRepository strategyGroupRepo;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** LIVE 모드 API 키 검증 완료 플래그 (첫 tick에서 1회 검증) */
    private volatile boolean liveKeyVerified = false;

    /** 현재 tick에서 사용 중인 분봉 단위 — persist()가 trade_log에 기록할 때 사용 */
    private volatile int currentTickUnitMin = 5;

    // LIVE 잔고 조회는 레이트를 아끼기 위해 짧은 TTL 캐시를 둡니다.
    private final Object krwCacheLock = new Object();
    private volatile long krwCacheAtMs = 0L;
    private volatile double krwCacheAvailable = 0.0;
    private volatile double krwCacheLocked = 0.0;
    private volatile long krwCacheTtlMs = 1500L;

    // Recent decision/guard logs for dashboard (ring buffer)
    private final Deque<DecisionLog> decisionLogs = new ArrayDeque<DecisionLog>();
    private final Object decisionLogLock = new Object();
    private volatile int decisionLogMaxSize = 200;

    public static class DecisionLog {
        public long tsEpochMs;
        public String market;
        public Integer candleUnitMin;
        public String signalAction;     // BUY/ADD_BUY/SELL/SIGNAL_ONLY/etc
        public String result;          // EXECUTED/BLOCKED/INFO/SKIPPED
        public String reasonCode;      // e.g., CATCH_UP_BLOCK, STALE_ENTRY_BLOCK
        public String reasonKo;        // Korean explanation (human-friendly)
        public Map<String, Object> details = new LinkedHashMap<String, Object>();
    }

    public List<DecisionLog> getRecentDecisionLogs(int limit) {
        int lim = Math.max(1, Math.min(500, limit));
        synchronized (decisionLogLock) {
            List<DecisionLog> out = new ArrayList<DecisionLog>(Math.min(lim, decisionLogs.size()));
            Iterator<DecisionLog> it = decisionLogs.descendingIterator(); // newest-first
            while (it.hasNext() && out.size() < lim) out.add(it.next());
            return out;
        }
    }

    private void addDecisionLog(String market, Integer unit, String signalAction, String result,
                                String reasonCode, String reasonKo, Map<String, Object> details) {
        DecisionLog d = new DecisionLog();
        d.tsEpochMs = System.currentTimeMillis();
        d.market = market;
        d.candleUnitMin = unit;
        d.signalAction = signalAction;
        d.result = result;
        d.reasonCode = reasonCode;
        d.reasonKo = reasonKo;
        if (details != null) d.details.putAll(details);

        synchronized (decisionLogLock) {
            decisionLogs.addLast(d);
            while (decisionLogs.size() > decisionLogMaxSize) decisionLogs.removeFirst();
        }
    }



// === Candle-boundary scheduler (new) ===
// Runs tick aligned to candle close time instead of frequent polling.
private final java.util.concurrent.ScheduledExecutorService boundaryExec =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "bot-boundary-scheduler");
                t.setDaemon(true);
                return t;
            }
        });

// === Real-time TP/SL ticker monitor (runs independently of candle boundary) ===
private final java.util.concurrent.ScheduledExecutorService tpSlExec =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "bot-tpsl-ticker");
                t.setDaemon(true);
                return t;
            }
        });
private volatile java.util.concurrent.ScheduledFuture<?> tpSlFuture;

private volatile boolean boundarySchedulerEnabled = true; // keep true by default
private volatile int boundaryBufferSeconds = 2; // run at candleClose + buffer (seconds)
private volatile int boundaryMaxRetry = 5;       // retry count if new candle not visible yet
private volatile int boundaryRetrySleepMs = 1000;// retry sleep
private volatile int boundaryCatchUpMaxCandles = 3; // if server paused, process up to N missed candles (best-effort)
private volatile int staleEntryTtlSeconds = 60; // 정상 tick 보호: 봉 마감 후 60초 이상 지연 시 매수 차단
private volatile boolean sellOnlyTick = false;   // Start 즉시 tick: 매도만 체크 (매수 스킵)
    private volatile long startedAt = 0L;

    /** 시장별 런타임 상태(다운연속, 마지막 캔들 등). 포지션 자체는 DB(Position) 기준으로 가져옵니다. */
    private final Map<String, MarketState> states = new ConcurrentHashMap<String, MarketState>();

    public TradingBotService(CandleService candleService,
                             BotProperties botProps,
                             StrategyProperties cfg,
                             TradeProperties tradeProps,
                             BotConfigRepository botConfigRepo,
                             MarketConfigRepository marketRepo,
                             StrategyStateRepository stateRepo,
                             PositionRepository positionRepo,
                             TradeRepository tradeRepo,
                             LiveOrderService liveOrders,
                             UpbitPrivateClient privateClient,
                             StrategyFactory strategyFactory,
                             TickerService tickerService,
                             StrategyGroupRepository strategyGroupRepo) {
        this.candleService = candleService;
        this.botProps = botProps;
        this.cfg = cfg;
        this.tradeProps = tradeProps;
        this.botConfigRepo = botConfigRepo;
        this.marketRepo = marketRepo;
        this.stateRepo = stateRepo;
        this.positionRepo = positionRepo;
        this.tradeRepo = tradeRepo;
        this.liveOrders = liveOrders;
        this.privateClient = privateClient;
        this.strategyFactory = strategyFactory;
        this.tickerService = tickerService;
        this.strategyGroupRepo = strategyGroupRepo;

        refreshMarketStates();
    }

    private static BigDecimal toBd(String s) {
        if (s == null || s.trim().isEmpty()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /**
     * LIVE에서만 의미 있는 KRW 잔고(available/locked)를 가져옵니다.
     * - 네트워크/레이트 이슈를 줄이기 위해 1~2초 TTL 캐시
     */
    private double getLiveAvailableKrwSafe() {
        if (privateClient == null) return 0.0;
        long now = System.currentTimeMillis();
        if (now - krwCacheAtMs < krwCacheTtlMs) return krwCacheAvailable;
        synchronized (krwCacheLock) {
            now = System.currentTimeMillis();
            if (now - krwCacheAtMs < krwCacheTtlMs) return krwCacheAvailable;
            try {
                List<UpbitAccount> accounts = privateClient.getAccounts();
                BigDecimal avail = BigDecimal.ZERO;
                BigDecimal locked = BigDecimal.ZERO;
                if (accounts != null) {
                    for (UpbitAccount a : accounts) {
                        if (a == null || a.currency == null) continue;
                        if (!"KRW".equalsIgnoreCase(a.currency)) continue;
                        avail = avail.add(toBd(a.balance));
                        locked = locked.add(toBd(a.locked));
                    }
                }
                krwCacheAvailable = avail.doubleValue();
                krwCacheLocked = locked.doubleValue();
                krwCacheAtMs = System.currentTimeMillis();
            } catch (Exception e) {
                // 실패 시: 이전 캐시 값 유지(0일 수도 있음)
                krwCacheAtMs = System.currentTimeMillis();
            }
            return krwCacheAvailable;
        }
    }

    @PostConstruct
    public void init() {
        // 재시작 시 pending 주문 상태를 한 번 동기화하여 UI/전략이 오작동하는 확률을 낮춥니다.
        try {
            if (liveOrders.isConfigured()) {
                liveOrders.syncPendingOrders();
            }
        } catch (Exception ignore) {}


// Apply runtime knobs from bot.* properties (so you can tune without code changes)
try {
    this.boundarySchedulerEnabled = botProps.isBoundarySchedulerEnabled();
    this.boundaryBufferSeconds = botProps.getBoundaryBufferSeconds();
    this.boundaryMaxRetry = botProps.getBoundaryMaxRetry();
    this.boundaryRetrySleepMs = botProps.getBoundaryRetrySleepMs();
    this.boundaryCatchUpMaxCandles = botProps.getCatchUpMaxCandles();
    this.staleEntryTtlSeconds = botProps.getStaleEntryTtlSeconds();
} catch (Exception ignore) {}

// Load per-market persistent runtime state (last candle, downStreak, etc.)
try {
    refreshMarketStates();
    for (String m : states.keySet()) {
        loadStateFromDb(m, states.get(m));
    }
} catch (Exception e) {
    e.printStackTrace();
}

// New: candle-boundary scheduler (self-healing). Leave legacy polling method available for rollback.
if (boundarySchedulerEnabled) {
    startBoundaryScheduler();
}    }

    public synchronized void refreshMarketStates() {
        List<MarketConfigEntity> all = marketRepo.findAllByOrderByMarketAsc();
        for (MarketConfigEntity mc : all) {
            if (!states.containsKey(mc.getMarket())) {
                states.put(mc.getMarket(), new MarketState(mc.getMarket()));
            }
        }
        Set<String> existing = new HashSet<String>();
        for (MarketConfigEntity mc : all) existing.add(mc.getMarket());
        Iterator<String> it = states.keySet().iterator();
        while (it.hasNext()) {
            String m = it.next();
            if (!existing.contains(m)) it.remove();
        }
    }

    public boolean start() {
        boolean changed = running.compareAndSet(false, true);
        if (changed) {
            this.startedAt = System.currentTimeMillis();
            log.info("[BOT] 자동매매 시작");

            // LIVE 모드: 시작 즉시 업비트 보유 자산 ↔ DB 포지션 동기화
            // (첫 캔들 사이클을 기다리지 않고, 이미 보유 중인 코인을 즉시 복구)
            try {
                BotConfigEntity bc = getBotConfig();
                if ("LIVE".equals(bc.getMode()) && liveOrders.isConfigured()) {
                    log.info("[BOT] LIVE 모드 시작 — 즉시 포지션 동기화 실행");
                    List<MarketConfigEntity> enabled = marketRepo.findByEnabledTrueOrderByMarketAsc();
                    if (!enabled.isEmpty()) {
                        syncPositionsFromUpbit(enabled);
                        liveKeyVerified = true;
                    }
                }
            } catch (Exception e) {
                log.warn("[BOT] 시작 시 포지션 동기화 실패 (첫 사이클에서 재시도됩니다): {}", e.getMessage());
            }

            // 즉시 첫 tick 실행: 매도 전략만 체크 (매수는 다음 정상 캔들 경계에서)
            // 이유: Start 시점은 캔들 경계와 무관하므로, 늦은 매수(추격)보다
            //       보유 포지션 보호(청산)가 우선. 매수는 깨끗한 데이터로 판단.
            // 주의: sellOnlyTick은 executor 내부에서 설정 (단일 스레드 → 경합 없음)
            boundaryExec.schedule(new Runnable() {
                @Override public void run() {
                    sellOnlyTick = true;
                    try {
                        log.info("[BOT] Start 즉시 — 매도 전략만 체크 (TP/SL/TIME_STOP/전략SELL)");
                        tickInternal(true);
                    } catch (Exception e) {
                        log.error("[BOT] 첫 tick 실행 실패", e);
                    } finally {
                        sellOnlyTick = false;
                    }
                }
            }, 0, java.util.concurrent.TimeUnit.MILLISECONDS);

            // 실시간 TP/SL 티커 모니터링 시작
             startTpSlTicker();
        }
        return changed;
    }

    public boolean stop() {
        boolean changed = running.compareAndSet(true, false);
        if (changed) {
            liveKeyVerified = false;
            sellOnlyTick = false;
            stopTpSlTicker();
            log.info("[BOT] 자동매매 중지");
        }
        return changed;
    }

// ===== 실시간 TP/SL 티커 모니터링 =====
// 캔들 경계와 무관하게 15초마다 오픈 포지션의 현재가를 조회하여 TP/SL 체크.
// 4시간봉 사용 시에도 급락/급등에 빠르게 반응 가능.

private void startTpSlTicker() {
    if (!botProps.isTpSlTickerEnabled()) {
        log.info("[TP/SL TICKER] 비활성화 상태 (bot.tpSlTickerEnabled=false)");
        return;
    }
    int intervalSec = botProps.getTpSlPollIntervalSeconds();
    log.info("[TP/SL TICKER] 시작 — {}초 간격으로 현재가 TP/SL 모니터링", intervalSec);
    tpSlFuture = tpSlExec.scheduleAtFixedRate(new Runnable() {
        @Override public void run() {
            try {
                tickTpSlFromTicker();
            } catch (Exception e) {
                log.debug("[TP/SL TICKER] 예외: {}", e.getMessage());
            }
        }
    }, intervalSec, intervalSec, java.util.concurrent.TimeUnit.SECONDS);
}

private void stopTpSlTicker() {
    if (tpSlFuture != null) {
        tpSlFuture.cancel(false);
        tpSlFuture = null;
        log.info("[TP/SL TICKER] 중지");
    }
}

private void tickTpSlFromTicker() {
    if (!running.get()) return;

    BotConfigEntity bc = getBotConfig();
    double tpPctVal = bd(bc.getTakeProfitPct());
    double slPctVal = bd(bc.getStopLossPct());
    double trailingStopPctVal = bd(bc.getTrailingStopPct());
    String mode = bc.getMode() == null ? "PAPER" : bc.getMode().toUpperCase();

    // 오픈 포지션이 있는 마켓만 수집
    List<String> openMarkets = new ArrayList<String>();
    Map<String, PositionEntity> openPositions = new LinkedHashMap<String, PositionEntity>();
    for (MarketConfigEntity mc : marketRepo.findByEnabledTrueOrderByMarketAsc()) {
        PositionEntity pe = positionRepo.findById(mc.getMarket()).orElse(null);
        if (pe != null && bd(pe.getQty()) > 0) {
            openMarkets.add(mc.getMarket());
            openPositions.put(mc.getMarket(), pe);
        }
    }
    if (openMarkets.isEmpty()) return;

    // 배치 티커 조회
    Map<String, Double> prices = tickerService.getTickerPrices(openMarkets);
    if (prices.isEmpty()) return;

    for (Map.Entry<String, PositionEntity> entry : openPositions.entrySet()) {
        String market = entry.getKey();
        PositionEntity pe = entry.getValue();
        Double tickerPrice = prices.get(market);
        if (tickerPrice == null || tickerPrice <= 0) continue;

        // MarketState에 lastPrice 업데이트
        MarketState st = states.get(market);
        if (st != null) st.lastPrice = tickerPrice;

        double avgPrice = bd(pe.getAvgPrice());
        if (avgPrice <= 0) continue;

        // LIVE 모드: pending 주문이 있으면 스킵 (중복 매도 방지)
        if ("LIVE".equals(mode) && liveOrders.isConfigured() && liveOrders.hasPendingOrder(market)) {
            continue;
        }

        // 그룹별 TP/SL 해석: 해당 마켓이 속한 그룹의 TP/SL 사용
        double effTpPct = tpPctVal;
        double effSlPct = slPctVal;
        List<StrategyGroupEntity> groups = strategyGroupRepo.findAllByOrderBySortOrderAsc();
        if (groups != null && !groups.isEmpty()) {
            for (StrategyGroupEntity g : groups) {
                if (g.getMarketsList().contains(market)) {
                    effTpPct = bd(g.getTakeProfitPct());
                    effSlPct = bd(g.getStopLossPct());
                    break;
                }
            }
        }

        SignalEvaluator.Result tpSlResult = SignalEvaluator.checkTpSl(true, avgPrice, tickerPrice, effTpPct, effSlPct);

        // 글로벌 트레일링 스탑 체크 (티커 기반 실시간)
        if (tpSlResult == null && trailingStopPctVal > 0 && st != null) {
            // peakHigh 실시간 갱신
            if (tickerPrice > st.peakHighSinceEntry) {
                st.peakHighSinceEntry = tickerPrice;
            }
            double peakHigh = st.peakHighSinceEntry;
            // 수익 구간에서만 트레일링 스탑 활성화 (손실 구간에서는 하드 SL 사용)
            if (peakHigh > avgPrice && tickerPrice > avgPrice) {
                double trailStop = peakHigh * (1.0 - trailingStopPctVal / 100.0);
                if (tickerPrice <= trailStop) {
                    double pnlPct = ((tickerPrice - avgPrice) / avgPrice) * 100.0;
                    String reason = String.format(java.util.Locale.ROOT,
                            "TRAILING_STOP peak=%.2f trail=%.2f pnl=%.2f%% [ticker]",
                            peakHigh, trailStop, pnlPct);
                    Signal sig = Signal.of(com.example.upbit.strategy.SignalAction.SELL, null, reason);
                    tpSlResult = new SignalEvaluator.Result(sig, null, "TRAILING_STOP", reason, true);
                }
            }
        }

        if (tpSlResult != null) {
            // 포지션 재확인 (다른 스레드에서 이미 청산했을 수 있음)
            PositionEntity recheck = positionRepo.findById(market).orElse(null);
            if (recheck == null || bd(recheck.getQty()) <= 0) continue;

            String tpSlType = tpSlResult.patternType;
            String tpSlReason = tpSlResult.reason + " [ticker]";
            double qty = bd(recheck.getQty());

            log.info("[TP/SL TICKER] {} 발동 | {} | 평단:{} → 현재:{} | {}",
                    tpSlType, market,
                    String.format("%.2f", avgPrice), String.format("%.2f", tickerPrice), tpSlReason);

            if ("PAPER".equals(mode)) {
                double fill = tickerPrice * (1.0 - tradeProps.getSlippageRate());
                double gross = qty * fill;
                double fee = gross * cfg.getFeeRate();
                double realized = (gross - fee) - (qty * avgPrice);
                persist(mode, market, "SELL", fill, qty, realized, 0.0, tpSlReason, tpSlType, tpSlReason, avgPrice);
                positionRepo.deleteById(market);
                if (st != null) { st.downStreak = 0; st.peakHighSinceEntry = 0.0; }
            } else {
                if (!liveOrders.isConfigured()) continue;
                try {
                    LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(market, qty);
                    if (!r.isFilled()) {
                        persist(mode, market, "SELL_PENDING", tickerPrice, qty, 0.0, 0.0,
                                "state=" + r.state + " vol=" + r.executedVolume + " [ticker]", tpSlType, tpSlReason);
                        continue;
                    }
                    double fill = r.avgPrice > 0 ? r.avgPrice : tickerPrice;
                    double gross = qty * fill;
                    double fee = gross * cfg.getFeeRate();
                    double realized = (gross - fee) - (qty * avgPrice);
                    persist(mode, market, "SELL", fill, qty, realized, 0.0,
                            tpSlReason + " uuid=" + r.uuid, tpSlType, tpSlReason, avgPrice);
                    positionRepo.deleteById(market);
                    if (st != null) { st.downStreak = 0; st.peakHighSinceEntry = 0.0; }
                } catch (Exception e) {
                    log.error("[TP/SL TICKER] LIVE 매도 실패 market={}: {}", market, e.getMessage());
                }
            }
        }
    }
}

private java.util.List<StrategyType> parseActiveStrategyTypes(BotConfigEntity bc) {
    java.util.List<StrategyType> out = new java.util.ArrayList<StrategyType>();
    if (bc == null) {
        out.add(StrategyType.CONSECUTIVE_DOWN_REBOUND);
        return out;
    }
    String csv = bc.getStrategyTypesCsv();
    if (csv != null && !csv.trim().isEmpty()) {
        String[] parts = csv.split(",");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i] == null ? "" : parts[i].trim();
            if (p.isEmpty()) continue;
            try { out.add(StrategyType.valueOf(p)); } catch (Exception ignore) {}
        }
    }
    if (out.isEmpty()) {
        try { out.add(StrategyType.valueOf(bc.getStrategyType())); } catch (Exception ignore) {}
    }
    if (out.isEmpty()) out.add(StrategyType.CONSECUTIVE_DOWN_REBOUND);
    return out;
}

    public BotStatus getStatus() {
        BotConfigEntity bc = getBotConfig();
        List<MarketConfigEntity> markets = marketRepo.findAllByOrderByMarketAsc();

        BotStatus s = new BotStatus();
        s.setRunning(running.get());
        s.setStartedAtEpochMillis(startedAt);

        s.setMode(bc.getMode());
        s.setCandleUnitMin(bc.getCandleUnitMin());
        s.setCapitalKrw(bd(bc.getCapitalKrw()));
        s.setTakeProfitPct(bd(bc.getTakeProfitPct()));
        s.setStopLossPct(bd(bc.getStopLossPct()));
        s.setStrategyLock(bc.isStrategyLock());
        s.setMinConfidence(bc.getMinConfidence());
        s.setTimeStopMinutes(bc.getTimeStopMinutes());
        s.setStrategyIntervalsCsv(bc.getStrategyIntervalsCsv());
        s.setEmaFilterCsv(bc.getEmaFilterCsv());
        s.setMaxAddBuysGlobal(bc.getMaxAddBuysGlobal());
        s.setOrderSizingMode(bc.getOrderSizingMode());
        s.setOrderSizingValue(bd(bc.getOrderSizingValue()));
        double baseOrderKrw = resolveBaseOrderKrw(bc);
        s.setBaseOrderKrw(baseOrderKrw);
        s.setStrategyType(bc.getStrategyType());
        java.util.List<StrategyType> active = parseActiveStrategyTypes(bc);
        java.util.List<String> activeNames = new java.util.ArrayList<String>();
        for (StrategyType t : active) activeNames.add(t.name());
        s.setStrategies(activeNames);

        double realized = calcRealizedPnl(bc.getMode());
        double unrealized = calcUnrealized();
        s.setRealizedPnlKrw(realized);
        s.setUnrealizedPnlKrw(unrealized);
        s.setTotalPnlKrw(realized + unrealized);

        if (bd(bc.getCapitalKrw()) > 0) s.setRoiPercent((s.getTotalPnlKrw() / bd(bc.getCapitalKrw())) * 100.0);
        else s.setRoiPercent(0.0);

        s.setSellCountToday(countSellsFrom(LocalDate.now()));
        s.setSellCountWeek(countSellsFrom(startOfWeek(LocalDate.now())));
        s.setSellCountMonth(countSellsFrom(LocalDate.now().with(TemporalAdjusters.firstDayOfMonth())));

        Map<String, BotStatus.MarketStatus> ms = new LinkedHashMap<String, BotStatus.MarketStatus>();
        for (MarketConfigEntity mc : markets) {
            MarketState st = states.get(mc.getMarket());
            if (st == null) st = new MarketState(mc.getMarket());

            PositionEntity pe = positionRepo.findById(mc.getMarket()).orElse(null);
            boolean open = pe != null && bd(pe.getQty()) > 0;

            BotStatus.MarketStatus m = new BotStatus.MarketStatus();
            m.setMarket(mc.getMarket());
            m.setEnabled(mc.isEnabled());
            // Show computed base order (global sizing) in UI
            m.setBaseOrderKrw(baseOrderKrw);

            m.setPositionOpen(open);
            m.setAvgPrice(open ? bd(pe.getAvgPrice()) : 0.0);
            m.setQty(open ? bd(pe.getQty()) : 0.0);
            m.setDownStreak(st.downStreak);
            m.setAddBuys(open ? pe.getAddBuys() : 0);
            m.setEntryStrategy(open ? pe.getEntryStrategy() : null);
            m.setLastPrice(st.lastPrice);
            m.setRealizedPnlKrw(calcMarketRealizedPnl(mc.getMarket(), bc.getMode()));

            ms.put(mc.getMarket(), m);
        }
        s.setMarkets(ms);

        // Strategy Groups 로드
        List<StrategyGroupEntity> groupEntities = strategyGroupRepo.findAllByOrderBySortOrderAsc();
        if (groupEntities != null && !groupEntities.isEmpty()) {
            List<BotStatus.StrategyGroupInfo> groupInfos = new ArrayList<BotStatus.StrategyGroupInfo>();
            for (StrategyGroupEntity ge : groupEntities) {
                BotStatus.StrategyGroupInfo gi = new BotStatus.StrategyGroupInfo();
                gi.setId(ge.getId());
                gi.setGroupName(ge.getGroupName());
                gi.setSortOrder(ge.getSortOrder());
                gi.setMarkets(ge.getMarketsList());
                gi.setStrategies(ge.getStrategyTypesList());
                gi.setCandleUnitMin(ge.getCandleUnitMin());
                gi.setOrderSizingMode(ge.getOrderSizingMode());
                gi.setOrderSizingValue(bd(ge.getOrderSizingValue()));
                gi.setTakeProfitPct(bd(ge.getTakeProfitPct()));
                gi.setStopLossPct(bd(ge.getStopLossPct()));
                gi.setMaxAddBuys(ge.getMaxAddBuys());
                gi.setStrategyLock(Boolean.TRUE.equals(ge.getStrategyLock()));
                gi.setMinConfidence(ge.getMinConfidence());
                gi.setTimeStopMinutes(ge.getTimeStopMinutes());
                gi.setStrategyIntervalsCsv(ge.getStrategyIntervalsCsv());
                gi.setEmaFilterCsv(ge.getEmaFilterCsv());
                groupInfos.add(gi);
            }
            s.setGroups(groupInfos);
        }

        // wins/totalTrades는 "SELL" 기준으로 집계 (익절/손절 등 청산 이벤트)
        List<TradeEntity> sells = tradeRepo.findTop200ByOrderByTsEpochMsDesc();
        int sellCount = 0;
        int winCount = 0;
        for (TradeEntity t : sells) {
            if (!"SELL".equals(t.getAction())) continue;
            sellCount++;
            if (bd(t.getPnlKrw()) > 0) winCount++;
        }
        s.setTotalTrades((int) tradeRepo.count());
        s.setWins(winCount);
        s.setWinRate(sellCount == 0 ? 0.0 : (winCount * 100.0 / sellCount));
        return s;
    }

    public List<TradeEntity> recentTrades() {
        return tradeRepo.findTop200ByOrderByTsEpochMsDesc();
    }

    /** UI에서 현재 코인 목록/ON/OFF/투입금 등을 조회할 때 사용 */
    public List<MarketConfigEntity> getMarketConfigs() {
        return marketRepo.findAllByOrderByMarketAsc();
    }

    public BotConfigEntity updateBotConfig(String mode,
                                          Integer candleUnitMin,
                                          Double capitalKrw,
                                          String strategyType,
                                          java.util.List<String> strategies,
                                          String orderSizingMode,
                                          Double orderSizingValue,
                                          Integer maxAddBuysGlobal,
                                          Double takeProfitPct,
                                          Double stopLossPct,
                                          Boolean strategyLock,
                                          Double minConfidence,
                                          Integer timeStopMinutes,
                                          String strategyIntervalsCsv,
                                          String emaFilterCsv) {
        BotConfigEntity bc = getBotConfig();
        if (mode != null && !mode.isEmpty()) bc.setMode(mode);
        if (candleUnitMin != null) bc.setCandleUnitMin(candleUnitMin.intValue());
        if (capitalKrw != null) bc.setCapitalKrw(BigDecimal.valueOf(capitalKrw.doubleValue()));
        if (strategyType != null && !strategyType.isEmpty()) bc.setStrategyType(strategyType);
        if (strategies != null && !strategies.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < strategies.size(); i++) {
                String v = strategies.get(i);
                if (v == null || v.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(",");
                sb.append(v.trim());
            }
            bc.setStrategyTypesCsv(sb.toString());
        }
        // order sizing (global). Keep existing if not provided.
        if (orderSizingMode != null && !orderSizingMode.trim().isEmpty()) {
            bc.setOrderSizingMode(orderSizingMode);
        }
        if (orderSizingValue != null && orderSizingValue.doubleValue() > 0) {
            bc.setOrderSizingValue(BigDecimal.valueOf(orderSizingValue.doubleValue()));
        }

        if (maxAddBuysGlobal != null) {
            bc.setMaxAddBuysGlobal(maxAddBuysGlobal.intValue());
        }
        if (takeProfitPct != null) {
            bc.setTakeProfitPct(BigDecimal.valueOf(takeProfitPct.doubleValue()));
        }
        if (stopLossPct != null) {
            bc.setStopLossPct(BigDecimal.valueOf(stopLossPct.doubleValue()));
        }
        if (strategyLock != null) {
            bc.setStrategyLock(strategyLock.booleanValue());
        }
        if (minConfidence != null) {
            bc.setMinConfidence(minConfidence.doubleValue());
        }
        if (timeStopMinutes != null) {
            bc.setTimeStopMinutes(timeStopMinutes.intValue());
        }
        if (strategyIntervalsCsv != null) {
            bc.setStrategyIntervalsCsv(strategyIntervalsCsv);
        }
        if (emaFilterCsv != null) {
            bc.setEmaFilterCsv(emaFilterCsv);
        }
        return botConfigRepo.save(bc);
    }

    public void updateMarkets(List<MarketConfigEntity> incoming) {
        for (MarketConfigEntity m : incoming) {
            if (m.getMarket() == null || m.getMarket().trim().isEmpty()) continue;
            MarketConfigEntity mc = marketRepo.findByMarket(m.getMarket()).orElseGet(MarketConfigEntity::new);
            mc.setMarket(m.getMarket());
            mc.setEnabled(m.isEnabled());
            // baseOrderKrw is BigDecimal in JPA
            if (m.getBaseOrderKrw() != null && bd(m.getBaseOrderKrw()) > 0) mc.setBaseOrderKrw(m.getBaseOrderKrw());
            else mc.setBaseOrderKrw(BigDecimal.valueOf(tradeProps.getGlobalBaseOrderKrw()));
            marketRepo.save(mc);
        }
        refreshMarketStates();
    }

    private BotConfigEntity getBotConfig() {
        List<BotConfigEntity> all = botConfigRepo.findAll();
        if (all == null || all.isEmpty()) {
            // DB 시드가 완료되지 않은 초기 상태 방어: 기본값 엔티티 반환
            BotConfigEntity def = new BotConfigEntity();
            def.setMode("PAPER");
            def.setCandleUnitMin(5);
            def.setMaxAddBuysGlobal(2);
            return def;
        }
        return all.get(0);
    }

    /**
     * Compute base order amount (1x BUY) based on global order sizing config.
     * - FIXED: orderSizingValue = KRW
     * - PCT: base = capitalKrw * (pct/100)
     * - ATR_RISK: base = capital * (targetRisk% / slPct%)
     *   → 변동성 높은 코인에 적은 금액, 낮은 코인에 많은 금액 배분
     * Always enforces minOrderKrw.
     */
    private double resolveBaseOrderKrw(BotConfigEntity bc) {
        if (bc == null) {
            return Math.max(tradeProps.getGlobalBaseOrderKrw(), tradeProps.getMinOrderKrw());
        }
        String mode = (bc.getOrderSizingMode() == null ? "FIXED" : bc.getOrderSizingMode().trim().toUpperCase());
        double val = bd(bc.getOrderSizingValue());
        double capital = bd(bc.getCapitalKrw());

        double base;
        if ("PCT".equals(mode) || "PERCENT".equals(mode) || "PERCENTAGE".equals(mode)) {
            base = capital * (val / 100.0);
        } else if ("ATR_RISK".equals(mode)) {
            // ATR_RISK: orderSizingValue = 거래당 목표 리스크 % (예: 1.0 = 자본의 1%)
            // 수식: orderKrw = capital * targetRiskPct / slPct
            // SL%가 크면(변동성 높음) → 주문금액 작아짐 = 리스크 정규화
            double targetRiskPct = val;
            if (targetRiskPct <= 0) targetRiskPct = 1.0;
            double slPct = bd(bc.getStopLossPct());
            if (slPct <= 0) slPct = 2.0; // SL 비활성화 시 기본값
            base = capital * (targetRiskPct / slPct);
        } else {
            base = val;
        }
        if (base <= 0) base = tradeProps.getGlobalBaseOrderKrw();
        if (base < tradeProps.getMinOrderKrw()) base = tradeProps.getMinOrderKrw();
        return base;
    }

    /**
     * 그룹 설정이 있는 경우 그룹의 orderSizingMode/Value를 사용하여 기본 주문금액 산출.
     * 자본금(capitalKrw)은 글로벌 bot_config에서 가져옴.
     */
    private double resolveBaseOrderKrw(BotConfigEntity bc, StrategyGroupEntity group) {
        if (group == null) return resolveBaseOrderKrw(bc);
        if (bc == null) return Math.max(tradeProps.getGlobalBaseOrderKrw(), tradeProps.getMinOrderKrw());

        String mode = group.getOrderSizingMode();
        if (mode == null || mode.trim().isEmpty()) mode = "FIXED";
        mode = mode.trim().toUpperCase();

        double val = bd(group.getOrderSizingValue());
        double capital = bd(bc.getCapitalKrw());

        double base;
        if ("PCT".equals(mode) || "PERCENT".equals(mode) || "PERCENTAGE".equals(mode)) {
            base = capital * (val / 100.0);
        } else if ("ATR_RISK".equals(mode)) {
            double targetRiskPct = val;
            if (targetRiskPct <= 0) targetRiskPct = 1.0;
            double slPct = bd(group.getStopLossPct());
            if (slPct <= 0) slPct = 2.0;
            base = capital * (targetRiskPct / slPct);
        } else {
            base = val;
        }
        if (base <= 0) base = tradeProps.getGlobalBaseOrderKrw();
        if (base < tradeProps.getMinOrderKrw()) base = tradeProps.getMinOrderKrw();
        return base;
    }

    private double calcUnrealized() {
        double sum = 0.0;
        for (MarketConfigEntity mc : marketRepo.findAllByOrderByMarketAsc()) {
            PositionEntity pe = positionRepo.findById(mc.getMarket()).orElse(null);
            MarketState st = states.get(mc.getMarket());
            if (pe != null && bd(pe.getQty()) > 0 && st != null) {
                sum += bd(pe.getQty()) * (st.lastPrice - bd(pe.getAvgPrice()));
            }
        }
        return sum;
    }

    private double calcRealizedPnl(String mode) {
        // 주의: findTop200 대신 findAll 사용해야 200건 초과 시 누적 손익 누락 방지
        List<TradeEntity> list = tradeRepo.findAll();
        double sum = 0.0;
        for (TradeEntity t : list) {
            if (!"SELL".equals(t.getAction())) continue;
            if (mode != null && !mode.equalsIgnoreCase(t.getMode())) continue;
            sum += bd(t.getPnlKrw());
        }
        return sum;
    }

    private double calcMarketRealizedPnl(String market, String mode) {
        List<TradeEntity> list = tradeRepo.findAll();
        double sum = 0.0;
        for (TradeEntity t : list) {
            if (!market.equals(t.getMarket())) continue;
            if (!"SELL".equals(t.getAction())) continue;
            if (mode != null && !mode.equalsIgnoreCase(t.getMode())) continue;
            sum += bd(t.getPnlKrw());
        }
        return sum;
    }

    private int countSellsFrom(LocalDate fromDate) {
        ZoneId zone = ZoneId.systemDefault();
        long from = fromDate.atStartOfDay(zone).toInstant().toEpochMilli();
        long to = System.currentTimeMillis();
        return (int) tradeRepo.countByActionAndTsEpochMsBetween("SELL", from, to);
    }

    private LocalDate startOfWeek(LocalDate date) {
        return date.minusDays((date.getDayOfWeek().getValue() + 6) % 7);
    }

    /**
 * === Tick 실행 방식 ===
 *
 * 기존 방식(폴링):
 * - @Scheduled(fixedDelay=...) 로 N초마다 "캔들 2개"를 조회해서 새 봉이 생겼는지 확인
 * - 장점: 구현 단순
 * - 단점: 분봉이 큰데도(15/30분봉) 호출이 잦아져 불필요 호출/429 위험 증가
 *
 * 개선 방식(캔들 경계 스케줄):
 * - "다음 캔들 종료 시각(+버퍼)"에 맞춰 실행하여, 분봉 단위로만 API 호출하도록 함
 * - 예: 30분봉이면 30분에 1회(+필요 시 짧은 재시도), 5초 폴링 제거 효과
 *
 * 안전장치(필수):
 * 1) 캔들 타임스탬프 가드: 최신 캔들 시간이 lastCandleUtc와 같으면 절대 재실행하지 않음
 * 2) 버퍼+재시도: 경계 직후 업비트 반영 지연/네트워크 지연 대비
 * 3) 스케줄 자가복구: 예외가 나도 다음 스케줄은 반드시 다시 예약(절대 멈추지 않음)
 *
 * 폴링 방식으로 즉시 원복하고 싶으면:
 * - 아래 tickPoll() 의 @Scheduled 주석을 해제하고,
 * - boundary scheduler(startBoundaryScheduler) 호출을 주석처리하면 됩니다.
 */

private List<UpbitCandle> getMinuteCandles2WithRetry(String market, int unit, String lastCandleUtc) {
    int tries = Math.max(1, boundaryMaxRetry);
    for (int i = 0; i < tries; i++) {
        List<UpbitCandle> candles = candleService.getMinuteCandles(market, unit, 2, null);
        if (candles != null && candles.size() >= 2) {
            UpbitCandle a = candles.get(0);
            UpbitCandle b = candles.get(1);
            UpbitCandle latest = b;
            if (a != null && b != null && a.candle_date_time_utc != null && b.candle_date_time_utc != null) {
                if (a.candle_date_time_utc.compareTo(b.candle_date_time_utc) > 0) latest = a;
            }
            // If we already have the latest candle, retry a few times (Upbit 반영 지연 대비)
            if (latest != null && latest.candle_date_time_utc != null && latest.candle_date_time_utc.equals(lastCandleUtc)) {
                // continue retry
            } else {
                return candles;
            }
        }
        if (i < tries - 1) {
            try { Thread.sleep(boundaryRetrySleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return candles; }
        }
    }
    return candleService.getMinuteCandles(market, unit, 2, null);
}

private void startBoundaryScheduler() {
    // Enabled flag can be changed later if you want to revert to polling
    if (!boundarySchedulerEnabled) return;

    boundaryExec.schedule(new Runnable() {
        @Override public void run() {
            scheduleNextBoundary();
        }
    }, 0, java.util.concurrent.TimeUnit.MILLISECONDS);
}

private void scheduleNextBoundary() {
    try {
        // Always reschedule first to guarantee "never stop" property even if tickInternal throws
        long delayMs = computeDelayToNextCandleCloseMs();
        log.debug("[SCHEDULER] 다음 tick 예약: {}초 후", delayMs / 1000);
        boundaryExec.schedule(new Runnable() {
            @Override public void run() {
                try {
                    tickInternal(true);
                } catch (Exception e) {
                    // swallow to keep scheduler alive; errors are visible in server logs
                    log.error("[TICK] 예외 발생", e);
                } finally {
                    // Self-heal: schedule again no matter what
                    scheduleNextBoundary();
                }
            }
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (Exception e) {
        // Worst-case fallback: try again shortly
        log.error("[SCHEDULER] 스케줄링 실패, 5초 후 재시도", e);
        boundaryExec.schedule(new Runnable() {
            @Override public void run() { scheduleNextBoundary(); }
        }, 5000, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}

/**
 * Delay until next candle boundary + buffer.
 * Uses UTC epoch minutes to avoid timezone/DST issues.
 */
private long computeDelayToNextCandleCloseMs() {
    BotConfigEntity bc = getBotConfig();
    java.util.List<StrategyType> active = parseActiveStrategyTypes(bc);
    int unit = active.isEmpty() ? bc.getCandleUnitMin() : bc.getMinEffectiveInterval(active);
    if (unit <= 0) unit = 5;

    Instant now = Instant.now();
    long epochMin = now.getEpochSecond() / 60L;
    long nextBoundaryMin = ((epochMin / unit) + 1L) * unit; // ceil to next unit boundary
    Instant nextBoundary = Instant.ofEpochSecond(nextBoundaryMin * 60L);

    long delay = Duration.between(now, nextBoundary).toMillis();
    long buffer = Math.max(0, boundaryBufferSeconds) * 1000L;
    delay += buffer;

    // If system time is skewed and delay becomes negative/too small, push to next unit.
    long minDelay = 200L;
    if (delay < minDelay) delay += (long) unit * 60_000L;

    return delay;
}


// ${bot.pollSeconds} 마다 실행되는 "폴링 방식" (원복용)
// @Scheduled(fixedDelayString = "${bot.pollSeconds:5}000")
public void tickPoll() {
    tickInternal(false);
}

// 캔들 경계(종료시각) 기반 tick (권장)
private void tickInternal(boolean boundaryAligned) {
        if (!running.get()) return;

        BotConfigEntity bc = getBotConfig();
        // 멀티 인터벌: 활성 전략의 최소 유효 인터벌을 tick 기준으로 사용
        java.util.List<StrategyType> activeStrats = parseActiveStrategyTypes(bc);
        int unit = activeStrats.isEmpty() ? bc.getCandleUnitMin() : bc.getMinEffectiveInterval(activeStrats);
        currentTickUnitMin = unit; // persist()에서 trade_log.candle_unit_min에 기록
        String mode = bc.getMode() == null ? "PAPER" : bc.getMode().toUpperCase();

        // ★ tick-level 그룹 스냅샷: 마켓 루프 전에 한 번 조회하여 일관성 보장
        //   → 그룹 저장(@Transactional) 중에도 tick 내 모든 마켓이 동일 스냅샷 참조
        final List<StrategyGroupEntity> tickGroups = strategyGroupRepo.findAllByOrderBySortOrderAsc();
        final boolean hasGroups = tickGroups != null && !tickGroups.isEmpty();

        // 대상 마켓(활성화) 순회
        List<MarketConfigEntity> enabled = marketRepo.findByEnabledTrueOrderByMarketAsc();

        if (enabled.isEmpty()) {
            log.warn("[TICK] 활성화된 마켓이 없습니다. 대시보드에서 코인을 활성화해주세요.");
            return;
        }

        log.debug("[TICK] {}분봉 | {} 모드 | 활성 마켓: {} | boundary={}", unit, mode, enabled.size(), boundaryAligned);

        // LIVE 모드: 첫 tick에서 API 키 유효성 사전 검증
        if ("LIVE".equals(mode) && liveOrders.isConfigured() && !liveKeyVerified) {
            try {
                privateClient.getAccounts();
                liveKeyVerified = true;
                log.info("[LIVE] API 키 검증 성공 — 주문 가능 상태입니다.");

                // 업비트 실제 보유 자산 ↔ DB 포지션 동기화
                // (재시작 또는 state=cancel 체결 누락으로 봇 DB에 포지션이 없는 경우 복구)
                syncPositionsFromUpbit(enabled);
            } catch (Exception authEx) {
                String msg = authEx.getMessage() != null ? authEx.getMessage() : "";
                log.error("[LIVE] API 키 검증 실패 — 매수/매도 주문이 불가합니다. 키를 확인하세요. error={}", msg);
                try {
                    Map<String, Object> det = new LinkedHashMap<String, Object>();
                    det.put("error", msg);
                    addDecisionLog(null, unit, "SYSTEM", "BLOCKED",
                            "API_KEY_INVALID",
                            "LIVE 모드 API 키 검증 실패. 업비트 대시보드에서 키 상태(만료/IP허용/권한)를 확인하세요.",
                            det);
                } catch (Exception ignore) {}
                // 키 검증 실패해도 시그널 평가는 계속 (SELL용 포지션 관리 등)
            }
        }

        for (MarketConfigEntity mc : enabled) {
            String market = mc.getMarket();
          try { // ── per-market 예외 격리: 한 마켓 실패가 다른 마켓에 영향주지 않음 ──
            MarketState st = states.get(market);
            if (st == null) {
                st = new MarketState(market);
                states.put(market, st);
            }

            // LIVE 모드에서는 pending 주문이 있으면 해당 마켓의 전략 실행을 막아 중복주문 위험을 줄입니다.
            if ("LIVE".equals(mode) && liveOrders.isConfigured() && liveOrders.hasPendingOrder(market)) {
                continue;
            }

            List<UpbitCandle> candles = boundaryAligned ? getMinuteCandles2WithRetry(market, unit, st.lastCandleUtc) : candleService.getMinuteCandles(market, unit, 2, null);
            if (candles.size() < 2) {
                log.warn("[{}] 캔들 조회 실패 ({}개 반환)", market, candles.size());
                continue;
            }

            UpbitCandle a = candles.get(0);
            UpbitCandle b = candles.get(1);
            UpbitCandle older = a;
            UpbitCandle latest = b;
            if (a.candle_date_time_utc != null && b.candle_date_time_utc != null) {
                if (a.candle_date_time_utc.compareTo(b.candle_date_time_utc) > 0) {
                    latest = a; older = b;
                } else {
                    latest = b; older = a;
                }
            }

            st.lastPrice = latest.trade_price;

            
if (latest.candle_date_time_utc == null) continue;
if (latest.candle_date_time_utc.equals(st.lastCandleUtc)) {
    log.debug("[{}] 새 캔들 없음 (동일 봉: {})", market, st.lastCandleUtc);
    continue;
}

log.info("[{}] 새 캔들 감지 | {} | 종가:{}", market, latest.candle_date_time_utc,
        String.format("%.2f", latest.trade_price));

// === catch-up + state persistence ===
// If server was paused and we missed multiple candle boundaries,
// we "catch up" by processing missed candles in order, BUT:
//  - we only allow ORDERS on the latest candle (to avoid trading stale patterns)
//  - earlier missed candles only update runtime state (downStreak / lastCandleUtc / etc.)
List<UpbitCandle> candlesToProcess = new ArrayList<UpbitCandle>();
if (boundaryAligned && st.lastCandleUtc != null) {
    try {
        List<UpbitCandle> chunk = candleService.getMinuteCandles(market, unit, 200, latest.candle_date_time_utc);
        List<UpbitCandle> asc = sortCandlesAsc(chunk);
        Instant lastSeen = parseUpbitUtc(st.lastCandleUtc);
        for (UpbitCandle c : asc) {
            if (c == null || c.candle_date_time_utc == null) continue;
            Instant ts = parseUpbitUtc(c.candle_date_time_utc);
            if (ts == null) continue;
            if (lastSeen != null && !ts.isAfter(lastSeen)) continue;
            candlesToProcess.add(c);
        }
        // keep only the last N candles (best effort)
        if (candlesToProcess.size() > boundaryCatchUpMaxCandles) {
            candlesToProcess = candlesToProcess.subList(candlesToProcess.size() - boundaryCatchUpMaxCandles, candlesToProcess.size());
        }
    } catch (Exception ignore) {
        candlesToProcess.clear();
    }
}

// Dashboard guard log: if we are processing multiple candles (catch-up), orders are only allowed on the latest candle.
try {
    if (candlesToProcess != null && candlesToProcess.size() > 1) {
        Map<String, Object> det = new LinkedHashMap<String, Object>();
        det.put("candlesToProcess", candlesToProcess.size());
        det.put("catchUpMaxCandles", boundaryCatchUpMaxCandles);
        det.put("note", "catch-up candles are state-only; orders allowed only on latest candle");
        addDecisionLog(market, unit, "N/A", "INFO",
                "CATCH_UP_BLOCK",
                "서버 지연/재시작 등으로 누락된 봉을 복구 중이라 과거 봉에서는 주문을 막고 상태만 반영했어요. (최신 봉에서만 주문 가능)",
                det);
    }
} catch (Exception ignore) {}

if (candlesToProcess.isEmpty()) {
    candlesToProcess.add(latest);
}

double prevClose = older.trade_price;
for (int ci = 0; ci < candlesToProcess.size(); ci++) {
    UpbitCandle cur = candlesToProcess.get(ci);
    if (cur == null || cur.candle_date_time_utc == null) continue;

    boolean isLatestCandle = (ci == candlesToProcess.size() - 1);
    boolean allowOrders = isLatestCandle;

    double close = cur.trade_price;
    if (close < prevClose) st.downStreak++;
    else st.downStreak = 0; st.peakHighSinceEntry = 0.0;
    prevClose = close;

    st.lastPrice = close;
    st.lastCandleUtc = cur.candle_date_time_utc;
    persistStateToDb(market, st);

    // state-only catch-up candles
    if (!allowOrders) continue;

    // ENTRY guard: 정상 tick 보호용 (스케줄러 지연 시)
    boolean staleEntry = isStaleForEntry(cur, unit);

                PositionEntity pe = positionRepo.findById(market).orElse(null);

// ===== STEP 1: TP/SL 최우선 체크 (그룹별 TP/SL 적용) =====
boolean openForTpSl = pe != null && bd(pe.getQty()) > 0;
double avgForTpSl = openForTpSl ? bd(pe.getAvgPrice()) : 0;
double tpPctVal = bd(bc.getTakeProfitPct());
double slPctVal = bd(bc.getStopLossPct());

// 그룹별 설정 해석: tick-level 스냅샷에서 해당 마켓의 그룹 조회 (일관성 보장)
StrategyGroupEntity marketGroup = null;
if (hasGroups) {
    for (StrategyGroupEntity tg : tickGroups) {
        if (tg.getMarketsList().contains(market)) { marketGroup = tg; break; }
    }
}
if (marketGroup != null) {
    tpPctVal = bd(marketGroup.getTakeProfitPct());
    slPctVal = bd(marketGroup.getStopLossPct());
}

SignalEvaluator.Result tpSlResult = SignalEvaluator.checkTpSl(openForTpSl, avgForTpSl, close, tpPctVal, slPctVal);
if (tpSlResult != null) {
    // TP/SL 발동 → 즉시 매도 (전략 평가 스킵)
    String tpSlType = tpSlResult.patternType;
    String tpSlReason = tpSlResult.reason;
    if ("PAPER".equals(mode)) {
        double fill = close * (1.0 - tradeProps.getSlippageRate());
        double gross = bd(pe.getQty()) * fill;
        double fee = gross * cfg.getFeeRate();
        double realized = (gross - fee) - (bd(pe.getQty()) * avgForTpSl);
        persist(mode, market, "SELL", fill, bd(pe.getQty()), realized, 0.0, tpSlReason, tpSlType, tpSlReason, avgForTpSl);
        positionRepo.deleteById(market);
        st.downStreak = 0; st.peakHighSinceEntry = 0.0;
    } else {
        if (!liveOrders.isConfigured()) throw new IllegalStateException("LIVE 모드인데 업비트 키가 없습니다.");
        LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(market, bd(pe.getQty()));
        if (!r.isFilled()) {
            persist(mode, market, "SELL_PENDING", close, bd(pe.getQty()), 0.0, 0.0, "state=" + r.state + " vol=" + r.executedVolume, tpSlType, tpSlReason);
            break;
        }
        double fill = r.avgPrice > 0 ? r.avgPrice : close;
        double gross = bd(pe.getQty()) * fill;
        double fee = gross * cfg.getFeeRate();
        double realized = (gross - fee) - (bd(pe.getQty()) * avgForTpSl);
        persist(mode, market, "SELL", fill, bd(pe.getQty()), realized, 0.0, tpSlReason + " uuid=" + r.uuid, tpSlType, tpSlReason, avgForTpSl);
        positionRepo.deleteById(market);
        st.downStreak = 0; st.peakHighSinceEntry = 0.0;
    }
    log.info("[{}] {} 매도 | 평단:{} → 현재:{} | 사유: {}", market, tpSlType,
            String.format("%.2f", avgForTpSl), String.format("%.2f", close), tpSlReason);
    break; // TP/SL 매도 완료 → 다음 마켓으로
}

// ===== STEP 1.5: Time Stop — 매수 전용 전략 시간 초과 청산 =====
int timeStopMin = bc.getTimeStopMinutes();
if (marketGroup != null) {
    timeStopMin = marketGroup.getTimeStopMinutes();
}
if (timeStopMin > 0 && openForTpSl && pe != null && pe.getOpenedAt() != null) {
    long elapsedMs = System.currentTimeMillis() - pe.getOpenedAt().toEpochMilli();
    long elapsedMin = elapsedMs / 60000L;
    if (elapsedMin >= timeStopMin) {
        // 진입 전략이 매수 전용인지 확인
        boolean isBuyOnlyEntry = false;
        String entryStrat = pe.getEntryStrategy();
        if (entryStrat != null && !entryStrat.isEmpty()) {
            try { isBuyOnlyEntry = StrategyType.valueOf(entryStrat).isBuyOnly(); } catch (Exception ignore) {}
        }
        if (isBuyOnlyEntry) {
            double pnlPct = avgForTpSl > 0 ? ((close - avgForTpSl) / avgForTpSl) * 100.0 : 0;
            if (pnlPct < 0) {
                // 손실 + 시간 초과 → 청산
                String tsReason = String.format(java.util.Locale.ROOT,
                        "TIME_STOP %dmin elapsed=%dmin entry=%s pnl=%.2f%%",
                        timeStopMin, elapsedMin, entryStrat, pnlPct);
                log.info("[{}] Time Stop 발동 | {}분 경과 + 손실 {}% | 진입전략: {}",
                        market, elapsedMin, String.format("%.2f", pnlPct), entryStrat);

                if ("PAPER".equals(mode)) {
                    double fill = close * (1.0 - tradeProps.getSlippageRate());
                    double gross = bd(pe.getQty()) * fill;
                    double fee = gross * cfg.getFeeRate();
                    double realized = (gross - fee) - (bd(pe.getQty()) * avgForTpSl);
                    persist(mode, market, "SELL", fill, bd(pe.getQty()), realized, 0.0, tsReason, "TIME_STOP", tsReason, avgForTpSl);
                    positionRepo.deleteById(market);
                    st.downStreak = 0; st.peakHighSinceEntry = 0.0;
                } else {
                    if (!liveOrders.isConfigured()) throw new IllegalStateException("LIVE 모드인데 업비트 키가 없습니다.");
                    LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(market, bd(pe.getQty()));
                    if (!r.isFilled()) {
                        persist(mode, market, "SELL_PENDING", close, bd(pe.getQty()), 0.0, 0.0,
                                "state=" + r.state + " vol=" + r.executedVolume, "TIME_STOP", tsReason);
                        break;
                    }
                    double fill = r.avgPrice > 0 ? r.avgPrice : close;
                    double gross = bd(pe.getQty()) * fill;
                    double fee = gross * cfg.getFeeRate();
                    double realized = (gross - fee) - (bd(pe.getQty()) * avgForTpSl);
                    persist(mode, market, "SELL", fill, bd(pe.getQty()), realized, 0.0,
                            tsReason + " uuid=" + r.uuid, "TIME_STOP", tsReason, avgForTpSl);
                    positionRepo.deleteById(market);
                    st.downStreak = 0; st.peakHighSinceEntry = 0.0;
                }
                break; // Time Stop 매도 완료 → 다음 마켓
            }
            // pnlPct >= 0: 이익 중이면 Time Stop 미발동 (TP 대기)
        }
    }
}

// ===== STEP 2: 전략 평가 — 그룹별 전략/인터벌 지원 =====

// 전략 그룹이 1개 이상 존재하는데 이 마켓이 어떤 그룹에도 속하지 않으면 → 전략 평가 스킵
// (TP/SL, 타임스탑은 위에서 이미 처리 → 기존 포지션 보호는 유지)
// (그룹 미설정 마켓이 글로벌 설정으로 폴백되어 의도치 않게 신규 매수되는 버그 방지)
//
// ★ 이중 방어: tick-level 스냅샷 + 실시간 DB 재확인
//   (1) tick 시작 시점의 hasGroups 스냅샷으로 1차 판단
//   (2) 만약 스냅샷에서 그룹이 없었지만 그 사이 저장되었을 수 있으므로 DB 재확인 (safety net)
if (marketGroup == null) {
    boolean groupsExist = hasGroups;
    if (!groupsExist) {
        // tick 스냅샷에서는 그룹 없음 → 혹시 그 사이 저장됐을 수 있으므로 실시간 재확인
        List<StrategyGroupEntity> liveGroups = strategyGroupRepo.findAllByOrderBySortOrderAsc();
        groupsExist = liveGroups != null && !liveGroups.isEmpty();
    }
    if (groupsExist) {
        log.info("[{}] 전략그룹 미지정 → 전략 평가 스킵 (TP/SL·타임스탑은 정상 작동)", market);
        try {
            Map<String, Object> det = new LinkedHashMap<String, Object>();
            det.put("market", market);
            det.put("hasGroupsSnapshot", hasGroups);
            det.put("reason", "마켓이 어떤 전략그룹에도 속하지 않음");
            addDecisionLog(market, unit, "N/A", "SKIPPED",
                    "GROUP_NOT_ASSIGNED",
                    "이 마켓은 전략그룹에 배정되지 않아 신규 매수가 차단되었습니다. (기존 포지션 TP/SL은 정상 작동)",
                    det);
        } catch (Exception ignore) {}
        continue;
    }
}

java.util.List<StrategyType> stypes;
if (marketGroup != null && !marketGroup.getStrategyTypesList().isEmpty()) {
    // 그룹별 전략 사용
    stypes = new java.util.ArrayList<StrategyType>();
    for (String sn : marketGroup.getStrategyTypesList()) {
        try { stypes.add(StrategyType.valueOf(sn)); } catch (Exception ignore) {}
    }
} else {
    stypes = parseActiveStrategyTypes(bc);
}

// 전략을 유효 인터벌별로 그룹화 (그룹 설정 우선)
java.util.Map<Integer, java.util.List<StrategyType>> stratsByInterval = new java.util.LinkedHashMap<Integer, java.util.List<StrategyType>>();
for (StrategyType stype : stypes) {
    int effInterval = (marketGroup != null) ? marketGroup.getEffectiveInterval(stype) : bc.getEffectiveInterval(stype);
    java.util.List<StrategyType> group = stratsByInterval.get(effInterval);
    if (group == null) { group = new java.util.ArrayList<StrategyType>(); stratsByInterval.put(effInterval, group); }
    group.add(stype);
}

// 전략별 EMA 트렌드 필터 맵 빌드 (그룹 설정 우선)
java.util.Map<String, Integer> emaTrendFilterMap = new java.util.HashMap<String, Integer>();
for (StrategyType stype2 : stypes) {
    emaTrendFilterMap.put(stype2.name(), (marketGroup != null) ? marketGroup.getEffectiveEmaPeriod(stype2) : bc.getEffectiveEmaPeriod(stype2));
}

// 각 인터벌 그룹별로 캔들 조회 + 전략 평가, 결과 중 최우선 신호 선택
SignalEvaluator.Result evalResult = null;
int evalGroupInterval = unit;
List<UpbitCandle> evalCandles = null; // 시장 상태 판단용 캔들 보존 // 전략 결과가 어떤 인터벌에서 나왔는지 추적

for (java.util.Map.Entry<Integer, java.util.List<StrategyType>> entry : stratsByInterval.entrySet()) {
    int groupInterval = entry.getKey();
    java.util.List<StrategyType> groupStrats = entry.getValue();

    // ★ 인터벌 경계 정렬: tick 기준 인터벌(unit)보다 큰 인터벌 그룹은
    // 해당 그룹의 캔들 경계에서만 평가 (예: 60분 tick에서 240분 그룹은 4시간마다만 평가)
    // sellOnlyTick 예외: 첫 tick에서는 모든 그룹의 SELL 기회를 체크
    if (groupInterval > unit && !sellOnlyTick) {
        long epochMin = java.time.Instant.now().getEpochSecond() / 60L;
        long tickBoundary = (epochMin / unit) * unit;
        if (tickBoundary % groupInterval != 0) {
            log.debug("[{}] {}분봉 경계 불일치 → 해당 그룹 스킵 (다음 {}분 경계에서 평가)", market, groupInterval, groupInterval);
            try {
                Map<String, Object> det = new LinkedHashMap<String, Object>();
                det.put("tickBoundaryMin", tickBoundary);
                det.put("groupInterval", groupInterval);
                det.put("strategies", groupStrats.toString());
                addDecisionLog(market, unit, "N/A", "SKIPPED",
                        "INTERVAL_BOUNDARY_SKIP",
                        groupInterval + "분봉 그룹은 " + groupInterval + "분 경계에서만 평가됩니다. 현재 " + unit + "분 경계이므로 스킵합니다.",
                        det);
            } catch (Exception ignore) {}
            continue;
        }
    }

    // 윈도우 크기: REGIME_PULLBACK(450), 그 외 EMA 기간에 따라 동적 조정
    boolean needsLargeWindow = groupStrats.contains(StrategyType.REGIME_PULLBACK);
    int maxEmaPeriod = 50;
    for (StrategyType gs : groupStrats) {
        int ep = bc.getEffectiveEmaPeriod(gs);
        if (ep > maxEmaPeriod) maxEmaPeriod = ep;
    }
    int emaWindowMin = Math.max(200, maxEmaPeriod * 3);
    int windowSize = needsLargeWindow ? 450 : emaWindowMin;

    List<UpbitCandle> window;
    if (windowSize > 200) {
        window = candleService.getMinuteCandlesPaged(market, groupInterval, windowSize);
    } else {
        window = candleService.getMinuteCandles(market, groupInterval, windowSize, null);
    }
    if (window == null || window.size() < 5) {
        log.debug("[{}] 전략 평가용 캔들 부족 ({}분봉 {}개) → 해당 그룹 스킵", market, groupInterval, window == null ? 0 : window.size());
        continue;
    }
    window.sort(new Comparator<UpbitCandle>() {
        @Override public int compare(UpbitCandle o1, UpbitCandle o2) {
            if (o1.candle_date_time_utc == null && o2.candle_date_time_utc == null) return 0;
            if (o1.candle_date_time_utc == null) return -1;
            if (o2.candle_date_time_utc == null) return 1;
            return o1.candle_date_time_utc.compareTo(o2.candle_date_time_utc);
        }
    });

    // 시장 상태 판단용 캔들 보존 (가장 긴 인터벌의 캔들 사용)
    if (evalCandles == null || window.size() > evalCandles.size()) {
        evalCandles = window;
    }

    StrategyContext ctx = new StrategyContext(market, groupInterval, window, pe, st.downStreak, emaTrendFilterMap);
    SignalEvaluator.Result groupResult = SignalEvaluator.evaluateStrategies(groupStrats, strategyFactory, ctx);

    if (groupResult.isEmpty()) continue;

    // 그룹 간 신호 병합: SELL(3) > ADD_BUY(2) > BUY(1), 동일 우선순위면 confidence 높은 것
    if (evalResult == null || evalResult.isEmpty()) {
        evalResult = groupResult;
        evalGroupInterval = groupInterval;
    } else {
        int pNew = signalPriority(groupResult.signal.action);
        int pCur = signalPriority(evalResult.signal.action);
        if (pNew > pCur) {
            evalResult = groupResult;
            evalGroupInterval = groupInterval;
        } else if (pNew == pCur && groupResult.confidence > evalResult.confidence) {
            evalResult = groupResult;
            evalGroupInterval = groupInterval;
        }
    }
}

if (evalResult == null || evalResult.isEmpty()) {
    log.info("[{}] 전략 평가 → 신호 없음 | 전략: {} | 인터벌: {}", market,
            stypes.size() > 3 ? stypes.size() + "개" : stypes.toString(),
            stratsByInterval.keySet());
    continue;
}

Signal chosen = evalResult.signal;
currentTickUnitMin = evalGroupInterval; // 전략 결과의 실제 분봉 단위 기록

log.info("[{}] 전략 신호 감지 | {} → {} | 사유: {}", market,
        evalResult.patternType, evalResult.signal.action, evalResult.reason);

    // ── BUY/ADD_BUY 가드 ──
    if (chosen.action == SignalAction.BUY || chosen.action == SignalAction.ADD_BUY) {
        // (1) Start 즉시 tick: 매도만 체크, 매수는 다음 정상 캔들 경계에서
        if (sellOnlyTick) {
            log.info("[{}] SELL_ONLY_TICK | {} 신호 있으나 Start 직후이므로 매수 스킵 → 다음 캔들 경계에서 판단",
                    market, chosen.action);
            try {
                Map<String, Object> det = new LinkedHashMap<String, Object>();
                det.put("candleUnitMin", unit);
                addDecisionLog(market, unit, chosen.action.name(), "SKIPPED",
                        "SELL_ONLY_TICK",
                        "Start 직후 매도 전용 tick입니다. 매수는 다음 정상 캔들 경계에서 판단합니다.",
                        det);
            } catch (Exception ignore) {}
            continue;
        }
        // (2) 정상 tick 보호: 스케줄러 지연으로 봉 마감 후 60초 이상 경과 시 매수 차단
        if (staleEntry) {
            log.warn("[{}] STALE 차단 | {} 신호 있으나 봉 마감 후 {}초 이상 경과 → 매수 차단",
                    market, chosen.action, staleEntryTtlSeconds);
            persist(mode, market, "SIGNAL_ONLY", close, 0.0, 0.0, 0.0,
                    "STALE_CANDLE age>" + staleEntryTtlSeconds + "s",
                    "STALE_CANDLE", "STALE_CANDLE");
            try {
                Map<String, Object> det = new LinkedHashMap<String, Object>();
                det.put("ttlSeconds", staleEntryTtlSeconds);
                det.put("candleUnitMin", unit);
                addDecisionLog(market, unit, chosen.action.name(), "BLOCKED",
                        "STALE_ENTRY_BLOCK",
                        "봉 마감 후 시간이 지나 매수를 차단했습니다. (스케줄러 지연 보호)",
                        det);
            } catch (Exception ignore) {}
            continue;
        }
    }

String patternType = evalResult.patternType;
String patternReason = evalResult.reason;
double signalConfidence = evalResult.confidence;

// === 드로다운 서킷브레이커: 총 실현 PnL이 자본 대비 한도 초과 시 신규 매수 차단 ===
if (chosen.action == SignalAction.BUY || chosen.action == SignalAction.ADD_BUY) {
    double maxDdPct = bd(bc.getMaxDrawdownPct());
    if (maxDdPct > 0) {
        double capitalVal = bd(bc.getCapitalKrw());
        if (capitalVal > 0) {
            double totalPnl = calcTotalRealizedPnl();
            double pnlPctOfCap = (totalPnl / capitalVal) * 100.0;
            if (pnlPctOfCap <= -maxDdPct) {
                log.warn("[{}] DRAWDOWN_CIRCUIT_BREAKER | 총 PnL {:.2f}원 = 자본 대비 {:.2f}% ≤ -{}% → 매수 차단",
                        market, totalPnl, pnlPctOfCap, maxDdPct);
                addDecisionLog(market, unit, chosen.action.name(), "BLOCKED",
                        "DRAWDOWN_CIRCUIT_BREAKER",
                        String.format(java.util.Locale.ROOT,
                                "총 손실 %.0f원(자본 대비 %.2f%%)이 한도(-%s%%)를 초과하여 신규 매수를 차단합니다.",
                                totalPnl, pnlPctOfCap, String.valueOf(maxDdPct)),
                        null);
                continue;
            }
        }
    }
    // 일일 손실 한도 체크
    double dailyLimitPct = bd(bc.getDailyLossLimitPct());
    if (dailyLimitPct > 0) {
        double capitalVal = bd(bc.getCapitalKrw());
        if (capitalVal > 0) {
            double todayPnl = calcTodayRealizedPnl();
            double todayPctOfCap = (todayPnl / capitalVal) * 100.0;
            if (todayPctOfCap <= -dailyLimitPct) {
                log.warn("[{}] DAILY_LOSS_LIMIT | 금일 PnL {}원 = 자본 대비 {}% ≤ -{}% → 매수 차단",
                        market, String.format("%.0f", todayPnl), String.format("%.2f", todayPctOfCap), dailyLimitPct);
                addDecisionLog(market, unit, chosen.action.name(), "BLOCKED",
                        "DAILY_LOSS_LIMIT",
                        String.format(java.util.Locale.ROOT,
                                "금일 손실 %.0f원(자본 대비 %.2f%%)이 한도(-%s%%)를 초과하여 신규 매수를 차단합니다.",
                                todayPnl, todayPctOfCap, String.valueOf(dailyLimitPct)),
                        null);
                continue;
            }
        }
    }

    // === 연속 손실 쿨다운: 3연패 시 2시간 BUY 차단 ===
    final int COOLDOWN_LOSS_STREAK = 3;
    final long COOLDOWN_MS = 2L * 60 * 60 * 1000; // 2시간
    List<TradeEntity> recentTrades = tradeRepo.findTop200ByOrderByTsEpochMsDesc();
    int lossStreak = 0;
    long lastLossTs = 0;
    for (TradeEntity te : recentTrades) {
        if (!"SELL".equals(te.getAction())) continue;
        if (bd(te.getPnlKrw()) < 0) {
            lossStreak++;
            if (lastLossTs == 0) lastLossTs = te.getTsEpochMs();
        } else {
            break; // 이전 거래가 수익이면 연속 손실 끊김
        }
    }
    if (lossStreak >= COOLDOWN_LOSS_STREAK && lastLossTs > 0) {
        long elapsed = System.currentTimeMillis() - lastLossTs;
        if (elapsed < COOLDOWN_MS) {
            long remainMin = (COOLDOWN_MS - elapsed) / 60000;
            log.warn("[{}] CONSECUTIVE_LOSS_COOLDOWN | {}연패 → 잔여 {}분 쿨다운 → 매수 차단",
                    market, lossStreak, remainMin);
            try {
                Map<String, Object> det = new LinkedHashMap<String, Object>();
                det.put("lossStreak", lossStreak);
                det.put("remainMinutes", remainMin);
                addDecisionLog(market, unit, chosen.action.name(), "BLOCKED",
                        "CONSECUTIVE_LOSS_COOLDOWN",
                        String.format(java.util.Locale.ROOT,
                                "%d연속 손실 후 쿨다운 중 (잔여 %d분). 매수를 차단합니다.",
                                lossStreak, remainMin),
                        det);
            } catch (Exception ignore) {}
            continue;
        }
    }
}

// === 글로벌 트레일링 스탑: 포지션 고점 대비 하락 시 청산 ===
if (pe != null && bd(pe.getQty()) > 0) {
    double globalTrailPct = bd(bc.getTrailingStopPct());
    if (globalTrailPct > 0) {
        double avgPriceTrail = bd(pe.getAvgPrice());
        if (avgPriceTrail > 0) {
            // 전략 자체 트레일링이 없는 경우에만 글로벌 적용
            boolean selfContained = false;
            String entryStrat = pe.getEntryStrategy();
            if (entryStrat != null) {
                try {
                    StrategyType est = StrategyType.valueOf(entryStrat);
                    selfContained = est.isSelfContained();
                } catch (Exception ignore) {}
            }
            if (!selfContained) {
                List<UpbitCandle> trailCandles = candleService.getMinuteCandles(market, unit, 200, null);
                if (trailCandles != null && !trailCandles.isEmpty()) {
                    double peakHigh = Indicators.peakHighSinceEntry(trailCandles, avgPriceTrail);
                    double trailStopPrice = peakHigh * (1.0 - globalTrailPct / 100.0);
                    if (close <= trailStopPrice && close < peakHigh) {
                        String trailReason = String.format(java.util.Locale.ROOT,
                                "GLOBAL_TRAILING_STOP peak=%.2f trail=%.2f%% stop=%.2f close=%.2f",
                                peakHigh, globalTrailPct, trailStopPrice, close);
                        log.info("[{}] 글로벌 트레일링 스탑 | 고점:{} → 현재:{} ({}% 하락) | 청산",
                                market, String.format("%.2f", peakHigh), String.format("%.2f", close),
                                String.format("%.2f", ((peakHigh - close) / peakHigh) * 100.0));
                        if ("PAPER".equals(mode)) {
                            double fill = close * (1.0 - tradeProps.getSlippageRate());
                            double gross = bd(pe.getQty()) * fill;
                            double fee = gross * cfg.getFeeRate();
                            double realized = (gross - fee) - (bd(pe.getQty()) * avgPriceTrail);
                            persist(mode, market, "SELL", fill, bd(pe.getQty()), realized, 0.0,
                                    trailReason, "TRAILING_STOP", trailReason, avgPriceTrail);
                            positionRepo.deleteById(market);
                            st.downStreak = 0; st.peakHighSinceEntry = 0.0;
                        } else {
                            if (liveOrders.isConfigured()) {
                                LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(market, bd(pe.getQty()));
                                if (r.isFilled()) {
                                    double fill = r.avgPrice > 0 ? r.avgPrice : close;
                                    double gross = bd(pe.getQty()) * fill;
                                    double fee = gross * cfg.getFeeRate();
                                    double realized = (gross - fee) - (bd(pe.getQty()) * avgPriceTrail);
                                    persist(mode, market, "SELL", fill, bd(pe.getQty()), realized, 0.0,
                                            trailReason + " uuid=" + r.uuid, "TRAILING_STOP", trailReason, avgPriceTrail);
                                    positionRepo.deleteById(market);
                                    st.downStreak = 0; st.peakHighSinceEntry = 0.0;
                                }
                            }
                        }
                        break;
                    }
                }
            }
        }
    }
}

// === 최대 투입금(capitalKrw) 체크: 보유 포지션 원금 합(대략) ===
        double cap = bd(bc.getCapitalKrw());
double used = calcOpenCostBasis();
double remainCap = Math.max(0.0, cap - used);

boolean open = pe != null && bd(pe.getQty()) > 0;

// (TP/SL은 STEP 1에서 이미 처리됨 - 여기까지 왔다면 TP/SL 미발동)
// 패턴 SELL은 수익/손실 관계없이 허용 (TP/SL은 안전장치 역할)

// === Strategy Lock 체크: 매수 전략과 다른 전략의 SELL 차단 ===
// 예외: 매도 전용 전략(하락 장악형, 이브닝스타, 흑삼병, 하락 삼법형)은 항상 허용
if (chosen.action == SignalAction.SELL && open && bc.isStrategyLock()) {
    String entryStrat = pe.getEntryStrategy();
    boolean sellOnlyStrategy = false;
    try { sellOnlyStrategy = StrategyType.valueOf(patternType).isSellOnly(); } catch (Exception ignore) {}

    if (!sellOnlyStrategy && entryStrat != null && !entryStrat.isEmpty() && !entryStrat.equals(patternType)) {
        log.info("[{}] 전략잠금 차단 | 매수전략={} ≠ 매도전략={} → SELL 무시 (매도전용 전략 아님)", market, entryStrat, patternType);
        persist(mode, market, "SIGNAL_ONLY", close, 0.0, 0.0, 0.0,
                "STRATEGY_LOCK: entry=" + entryStrat + " sell=" + patternType,
                "STRATEGY_LOCK", "전략잠금: 매수전략(" + entryStrat + ")과 다른 전략(" + patternType + ")의 매도 차단");
        continue;
    }
    // sellOnlyStrategy == true → Lock 예외: 매도 전용 전략은 차단하지 않음
}

// === SELL ===
if (chosen.action == SignalAction.SELL && open) {
    if ("PAPER".equals(mode)) {
        double fill = close * (1.0 - tradeProps.getSlippageRate());
        double gross = bd(pe.getQty()) * fill;
        double fee = gross * cfg.getFeeRate();
        double realized = (gross - fee) - (bd(pe.getQty()) * bd(pe.getAvgPrice()));
        double pnlPct = bd(pe.getAvgPrice()) > 0 ? ((close - bd(pe.getAvgPrice())) / bd(pe.getAvgPrice())) * 100.0 : 0;
        persist(mode, market, "SELL", fill, bd(pe.getQty()), realized, 0.0, patternReason, patternType, patternReason, bd(pe.getAvgPrice()), signalConfidence);
        positionRepo.deleteById(market);
        st.downStreak = 0; st.peakHighSinceEntry = 0.0;
        log.info("[{}] 패턴매도 | {} | 평단:{} → 현재:{} | 수익률:{} | 실현손익:{}원",
                market, patternType, String.format("%.2f", bd(pe.getAvgPrice())),
                String.format("%.2f", close), String.format("%.2f%%", pnlPct),
                String.format("%.0f", realized));
        break; // 이 마켓 처리 완료 -> 다음 마켓으로 진행 (return; 은 다른 마켓까지 skip함)
    } else {
        if (!liveOrders.isConfigured()) throw new IllegalStateException("LIVE 모드인데 업비트 키가 없습니다.");
        LiveOrderService.OrderResult r = liveOrders.placeAskMarketOrder(market, bd(pe.getQty()));
        if (!r.isFilled()) {
            persist(mode, market, "SELL_PENDING", close, bd(pe.getQty()), 0.0, 0.0, "state=" + r.state + " vol=" + r.executedVolume, patternType, patternReason);
            break;
        }
        double fill = r.avgPrice > 0 ? r.avgPrice : close;
        double gross = bd(pe.getQty()) * fill;
        double fee = gross * cfg.getFeeRate();
        double realized = (gross - fee) - (bd(pe.getQty()) * bd(pe.getAvgPrice()));
        double pnlPctLive = bd(pe.getAvgPrice()) > 0 ? ((fill - bd(pe.getAvgPrice())) / bd(pe.getAvgPrice())) * 100.0 : 0;
        persist(mode, market, "SELL", fill, bd(pe.getQty()), realized, 0.0, patternReason + " uuid=" + r.uuid, patternType, patternReason, bd(pe.getAvgPrice()), signalConfidence);
        positionRepo.deleteById(market);
        st.downStreak = 0; st.peakHighSinceEntry = 0.0;
        log.info("[{}] 패턴매도(LIVE) | {} | 평단:{} → 체결:{} | 수익률:{} | 실현손익:{}원",
                market, patternType, String.format("%.2f", bd(pe.getAvgPrice())),
                String.format("%.2f", fill), String.format("%.2f%%", pnlPctLive),
                String.format("%.0f", realized));
        break;
    }
}

// === Confidence Score 필터: 최소 점수 미달 BUY/ADD_BUY 차단 (SELL은 항상 허용) ===
double effMinConfidence = bc.getMinConfidence();
if (marketGroup != null) {
    effMinConfidence = marketGroup.getMinConfidence();
}
if ((chosen.action == SignalAction.BUY || chosen.action == SignalAction.ADD_BUY)
        && effMinConfidence > 0 && chosen.confidence < effMinConfidence) {
    log.info("[{}] 신뢰도 미달 차단 | {}={} score={} < min={} → 진입 무시",
            market, chosen.action, patternType,
            String.format("%.1f", chosen.confidence), String.format("%.1f", effMinConfidence));
    persist(mode, market, "SIGNAL_ONLY", close, 0.0, 0.0, 0.0,
            "LOW_CONFIDENCE: score=" + String.format("%.1f", chosen.confidence)
                    + " < min=" + String.format("%.1f", effMinConfidence),
            patternType, patternReason, 0, chosen.confidence);
    continue;
}

// === BUY ===
if (chosen.action == SignalAction.BUY && !open) {
    double baseOrderKrw = resolveBaseOrderKrw(bc, marketGroup);
    double orderKrw = baseOrderKrw;

    // If not enough remaining capital, allow partial buy with the remaining amount.
    // (Keeps behavior consistent with the requested "use remaining budget" rule.)
    if (orderKrw > remainCap) {
        if (remainCap >= tradeProps.getMinOrderKrw()) {
            orderKrw = remainCap;
            persist(mode, market, "BUY_PARTIAL", close, 0.0, 0.0, 0.0,
                    "CAP_PARTIAL used=" + orderKrw + " planned=" + baseOrderKrw,
                    patternType, patternReason);

            // 대시보드(Order Guard)에 사람이 이해할 수 있는 차단/조정 사유 기록
            try {
                Map<String, Object> det = new LinkedHashMap<String, Object>();
                det.put("capitalKrw", cap);
                det.put("used", used);
                det.put("remain", remainCap);
                det.put("planned", baseOrderKrw);
                det.put("applied", orderKrw);
                addDecisionLog(market, unit, chosen.action.name(), "PARTIAL",
                        "CAPITAL_PARTIAL",
                        "총 예산(capital) 범위 안에서만 매수하려고, 남은 금액으로만 부분 매수합니다.",
                        det);
            } catch (Exception ignore) {}
        } else {
            persist(mode, market, "SIGNAL_ONLY", close, 0.0, 0.0, 0.0,
                    "CAP_LIMIT remain=" + remainCap + " need=" + orderKrw,
                    patternType, patternReason);

            try {
                Map<String, Object> det = new LinkedHashMap<String, Object>();
                det.put("capitalKrw", cap);
                det.put("used", used);
                det.put("remain", remainCap);
                det.put("need", orderKrw);
                det.put("minOrderKrw", tradeProps.getMinOrderKrw());
                addDecisionLog(market, unit, chosen.action.name(), "BLOCKED",
                        "CAPITAL_LIMIT_BLOCK",
                        "총 예산(capital) 대비 남은 금액이 부족해서 매수 주문을 보내지 않았어요.",
                        det);
            } catch (Exception ignore) {}
            continue;
        }
    }

    executeBuy(mode, market, close, st.downStreak, orderKrw, patternReason, patternType, patternReason, signalConfidence);
    // log.info는 executeBuy 내부에서 persist("BUY")가 된 경우에만 의미 있음
    // executeBuy가 false 리턴해도 BUY_PENDING/BUY_FAILED로 이미 persist됨
    break; // 다음 마켓도 처리되도록 return 대신 break
}

// === ADD_BUY ===
int maxAddBuysGlobal = (bc != null ? bc.getMaxAddBuysGlobal() : tradeProps.getMaxAddBuys());
// 주의: 0은 "추가매수 금지"를 명시적으로 의미하므로 덮어쓰지 않음. 음수만 방어.
if (maxAddBuysGlobal < 0) maxAddBuysGlobal = tradeProps.getMaxAddBuys();
// 그룹별 maxAddBuys 해석
if (marketGroup != null) {
    maxAddBuysGlobal = marketGroup.getMaxAddBuys();
    if (maxAddBuysGlobal < 0) maxAddBuysGlobal = tradeProps.getMaxAddBuys();
}

// === Strategy Lock 체크: 매수 전략과 다른 전략의 ADD_BUY도 차단 ===
boolean effStrategyLock = bc.isStrategyLock();
if (marketGroup != null && marketGroup.getStrategyLock() != null) {
    effStrategyLock = marketGroup.getStrategyLock().booleanValue();
}
if (chosen.action == SignalAction.ADD_BUY && open && effStrategyLock) {
    String entryStrat = pe.getEntryStrategy();
    if (entryStrat != null && !entryStrat.isEmpty() && !entryStrat.equals(patternType)) {
        log.info("[{}] 전략잠금 차단 | 매수전략={} ≠ 추가매수전략={} → ADD_BUY 무시", market, entryStrat, patternType);
        persist(mode, market, "SIGNAL_ONLY", close, 0.0, 0.0, 0.0,
                "STRATEGY_LOCK: entry=" + entryStrat + " addBuy=" + patternType,
                "STRATEGY_LOCK", "전략잠금: 매수전략(" + entryStrat + ")과 다른 전략(" + patternType + ")의 추가매수 차단");
        try {
            Map<String, Object> det = new LinkedHashMap<String, Object>();
            det.put("entryStrategy", entryStrat);
            det.put("addBuyStrategy", patternType);
            det.put("market", market);
            addDecisionLog(market, unit, "ADD_BUY", "BLOCKED",
                    "STRATEGY_LOCK",
                    "전략잠금(ON): 매수전략(" + entryStrat + ")과 다른 전략(" + patternType + ")의 추가매수를 차단했습니다.",
                    det);
        } catch (Exception ignore) {}
        continue;
    }
}

if (chosen.action == SignalAction.ADD_BUY && open && cfg.isAddBuyOnEachExtraDown() && pe.getAddBuys() < maxAddBuysGlobal) {
    int next = pe.getAddBuys() + 1;
    double base = resolveBaseOrderKrw(bc, marketGroup);
    double orderKrw = base * Math.pow(tradeProps.getAddBuyMultiplier(), next);
    if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();

    // If not enough remaining capital, allow partial add-buy with the remaining amount.
    if (orderKrw > remainCap) {
        if (remainCap >= tradeProps.getMinOrderKrw()) {
            orderKrw = remainCap;
            persist(mode, market, "ADD_BUY_PARTIAL", close, 0.0, 0.0, 0.0,
                    "CAP_PARTIAL used=" + orderKrw + " planned=" + (base * Math.pow(tradeProps.getAddBuyMultiplier(), next)),
                    patternType, patternReason);

            try {
                Map<String, Object> det = new LinkedHashMap<String, Object>();
                det.put("capitalKrw", cap);
                det.put("used", used);
                det.put("remain", remainCap);
                det.put("planned", base * Math.pow(tradeProps.getAddBuyMultiplier(), next));
                det.put("applied", orderKrw);
                det.put("addBuyIndex", next);
                addDecisionLog(market, unit, chosen.action.name(), "PARTIAL",
                        "CAPITAL_PARTIAL",
                        "총 예산(capital) 범위 안에서만 추가매수하려고, 남은 금액으로만 부분 매수합니다.",
                        det);
            } catch (Exception ignore) {}
        } else {
            persist(mode, market, "SIGNAL_ONLY", close, 0.0, 0.0, 0.0,
                    "CAP_LIMIT remain=" + remainCap + " need=" + orderKrw,
                    patternType, patternReason);

            try {
                Map<String, Object> det = new LinkedHashMap<String, Object>();
                det.put("capitalKrw", cap);
                det.put("used", used);
                det.put("remain", remainCap);
                det.put("need", orderKrw);
                det.put("minOrderKrw", tradeProps.getMinOrderKrw());
                det.put("addBuyIndex", next);
                addDecisionLog(market, unit, chosen.action.name(), "BLOCKED",
                        "CAPITAL_LIMIT_BLOCK",
                        "총 예산(capital) 대비 남은 금액이 부족해서 추가매수 주문을 보내지 않았어요.",
                        det);
            } catch (Exception ignore) {}
            continue;
        }
    }

    executeAddBuy(mode, market, close, orderKrw, patternReason, patternType, patternReason, signalConfidence);
    log.info("[{}] 추가매수 | {} | 가격:{} | 투입:{}원 | {}회차 | 사유: {}", market, patternType,
            String.format("%.2f", close), String.format("%.0f", orderKrw), next, patternReason);
    break; // 다음 마켓도 처리되도록 return 대신 break
}

            }
          } catch (Exception marketEx) {
              // ── per-market 예외 격리: 이 마켓만 실패하고 나머지는 계속 진행 ──
              log.error("[{}] 마켓 처리 중 예외 — 다른 마켓은 계속 처리합니다.", market, marketEx);
              try {
                  String errMsg = marketEx.getMessage() != null ? marketEx.getMessage() : marketEx.getClass().getSimpleName();
                  // errMsg 너무 길면 truncate
                  if (errMsg.length() > 300) errMsg = errMsg.substring(0, 300) + "...";
                  boolean isAuth = errMsg.contains("401") || errMsg.contains("Unauthorized") || errMsg.contains("인증");
                  String action = isAuth ? "BUY_FAILED" : "SIGNAL_ONLY";
                  persist(mode, market, action, 0, 0, 0, 0, "EXCEPTION: " + errMsg);
                  Map<String, Object> det = new LinkedHashMap<String, Object>();
                  det.put("exception", errMsg);
                  det.put("type", marketEx.getClass().getSimpleName());
                  addDecisionLog(market, unit, "TICK", "BLOCKED",
                          isAuth ? "AUTH_FAILURE" : "MARKET_EXCEPTION",
                          isAuth ? "API 키 인증 실패 (401). 업비트에서 키 상태를 확인하세요."
                                 : "마켓 처리 중 오류: " + errMsg,
                          det);
              } catch (Exception ignore) {}
          }
        }

    }

    /**
     * 신규 진입(BUY) 실행.
     * - PAPER: 슬리피지+수수료 반영하여 가상 체결
     * - LIVE: 업비트 price(bid) 주문 후 done 확정 시 포지션 생성
     */
    private boolean executeBuy(String mode, String market, double close, int downStreak, double orderKrw, String note, String patternType, String patternReason, double confidence) {
        if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();

        if ("PAPER".equals(mode)) {
            double fee = orderKrw * cfg.getFeeRate();
            double net = orderKrw - fee;
            double fill = close * (1.0 + tradeProps.getSlippageRate());
            double qty = net / fill;

            PositionEntity np = new PositionEntity();
            np.setMarket(market);
            np.setQty(qty);
            np.setAvgPrice(fill);
            np.setAddBuys(0);
            np.setOpenedAt(Instant.now());
            np.setEntryStrategy(patternType);
            positionRepo.save(np);

            persist(mode, market, "BUY", fill, qty, 0.0, 0.0, note + " DownStreak=" + downStreak, patternType, patternReason, 0, confidence);
            log.info("[{}] 매수 | {} | 가격:{} | 투입:{}원 | 사유: {}",
                    market, patternType, String.format("%.2f", fill), String.format("%.0f", orderKrw), note);
            // 트레일링 스탑용 peakHigh 초기화
            MarketState mst = states.get(market);
            if (mst != null) mst.peakHighSinceEntry = fill;
            return true;
        }

        // LIVE
        if (!liveOrders.isConfigured()) throw new IllegalStateException("LIVE 모드인데 업비트 키가 없습니다.");

        // 주문 전 원화 잔고 체크(주문 요청 자체를 보내지 않음)
        // - capital(총 예산)과 별개로, 실제 지갑의 Available KRW가 부족하면 업비트가 4xx를 줄 수 있으니 선제 차단
        double liveAvail = getLiveAvailableKrwSafe();
        if (liveAvail > 0 && liveAvail + 0.0001 < orderKrw) {
            persist(mode, market, "BUY_BLOCKED", close, 0.0, 0.0, 0.0,
                    "INSUFFICIENT_KRW avail=" + liveAvail + " need=" + orderKrw,
                    patternType, patternReason);
            try {
                Map<String, Object> det = new LinkedHashMap<String, Object>();
                det.put("availableKrw", liveAvail);
                det.put("needKrw", orderKrw);
                addDecisionLog(market, null, "BUY", "BLOCKED",
                        "INSUFFICIENT_KRW_BLOCK",
                        "원화 잔고(Available KRW)가 부족해서 매수 주문을 보내지 않았어요.",
                        det);
            } catch (Exception ignore) {}
            return false;
        }

        LiveOrderService.OrderResult r = liveOrders.placeBidPriceOrder(market, orderKrw);
        if (!r.isFilled()) {
            persist(mode, market, "BUY_PENDING", close, 0.0, 0.0, 0.0, "state=" + r.state + " vol=" + r.executedVolume, patternType, patternReason);
            return false;
        }

        double fill = r.avgPrice > 0 ? r.avgPrice : close;
        double qty = r.executedVolume;
        if (qty <= 0) {
            persist(mode, market, "BUY_FAILED", close, 0.0, 0.0, 0.0, "executedVolume=0", patternType, patternReason);
            return false;
        }

        PositionEntity np = new PositionEntity();
        np.setMarket(market);
        np.setQty(qty);
        np.setAvgPrice(fill);
        np.setAddBuys(0);
        np.setOpenedAt(Instant.now());
        np.setEntryStrategy(patternType);
        positionRepo.save(np);

        persist(mode, market, "BUY", fill, qty, 0.0, 0.0, note + " uuid=" + r.uuid, patternType, patternReason, 0, confidence);
        log.info("[{}] 매수 체결 | {} | 체결가:{} | 수량:{} | 투입:{}원 | state={} | 사유: {}",
                market, patternType, String.format("%.2f", fill), String.format("%.8f", qty),
                String.format("%.0f", orderKrw), r.state, note);
        // 트레일링 스탑용 peakHigh 초기화
        MarketState mst = states.get(market);
        if (mst != null) mst.peakHighSinceEntry = fill;
        return true;
    }

    /**
     * 추가매수(ADD_BUY) 실행.
     * - PAPER/LIVE 동일하게 평균단가 재계산 후 Position 업데이트
     */
    private void executeAddBuy(String mode, String market, double close, double orderKrw, String note, String patternType, String patternReason, double confidence) {
        PositionEntity pe = positionRepo.findById(market).orElse(null);
        if (pe == null || bd(pe.getQty()) <= 0) return;
        int next = pe.getAddBuys() + 1;

        if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();

        if ("PAPER".equals(mode)) {
            double fee = orderKrw * cfg.getFeeRate();
            double net = orderKrw - fee;
            double fill = close * (1.0 + tradeProps.getSlippageRate());
            double addQty = net / fill;

            double newQty = bd(pe.getQty()) + addQty;
            double newAvg = ((bd(pe.getQty()) * bd(pe.getAvgPrice())) + (addQty * fill)) / newQty;

            pe.setQty(newQty);
            pe.setAvgPrice(newAvg);
            pe.setAddBuys(next);
            positionRepo.save(pe);

            persist(mode, market, "ADD_BUY", fill, addQty, 0.0, 0.0, "addBuys=" + next, patternType, patternReason, 0, confidence);
            return;
        }

        if (!liveOrders.isConfigured()) throw new IllegalStateException("LIVE 모드인데 업비트 키가 없습니다.");

        double liveAvail = getLiveAvailableKrwSafe();
        if (liveAvail > 0 && liveAvail + 0.0001 < orderKrw) {
            persist(mode, market, "ADD_BUY_BLOCKED", close, 0.0, 0.0, 0.0,
                    "INSUFFICIENT_KRW avail=" + liveAvail + " need=" + orderKrw,
                    patternType, patternReason);
            try {
                Map<String, Object> det = new LinkedHashMap<String, Object>();
                det.put("availableKrw", liveAvail);
                det.put("needKrw", orderKrw);
                addDecisionLog(market, null, "ADD_BUY", "BLOCKED",
                        "INSUFFICIENT_KRW_BLOCK",
                        "원화 잔고(Available KRW)가 부족해서 추가매수 주문을 보내지 않았어요.",
                        det);
            } catch (Exception ignore) {}
            return;
        }

        LiveOrderService.OrderResult r = liveOrders.placeBidPriceOrder(market, orderKrw);
        if (!r.isFilled()) {
            persist(mode, market, "ADD_BUY_PENDING", close, 0.0, 0.0, 0.0, "state=" + r.state + " vol=" + r.executedVolume, patternType, patternReason);
            return;
        }

        double fill = r.avgPrice > 0 ? r.avgPrice : close;
        double addQty = r.executedVolume;
        if (addQty <= 0) {
            persist(mode, market, "ADD_BUY_FAILED", close, 0.0, 0.0, 0.0, "executedVolume=0", patternType, patternReason);
            return;
        }

        double newQty = bd(pe.getQty()) + addQty;
        double newAvg = ((bd(pe.getQty()) * bd(pe.getAvgPrice())) + (addQty * fill)) / newQty;

        pe.setQty(newQty);
        pe.setAvgPrice(newAvg);
        pe.setAddBuys(next);
        positionRepo.save(pe);

        persist(mode, market, "ADD_BUY", fill, addQty, 0.0, 0.0, "addBuys=" + next + " uuid=" + r.uuid, patternType, patternReason, 0, confidence);
    }

    /** 총 실현 PnL 합계 (드로다운 서킷브레이커 판정용) */
    private double calcTotalRealizedPnl() {
        try {
            List<TradeEntity> sellTrades = tradeRepo.findAll();
            double totalPnl = 0.0;
            for (TradeEntity t : sellTrades) {
                if ("SELL".equals(t.getAction()) && t.getPnlKrw() != null) {
                    totalPnl += t.getPnlKrw().doubleValue();
                }
            }
            return totalPnl;
        } catch (Exception e) {
            log.warn("calcTotalRealizedPnl error: {}", e.getMessage());
            return 0.0;
        }
    }

    /** 금일 실현 PnL 합계 (일일 손실 한도 판정용) */
    private double calcTodayRealizedPnl() {
        try {
            long todayStartMs = LocalDate.now(ZoneId.of("Asia/Seoul"))
                    .atStartOfDay(ZoneId.of("Asia/Seoul"))
                    .toInstant().toEpochMilli();
            List<TradeEntity> allTrades = tradeRepo.findAll();
            double todayPnl = 0.0;
            for (TradeEntity t : allTrades) {
                if ("SELL".equals(t.getAction()) && t.getTsEpochMs() >= todayStartMs && t.getPnlKrw() != null) {
                    todayPnl += t.getPnlKrw().doubleValue();
                }
            }
            return todayPnl;
        } catch (Exception e) {
            log.warn("calcTodayRealizedPnl error: {}", e.getMessage());
            return 0.0;
        }
    }

    private double calcOpenCostBasis() {
        double sum = 0.0;
        for (MarketConfigEntity mc : marketRepo.findAllByOrderByMarketAsc()) {
            PositionEntity pe = positionRepo.findById(mc.getMarket()).orElse(null);
            if (pe != null && bd(pe.getQty()) > 0) {
                sum += bd(pe.getQty()) * bd(pe.getAvgPrice());
            }
        }
        return sum;
    }

    private void persist(String mode, String market, String action, double price, double qty, double pnl, double roi, String note, String patternType, String patternReason) {
        persist(mode, market, action, price, qty, pnl, roi, note, patternType, patternReason, 0, 0);
    }

    /** avgBuyPrice 포함 (매도 시 사용) */
    private void persist(String mode, String market, String action, double price, double qty, double pnl, double roi, String note, String patternType, String patternReason, double avgBuyPrice) {
        persist(mode, market, action, price, qty, pnl, roi, note, patternType, patternReason, avgBuyPrice, 0);
    }

    /** confidence 포함 (전체) */
    private void persist(String mode, String market, String action, double price, double qty, double pnl, double roi, String note, String patternType, String patternReason, double avgBuyPrice, double confidence) {
        TradeEntity t = new TradeEntity();
        t.setTsEpochMs(System.currentTimeMillis());
        t.setMarket(market);
        t.setAction(action);
        t.setPrice(price);
        t.setQty(qty);
        t.setPnlKrw(pnl);
        // roi: 실제 수익률 계산 (avgBuyPrice가 있으면 자동 계산)
        if (roi == 0 && avgBuyPrice > 0 && price > 0) {
            roi = ((price - avgBuyPrice) / avgBuyPrice) * 100.0;
        }
        t.setRoiPercent(roi);
        t.setMode(mode);
        t.setPatternType(truncate(patternType, 64));
        t.setPatternReason(truncate(patternReason, 512));
        t.setNote(truncate(note, 512));
        if (avgBuyPrice > 0) {
            t.setAvgBuyPrice(avgBuyPrice);
        }
        if (confidence > 0) {
            t.setConfidence(confidence);
        }
        t.setCandleUnitMin(currentTickUnitMin);
        tradeRepo.save(t);
    }

    private void persist(String mode, String market, String action, double price, double qty, double pnl, double roi, String note) {
        persist(mode, market, action, price, qty, pnl, roi, note, null, null, 0);
    }

    /** DB 컬럼 길이 초과 방지 */
    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    /**
     * 업비트 실제 보유 자산 ↔ DB 포지션 동기화.
     *
     * 다음 상황을 자동 복구합니다:
     * - 시장가 매수 후 state=cancel (잔여분 취소)로 인해 봇이 BUY_PENDING으로 기록하고 포지션 미생성한 경우
     * - 봇 재시작으로 포지션 DB가 리셋된 경우
     * - 수동으로 업비트 앱에서 매수한 코인을 봇이 관리하도록 하고 싶은 경우
     *
     * 동기화 방향: 업비트 → 봇 DB (업비트를 source of truth로 사용)
     * - 업비트에 보유 중인데 봇 DB에 없으면 → 포지션 생성
     * - 봇 DB에 포지션 있는데 업비트에 없으면 → 포지션 삭제 (수동 매도 등)
     */
    private void syncPositionsFromUpbit(List<MarketConfigEntity> enabledMarkets) {
        log.info("[SYNC] 업비트 ↔ 봇 포지션 동기화 시작 (활성 마켓 {}개)", enabledMarkets.size());
        try {
            List<UpbitAccount> accounts = privateClient.getAccounts();
            if (accounts == null || accounts.isEmpty()) {
                log.info("[SYNC] 업비트 계정에 보유 자산 없음 → 동기화 스킵");
                return;
            }
            log.info("[SYNC] 업비트 보유 자산 {}개 조회됨", accounts.size());

            // 활성 마켓의 currency 집합 (예: KRW-ADA → ADA)
            Set<String> enabledCurrencies = new HashSet<String>();
            Map<String, String> currencyToMarket = new HashMap<String, String>();
            for (MarketConfigEntity mc : enabledMarkets) {
                String m = mc.getMarket(); // "KRW-ADA"
                if (m != null && m.startsWith("KRW-")) {
                    String cur = m.substring(4); // "ADA"
                    enabledCurrencies.add(cur);
                    currencyToMarket.put(cur, m);
                }
            }
            log.info("[SYNC] 활성 통화: {}", enabledCurrencies);

            // 업비트 보유 자산 중 활성 마켓에 해당하는 것만 필터
            Map<String, UpbitAccount> upbitHoldings = new HashMap<String, UpbitAccount>();
            for (UpbitAccount a : accounts) {
                if (a.currency == null || "KRW".equals(a.currency)) continue;
                if (!enabledCurrencies.contains(a.currency)) continue;
                java.math.BigDecimal bal = a.balanceAsBigDecimal();
                if (bal.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                    log.info("[SYNC] {} 잔고 0 → 스킵", a.currency);
                    continue;
                }
                upbitHoldings.put(a.currency, a);
                log.info("[SYNC] 업비트 보유 확인: {} | balance={} | locked={} | avg_buy_price={}",
                        a.currency, a.balance, a.locked, a.avg_buy_price);
            }

            if (upbitHoldings.isEmpty()) {
                log.info("[SYNC] 활성 마켓 중 업비트 보유 자산 없음 → 동기화 불필요");
            }

            int synced = 0;
            int skipped = 0;

            // 1) 업비트에 있는데 봇 DB에 없으면 → 포지션 복구
            for (Map.Entry<String, UpbitAccount> entry : upbitHoldings.entrySet()) {
                String currency = entry.getKey();
                UpbitAccount acct = entry.getValue();
                String market = currencyToMarket.get(currency);
                if (market == null) continue;

                Optional<PositionEntity> existing = positionRepo.findById(market);
                if (existing.isPresent()) {
                    log.info("[SYNC] {} 이미 봇 DB에 포지션 존재 → 스킵 (DB수량={}, DB평균가={})",
                            market, existing.get().getQty().toPlainString(), existing.get().getAvgPrice().toPlainString());
                    skipped++;
                    continue;
                }

                java.math.BigDecimal qty = acct.balanceAsBigDecimal().add(acct.lockedAsBigDecimal());
                java.math.BigDecimal avgPrice = acct.avgBuyPriceAsBigDecimal();

                if (qty.compareTo(java.math.BigDecimal.ZERO) <= 0) continue;

                PositionEntity np = new PositionEntity();
                np.setMarket(market);
                np.setQty(qty);
                np.setAvgPrice(avgPrice);
                np.setAddBuys(0);
                np.setOpenedAt(Instant.now());
                positionRepo.save(np);
                synced++;

                log.warn("[SYNC] ★ 포지션 복구: {} | 수량={} | 평균가={} | (업비트에 보유 중이나 봇 DB에 누락됨)",
                        market, qty.toPlainString(), avgPrice.toPlainString());

                // 거래 기록에도 남김
                persist("LIVE", market, "BUY_SYNC", avgPrice.doubleValue(), qty.doubleValue(),
                        0.0, 0.0, "업비트 보유자산에서 자동 복구 (avg_buy_price=" + avgPrice.toPlainString() + ")",
                        "POSITION_SYNC", "봇 DB 누락 포지션 복구");
            }

            // 2) 봇 DB에 있는데 업비트에 없으면 → 포지션 삭제 (수동 매도 등)
            int removed = 0;
            for (PositionEntity pos : positionRepo.findAll()) {
                String market = pos.getMarket();
                if (market == null || !market.startsWith("KRW-")) continue;
                String currency = market.substring(4);
                if (!enabledCurrencies.contains(currency)) continue;

                if (!upbitHoldings.containsKey(currency)) {
                    log.warn("[SYNC] ★ 포지션 삭제: {} | (업비트에 보유하지 않으나 봇 DB에 남아있음)", market);
                    persist("LIVE", market, "SELL_SYNC", 0, pos.getQty().doubleValue(),
                            0.0, 0.0, "업비트 미보유 → 봇 포지션 자동 삭제",
                            "POSITION_SYNC", "업비트 미보유 포지션 정리");
                    positionRepo.deleteById(market);
                    removed++;
                }
            }

            log.info("[SYNC] 동기화 완료 — 복구:{} / 이미존재:{} / 삭제:{}", synced, skipped, removed);

        } catch (Exception e) {
            log.error("[SYNC] 포지션 동기화 실패: {}", e.getMessage(), e);
        }
    }


    private void loadStateFromDb(String market, MarketState st) {
    if (market == null || st == null) return;
    try {
        StrategyStateEntity e = stateRepo.findById(market).orElse(null);
        if (e == null) return;
        if (e.getLastCandleUtc() != null) st.lastCandleUtc = e.getLastCandleUtc();
        if (e.getLastPrice() != null) st.lastPrice = e.getLastPrice();
        if (e.getDownStreak() != null) st.downStreak = e.getDownStreak();
    } catch (Exception ignore) {}
}

private void persistStateToDb(String market, MarketState st) {
    if (market == null || st == null) return;
    try {
        StrategyStateEntity e = stateRepo.findById(market).orElse(null);
        if (e == null) e = new StrategyStateEntity(market);
        e.setLastCandleUtc(st.lastCandleUtc);
        e.setLastPrice(st.lastPrice);
        e.setDownStreak(st.downStreak);
        stateRepo.save(e);
    } catch (Exception ignore) {}
}

private static Instant parseUpbitUtc(String isoUtcNoZone) {
    if (isoUtcNoZone == null || isoUtcNoZone.isEmpty()) return null;
    // Upbit candle_date_time_utc is typically like "2026-02-15T10:30:00"
    LocalDateTime ldt = LocalDateTime.parse(isoUtcNoZone);
    return ldt.toInstant(ZoneOffset.UTC);
}

private static List<UpbitCandle> sortCandlesAsc(List<UpbitCandle> in) {
    List<UpbitCandle> out = new ArrayList<UpbitCandle>(in);
    Collections.sort(out, new Comparator<UpbitCandle>() {
        @Override public int compare(UpbitCandle o1, UpbitCandle o2) {
            Instant a = parseUpbitUtc(o1 == null ? null : o1.candle_date_time_utc);
            Instant b = parseUpbitUtc(o2 == null ? null : o2.candle_date_time_utc);
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            return a.compareTo(b);
        }
    });
    return out;
}

/** 신호 우선순위: SELL(3) > ADD_BUY(2) > BUY(1) — 멀티 인터벌 그룹 간 병합에 사용 */
private static int signalPriority(SignalAction a) {
    if (a == null) return 0;
    switch (a) {
        case SELL: return 3;
        case ADD_BUY: return 2;
        case BUY: return 1;
        default: return 0;
    }
}

/**
 * STALE 판정: 정상 스케줄러 tick 보호용.
 * 봉 마감 후 60초(기본) 이상 경과 시 매수 차단.
 * (Start 즉시 tick은 sellOnlyTick 플래그로 별도 제어)
 */
private boolean isStaleForEntry(UpbitCandle latest, int unit) {
    if (latest == null || latest.candle_date_time_utc == null) return false;
    if (staleEntryTtlSeconds <= 0) return false;
    Instant candleStart = parseUpbitUtc(latest.candle_date_time_utc);
    if (candleStart == null) return false;

    if (unit <= 0) unit = 5;
    Instant candleEnd = candleStart.plusSeconds((long) unit * 60);

    long ageSec = Duration.between(candleEnd, Instant.now()).getSeconds();
    if (ageSec < 0) ageSec = 0;
    return ageSec > staleEntryTtlSeconds;
}

/**
 * 주어진 마켓이 속한 전략 그룹을 찾아 반환합니다.
 * 그룹이 없거나 해당 마켓이 어떤 그룹에도 속하지 않으면 null을 반환합니다.
 * (null = 글로벌 bot_config 설정 사용)
 */
private StrategyGroupEntity findGroupForMarket(String market) {
    if (market == null) return null;
    List<StrategyGroupEntity> groups = strategyGroupRepo.findAllByOrderBySortOrderAsc();
    if (groups == null || groups.isEmpty()) return null;
    for (StrategyGroupEntity g : groups) {
        if (g.getMarketsList().contains(market)) {
            return g;
        }
    }
    return null;
}

static class MarketState {
        volatile String lastCandleUtc = null;
        volatile double lastPrice = 0.0;
        volatile int downStreak = 0;
        /** 진입 이후 최고가 — 티커에서 실시간 갱신하여 트레일링 스탑에 사용 */
        volatile double peakHighSinceEntry = 0.0;

        MarketState(String market) {}
    }
}