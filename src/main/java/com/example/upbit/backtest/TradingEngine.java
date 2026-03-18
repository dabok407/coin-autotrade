package com.example.upbit.backtest;

import com.example.upbit.api.BacktestResponse;
import com.example.upbit.api.BacktestTradeRow;
import com.example.upbit.config.StrategyProperties;
import com.example.upbit.config.TradeProperties;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.strategy.*;

import java.time.ZoneOffset;
import java.util.*;

/**
 * 트레이딩 핵심 엔진 (Single Source of Truth).
 *
 * 백테스트(BacktestService)와 실매매(TradingBotService) 모두
 * 이 클래스의 로직을 기준으로 동일한 매매 판단을 수행합니다.
 *
 * 핵심 로직:
 * - 멀티 마켓 + 멀티 인터벌 타임라인 병합
 * - 동시 타임스탬프의 모든 인터벌 전략 평가 + 최적 시그널 선택
 * - TP/SL/TimeStop 체크
 * - Cross-strategy ADD_BUY (다른 전략 BUY -> 손실 중 ADD_BUY 전환)
 * - 전략 락/신뢰도 필터링
 */
public class TradingEngine {

    private final StrategyFactory strategyFactory;
    private final StrategyProperties strategyCfg;
    private final TradeProperties tradeProps;

    public TradingEngine(StrategyFactory strategyFactory,
                         StrategyProperties strategyCfg,
                         TradeProperties tradeProps) {
        this.strategyFactory = strategyFactory;
        this.strategyCfg = strategyCfg;
        this.tradeProps = tradeProps;
    }

    /**
     * 상세 시뮬레이션: 거래 내역(BacktestTradeRow) 포함 결과 반환.
     */
    public BacktestResponse simulate(SimulationParams params,
                                      Map<String, Map<Integer, List<UpbitCandle>>> candlesByMI) {
        SimState state = runLoop(params, candlesByMI, true);

        BacktestResponse res = new BacktestResponse();
        res.candleUnitMin = params.candleUnitMin;
        if (params.hasGroups && params.marketGroupMap != null && !params.marketGroupMap.isEmpty()) {
            SimulationParams.MarketGroupSettings firstGs = params.marketGroupMap.values().iterator().next();
            res.usedTpPct = firstGs.tpPct;
            res.usedSlPct = firstGs.slPct;
        } else {
            res.usedTpPct = params.tpPct;
            res.usedSlPct = params.slPct;
        }
        for (StrategyType t : params.strategies) res.strategies.add(t.name());

        int totalCandleCount = 0;
        for (Map.Entry<String, Map<Integer, List<UpbitCandle>>> e : candlesByMI.entrySet()) {
            res.markets.add(e.getKey());
            for (List<UpbitCandle> cs : e.getValue().values()) {
                totalCandleCount += cs.size();
            }
        }
        res.candleCount = totalCandleCount;

        res.trades = state.trades;
        res.tradesCount = state.trades.size();
        res.wins = state.winCount;
        res.winRate = (state.sellCount == 0 ? 0.0 : (state.winCount * 100.0 / state.sellCount));
        res.finalCapital = state.capital;
        res.tpSellCount = state.tpSellCount;
        res.slSellCount = state.slSellCount;
        res.patternSellCount = state.patternSellCount;

        double start = params.capitalKrw;
        res.totalReturn = state.capital - start;
        res.roi = (start <= 0 ? 0.0 : (res.totalReturn / start) * 100.0);
        res.totalPnl = res.totalReturn;
        res.totalRoi = res.roi;
        res.totalTrades = res.tradesCount;

        return res;
    }

