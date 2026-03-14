package com.example.upbit.backtest;

import com.example.upbit.config.StrategyProperties;
import com.example.upbit.config.TradeProperties;
import com.example.upbit.db.OptimizationResultEntity;
import com.example.upbit.db.OptimizationResultRepository;
import com.example.upbit.db.PositionEntity;
import com.example.upbit.market.CandleCacheService;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.strategy.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 배치 최적화 서비스: 시그널 사전계산(Signal Tape) + 밀집 배열 리플레이.
 *
 * 최적화 포인트:
 * 1. MAX_WINDOW=200 (지표 계산 O(n) 보장)
 * 2. 시그널 테이프 = double[] closes + long[] timestamps + 희소 시그널 맵
 * 3. 리플레이: primitive 배열 순회 → JIT 최적화 극대화
 * 4. self-contained 전략: 매 5캔들마다만 has_pos 평가 (80% 감소)
 */
@Service
public class BatchOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(BatchOptimizationService.class);

    // ===== 파라미터 그리드 =====
    private static final String[] MARKETS = {"KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-ADA"};
    private static final int[] INTERVALS = {5, 15, 30, 60, 240, 1440};
    private static final double[] TP_VALUES = {0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 10.0, 15.0, 20.0, 30.0};
    private static final double[] SL_VALUES = {0.3, 0.5, 0.8, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 10.0, 15.0, 20.0, 30.0};
    private static final int[] MAX_ADD_BUYS = {0, 1, 2, 3};
    private static final double[] MIN_CONFIDENCES = {0, 5, 6, 7, 8};
    private static final boolean[] STRATEGY_LOCKS = {false, true};
    private static final int[] TIME_STOPS = {0, 240, 480, 720, 1440, 2880, 4320, 10080};
    private static final int[] EMA_PERIODS = {0, 20, 50, 75, 100};

    private static final double DEFAULT_CAPITAL = 1000000.0;
    private static final int RISK_COMBOS = TP_VALUES.length * SL_VALUES.length
            * MAX_ADD_BUYS.length * MIN_CONFIDENCES.length * STRATEGY_LOCKS.length * TIME_STOPS.length;

    // 지표 계산 윈도우 제한 (EMA-100이 수렴하려면 ~150개면 충분)
    private static final int MAX_WINDOW = 200;
    // self-contained 전략의 has_pos 평가 간격 (5캔들마다)
    private static final int HAS_POS_EVAL_INTERVAL = 5;

    private final StrategyFactory strategyFactory;
    private final CandleCacheService candleCacheService;
    private final StrategyProperties strategyCfg;
    private final TradeProperties tradeProps;
    private final OptimizationResultRepository resultRepo;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalCombinations = new AtomicLong(0);
    private final AtomicLong completedCombinations = new AtomicLong(0);
    private volatile String currentRunId = "";
    private volatile String statusMessage = "";

    private final ConcurrentSkipListSet<ResultEntry> topResults =
            new ConcurrentSkipListSet<ResultEntry>(new Comparator<ResultEntry>() {
                @Override
                public int compare(ResultEntry a, ResultEntry b) {
                    int c = Double.compare(b.roi, a.roi);
                    if (c != 0) return c;
                    return Long.compare(a.id, b.id);
                }
            });
    private static final int TOP_N = 500;
    private static final int TOP_N_PER_MARKET = 100;
    private final AtomicLong resultIdSeq = new AtomicLong(0);

    /** 마켓별 Top N 결과 (글로벌 Top과 별도 관리) */
    private final ConcurrentHashMap<String, ConcurrentSkipListSet<ResultEntry>> topResultsByMarket =
            new ConcurrentHashMap<String, ConcurrentSkipListSet<ResultEntry>>();

    private final ConcurrentHashMap<String, FastTape> tapeCache = new ConcurrentHashMap<String, FastTape>();

    public BatchOptimizationService(StrategyFactory strategyFactory,
                                     CandleCacheService candleCacheService,
                                     StrategyProperties strategyCfg,
                                     TradeProperties tradeProps,
                                     OptimizationResultRepository resultRepo) {
        this.strategyFactory = strategyFactory;
        this.candleCacheService = candleCacheService;
        this.strategyCfg = strategyCfg;
        this.tradeProps = tradeProps;
        this.resultRepo = resultRepo;
    }

    public boolean isRunning() { return running.get(); }
    public long getTotalCombinations() { return totalCombinations.get(); }
    public long getCompletedCombinations() { return completedCombinations.get(); }
    public String getCurrentRunId() { return currentRunId; }
    public String getStatusMessage() { return statusMessage; }

    public String startOptimization() {
        if (!running.compareAndSet(false, true)) return currentRunId;
        currentRunId = UUID.randomUUID().toString().substring(0, 8);
        topResults.clear();
        topResultsByMarket.clear();
        resultIdSeq.set(0);
        completedCombinations.set(0);
        totalCombinations.set(0);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runOptimization(currentRunId);
                } catch (Exception e) {
                    log.error("최적화 실행 오류", e);
                    statusMessage = "오류: " + e.getMessage();
                } finally {
                    running.set(false);
                }
            }
        }, "batch-optimization");
        t.setDaemon(true);
        t.start();
        return currentRunId;
    }

    // ===== 최적화된 시그널 테이프 (밀집 배열 + 희소 시그널) =====

    /** 희소 시그널 이벤트: 실제 시그널이 있는 캔들만 기록 */
    static class SparseSignal {
        int candleIdx;          // closes/timestamps 배열 내 인덱스
        byte type;              // 1=BUY(no-pos), 2=SELL(has-pos), 3=ADD_BUY(has-pos)
        double confidence;
        String patternType;
    }

    /** 밀집 배열 기반 시그널 테이프 */
    static class FastTape {
        StrategyType strategy;
        String market;
        int interval;
        int emaPeriod;
        boolean isBuyOnly;

        // 밀집 배열 (모든 캔들)
        double[] closes;
        long[] timestamps;
        int length;

        // 희소 시그널 (시그널 있는 캔들만)
        SparseSignal[] signals;
        int signalCount;
    }

    // ===== 메인 로직 =====

    private void runOptimization(String runId) {
        statusMessage = "캔들 캐시 로딩 중...";
        log.info("=== 최적화 시작 v3 (runId={}) ===", runId);

        Map<String, Map<Integer, List<UpbitCandle>>> allCandles = candleCacheService.getAllCached();
        if (allCandles.isEmpty()) {
            statusMessage = "캔들 캐시 비어있음";
            return;
        }

        List<StrategyType> buyStrategies = new ArrayList<StrategyType>();
        for (StrategyType st : StrategyType.values()) {
            if (st.isDeprecated()) continue;
            if (!st.isSellOnly()) buyStrategies.add(st);
        }

        // 테이프 작업 생성
        List<TapeTask> tasks = new ArrayList<TapeTask>();
        for (StrategyType st : buyStrategies) {
            boolean configurable = "CONFIGURABLE".equals(st.emaTrendFilterMode());
            int[] emaGrid = configurable ? EMA_PERIODS : new int[]{0};
            for (String market : MARKETS) {
                Map<Integer, List<UpbitCandle>> mc = allCandles.get(market);
                if (mc == null) continue;
                for (int interval : INTERVALS) {
                    List<UpbitCandle> candles = mc.get(interval);
                    if (candles == null || candles.size() < 10) continue;
                    for (int ema : emaGrid) {
                        TapeTask t = new TapeTask();
                        t.strategy = st;
                        t.market = market;
                        t.interval = interval;
                        t.emaPeriod = ema;
                        t.candles = candles;
                        tasks.add(t);
                    }
                }
            }
        }

        int tapeCount = tasks.size();
        totalCombinations.set((long) tapeCount * RISK_COMBOS);
        log.info("테이프: {}, 위험조합: {}, 총: {}", tapeCount, RISK_COMBOS, (long) tapeCount * RISK_COMBOS);

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(cores);
        log.info("코어: {}", cores);

        statusMessage = String.format("최적화 중... (%d 테이프 × %d 조합)", tapeCount, RISK_COMBOS);
        long startTime = System.currentTimeMillis();

        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (final TapeTask task : tasks) {
            futures.add(executor.submit(new Runnable() {
                @Override
                public void run() {
                    processTask(task);
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { log.error("작업 오류", e); }
        }
        executor.shutdown();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("=== 완료: {}분 {}초 ===", elapsed / 60000, (elapsed % 60000) / 1000);

        statusMessage = "결과 저장 중...";
        saveTopResults(runId);

        statusMessage = String.format("완료! %d 조합, %d분 %d초, Top %d 저장",
                completedCombinations.get(), elapsed / 60000, (elapsed % 60000) / 1000,
                Math.min(TOP_N, topResults.size()));
        log.info(statusMessage);
    }

    static class TapeTask {
        StrategyType strategy;
        String market;
        int interval;
        int emaPeriod;
        List<UpbitCandle> candles;
    }

    private void processTask(TapeTask task) {
        try {
            FastTape tape = generateFastTape(task);
            if (tape == null || tape.signalCount == 0) {
                completedCombinations.addAndGet(RISK_COMBOS);
                return;
            }

            String cacheKey = task.strategy.name() + "_" + task.market + "_" + task.interval + "_" + task.emaPeriod;
            tapeCache.put(cacheKey, tape);

            // 리플레이 상수 캐시
            final double slippage = tradeProps.getSlippageRate();
            final double feeRate = strategyCfg.getFeeRate();
            final double minOrderKrw = tradeProps.getMinOrderKrw();
            final double addBuyMult = tradeProps.getAddBuyMultiplier();

            for (double tp : TP_VALUES) {
                for (double sl : SL_VALUES) {
                    for (int maxAdd : MAX_ADD_BUYS) {
                        for (double minConf : MIN_CONFIDENCES) {
                            for (boolean lock : STRATEGY_LOCKS) {
                                for (int timeStop : TIME_STOPS) {
                                    replayFastTape(tape, tp, sl, maxAdd, minConf, lock, timeStop,
                                            slippage, feeRate, minOrderKrw, addBuyMult);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("작업 오류: {} {} {}분: {}", task.strategy, task.market, task.interval, e.getMessage());
            completedCombinations.addAndGet(RISK_COMBOS);
        }
    }

    // ===== 테이프 생성 (전략 1회 평가) =====

    private FastTape generateFastTape(TapeTask task) {
        List<UpbitCandle> candles = task.candles;
        StrategyType strategy = task.strategy;
        boolean selfContained = strategy.isSelfContained();
        boolean buyOnly = strategy.isBuyOnly();

        Map<String, Integer> emaMap = new HashMap<String, Integer>();
        emaMap.put(strategy.name(), task.emaPeriod);
        List<StrategyType> singleStrat = Collections.singletonList(strategy);

        int n = candles.size() - 1; // idx 1부터 시작이므로
        if (n < 1) return null;

        double[] closes = new double[n];
        long[] timestamps = new long[n];
        List<SparseSignal> signalList = new ArrayList<SparseSignal>();

        for (int idx = 1; idx < candles.size(); idx++) {
            UpbitCandle cur = candles.get(idx);
            if (cur == null || cur.candle_date_time_utc == null) {
                closes[idx - 1] = 0;
                timestamps[idx - 1] = 0;
                continue;
            }

            int arrIdx = idx - 1;
            closes[arrIdx] = cur.trade_price;
            try {
                timestamps[arrIdx] = LocalDateTime.parse(cur.candle_date_time_utc)
                        .toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (Exception e) {
                timestamps[arrIdx] = 0;
            }

            // 윈도우 생성 (크기 제한)
            int windowEnd = Math.min(idx, candles.size());
            if (windowEnd < 2) continue;
            int windowStart = Math.max(0, windowEnd - MAX_WINDOW);
            List<UpbitCandle> window = candles.subList(windowStart, windowEnd);

            // (A) BUY 시그널 (position 없을 때)
            StrategyContext ctxNone = new StrategyContext(
                    task.market, task.interval, window, null, 0, emaMap);
            SignalEvaluator.Result buyResult = SignalEvaluator.evaluateStrategies(
                    singleStrat, strategyFactory, ctxNone);

            if (!buyResult.isEmpty() && buyResult.signal.action == SignalAction.BUY) {
                SparseSignal sig = new SparseSignal();
                sig.candleIdx = arrIdx;
                sig.type = 1; // BUY
                sig.confidence = buyResult.confidence;
                sig.patternType = buyResult.patternType;
                signalList.add(sig);
            }

            // (B) SELL/ADD_BUY 시그널 (self-contained, position 있을 때)
            // 매 HAS_POS_EVAL_INTERVAL 캔들마다만 평가 (성능 최적화)
            if (selfContained && (idx % HAS_POS_EVAL_INTERVAL == 0)) {
                PositionEntity syntheticPos = new PositionEntity();
                syntheticPos.setQty(BigDecimal.valueOf(1.0));
                syntheticPos.setAvgPrice(BigDecimal.valueOf(cur.trade_price));
                syntheticPos.setAddBuys(0);
                syntheticPos.setEntryStrategy(strategy.name());

                StrategyContext ctxOpen = new StrategyContext(
                        task.market, task.interval, window, syntheticPos, 0, emaMap);
                SignalEvaluator.Result sellResult = SignalEvaluator.evaluateStrategies(
                        singleStrat, strategyFactory, ctxOpen);

                if (!sellResult.isEmpty()) {
                    SignalAction act = sellResult.signal.action;
                    if (act == SignalAction.SELL || act == SignalAction.ADD_BUY) {
                        SparseSignal sig = new SparseSignal();
                        sig.candleIdx = arrIdx;
                        sig.type = (act == SignalAction.SELL) ? (byte) 2 : (byte) 3;
                        sig.confidence = sellResult.confidence;
                        sig.patternType = sellResult.patternType;
                        signalList.add(sig);
                    }
                }
            }
        }

        FastTape tape = new FastTape();
        tape.strategy = strategy;
        tape.market = task.market;
        tape.interval = task.interval;
        tape.emaPeriod = task.emaPeriod;
        tape.isBuyOnly = buyOnly;
        tape.closes = closes;
        tape.timestamps = timestamps;
        tape.length = n;
        tape.signals = signalList.toArray(new SparseSignal[0]);
        tape.signalCount = tape.signals.length;

        return tape;
    }

    // ===== 초고속 리플레이 (primitive 배열 기반) =====

    private void replayFastTape(FastTape tape, double tpPct, double slPct,
                                  int maxAddBuys, double minConfidence,
                                  boolean strategyLock, int timeStopMinutes,
                                  double slippage, double feeRate, double minOrderKrw, double addBuyMult) {
        try {
            double capital = DEFAULT_CAPITAL;
            double baseOrderKrw = DEFAULT_CAPITAL * 0.9;
            boolean isBuyOnly = tape.isBuyOnly;

            double qty = 0, avg = 0;
            int addBuyCount = 0;
            String entryStrat = null;
            long entryTsMs = 0;

            int sellCount = 0, winCount = 0;
            int tpSells = 0, slSells = 0, patternSells = 0;

            // 희소 시그널 인덱스 (정렬된 시그널 배열을 따라감)
            int sigPtr = 0;
            SparseSignal[] signals = tape.signals;
            int sigLen = tape.signalCount;

            double[] closes = tape.closes;
            long[] timestamps = tape.timestamps;
            int len = tape.length;

            // Period boundaries for 3-month and 1-month metrics
            long maxTs = timestamps[len - 1];
            long boundary3m = maxTs - 90L * 24 * 60 * 60 * 1000;
            long boundary1m = maxTs - 30L * 24 * 60 * 60 * 1000;
            double capital3mStart = -1;
            double capital1mStart = -1;
            int sells3m = 0, wins3m = 0;
            int sells1m = 0, wins1m = 0;

            for (int i = 0; i < len; i++) {
                double close = closes[i];
                if (close <= 0) continue;
                long ts = timestamps[i];
                if (capital3mStart < 0 && ts >= boundary3m) capital3mStart = capital + (qty > 0 ? qty * close : 0);
                if (capital1mStart < 0 && ts >= boundary1m) capital1mStart = capital + (qty > 0 ? qty * close : 0);
                boolean open = qty > 0;

                // === TP/SL ===
                if (open && avg > 0) {
                    double pnlPct = ((close - avg) / avg) * 100.0;
                    if (slPct > 0 && pnlPct <= -slPct) {
                        double fill = close * (1.0 - slippage);
                        double gross = qty * fill;
                        double fee = gross * feeRate;
                        double realized = (gross - fee) - (qty * avg);
                        sellCount++; slSells++;
                        if (realized > 0) winCount++;
                        long sellTs = timestamps[i];
                        if (sellTs >= boundary3m) { sells3m++; if (realized > 0) wins3m++; }
                        if (sellTs >= boundary1m) { sells1m++; if (realized > 0) wins1m++; }
                        capital += (gross - fee);
                        qty = 0; avg = 0; addBuyCount = 0; entryStrat = null; entryTsMs = 0;
                        continue;
                    }
                    if (tpPct > 0 && pnlPct >= tpPct) {
                        double fill = close * (1.0 - slippage);
                        double gross = qty * fill;
                        double fee = gross * feeRate;
                        double realized = (gross - fee) - (qty * avg);
                        sellCount++; tpSells++;
                        if (realized > 0) winCount++;
                        long sellTs = timestamps[i];
                        if (sellTs >= boundary3m) { sells3m++; if (realized > 0) wins3m++; }
                        if (sellTs >= boundary1m) { sells1m++; if (realized > 0) wins1m++; }
                        capital += (gross - fee);
                        qty = 0; avg = 0; addBuyCount = 0; entryStrat = null; entryTsMs = 0;
                        continue;
                    }
                }

                // === Time Stop ===
                if (timeStopMinutes > 0 && open && entryTsMs > 0 && timestamps[i] > 0 && isBuyOnly) {
                    long elapsedMin = (timestamps[i] - entryTsMs) / 60000L;
                    if (elapsedMin >= timeStopMinutes) {
                        double pnlPct = avg > 0 ? ((close - avg) / avg) * 100.0 : 0;
                        if (pnlPct < 0) {
                            double fill = close * (1.0 - slippage);
                            double gross = qty * fill;
                            double fee = gross * feeRate;
                            double realized = (gross - fee) - (qty * avg);
                            sellCount++;
                            if (realized > 0) winCount++;
                            long sellTs = timestamps[i];
                            if (sellTs >= boundary3m) { sells3m++; if (realized > 0) wins3m++; }
                            if (sellTs >= boundary1m) { sells1m++; if (realized > 0) wins1m++; }
                            capital += (gross - fee);
                            qty = 0; avg = 0; addBuyCount = 0; entryStrat = null; entryTsMs = 0;
                            continue;
                        }
                    }
                }

                // === 희소 시그널 적용 ===
                // 현재 캔들에 해당하는 시그널들 처리
                while (sigPtr < sigLen && signals[sigPtr].candleIdx == i) {
                    SparseSignal sig = signals[sigPtr];
                    sigPtr++;

                    open = qty > 0; // 업데이트 (이전 시그널로 변경되었을 수 있음)

                    if (sig.type == 1 && !open) {
                        // BUY
                        if (minConfidence > 0 && sig.confidence < minConfidence) continue;
                        double orderKrw = baseOrderKrw;
                        if (orderKrw < minOrderKrw) orderKrw = minOrderKrw;
                        if (capital < orderKrw) {
                            if (capital >= minOrderKrw) orderKrw = capital;
                            else continue;
                        }
                        double fee = orderKrw * feeRate;
                        double fill = close * (1.0 + slippage);
                        qty = (orderKrw - fee) / fill;
                        avg = fill;
                        addBuyCount = 0;
                        entryStrat = sig.patternType;
                        entryTsMs = timestamps[i];
                        capital -= orderKrw;
                    } else if (sig.type == 2 && open) {
                        // SELL (self-contained)
                        boolean allowSell = true;
                        if (strategyLock && entryStrat != null && !entryStrat.isEmpty()
                                && !entryStrat.equals(sig.patternType)) {
                            boolean isSellOnly = false;
                            try { isSellOnly = StrategyType.valueOf(sig.patternType).isSellOnly(); }
                            catch (Exception ignore) {}
                            if (!isSellOnly) allowSell = false;
                        }
                        if (allowSell) {
                            double fill = close * (1.0 - slippage);
                            double gross = qty * fill;
                            double fee = gross * feeRate;
                            double realized = (gross - fee) - (qty * avg);
                            sellCount++; patternSells++;
                            if (realized > 0) winCount++;
                            long sellTs = timestamps[i];
                            if (sellTs >= boundary3m) { sells3m++; if (realized > 0) wins3m++; }
                            if (sellTs >= boundary1m) { sells1m++; if (realized > 0) wins1m++; }
                            capital += (gross - fee);
                            qty = 0; avg = 0; addBuyCount = 0; entryStrat = null; entryTsMs = 0;
                        }
                    } else if (sig.type == 3 && open && addBuyCount < maxAddBuys) {
                        // ADD_BUY
                        if (strategyLock && entryStrat != null && !entryStrat.isEmpty()
                                && !entryStrat.equals(sig.patternType)) continue;
                        int next = addBuyCount + 1;
                        double orderKrw = baseOrderKrw * Math.pow(addBuyMult, next);
                        if (orderKrw < minOrderKrw) orderKrw = minOrderKrw;
                        if (capital < orderKrw) {
                            if (capital >= minOrderKrw) orderKrw = capital;
                            else continue;
                        }
                        double fee = orderKrw * feeRate;
                        double fill = close * (1.0 + slippage);
                        double addQty = (orderKrw - fee) / fill;
                        double newQty = qty + addQty;
                        avg = (avg * qty + fill * addQty) / newQty;
                        qty = newQty;
                        addBuyCount++;
                        capital -= orderKrw;
                    }
                }

                // 남은 시그널이 미래에 있으면 sigPtr 그대로 유지
            }

            // Open position value at end
            double endValue = capital + (qty > 0 ? qty * closes[len - 1] : 0);
            double roi = ((endValue - DEFAULT_CAPITAL) / DEFAULT_CAPITAL) * 100.0;
            double roi3mVal = capital3mStart > 0 ? ((endValue - capital3mStart) / capital3mStart) * 100.0 : 0;
            double roi1mVal = capital1mStart > 0 ? ((endValue - capital1mStart) / capital1mStart) * 100.0 : 0;

            if (sellCount > 0) {
                ResultEntry entry = new ResultEntry();
                entry.id = resultIdSeq.incrementAndGet();
                entry.strategy = tape.strategy.name();
                entry.market = tape.market;
                entry.interval = tape.interval;
                entry.emaPeriod = tape.emaPeriod;
                entry.tp = tpPct;
                entry.sl = slPct;
                entry.maxAddBuys = maxAddBuys;
                entry.minConfidence = minConfidence;
                entry.strategyLock = strategyLock;
                entry.timeStop = timeStopMinutes;
                entry.roi = roi;
                entry.winRate = (winCount * 100.0 / sellCount);
                entry.totalTrades = sellCount;
                entry.wins = winCount;
                entry.totalPnl = endValue - DEFAULT_CAPITAL;
                entry.finalCapital = endValue;
                entry.tpSellCount = tpSells;
                entry.slSellCount = slSells;
                entry.patternSellCount = patternSells;
                entry.phase = 1;
                entry.roi3m = roi3mVal;
                entry.winRate3m = sells3m > 0 ? (wins3m * 100.0 / sells3m) : null;
                entry.totalTrades3m = sells3m;
                entry.wins3m = wins3m;
                entry.roi1m = roi1mVal;
                entry.winRate1m = sells1m > 0 ? (wins1m * 100.0 / sells1m) : null;
                entry.totalTrades1m = sells1m;
                entry.wins1m = wins1m;

                topResults.add(entry);
                while (topResults.size() > TOP_N * 2) {
                    topResults.pollLast();
                }

                // 마켓별 Top N도 별도 추적
                ConcurrentSkipListSet<ResultEntry> marketSet = topResultsByMarket.get(tape.market);
                if (marketSet == null) {
                    Comparator<ResultEntry> cmp = new Comparator<ResultEntry>() {
                        @Override
                        public int compare(ResultEntry a, ResultEntry b) {
                            int c = Double.compare(b.roi, a.roi);
                            if (c != 0) return c;
                            return Long.compare(a.id, b.id);
                        }
                    };
                    topResultsByMarket.putIfAbsent(tape.market, new ConcurrentSkipListSet<ResultEntry>(cmp));
                    marketSet = topResultsByMarket.get(tape.market);
                }
                marketSet.add(entry);
                while (marketSet.size() > TOP_N_PER_MARKET * 2) {
                    marketSet.pollLast();
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("리플레이 오류: {} tp={} sl={}: {}", tape.strategy, tpPct, slPct, e.getMessage());
            }
        } finally {
            long done = completedCombinations.incrementAndGet();
            if (done % 500000 == 0) {
                long total = totalCombinations.get();
                double pct = total > 0 ? (done * 100.0 / total) : 0;
                log.info("진행: {}/{} ({}%)", done, total, String.format("%.1f", pct));
                statusMessage = String.format("진행 중... %s/%s (%.1f%%)", done, total, pct);
            }
        }
    }

    // ===== DB 저장 =====

    private void saveTopResults(String runId) {
        List<OptimizationResultEntity> entities = new ArrayList<OptimizationResultEntity>();
        int count = 0;
        for (ResultEntry entry : topResults) {
            if (count >= TOP_N) break;
            OptimizationResultEntity e = new OptimizationResultEntity();
            e.setRunId(runId);
            e.setStrategyType(entry.strategy);
            e.setMarket(entry.market);
            e.setIntervalMin(entry.interval);
            e.setTpPct(entry.tp);
            e.setSlPct(entry.sl);
            e.setMaxAddBuys(entry.maxAddBuys);
            e.setMinConfidence(entry.minConfidence);
            e.setStrategyLock(entry.strategyLock);
            e.setTimeStopMinutes(entry.timeStop);
            e.setEmaPeriod(entry.emaPeriod);
            e.setRoi(entry.roi);
            e.setWinRate(entry.winRate);
            e.setTotalTrades(entry.totalTrades);
            e.setWins(entry.wins);
            e.setTotalPnl(entry.totalPnl);
            e.setFinalCapital(entry.finalCapital);
            e.setTpSellCount(entry.tpSellCount);
            e.setSlSellCount(entry.slSellCount);
            e.setPatternSellCount(entry.patternSellCount);
            e.setPhase(entry.phase);
            e.setStrategiesCsv(entry.strategiesCsv);
            e.setStrategyIntervalsCsv(entry.strategyIntervalsCsv);
            e.setEmaFilterCsv(entry.emaFilterCsv);
            e.setRoi3m(entry.roi3m);
            e.setWinRate3m(entry.winRate3m);
            e.setTotalTrades3m(entry.totalTrades3m);
            e.setWins3m(entry.wins3m);
            e.setRoi1m(entry.roi1m);
            e.setWinRate1m(entry.winRate1m);
            e.setTotalTrades1m(entry.totalTrades1m);
            e.setWins1m(entry.wins1m);
            entities.add(e);
            count++;
            if (entities.size() >= 100) {
                resultRepo.saveAll(entities);
                entities.clear();
            }
        }
        if (!entities.isEmpty()) resultRepo.saveAll(entities);
        log.info("글로벌 Top {} 결과 DB 저장 완료 (runId={})", count, runId);

        // 마켓별 Top 결과도 저장 (글로벌에 없는 것만)
        for (Map.Entry<String, ConcurrentSkipListSet<ResultEntry>> me : topResultsByMarket.entrySet()) {
            String market = me.getKey();
            int mCount = 0;
            for (ResultEntry entry : me.getValue()) {
                if (mCount >= TOP_N_PER_MARKET) break;
                // 글로벌 Top에 이미 있으면 skip (중복 방지)
                if (topResults.contains(entry)) { mCount++; continue; }
                OptimizationResultEntity e = new OptimizationResultEntity();
                e.setRunId(runId);
                e.setStrategyType(entry.strategy);
                e.setMarket(entry.market);
                e.setIntervalMin(entry.interval);
                e.setTpPct(entry.tp);
                e.setSlPct(entry.sl);
                e.setMaxAddBuys(entry.maxAddBuys);
                e.setMinConfidence(entry.minConfidence);
                e.setStrategyLock(entry.strategyLock);
                e.setTimeStopMinutes(entry.timeStop);
                e.setEmaPeriod(entry.emaPeriod);
                e.setRoi(entry.roi);
                e.setWinRate(entry.winRate);
                e.setTotalTrades(entry.totalTrades);
                e.setWins(entry.wins);
                e.setTotalPnl(entry.totalPnl);
                e.setFinalCapital(entry.finalCapital);
                e.setTpSellCount(entry.tpSellCount);
                e.setSlSellCount(entry.slSellCount);
                e.setPatternSellCount(entry.patternSellCount);
                e.setPhase(entry.phase);
                e.setStrategiesCsv(entry.strategiesCsv);
                e.setStrategyIntervalsCsv(entry.strategyIntervalsCsv);
                e.setEmaFilterCsv(entry.emaFilterCsv);
                e.setRoi3m(entry.roi3m);
                e.setWinRate3m(entry.winRate3m);
                e.setTotalTrades3m(entry.totalTrades3m);
                e.setWins3m(entry.wins3m);
                e.setRoi1m(entry.roi1m);
                e.setWinRate1m(entry.winRate1m);
                e.setTotalTrades1m(entry.totalTrades1m);
                e.setWins1m(entry.wins1m);
                entities.add(e);
                mCount++;
                if (entities.size() >= 100) {
                    resultRepo.saveAll(entities);
                    entities.clear();
                }
            }
            if (!entities.isEmpty()) {
                resultRepo.saveAll(entities);
                entities.clear();
            }
            log.info("마켓 {} Top {} 결과 DB 저장", market, mCount);
        }
    }

    // ===== 결과 조회 =====

    public List<OptimizationResultEntity> getResults(String runId) {
        return resultRepo.findByRunIdOrderByRoiDesc(runId);
    }

    public List<OptimizationResultEntity> getResultsByMarket(String runId, String market) {
        return resultRepo.findByRunIdAndMarketOrderByRoiDesc(runId, market);
    }

    public List<OptimizationResultEntity> getResultsByPhase(String runId, int phase) {
        return resultRepo.findByRunIdAndPhaseOrderByRoiDesc(runId, phase);
    }

    public List<OptimizationResultEntity> getResultsByPhaseAndMarket(String runId, int phase, String market) {
        return resultRepo.findByRunIdAndPhaseAndMarketOrderByRoiDesc(runId, phase, market);
    }

    public List<ResultEntry> getTopResultsInMemory() {
        List<ResultEntry> list = new ArrayList<ResultEntry>();
        int count = 0;
        for (ResultEntry e : topResults) {
            if (count >= TOP_N) break;
            list.add(e);
            count++;
        }
        return list;
    }

    public List<ResultEntry> getTopResultsByMarketInMemory(String market) {
        ConcurrentSkipListSet<ResultEntry> set = topResultsByMarket.get(market);
        if (set == null) return Collections.emptyList();
        List<ResultEntry> list = new ArrayList<ResultEntry>();
        int count = 0;
        for (ResultEntry e : set) {
            if (count >= TOP_N_PER_MARKET) break;
            list.add(e);
            count++;
        }
        return list;
    }

    public Map<String, List<ResultEntry>> getAllMarketsTopInMemory() {
        Map<String, List<ResultEntry>> result = new LinkedHashMap<String, List<ResultEntry>>();
        for (String market : MARKETS) {
            List<ResultEntry> top = getTopResultsByMarketInMemory(market);
            if (!top.isEmpty()) result.put(market, top);
        }
        return result;
    }

    // ===== 결과 엔트리 =====

    public static class ResultEntry {
        public long id;
        public String strategy;
        public String market;
        public int interval;
        public int emaPeriod;
        public double tp;
        public double sl;
        public int maxAddBuys;
        public double minConfidence;
        public boolean strategyLock;
        public int timeStop;
        public double roi;
        public double winRate;
        public int totalTrades;
        public int wins;
        public double totalPnl;
        public double finalCapital;
        public int tpSellCount;
        public int slSellCount;
        public int patternSellCount;
        public String strategiesCsv;       // for Phase 2
        public String strategyIntervalsCsv; // for Phase 2
        public String emaFilterCsv;         // for Phase 2
        public int phase = 1;
        public Double roi3m;
        public Double winRate3m;
        public Integer totalTrades3m;
        public Integer wins3m;
        public Double roi1m;
        public Double winRate1m;
        public Integer totalTrades1m;
        public Integer wins1m;
    }

    /** Phase 2: Multi-strategy tape entry */
    static class StrategyTapeEntry {
        StrategyType strategy;
        int interval;
        int emaPeriod;
        boolean isBuyOnly;
        boolean isSellOnly;
        int[] signalMasterIdxs;   // master timeline index for each signal
        SparseSignal[] signals;
        int signalCount;
    }

    /** Phase 2: Multi-strategy combined tape */
    static class MultiStrategyTape {
        String market;
        long[] masterTimeline;
        double[] masterCloses;
        int masterLength;
        List<StrategyTapeEntry> entries;
        String strategiesCsv;
        String strategyIntervalsCsv;
        String emaFilterCsv;
    }

    /** Phase 2: Strategy config from Phase 1 results */
    static class StrategyConfig {
        StrategyType strategy;
        int bestInterval;
        int bestEmaPeriod;
        double bestRoi;
    }

    public String startPhase2Optimization(final String phase1RunId, final int topN) {
        if (!running.compareAndSet(false, true)) return currentRunId;
        currentRunId = UUID.randomUUID().toString().substring(0, 8);
        topResults.clear();
        topResultsByMarket.clear();
        resultIdSeq.set(0);
        completedCombinations.set(0);
        totalCombinations.set(0);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runPhase2(currentRunId, phase1RunId, topN);
                } catch (Exception e) {
                    log.error("Phase 2 최적화 오류", e);
                    statusMessage = "오류: " + e.getMessage();
                } finally {
                    running.set(false);
                }
            }
        }, "batch-optimization-p2");
        t.setDaemon(true);
        t.start();
        return currentRunId;
    }

    private void runPhase2(String runId, String phase1RunId, int topN) {
        statusMessage = "Phase 2: Phase 1 결과 분석 중...";
        log.info("=== Phase 2 시작 (runId={}, phase1RunId={}, topN={}) ===", runId, phase1RunId, topN);

        // Load candle data
        Map<String, Map<Integer, List<UpbitCandle>>> allCandles = candleCacheService.getAllCached();
        if (allCandles.isEmpty()) {
            statusMessage = "캔들 캐시 비어있음";
            return;
        }

        // Sell-only strategies (deprecated 제외)
        List<StrategyType> sellOnlyStrats = new ArrayList<StrategyType>();
        for (StrategyType st : StrategyType.values()) {
            if (st.isDeprecated()) continue;
            if (st.isSellOnly()) sellOnlyStrats.add(st);
        }

        // For each market, get top N strategies from Phase 1
        Map<String, List<StrategyConfig>> marketTopStrategies = new LinkedHashMap<String, List<StrategyConfig>>();
        for (String market : MARKETS) {
            List<OptimizationResultEntity> results = resultRepo.findByRunIdAndMarketOrderByRoiDesc(phase1RunId, market);
            Set<String> seen = new LinkedHashSet<String>();
            List<StrategyConfig> configs = new ArrayList<StrategyConfig>();
            for (OptimizationResultEntity r : results) {
                if (seen.contains(r.getStrategyType())) continue;
                seen.add(r.getStrategyType());
                StrategyConfig sc = new StrategyConfig();
                sc.strategy = StrategyType.valueOf(r.getStrategyType());
                sc.bestInterval = r.getIntervalMin();
                sc.bestEmaPeriod = r.getEmaPeriod();
                sc.bestRoi = r.getRoi();
                configs.add(sc);
                if (configs.size() >= topN) break;
            }
            if (!configs.isEmpty()) {
                marketTopStrategies.put(market, configs);
                log.info("마켓 {} Top {} 전략: {}", market, configs.size(), seen);
            }
        }

        // Generate needed tapes (reuse cache if available)
        statusMessage = "Phase 2: 시그널 테이프 생성 중...";
        for (Map.Entry<String, List<StrategyConfig>> me : marketTopStrategies.entrySet()) {
            String market = me.getKey();
            Map<Integer, List<UpbitCandle>> mc = allCandles.get(market);
            if (mc == null) continue;

            for (StrategyConfig sc : me.getValue()) {
                generateAndCacheTape(sc.strategy, market, sc.bestInterval, sc.bestEmaPeriod, mc);
            }
            // Also generate sell-only strategy tapes for this market
            for (StrategyType sellSt : sellOnlyStrats) {
                // Sell-only at common intervals
                for (int interval : new int[]{60, 240}) {
                    generateAndCacheTape(sellSt, market, interval, 0, mc);
                }
            }
        }

        // Generate all multi-strategy combinations
        List<MultiStrategyTape> multiTapes = new ArrayList<MultiStrategyTape>();
        for (Map.Entry<String, List<StrategyConfig>> me : marketTopStrategies.entrySet()) {
            String market = me.getKey();
            List<StrategyConfig> configs = me.getValue();
            Map<Integer, List<UpbitCandle>> mc = allCandles.get(market);
            if (mc == null) continue;

            // Generate combos of size 2 to min(5, configs.size())
            int maxComboSize = Math.min(5, configs.size());
            for (int size = 2; size <= maxComboSize; size++) {
                List<List<StrategyConfig>> combos = combinations(configs, size);
                for (List<StrategyConfig> combo : combos) {
                    // Base combo (buy strategies only)
                    MultiStrategyTape baseTape = buildMultiStrategyTape(market, combo, Collections.<StrategyType>emptyList(), mc);
                    if (baseTape != null) multiTapes.add(baseTape);

                    // Combo + each sell-only strategy
                    for (StrategyType sellSt : sellOnlyStrats) {
                        MultiStrategyTape withSell = buildMultiStrategyTape(market, combo, Collections.singletonList(sellSt), mc);
                        if (withSell != null) multiTapes.add(withSell);
                    }
                }
            }

            // Also test single buy strategies with sell-only strategies
            for (StrategyConfig sc : configs) {
                List<StrategyConfig> single = Collections.singletonList(sc);
                for (StrategyType sellSt : sellOnlyStrats) {
                    MultiStrategyTape withSell = buildMultiStrategyTape(market, single, Collections.singletonList(sellSt), mc);
                    if (withSell != null) multiTapes.add(withSell);
                }
            }
        }

        int tapeCount = multiTapes.size();
        totalCombinations.set((long) tapeCount * RISK_COMBOS);
        log.info("Phase 2: 조합 테이프: {}, 리스크: {}, 총: {}", tapeCount, RISK_COMBOS, (long) tapeCount * RISK_COMBOS);

        statusMessage = String.format("Phase 2: 최적화 중... (%d 조합 × %d 리스크)", tapeCount, RISK_COMBOS);
        long startTime = System.currentTimeMillis();

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(cores);

        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (final MultiStrategyTape mst : multiTapes) {
            futures.add(executor.submit(new Runnable() {
                @Override
                public void run() {
                    processMultiStrategyTape(mst);
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { log.error("Phase 2 작업 오류", e); }
        }
        executor.shutdown();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("=== Phase 2 완료: {}분 {}초 ===", elapsed / 60000, (elapsed % 60000) / 1000);

        statusMessage = "Phase 2: 결과 저장 중...";
        saveTopResults(runId);

        statusMessage = String.format("Phase 2 완료! %d 조합, %d분 %d초, Top %d 저장",
                completedCombinations.get(), elapsed / 60000, (elapsed % 60000) / 1000,
                Math.min(TOP_N, topResults.size()));
        log.info(statusMessage);

        // Clear tape cache to free memory
        tapeCache.clear();
    }

    private void generateAndCacheTape(StrategyType strategy, String market, int interval, int emaPeriod,
                                        Map<Integer, List<UpbitCandle>> mc) {
        String cacheKey = strategy.name() + "_" + market + "_" + interval + "_" + emaPeriod;
        if (tapeCache.containsKey(cacheKey)) return;

        List<UpbitCandle> candles = mc.get(interval);
        if (candles == null || candles.size() < 10) return;

        TapeTask task = new TapeTask();
        task.strategy = strategy;
        task.market = market;
        task.interval = interval;
        task.emaPeriod = emaPeriod;
        task.candles = candles;

        FastTape tape = generateFastTape(task);
        if (tape != null && tape.signalCount > 0) {
            tapeCache.put(cacheKey, tape);
        }
    }

    private MultiStrategyTape buildMultiStrategyTape(String market, List<StrategyConfig> buyConfigs,
                                                       List<StrategyType> sellOnlyStrats,
                                                       Map<Integer, List<UpbitCandle>> mc) {
        // Find finest interval among all strategies
        int finestInterval = Integer.MAX_VALUE;
        for (StrategyConfig sc : buyConfigs) {
            finestInterval = Math.min(finestInterval, sc.bestInterval);
        }
        for (StrategyType st : sellOnlyStrats) {
            finestInterval = Math.min(finestInterval, 60); // sell-only default
        }

        // Get master timeline from finest interval
        List<UpbitCandle> masterCandles = mc.get(finestInterval);
        if (masterCandles == null || masterCandles.size() < 10) return null;

        int n = masterCandles.size() - 1;
        long[] masterTimeline = new long[n];
        double[] masterCloses = new double[n];
        for (int i = 1; i < masterCandles.size(); i++) {
            UpbitCandle c = masterCandles.get(i);
            if (c != null && c.candle_date_time_utc != null) {
                masterCloses[i - 1] = c.trade_price;
                try {
                    masterTimeline[i - 1] = LocalDateTime.parse(c.candle_date_time_utc)
                            .toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (Exception e) {
                    masterTimeline[i - 1] = 0;
                }
            }
        }

        List<StrategyTapeEntry> entries = new ArrayList<StrategyTapeEntry>();
        StringBuilder stratsCsv = new StringBuilder();
        StringBuilder intervalsCsv = new StringBuilder();
        StringBuilder emaCsv = new StringBuilder();

        // Map buy strategy tapes
        for (StrategyConfig sc : buyConfigs) {
            String cacheKey = sc.strategy.name() + "_" + market + "_" + sc.bestInterval + "_" + sc.bestEmaPeriod;
            FastTape ft = tapeCache.get(cacheKey);
            if (ft == null) continue;

            StrategyTapeEntry ste = mapToMasterTimeline(ft, masterTimeline, n);
            if (ste == null) continue;
            ste.strategy = sc.strategy;
            ste.interval = sc.bestInterval;
            ste.emaPeriod = sc.bestEmaPeriod;
            ste.isBuyOnly = sc.strategy.isBuyOnly();
            ste.isSellOnly = false;
            entries.add(ste);

            if (stratsCsv.length() > 0) { stratsCsv.append(","); intervalsCsv.append(","); emaCsv.append(","); }
            stratsCsv.append(sc.strategy.name());
            intervalsCsv.append(sc.strategy.name()).append(":").append(sc.bestInterval);
            emaCsv.append(sc.strategy.name()).append(":").append(sc.bestEmaPeriod);
        }

        // Map sell-only strategy tapes
        for (StrategyType sellSt : sellOnlyStrats) {
            int sellInterval = 240; // try 240 first, fallback to 60
            String cacheKey = sellSt.name() + "_" + market + "_" + sellInterval + "_0";
            FastTape ft = tapeCache.get(cacheKey);
            if (ft == null) {
                sellInterval = 60;
                cacheKey = sellSt.name() + "_" + market + "_" + sellInterval + "_0";
                ft = tapeCache.get(cacheKey);
            }
            if (ft == null) continue;

            StrategyTapeEntry ste = mapToMasterTimeline(ft, masterTimeline, n);
            if (ste == null) continue;
            ste.strategy = sellSt;
            ste.interval = sellInterval;
            ste.emaPeriod = 0;
            ste.isBuyOnly = false;
            ste.isSellOnly = true;
            entries.add(ste);

            if (stratsCsv.length() > 0) { stratsCsv.append(","); intervalsCsv.append(","); }
            stratsCsv.append(sellSt.name());
            intervalsCsv.append(sellSt.name()).append(":").append(sellInterval);
        }

        if (entries.size() < 2) return null; // need at least 2 strategies

        MultiStrategyTape mst = new MultiStrategyTape();
        mst.market = market;
        mst.masterTimeline = masterTimeline;
        mst.masterCloses = masterCloses;
        mst.masterLength = n;
        mst.entries = entries;
        mst.strategiesCsv = stratsCsv.toString();
        mst.strategyIntervalsCsv = intervalsCsv.toString();
        mst.emaFilterCsv = emaCsv.toString();
        return mst;
    }

    private StrategyTapeEntry mapToMasterTimeline(FastTape ft, long[] masterTimeline, int masterLen) {
        if (ft.signalCount == 0) return null;

        List<Integer> mappedIdxs = new ArrayList<Integer>();
        List<SparseSignal> mappedSigs = new ArrayList<SparseSignal>();

        for (int s = 0; s < ft.signalCount; s++) {
            SparseSignal sig = ft.signals[s];
            if (sig.candleIdx >= ft.length) continue;
            long sigTs = ft.timestamps[sig.candleIdx];
            if (sigTs <= 0) continue;

            // Binary search in master timeline
            int masterIdx = Arrays.binarySearch(masterTimeline, sigTs);
            if (masterIdx < 0) masterIdx = -masterIdx - 2; // floor
            if (masterIdx < 0) masterIdx = 0;
            if (masterIdx >= masterLen) masterIdx = masterLen - 1;

            // Ensure we don't look ahead
            if (masterTimeline[masterIdx] > sigTs) continue;

            mappedIdxs.add(masterIdx);
            mappedSigs.add(sig);
        }

        if (mappedIdxs.isEmpty()) return null;

        StrategyTapeEntry ste = new StrategyTapeEntry();
        ste.signalMasterIdxs = new int[mappedIdxs.size()];
        ste.signals = mappedSigs.toArray(new SparseSignal[0]);
        ste.signalCount = mappedIdxs.size();
        for (int i = 0; i < mappedIdxs.size(); i++) {
            ste.signalMasterIdxs[i] = mappedIdxs.get(i);
        }
        return ste;
    }

    /** Generate all combinations of size k from list */
    private <T> List<List<T>> combinations(List<T> list, int k) {
        List<List<T>> result = new ArrayList<List<T>>();
        combinationsHelper(list, k, 0, new ArrayList<T>(), result);
        return result;
    }

    private <T> void combinationsHelper(List<T> list, int k, int start, List<T> current, List<List<T>> result) {
        if (current.size() == k) {
            result.add(new ArrayList<T>(current));
            return;
        }
        for (int i = start; i < list.size(); i++) {
            current.add(list.get(i));
            combinationsHelper(list, k, i + 1, current, result);
            current.remove(current.size() - 1);
        }
    }

    private void processMultiStrategyTape(MultiStrategyTape mst) {
        final double slippage = tradeProps.getSlippageRate();
        final double feeRate = strategyCfg.getFeeRate();
        final double minOrderKrw = tradeProps.getMinOrderKrw();
        final double addBuyMult = tradeProps.getAddBuyMultiplier();

        for (double tp : TP_VALUES) {
            for (double sl : SL_VALUES) {
                for (int maxAdd : MAX_ADD_BUYS) {
                    for (double minConf : MIN_CONFIDENCES) {
                        for (boolean lock : STRATEGY_LOCKS) {
                            for (int timeStop : TIME_STOPS) {
                                replayMultiStrategyTape(mst, tp, sl, maxAdd, minConf, lock, timeStop,
                                        slippage, feeRate, minOrderKrw, addBuyMult);
                            }
                        }
                    }
                }
            }
        }
    }

    private void replayMultiStrategyTape(MultiStrategyTape mst, double tpPct, double slPct,
                                           int maxAddBuys, double minConfidence,
                                           boolean strategyLock, int timeStopMinutes,
                                           double slippage, double feeRate, double minOrderKrw, double addBuyMult) {
        try {
            double capital = DEFAULT_CAPITAL;
            double baseOrderKrw = DEFAULT_CAPITAL * 0.9;

            double qty = 0, avg = 0;
            int addBuyCount = 0;
            String entryStrat = null;
            long entryTsMs = 0;
            boolean entryIsBuyOnly = false;

            int sellCount = 0, winCount = 0;
            int tpSells = 0, slSells = 0, patternSells = 0;

            double[] closes = mst.masterCloses;
            long[] timestamps = mst.masterTimeline;
            int len = mst.masterLength;

            // Period boundaries
            long maxTs = timestamps[len - 1];
            long boundary3m = maxTs - 90L * 24 * 60 * 60 * 1000;
            long boundary1m = maxTs - 30L * 24 * 60 * 60 * 1000;
            double capital3mStart = -1;
            double capital1mStart = -1;
            int sells3m = 0, wins3m = 0;
            int sells1m = 0, wins1m = 0;

            // Signal pointers for each strategy tape entry
            int entryCount = mst.entries.size();
            int[] sigPtrs = new int[entryCount];

            for (int i = 0; i < len; i++) {
                double close = closes[i];
                if (close <= 0) continue;
                long ts = timestamps[i];
                boolean open = qty > 0;

                // Period snapshots
                if (capital3mStart < 0 && ts >= boundary3m) capital3mStart = capital + (open ? qty * close : 0);
                if (capital1mStart < 0 && ts >= boundary1m) capital1mStart = capital + (open ? qty * close : 0);

                // === TP/SL ===
                if (open && avg > 0) {
                    double pnlPct = ((close - avg) / avg) * 100.0;
                    if (slPct > 0 && pnlPct <= -slPct) {
                        double fill = close * (1.0 - slippage);
                        double gross = qty * fill;
                        double fee = gross * feeRate;
                        double realized = (gross - fee) - (qty * avg);
                        sellCount++; slSells++;
                        if (realized > 0) winCount++;
                        if (ts >= boundary3m) { sells3m++; if (realized > 0) wins3m++; }
                        if (ts >= boundary1m) { sells1m++; if (realized > 0) wins1m++; }
                        capital += (gross - fee);
                        qty = 0; avg = 0; addBuyCount = 0; entryStrat = null; entryTsMs = 0; entryIsBuyOnly = false;
                        continue;
                    }
                    if (tpPct > 0 && pnlPct >= tpPct) {
                        double fill = close * (1.0 - slippage);
                        double gross = qty * fill;
                        double fee = gross * feeRate;
                        double realized = (gross - fee) - (qty * avg);
                        sellCount++; tpSells++;
                        if (realized > 0) winCount++;
                        if (ts >= boundary3m) { sells3m++; if (realized > 0) wins3m++; }
                        if (ts >= boundary1m) { sells1m++; if (realized > 0) wins1m++; }
                        capital += (gross - fee);
                        qty = 0; avg = 0; addBuyCount = 0; entryStrat = null; entryTsMs = 0; entryIsBuyOnly = false;
                        continue;
                    }
                }

                // === Time Stop ===
                if (timeStopMinutes > 0 && open && entryTsMs > 0 && ts > 0 && entryIsBuyOnly) {
                    long elapsedMin = (ts - entryTsMs) / 60000L;
                    if (elapsedMin >= timeStopMinutes) {
                        double pnlPct = avg > 0 ? ((close - avg) / avg) * 100.0 : 0;
                        if (pnlPct < 0) {
                            double fill = close * (1.0 - slippage);
                            double gross = qty * fill;
                            double fee = gross * feeRate;
                            double realized = (gross - fee) - (qty * avg);
                            sellCount++;
                            if (realized > 0) winCount++;
                            if (ts >= boundary3m) { sells3m++; if (realized > 0) wins3m++; }
                            if (ts >= boundary1m) { sells1m++; if (realized > 0) wins1m++; }
                            capital += (gross - fee);
                            qty = 0; avg = 0; addBuyCount = 0; entryStrat = null; entryTsMs = 0; entryIsBuyOnly = false;
                            continue;
                        }
                    }
                }

                // === Collect signals from all strategy tapes at current candle ===
                // Priority merging: SELL(3) > ADD_BUY(2) > BUY(1)
                SparseSignal bestSig = null;
                int bestPriority = 0;
                double bestConf = -1;
                StrategyTapeEntry bestEntry = null;
                open = qty > 0;

                for (int e = 0; e < entryCount; e++) {
                    StrategyTapeEntry ste = mst.entries.get(e);
                    int ptr = sigPtrs[e];
                    while (ptr < ste.signalCount && ste.signalMasterIdxs[ptr] < i) ptr++;
                    sigPtrs[e] = ptr; // advance past old signals

                    while (ptr < ste.signalCount && ste.signalMasterIdxs[ptr] == i) {
                        SparseSignal sig = ste.signals[ptr];
                        ptr++;
                        sigPtrs[e] = ptr;

                        int priority;
                        if (sig.type == 2) priority = 3; // SELL
                        else if (sig.type == 3) priority = 2; // ADD_BUY
                        else priority = 1; // BUY

                        // Applicability check
                        // Cross-strategy ADD_BUY (옵션 D): BUY 신호 + 포지션 열림 + 손실 중일 때만
                        // 평단가를 낮추기 위한 추가매수 전환
                        boolean crossStratAddBuy = false;
                        if (sig.type == 1 && open) {
                            double currentPnlPct = avg > 0 ? ((close - avg) / avg) * 100.0 : 0;
                            if (addBuyCount < maxAddBuys && currentPnlPct < 0) {
                                crossStratAddBuy = true; // 손실 중 → BUY → ADD_BUY 전환 후보
                            } else {
                                continue; // 수익 중이거나 한도 초과 → 스킵
                            }
                        }
                        if ((sig.type == 2 || sig.type == 3) && !open) continue; // Can't SELL/ADD when no position

                        // Strategy Lock filter for SELL/ADD_BUY (cross-strategy ADD_BUY는 잠금 제외)
                        if (strategyLock && open && entryStrat != null && (sig.type == 2 || sig.type == 3)) {
                            if (!entryStrat.equals(sig.patternType) && !ste.isSellOnly) {
                                continue; // Blocked by strategy lock (sell-only bypasses)
                            }
                        }

                        // Confidence filter for BUY (including cross-strategy ADD_BUY)
                        if (sig.type == 1 && minConfidence > 0 && sig.confidence < minConfidence) continue;

                        // Cross-strategy ADD_BUY는 ADD_BUY 우선순위(2)로 취급
                        if (crossStratAddBuy) {
                            int xPriority = 2; // ADD_BUY priority
                            if (xPriority > bestPriority || (xPriority == bestPriority && sig.confidence > bestConf)) {
                                bestSig = sig;
                                bestPriority = xPriority;
                                bestConf = sig.confidence;
                                bestEntry = ste;
                            }
                            continue; // 아래 일반 우선순위 머지 스킵
                        }

                        // Priority merge
                        if (priority > bestPriority || (priority == bestPriority && sig.confidence > bestConf)) {
                            bestSig = sig;
                            bestPriority = priority;
                            bestConf = sig.confidence;
                            bestEntry = ste;
                        }
                    }
                }

                // Execute best signal
                if (bestSig != null) {
                    // Cross-strategy ADD_BUY (옵션 D): BUY 신호 + 포지션 열림 + 손실 중 → ADD_BUY
                    double xPnlPct = (open && avg > 0) ? ((close - avg) / avg) * 100.0 : 0;
                    if (bestSig.type == 1 && open && addBuyCount < maxAddBuys && xPnlPct < 0) {
                        int next = addBuyCount + 1;
                        double orderKrw = baseOrderKrw * Math.pow(addBuyMult, next);
                        if (orderKrw < minOrderKrw) orderKrw = minOrderKrw;
                        if (capital < orderKrw) {
                            if (capital >= minOrderKrw) orderKrw = capital;
                            else { /* skip */ }
                        }
                        if (capital >= orderKrw || capital >= minOrderKrw) {
                            if (capital < orderKrw) orderKrw = capital;
                            double fee = orderKrw * feeRate;
                            double fill = close * (1.0 + slippage);
                            double addQty = (orderKrw - fee) / fill;
                            double newQty = qty + addQty;
                            avg = (avg * qty + fill * addQty) / newQty;
                            qty = newQty;
                            addBuyCount++;
                            capital -= orderKrw;
                        }
                    } else if (bestSig.type == 1 && !open) {
                        // BUY
                        double orderKrw = baseOrderKrw;
                        if (orderKrw < minOrderKrw) orderKrw = minOrderKrw;
                        if (capital < orderKrw) {
                            if (capital >= minOrderKrw) orderKrw = capital;
                            else { completedCombinations.incrementAndGet(); return; }
                        }
                        double fee = orderKrw * feeRate;
                        double fill = close * (1.0 + slippage);
                        qty = (orderKrw - fee) / fill;
                        avg = fill;
                        addBuyCount = 0;
                        entryStrat = bestSig.patternType;
                        entryTsMs = ts;
                        entryIsBuyOnly = bestEntry.isBuyOnly;
                        capital -= orderKrw;
                    } else if (bestSig.type == 2 && open) {
                        // SELL (pattern)
                        double fill = close * (1.0 - slippage);
                        double gross = qty * fill;
                        double fee = gross * feeRate;
                        double realized = (gross - fee) - (qty * avg);
                        sellCount++; patternSells++;
                        if (realized > 0) winCount++;
                        if (ts >= boundary3m) { sells3m++; if (realized > 0) wins3m++; }
                        if (ts >= boundary1m) { sells1m++; if (realized > 0) wins1m++; }
                        capital += (gross - fee);
                        qty = 0; avg = 0; addBuyCount = 0; entryStrat = null; entryTsMs = 0; entryIsBuyOnly = false;
                    } else if (bestSig.type == 3 && open && addBuyCount < maxAddBuys) {
                        // ADD_BUY
                        int next = addBuyCount + 1;
                        double orderKrw = baseOrderKrw * Math.pow(addBuyMult, next);
                        if (orderKrw < minOrderKrw) orderKrw = minOrderKrw;
                        if (capital < orderKrw) {
                            if (capital >= minOrderKrw) orderKrw = capital;
                            else continue;
                        }
                        double fee = orderKrw * feeRate;
                        double fill = close * (1.0 + slippage);
                        double addQty = (orderKrw - fee) / fill;
                        double newQty = qty + addQty;
                        avg = (avg * qty + fill * addQty) / newQty;
                        qty = newQty;
                        addBuyCount++;
                        capital -= orderKrw;
                    }
                }
            }

            // End value including open position
            double endValue = capital + (qty > 0 ? qty * closes[len - 1] : 0);
            double roi = ((endValue - DEFAULT_CAPITAL) / DEFAULT_CAPITAL) * 100.0;
            double roi3mVal = capital3mStart > 0 ? ((endValue - capital3mStart) / capital3mStart) * 100.0 : 0;
            double roi1mVal = capital1mStart > 0 ? ((endValue - capital1mStart) / capital1mStart) * 100.0 : 0;

            if (sellCount > 0) {
                ResultEntry entry = new ResultEntry();
                entry.id = resultIdSeq.incrementAndGet();
                entry.strategy = mst.strategiesCsv; // all strategies
                entry.market = mst.market;
                entry.interval = 0; // mixed intervals
                entry.emaPeriod = 0;
                entry.tp = tpPct;
                entry.sl = slPct;
                entry.maxAddBuys = maxAddBuys;
                entry.minConfidence = minConfidence;
                entry.strategyLock = strategyLock;
                entry.timeStop = timeStopMinutes;
                entry.roi = roi;
                entry.winRate = (winCount * 100.0 / sellCount);
                entry.totalTrades = sellCount;
                entry.wins = winCount;
                entry.totalPnl = endValue - DEFAULT_CAPITAL;
                entry.finalCapital = endValue;
                entry.tpSellCount = tpSells;
                entry.slSellCount = slSells;
                entry.patternSellCount = patternSells;
                entry.phase = 2;
                entry.strategiesCsv = mst.strategiesCsv;
                entry.strategyIntervalsCsv = mst.strategyIntervalsCsv;
                entry.emaFilterCsv = mst.emaFilterCsv;
                entry.roi3m = roi3mVal;
                entry.winRate3m = sells3m > 0 ? (wins3m * 100.0 / sells3m) : null;
                entry.totalTrades3m = sells3m;
                entry.wins3m = wins3m;
                entry.roi1m = roi1mVal;
                entry.winRate1m = sells1m > 0 ? (wins1m * 100.0 / sells1m) : null;
                entry.totalTrades1m = sells1m;
                entry.wins1m = wins1m;

                topResults.add(entry);
                while (topResults.size() > TOP_N * 2) topResults.pollLast();

                ConcurrentSkipListSet<ResultEntry> marketSet = topResultsByMarket.get(mst.market);
                if (marketSet == null) {
                    Comparator<ResultEntry> cmp = new Comparator<ResultEntry>() {
                        @Override
                        public int compare(ResultEntry a, ResultEntry b) {
                            int c = Double.compare(b.roi, a.roi);
                            if (c != 0) return c;
                            return Long.compare(a.id, b.id);
                        }
                    };
                    topResultsByMarket.putIfAbsent(mst.market, new ConcurrentSkipListSet<ResultEntry>(cmp));
                    marketSet = topResultsByMarket.get(mst.market);
                }
                marketSet.add(entry);
                while (marketSet.size() > TOP_N_PER_MARKET * 2) marketSet.pollLast();
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Phase 2 리플레이 오류: {}: {}", mst.strategiesCsv, e.getMessage());
            }
        } finally {
            long done = completedCombinations.incrementAndGet();
            if (done % 500000 == 0) {
                long total = totalCombinations.get();
                double pct = total > 0 ? (done * 100.0 / total) : 0;
                log.info("Phase 2 진행: {}/{} ({}%)", done, total, String.format("%.1f", pct));
                statusMessage = String.format("Phase 2 진행 중... %s/%s (%.1f%%)", done, total, pct);
            }
        }
    }
}