    /**
     * 경량 시뮬레이션: ROI/승률/거래수만 반환 (최적화 루프 고속화).
     */
    public BacktestSimulator.OptimizationMetrics simulateFast(
            SimulationParams params,
            Map<String, Map<Integer, List<UpbitCandle>>> candlesByMI) {
        SimState state = runLoop(params, candlesByMI, false);

        BacktestSimulator.OptimizationMetrics m = new BacktestSimulator.OptimizationMetrics();
        m.roi = (params.capitalKrw <= 0 ? 0.0 : ((state.capital - params.capitalKrw) / params.capitalKrw) * 100.0);
        m.winRate = (state.sellCount == 0 ? 0.0 : (state.winCount * 100.0 / state.sellCount));
        m.totalTrades = state.sellCount;
        m.wins = state.winCount;
        m.finalCapital = state.capital;
        m.totalPnl = state.capital - params.capitalKrw;
        m.tpSellCount = state.tpSellCount;
        m.slSellCount = state.slSellCount;
        m.patternSellCount = state.patternSellCount;
        return m;
    }

    // ===== 핵심 시뮬레이션 루프 (canonical 구현) =====

    private SimState runLoop(SimulationParams params,
                             Map<String, Map<Integer, List<UpbitCandle>>> candlesByMI,
                             boolean detailed) {

        double capital = params.capitalKrw;
        double tpPct = params.tpPct;
        double slPct = params.slPct;
        double baseOrderKrw = params.baseOrderKrw;
        int maxAddBuysGlobal = params.maxAddBuys;
        boolean strategyLockEnabled = params.strategyLock;
        double minConfidence = params.minConfidence;
        int timeStopMinutes = params.timeStopMinutes;
        Map<String, Integer> emaTrendFilterMap = params.emaTrendFilterMap;
        Map<Integer, List<StrategyType>> stratsByInterval = params.stratsByInterval;
        boolean hasGroups = params.hasGroups;
        Map<String, SimulationParams.MarketGroupSettings> marketGroupMap = params.marketGroupMap;

        SimState st = new SimState();
        st.capital = capital;
        if (detailed) st.trades = new ArrayList<BacktestTradeRow>();

        // 마켓별 포지션 상태 초기화
        Map<String, Pos> posByMarket = new HashMap<String, Pos>();
        for (String m : params.markets) {
            Pos p = new Pos();
            Map<Integer, List<UpbitCandle>> byInterval = candlesByMI.get(m);
            if (byInterval != null) {
                for (Map.Entry<Integer, List<UpbitCandle>> e : byInterval.entrySet()) {
                    List<UpbitCandle> series = e.getValue();
                    if (series == null) series = new ArrayList<UpbitCandle>();
                    p.seriesByInterval.put(e.getKey(), series);
                    p.idxByInterval.put(e.getKey(), series.size() > 1 ? 1 : 0);
                }
            }
            posByMarket.put(m, p);
        }

        // 멀티 마켓 + 멀티 인터벌 타임라인 병합 루프
        while (true) {
            // 모든 (마켓, 인터벌) 조합 중 가장 빠른 다음 캔들 찾기
            String nextMarket = null;
            int nextInterval = 0;
            UpbitCandle nextCur = null;
            UpbitCandle nextPrev = null;

            for (String m : params.markets) {
                Pos p = posByMarket.get(m);
                if (p == null) continue;
                for (Map.Entry<Integer, Integer> ie : p.idxByInterval.entrySet()) {
                    int intv = ie.getKey();
                    // 전략이 배정되지 않은 인터벌 스킵
                    if (stratsByInterval.get(intv) == null || stratsByInterval.get(intv).isEmpty()) continue;
                    int idx = ie.getValue();
                    List<UpbitCandle> series = p.seriesByInterval.get(intv);
                    if (series == null || idx >= series.size()) continue;
                    if (idx < 1) continue;
                    UpbitCandle cur = series.get(idx);
                    if (cur == null || cur.candle_date_time_utc == null) continue;
                    if (nextCur == null || cur.candle_date_time_utc.compareTo(nextCur.candle_date_time_utc) < 0) {
                        nextMarket = m;
                        nextInterval = intv;
                        nextCur = cur;
                        nextPrev = series.get(idx - 1);
                    }
                }
            }
            if (nextCur == null) break;

            Pos mp = posByMarket.get(nextMarket);
            int curIdx = mp.idxByInterval.get(nextInterval);
            mp.idxByInterval.put(nextInterval, curIdx + 1);

            // ===== 타임스탬프 그룹핑: 같은 시간대의 다른 인터벌 캔들도 함께 수집 =====
            List<int[]> sameTimeIntervals = new ArrayList<int[]>();
            sameTimeIntervals.add(new int[]{nextInterval, curIdx});

            List<int[]> toAdvance = new ArrayList<int[]>();
            for (Map.Entry<Integer, Integer> ie2 : mp.idxByInterval.entrySet()) {
                int intv2 = ie2.getKey();
                if (intv2 == nextInterval) continue;
                if (stratsByInterval.get(intv2) == null || stratsByInterval.get(intv2).isEmpty()) continue;
                int idx2 = ie2.getValue();
                List<UpbitCandle> series2 = mp.seriesByInterval.get(intv2);
                if (series2 == null || idx2 >= series2.size() || idx2 < 1) continue;
                UpbitCandle cur2 = series2.get(idx2);
                if (cur2 != null && cur2.candle_date_time_utc != null
                        && cur2.candle_date_time_utc.equals(nextCur.candle_date_time_utc)) {
                    sameTimeIntervals.add(new int[]{intv2, idx2});
                    toAdvance.add(new int[]{intv2, idx2 + 1});
                }
            }
            for (int[] adv : toAdvance) {
                mp.idxByInterval.put(adv[0], adv[1]);
            }

            double close = nextCur.trade_price;
            if (close < mp.lastClose && mp.lastClose > 0) mp.downStreak++;
            else if (close >= mp.lastClose || mp.lastClose == 0) mp.downStreak = 0;
            mp.lastClose = close;

            boolean open = mp.qty > 0;

            // ===== 그룹별 유효 설정 해석 =====
            SimulationParams.MarketGroupSettings mgs = hasGroups && marketGroupMap != null
                    ? marketGroupMap.get(nextMarket) : null;
            double effTpPct = (mgs != null ? mgs.tpPct : tpPct);
            double effSlPct = (mgs != null ? mgs.slPct : slPct);
            int effMaxAddBuys = (mgs != null ? mgs.maxAddBuys : maxAddBuysGlobal);
            boolean effStrategyLock = (mgs != null ? mgs.strategyLock : strategyLockEnabled);
            double effMinConfidence = (mgs != null ? mgs.minConfidence : minConfidence);
            int effTimeStopMin = (mgs != null ? mgs.timeStopMinutes : timeStopMinutes);
            double effBaseOrderKrw = (mgs != null ? mgs.baseOrderKrw : baseOrderKrw);

            // ===== TP/SL 체크 =====
            SignalEvaluator.Result tpSlResult = SignalEvaluator.checkTpSl(open, mp.avg, close, effTpPct, effSlPct);
            if (tpSlResult != null) {
                double fill = close * (1.0 - tradeProps.getSlippageRate());
                double gross = mp.qty * fill;
                double fee = gross * strategyCfg.getFeeRate();
                double realized = (gross - fee) - (mp.qty * mp.avg);
                st.sellCount++;
                if (tpSlResult.patternType != null && tpSlResult.patternType.equals("STOP_LOSS")) st.slSellCount++;
                else st.tpSellCount++;
                if (realized > 0) st.winCount++;
                if (detailed) {
                    BacktestTradeRow row = makeRow(nextCur, nextMarket, "SELL", tpSlResult.patternType,
                            fill, mp.qty, realized, tpSlResult.reason, mp.avg);
                    row.confidence = 0;
                    row.candleUnitMin = nextInterval;
                    st.trades.add(row);
                }
                mp.reset();
                st.capital += (gross - fee);
                continue;
            }

            // ===== Time Stop 체크 =====
            if (effTimeStopMin > 0 && open && mp.entryTsMs > 0 && nextCur.candle_date_time_utc != null) {
                long curTsMs = java.time.LocalDateTime.parse(nextCur.candle_date_time_utc)
                        .toInstant(ZoneOffset.UTC).toEpochMilli();
                long elapsedMin = (curTsMs - mp.entryTsMs) / 60000L;
                if (elapsedMin >= effTimeStopMin) {
                    boolean isBuyOnlyEntry = false;
                    if (mp.entryStrategy != null && !mp.entryStrategy.isEmpty()) {
                        try { isBuyOnlyEntry = StrategyType.valueOf(mp.entryStrategy).isBuyOnly(); }
                        catch (Exception ignore) {}
                    }
                    if (isBuyOnlyEntry) {
                        double pnlPct = mp.avg > 0 ? ((close - mp.avg) / mp.avg) * 100.0 : 0;
                        if (pnlPct < 0) {
                            double fill = close * (1.0 - tradeProps.getSlippageRate());
                            double gross = mp.qty * fill;
                            double fee = gross * strategyCfg.getFeeRate();
                            double realized = (gross - fee) - (mp.qty * mp.avg);
                            st.sellCount++;
                            if (realized > 0) st.winCount++;
                            if (detailed) {
                                String tsReason = String.format(Locale.ROOT,
                                        "TIME_STOP %dmin elapsed=%dmin entry=%s pnl=%.2f%%",
                                        effTimeStopMin, elapsedMin, mp.entryStrategy, pnlPct);
                                BacktestTradeRow tsRow = makeRow(nextCur, nextMarket, "SELL", "TIME_STOP",
                                        fill, mp.qty, realized, tsReason, mp.avg);
                                tsRow.confidence = 0;
                                tsRow.candleUnitMin = nextInterval;
                                st.trades.add(tsRow);
                            }
                            mp.reset();
                            st.capital += (gross - fee);
                            continue;
                        }
                    }
                }
            }

            // ===== 전략 평가: 동시 타임스탬프의 모든 인터벌 전략을 평가하고 최적 선택 =====
            com.example.upbit.db.PositionEntity syntheticPos = null;
            if (mp.qty > 0) {
                syntheticPos = new com.example.upbit.db.PositionEntity();
                syntheticPos.setQty(java.math.BigDecimal.valueOf(mp.qty));
                syntheticPos.setAvgPrice(java.math.BigDecimal.valueOf(mp.avg));
                syntheticPos.setAddBuys(mp.addBuys);
                syntheticPos.setEntryStrategy(mp.entryStrategy);
            }

            SignalEvaluator.Result bestEval = null;
            int bestEvalInterval = nextInterval;
            int bestPriority = -1;

            for (int[] sti : sameTimeIntervals) {
                int evalInterval = sti[0];
                int evalCurIdx = sti[1];

                List<StrategyType> groupStrats = stratsByInterval.get(evalInterval);
                if (groupStrats == null || groupStrats.isEmpty()) continue;

                // 그룹 모드: 해당 마켓의 그룹 전략만 필터링
                if (hasGroups && mgs != null && mgs.strategyNames != null) {
                    List<StrategyType> filtered = new ArrayList<StrategyType>();
                    for (StrategyType stt : groupStrats) {
                        if (mgs.strategyNames.contains(stt.name())) filtered.add(stt);
                    }
                    groupStrats = filtered;
                    if (groupStrats.isEmpty()) continue;
                }

                List<UpbitCandle> series = mp.seriesByInterval.get(evalInterval);
                int windowEnd = Math.min(evalCurIdx, series.size());
                if (windowEnd < 2) continue;
                List<UpbitCandle> window = series.subList(0, windowEnd);

                StrategyContext ctx = new StrategyContext(nextMarket, evalInterval, window,
                        syntheticPos, mp.downStreak, emaTrendFilterMap);
                SignalEvaluator.Result evalResult = SignalEvaluator.evaluateStrategies(
                        groupStrats, strategyFactory, ctx);

                if (evalResult.isEmpty()) continue;

                // 우선순위: SELL(3) > ADD_BUY(2) > BUY(1), 동일 우선순위면 confidence 높은 쪽
                int priority;
                if (evalResult.signal.action == SignalAction.SELL) priority = 3;
                else if (evalResult.signal.action == SignalAction.ADD_BUY) priority = 2;
                else priority = 1;

                if (bestEval == null
                        || priority > bestPriority
                        || (priority == bestPriority && evalResult.confidence > bestEval.confidence)) {
                    bestEval = evalResult;
                    bestPriority = priority;
                    bestEvalInterval = evalInterval;
                }
            }

            if (bestEval == null || bestEval.isEmpty()) continue;

            SignalEvaluator.Result evalResult = bestEval;
            nextInterval = bestEvalInterval;
            String patternType = evalResult.patternType;
            String reason = evalResult.reason;

            // ===== BTC 방향 필터: BTC close < EMA 이면 BUY 차단 =====
            boolean btcBlocked = false;
            if (params.btcFilterEnabled && params.btcCandles != null && !params.btcCandles.isEmpty()
                    && (evalResult.signal.action == SignalAction.BUY)) {
                btcBlocked = !checkBtcFilter(params.btcCandles, params.btcEmaPeriod,
                        nextCur.candle_date_time_utc);
            }

            // ===== BUY: 신규 포지션 진입 =====
            if (evalResult.signal.action == SignalAction.BUY && !open && !btcBlocked) {
                if (effMinConfidence > 0 && evalResult.confidence < effMinConfidence) continue;
                double orderKrw = effBaseOrderKrw;
                if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();
                if (st.capital < orderKrw) {
                    if (st.capital >= tradeProps.getMinOrderKrw()) orderKrw = st.capital; else continue;
                }

                double fee = orderKrw * strategyCfg.getFeeRate();
                double net = orderKrw - fee;
                double fill = close * (1.0 + tradeProps.getSlippageRate());
                double addQtyVal = net / fill;

                mp.qty = addQtyVal;
                mp.avg = fill;
                mp.addBuys = 0;
                mp.entryStrategy = patternType;
                mp.entryTsMs = (nextCur.candle_date_time_utc != null)
                        ? java.time.LocalDateTime.parse(nextCur.candle_date_time_utc)
                            .toInstant(ZoneOffset.UTC).toEpochMilli()
                        : 0;

                st.capital -= orderKrw;
                if (detailed) {
                    BacktestTradeRow buyRow = makeRow(nextCur, nextMarket, "BUY", patternType,
                            fill, addQtyVal, 0.0, reason, 0);
                    buyRow.confidence = evalResult.confidence;
                    buyRow.candleUnitMin = nextInterval;
                    st.trades.add(buyRow);
                }
                continue;
            }

            // ===== Cross-strategy ADD_BUY: 다른 전략의 BUY -> 손실 중 ADD_BUY 전환 =====
            if (evalResult.signal.action == SignalAction.BUY && open && !btcBlocked && mp.addBuys < effMaxAddBuys) {
                double currentPnlPct = mp.avg > 0 ? ((close - mp.avg) / mp.avg) * 100.0 : 0;
                if (currentPnlPct >= 0) continue; // 수익 중이면 스킵

                if (effMinConfidence > 0 && evalResult.confidence < effMinConfidence) continue;

                int next = mp.addBuys + 1;
                double orderKrw = effBaseOrderKrw * Math.pow(tradeProps.getAddBuyMultiplier(), next);
                if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();
                if (st.capital < orderKrw) {
                    if (st.capital >= tradeProps.getMinOrderKrw()) orderKrw = st.capital; else continue;
                }

                double fee = orderKrw * strategyCfg.getFeeRate();
                double net = orderKrw - fee;
                double fill = close * (1.0 + tradeProps.getSlippageRate());
                double addQtyVal = net / fill;

                double newQty = mp.qty + addQtyVal;
                mp.avg = (mp.avg * mp.qty + fill * addQtyVal) / newQty;
                mp.qty = newQty;
                mp.addBuys++;

                st.capital -= orderKrw;
                if (detailed) {
                    String xStratLabel = patternType + "(X-STRAT)";
                    String xReason = reason + " | cross-strat ADD_BUY (loss=" + String.format("%.2f", currentPnlPct) + "%)";
                    BacktestTradeRow abRow = makeRow(nextCur, nextMarket, "ADD_BUY", xStratLabel,
                            fill, addQtyVal, 0.0, xReason, 0);
                    abRow.confidence = evalResult.confidence;
                    abRow.candleUnitMin = nextInterval;
                    st.trades.add(abRow);
                }
                continue;
            }

            // ===== ADD_BUY: 동일 전략 추가매수 =====
            if (evalResult.signal.action == SignalAction.ADD_BUY && open && mp.addBuys < effMaxAddBuys) {
                if (effStrategyLock && mp.entryStrategy != null
                        && !mp.entryStrategy.isEmpty() && !mp.entryStrategy.equals(patternType)) {
                    continue;
                }
                int next = mp.addBuys + 1;
                double orderKrw = effBaseOrderKrw * Math.pow(tradeProps.getAddBuyMultiplier(), next);
                if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();
                if (st.capital < orderKrw) {
                    if (st.capital >= tradeProps.getMinOrderKrw()) orderKrw = st.capital; else continue;
                }

                double fee = orderKrw * strategyCfg.getFeeRate();
                double net = orderKrw - fee;
                double fill = close * (1.0 + tradeProps.getSlippageRate());
                double addQtyVal = net / fill;

                double newQty = mp.qty + addQtyVal;
                mp.avg = (mp.avg * mp.qty + fill * addQtyVal) / newQty;
                mp.qty = newQty;
                mp.addBuys++;

                st.capital -= orderKrw;
                if (detailed) {
                    BacktestTradeRow abRow = makeRow(nextCur, nextMarket, "ADD_BUY", patternType,
                            fill, addQtyVal, 0.0, reason, 0);
                    abRow.confidence = evalResult.confidence;
                    abRow.candleUnitMin = nextInterval;
                    st.trades.add(abRow);
                }
                continue;
            }

            // ===== SELL: 포지션 청산 =====
            if (evalResult.signal.action == SignalAction.SELL && open) {
                if (effStrategyLock && !evalResult.isTpSl && mp.entryStrategy != null
                        && !mp.entryStrategy.isEmpty() && !mp.entryStrategy.equals(patternType)) {
                    boolean sellOnlyStrategy = false;
                    try { sellOnlyStrategy = StrategyType.valueOf(patternType).isSellOnly(); }
                    catch (Exception ignore) {}
                    if (!sellOnlyStrategy) continue;
                }
                double fill = close * (1.0 - tradeProps.getSlippageRate());
                double gross = mp.qty * fill;
                double fee = gross * strategyCfg.getFeeRate();
                double realized = (gross - fee) - (mp.qty * mp.avg);

                st.sellCount++;
                if (evalResult.isTpSl) {
                    if ("STOP_LOSS".equals(evalResult.patternType)) st.slSellCount++;
                    else st.tpSellCount++;
                } else {
                    st.patternSellCount++;
                }
                if (realized > 0) st.winCount++;

                if (detailed) {
                    BacktestTradeRow sellRow = makeRow(nextCur, nextMarket, "SELL", patternType,
                            fill, mp.qty, realized, reason, mp.avg);
                    sellRow.confidence = evalResult.confidence;
                    sellRow.candleUnitMin = nextInterval;
                    st.trades.add(sellRow);
                }

                mp.reset();
                st.capital += (gross - fee);
            }
        }

        // ===== 미청산 포지션 시가평가 (Mark-to-Market) =====
        // 백테스트 종료 시점에 보유 중인 포지션을 마지막 종가로 평가하여 capital에 반영
        for (Map.Entry<String, Pos> pe : posByMarket.entrySet()) {
            Pos mp = pe.getValue();
            if (mp.qty > 0 && mp.lastClose > 0) {
                double fill = mp.lastClose * (1.0 - tradeProps.getSlippageRate());
                double gross = mp.qty * fill;
                double fee = gross * strategyCfg.getFeeRate();
                st.capital += (gross - fee);
            }
        }

        return st;
    }

    // ===== BTC 방향 필터 (백테스트용) =====

    /**
     * BTC close >= EMA(period) 인지 확인.
     * 현재 캔들 시각 이전까지의 BTC 캔들로 EMA를 계산한다.
     *
     * @param btcCandles 시간순 정렬된 BTC 캔들 리스트
     * @param emaPeriod  EMA 기간
     * @param curUtc     현재 캔들의 UTC 타임스탬프 (yyyy-MM-ddTHH:mm:ss)
     * @return true면 롱 허용, false면 차단
     */
    private boolean checkBtcFilter(List<UpbitCandle> btcCandles, int emaPeriod, String curUtc) {
        if (curUtc == null || btcCandles.isEmpty()) return true;

        // 현재 타임스탬프 이하의 BTC 캔들만 수집
        int endIdx = -1;
        for (int i = btcCandles.size() - 1; i >= 0; i--) {
            UpbitCandle bc = btcCandles.get(i);
            if (bc.candle_date_time_utc != null && bc.candle_date_time_utc.compareTo(curUtc) <= 0) {
                endIdx = i;
                break;
            }
        }
        if (endIdx < 0 || endIdx + 1 < emaPeriod) return true; // 데이터 부족 시 허용

        // EMA 계산: 해당 시점까지의 캔들로 계산
        List<UpbitCandle> window = btcCandles.subList(0, endIdx + 1);
        double ema = com.example.upbit.strategy.Indicators.ema(window, emaPeriod);
        if (Double.isNaN(ema)) return true;

        double btcClose = btcCandles.get(endIdx).trade_price;
        return btcClose >= ema;
    }

    // ===== 내부 클래스 =====

    /** 시뮬레이션 상태 (루프 결과) */
    private static class SimState {
        double capital;
        int sellCount;
        int winCount;
        int tpSellCount;
        int slSellCount;
        int patternSellCount;
        List<BacktestTradeRow> trades;
    }

    /** 마켓별 포지션 상태 */
    private static class Pos {
        Map<Integer, List<UpbitCandle>> seriesByInterval = new HashMap<Integer, List<UpbitCandle>>();
        Map<Integer, Integer> idxByInterval = new HashMap<Integer, Integer>();
        double qty = 0.0;
        double avg = 0.0;
        int addBuys = 0;
        int downStreak = 0;
        String entryStrategy = null;
        long entryTsMs = 0;
        double lastClose = 0.0;

        void reset() {
            qty = 0.0; avg = 0.0; addBuys = 0; downStreak = 0;
            entryStrategy = null; entryTsMs = 0;
        }
    }

    private static BacktestTradeRow makeRow(UpbitCandle c, String market, String action, String type,
                                             double price, double qty, double pnl, String note, double avgBuyPrice) {
        BacktestTradeRow r = new BacktestTradeRow();
        r.ts = (c.candle_date_time_utc == null ? null : c.candle_date_time_utc.toString());
        r.market = market;
        r.action = action;
        r.orderType = type;
        r.price = price;
        r.qty = qty;
        r.pnlKrw = pnl;
        r.note = note;
        r.avgBuyPrice = avgBuyPrice;
        if (avgBuyPrice > 0 && price > 0) {
            r.roiPercent = ((price - avgBuyPrice) / avgBuyPrice) * 100.0;
        }
        return r;
    }
}
