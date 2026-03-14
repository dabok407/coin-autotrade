package com.example.upbit.backtest;

import com.example.upbit.api.BacktestResponse;
import com.example.upbit.api.BacktestTradeRow;
import com.example.upbit.config.StrategyProperties;
import com.example.upbit.config.TradeProperties;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.strategy.*;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 순수 계산 기반 백테스트 시뮬레이터.
 * API 호출 없이 사전 로딩된 캔들 데이터로 시뮬레이션을 수행합니다.
 * 스레드 안전: 모든 상태가 메서드 내부에서 생성됩니다.
 */
@Component
public class BacktestSimulator {

    private final StrategyFactory strategyFactory;
    private final StrategyProperties strategyCfg;
    private final TradeProperties tradeProps;

    public BacktestSimulator(StrategyFactory strategyFactory,
                              StrategyProperties strategyCfg,
                              TradeProperties tradeProps) {
        this.strategyFactory = strategyFactory;
        this.strategyCfg = strategyCfg;
        this.tradeProps = tradeProps;
    }

    /**
     * 사전 로딩된 캔들 데이터로 시뮬레이션 실행.
     *
     * @param params      시뮬레이션 파라미터
     * @param candlesByMI Map<market, Map<intervalMin, List<UpbitCandle>>>
     * @return 백테스트 결과
     */
    public BacktestResponse simulate(SimulationParams params,
                                      Map<String, Map<Integer, List<UpbitCandle>>> candlesByMI) {

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

        BacktestResponse res = new BacktestResponse();
        res.candleUnitMin = params.candleUnitMin;
        res.usedTpPct = tpPct;
        res.usedSlPct = slPct;
        for (StrategyType t : params.strategies) res.strategies.add(t.name());

        int totalCandleCount = 0;
        for (Map.Entry<String, Map<Integer, List<UpbitCandle>>> e : candlesByMI.entrySet()) {
            res.markets.add(e.getKey());
            for (List<UpbitCandle> cs : e.getValue().values()) {
                totalCandleCount += cs.size();
            }
        }
        res.candleCount = totalCandleCount;

        int sellCount = 0, winCount = 0;
        int tpSellCount = 0, slSellCount = 0, patternSellCount = 0;

        // 마켓별 포지션 상태
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
            String nextMarket = null;
            int nextInterval = 0;
            UpbitCandle nextCur = null;
            UpbitCandle nextPrev = null;

            for (String m : params.markets) {
                Pos p = posByMarket.get(m);
                if (p == null) continue;
                for (Map.Entry<Integer, Integer> ie : p.idxByInterval.entrySet()) {
                    int intv = ie.getKey();
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

            double close = nextCur.trade_price;
            if (close < mp.lastClose && mp.lastClose > 0) mp.downStreak++;
            else if (close >= mp.lastClose || mp.lastClose == 0) mp.downStreak = 0;
            mp.lastClose = close;

            boolean open = mp.qty > 0;

            // 그룹별 유효 설정
            SimulationParams.MarketGroupSettings mgs = hasGroups && marketGroupMap != null
                    ? marketGroupMap.get(nextMarket) : null;
            double effTpPct = (mgs != null ? mgs.tpPct : tpPct);
            double effSlPct = (mgs != null ? mgs.slPct : slPct);
            int effMaxAddBuys = (mgs != null ? mgs.maxAddBuys : maxAddBuysGlobal);
            boolean effStrategyLock = (mgs != null ? mgs.strategyLock : strategyLockEnabled);
            double effMinConfidence = (mgs != null ? mgs.minConfidence : minConfidence);
            int effTimeStopMin = (mgs != null ? mgs.timeStopMinutes : timeStopMinutes);
            double effBaseOrderKrw = (mgs != null ? mgs.baseOrderKrw : baseOrderKrw);

            // TP/SL 체크
            SignalEvaluator.Result tpSlResult = SignalEvaluator.checkTpSl(open, mp.avg, close, effTpPct, effSlPct);
            if (tpSlResult != null) {
                double fill = close * (1.0 - tradeProps.getSlippageRate());
                double gross = mp.qty * fill;
                double fee = gross * strategyCfg.getFeeRate();
                double realized = (gross - fee) - (mp.qty * mp.avg);
                sellCount++;
                if (tpSlResult.patternType != null && tpSlResult.patternType.equals("STOP_LOSS")) slSellCount++;
                else tpSellCount++;
                if (realized > 0) winCount++;
                BacktestTradeRow row = makeRow(nextCur, nextMarket, "SELL", tpSlResult.patternType,
                        fill, mp.qty, realized, tpSlResult.reason, mp.avg);
                row.confidence = 0;
                row.candleUnitMin = nextInterval;
                res.trades.add(row);
                mp.reset();
                capital += (gross - fee);
                continue;
            }

            // Time Stop 체크
            if (effTimeStopMin > 0 && open && mp.entryTsMs > 0 && nextCur.candle_date_time_utc != null) {
                long curTsMs = java.time.LocalDateTime.parse(nextCur.candle_date_time_utc)
                        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
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
                            sellCount++;
                            if (realized > 0) winCount++;
                            String tsReason = String.format(Locale.ROOT,
                                    "TIME_STOP %dmin elapsed=%dmin entry=%s pnl=%.2f%%",
                                    effTimeStopMin, elapsedMin, mp.entryStrategy, pnlPct);
                            BacktestTradeRow tsRow = makeRow(nextCur, nextMarket, "SELL", "TIME_STOP",
                                    fill, mp.qty, realized, tsReason, mp.avg);
                            tsRow.confidence = 0;
                            tsRow.candleUnitMin = nextInterval;
                            res.trades.add(tsRow);
                            mp.reset();
                            capital += (gross - fee);
                            continue;
                        }
                    }
                }
            }

            // 전략 평가
            List<StrategyType> groupStrats = stratsByInterval.get(nextInterval);
            if (groupStrats == null || groupStrats.isEmpty()) continue;

            // 그룹 모드: 해당 마켓의 그룹 전략만 필터링
            if (hasGroups && mgs != null && mgs.strategyNames != null) {
                List<StrategyType> filtered = new ArrayList<StrategyType>();
                for (StrategyType st : groupStrats) {
                    if (mgs.strategyNames.contains(st.name())) filtered.add(st);
                }
                groupStrats = filtered;
                if (groupStrats.isEmpty()) continue;
            }

            List<UpbitCandle> series = mp.seriesByInterval.get(nextInterval);
            // FIX: curIdx(현재 캔들)를 제외하여 look-ahead bias 방지
            int windowEnd = Math.min(curIdx, series.size());
            if (windowEnd < 2) continue;
            List<UpbitCandle> window = series.subList(0, windowEnd);

            com.example.upbit.db.PositionEntity syntheticPos = null;
            if (mp.qty > 0) {
                syntheticPos = new com.example.upbit.db.PositionEntity();
                syntheticPos.setQty(java.math.BigDecimal.valueOf(mp.qty));
                syntheticPos.setAvgPrice(java.math.BigDecimal.valueOf(mp.avg));
                syntheticPos.setAddBuys(mp.addBuys);
                syntheticPos.setEntryStrategy(mp.entryStrategy);
            }
            StrategyContext ctx = new StrategyContext(nextMarket, nextInterval, window, syntheticPos,
                    mp.downStreak, emaTrendFilterMap);
            SignalEvaluator.Result evalResult = SignalEvaluator.evaluateStrategies(groupStrats, strategyFactory, ctx);

            if (evalResult.isEmpty()) continue;

            String patternType = evalResult.patternType;
            String reason = evalResult.reason;

            if (evalResult.signal.action == SignalAction.BUY && !open) {
                if (effMinConfidence > 0 && evalResult.confidence < effMinConfidence) continue;
                double orderKrw = effBaseOrderKrw;
                if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();
                if (capital < orderKrw) {
                    if (capital >= tradeProps.getMinOrderKrw()) orderKrw = capital; else continue;
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
                            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                        : 0;

                capital -= orderKrw;
                BacktestTradeRow buyRow = makeRow(nextCur, nextMarket, "BUY", patternType,
                        fill, addQtyVal, 0.0, reason, 0);
                buyRow.confidence = evalResult.confidence;
                buyRow.candleUnitMin = nextInterval;
                res.trades.add(buyRow);
                continue;
            }

            if (evalResult.signal.action == SignalAction.ADD_BUY && open && mp.addBuys < effMaxAddBuys) {
                if (effStrategyLock && mp.entryStrategy != null
                        && !mp.entryStrategy.isEmpty() && !mp.entryStrategy.equals(patternType)) {
                    continue;
                }
                int next = mp.addBuys + 1;
                double orderKrw = effBaseOrderKrw * Math.pow(tradeProps.getAddBuyMultiplier(), next);
                if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();
                if (capital < orderKrw) {
                    if (capital >= tradeProps.getMinOrderKrw()) orderKrw = capital; else continue;
                }

                double fee = orderKrw * strategyCfg.getFeeRate();
                double net = orderKrw - fee;
                double fill = close * (1.0 + tradeProps.getSlippageRate());
                double addQtyVal = net / fill;

                double newQty = mp.qty + addQtyVal;
                mp.avg = (mp.avg * mp.qty + fill * addQtyVal) / newQty;
                mp.qty = newQty;
                mp.addBuys++;

                capital -= orderKrw;
                BacktestTradeRow abRow = makeRow(nextCur, nextMarket, "ADD_BUY", patternType,
                        fill, addQtyVal, 0.0, reason, 0);
                abRow.confidence = evalResult.confidence;
                abRow.candleUnitMin = nextInterval;
                res.trades.add(abRow);
                continue;
            }

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

                sellCount++;
                if (evalResult.isTpSl) {
                    if ("STOP_LOSS".equals(evalResult.patternType)) slSellCount++;
                    else tpSellCount++;
                } else {
                    patternSellCount++;
                }
                if (realized > 0) winCount++;

                BacktestTradeRow sellRow = makeRow(nextCur, nextMarket, "SELL", patternType,
                        fill, mp.qty, realized, reason, mp.avg);
                sellRow.confidence = evalResult.confidence;
                sellRow.candleUnitMin = nextInterval;
                res.trades.add(sellRow);

                mp.reset();
                capital += (gross - fee);
            }
        }

        res.tradesCount = res.trades.size();
        res.wins = winCount;
        res.winRate = (sellCount == 0 ? 0.0 : (winCount * 100.0 / sellCount));
        res.finalCapital = capital;
        res.tpSellCount = tpSellCount;
        res.slSellCount = slSellCount;
        res.patternSellCount = patternSellCount;

        double start = params.capitalKrw;
        res.totalReturn = capital - start;
        res.roi = (start <= 0 ? 0.0 : (res.totalReturn / start) * 100.0);
        res.totalPnl = res.totalReturn;
        res.totalRoi = res.roi;
        res.totalTrades = res.tradesCount;

        return res;
    }

    // ===== 경량 시뮬레이션 (최적화 엔진용: trade row 생성 없이 ROI/승률만 계산) =====

    /**
     * 경량 시뮬레이션: ROI, 승률, 거래수만 반환 (최적화 루프 고속화).
     */
    public OptimizationMetrics simulateFast(SimulationParams params,
                                             Map<String, Map<Integer, List<UpbitCandle>>> candlesByMI) {
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

        int sellCount = 0, winCount = 0;
        int tpSells = 0, slSells = 0, patternSells = 0;

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

        while (true) {
            String nextMarket = null;
            int nextInterval = 0;
            UpbitCandle nextCur = null;

            for (String m : params.markets) {
                Pos p = posByMarket.get(m);
                if (p == null) continue;
                for (Map.Entry<Integer, Integer> ie : p.idxByInterval.entrySet()) {
                    int intv = ie.getKey();
                    int idx = ie.getValue();
                    List<UpbitCandle> series = p.seriesByInterval.get(intv);
                    if (series == null || idx >= series.size() || idx < 1) continue;
                    UpbitCandle cur = series.get(idx);
                    if (cur == null || cur.candle_date_time_utc == null) continue;
                    if (nextCur == null || cur.candle_date_time_utc.compareTo(nextCur.candle_date_time_utc) < 0) {
                        nextMarket = m;
                        nextInterval = intv;
                        nextCur = cur;
                    }
                }
            }
            if (nextCur == null) break;

            Pos mp = posByMarket.get(nextMarket);
            int curIdx = mp.idxByInterval.get(nextInterval);
            mp.idxByInterval.put(nextInterval, curIdx + 1);

            double close = nextCur.trade_price;
            if (close < mp.lastClose && mp.lastClose > 0) mp.downStreak++;
            else mp.downStreak = 0;
            mp.lastClose = close;

            boolean open = mp.qty > 0;

            // TP/SL
            SignalEvaluator.Result tpSlResult = SignalEvaluator.checkTpSl(open, mp.avg, close, tpPct, slPct);
            if (tpSlResult != null) {
                double fill = close * (1.0 - tradeProps.getSlippageRate());
                double gross = mp.qty * fill;
                double fee = gross * strategyCfg.getFeeRate();
                double realized = (gross - fee) - (mp.qty * mp.avg);
                sellCount++;
                if (tpSlResult.patternType != null && tpSlResult.patternType.equals("STOP_LOSS")) slSells++;
                else tpSells++;
                if (realized > 0) winCount++;
                mp.reset();
                capital += (gross - fee);
                continue;
            }

            // Time Stop
            if (timeStopMinutes > 0 && open && mp.entryTsMs > 0 && nextCur.candle_date_time_utc != null) {
                long curTsMs = java.time.LocalDateTime.parse(nextCur.candle_date_time_utc)
                        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
                long elapsedMin = (curTsMs - mp.entryTsMs) / 60000L;
                if (elapsedMin >= timeStopMinutes) {
                    boolean isBuyOnlyEntry = false;
                    if (mp.entryStrategy != null) {
                        try { isBuyOnlyEntry = StrategyType.valueOf(mp.entryStrategy).isBuyOnly(); }
                        catch (Exception ignore) {}
                    }
                    if (isBuyOnlyEntry && mp.avg > 0 && ((close - mp.avg) / mp.avg) < 0) {
                        double fill = close * (1.0 - tradeProps.getSlippageRate());
                        double gross = mp.qty * fill;
                        double fee = gross * strategyCfg.getFeeRate();
                        double realized = (gross - fee) - (mp.qty * mp.avg);
                        sellCount++;
                        if (realized > 0) winCount++;
                        mp.reset();
                        capital += (gross - fee);
                        continue;
                    }
                }
            }

            // 전략 평가
            List<StrategyType> groupStrats = stratsByInterval.get(nextInterval);
            if (groupStrats == null || groupStrats.isEmpty()) continue;

            List<UpbitCandle> series = mp.seriesByInterval.get(nextInterval);
            int windowEnd = Math.min(curIdx, series.size());
            if (windowEnd < 2) continue;
            List<UpbitCandle> window = series.subList(0, windowEnd);

            com.example.upbit.db.PositionEntity syntheticPos = null;
            if (mp.qty > 0) {
                syntheticPos = new com.example.upbit.db.PositionEntity();
                syntheticPos.setQty(java.math.BigDecimal.valueOf(mp.qty));
                syntheticPos.setAvgPrice(java.math.BigDecimal.valueOf(mp.avg));
                syntheticPos.setAddBuys(mp.addBuys);
                syntheticPos.setEntryStrategy(mp.entryStrategy);
            }
            StrategyContext ctx = new StrategyContext(nextMarket, nextInterval, window, syntheticPos,
                    mp.downStreak, emaTrendFilterMap);
            SignalEvaluator.Result evalResult = SignalEvaluator.evaluateStrategies(groupStrats, strategyFactory, ctx);

            if (evalResult.isEmpty()) continue;

            String patternType = evalResult.patternType;

            if (evalResult.signal.action == SignalAction.BUY && !open) {
                if (minConfidence > 0 && evalResult.confidence < minConfidence) continue;
                double orderKrw = baseOrderKrw;
                if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();
                if (capital < orderKrw) {
                    if (capital >= tradeProps.getMinOrderKrw()) orderKrw = capital; else continue;
                }
                double fee = orderKrw * strategyCfg.getFeeRate();
                double fill = close * (1.0 + tradeProps.getSlippageRate());
                mp.qty = (orderKrw - fee) / fill;
                mp.avg = fill;
                mp.addBuys = 0;
                mp.entryStrategy = patternType;
                mp.entryTsMs = nextCur.candle_date_time_utc != null
                        ? java.time.LocalDateTime.parse(nextCur.candle_date_time_utc)
                            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                        : 0;
                capital -= orderKrw;
                continue;
            }

            if (evalResult.signal.action == SignalAction.ADD_BUY && open && mp.addBuys < maxAddBuysGlobal) {
                if (strategyLockEnabled && mp.entryStrategy != null
                        && !mp.entryStrategy.isEmpty() && !mp.entryStrategy.equals(patternType)) continue;
                int next = mp.addBuys + 1;
                double orderKrw = baseOrderKrw * Math.pow(tradeProps.getAddBuyMultiplier(), next);
                if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();
                if (capital < orderKrw) {
                    if (capital >= tradeProps.getMinOrderKrw()) orderKrw = capital; else continue;
                }
                double fee = orderKrw * strategyCfg.getFeeRate();
                double fill = close * (1.0 + tradeProps.getSlippageRate());
                double addQtyVal = (orderKrw - fee) / fill;
                double newQty = mp.qty + addQtyVal;
                mp.avg = (mp.avg * mp.qty + fill * addQtyVal) / newQty;
                mp.qty = newQty;
                mp.addBuys++;
                capital -= orderKrw;
                continue;
            }

            if (evalResult.signal.action == SignalAction.SELL && open) {
                if (strategyLockEnabled && !evalResult.isTpSl && mp.entryStrategy != null
                        && !mp.entryStrategy.isEmpty() && !mp.entryStrategy.equals(patternType)) {
                    boolean sellOnly = false;
                    try { sellOnly = StrategyType.valueOf(patternType).isSellOnly(); }
                    catch (Exception ignore) {}
                    if (!sellOnly) continue;
                }
                double fill = close * (1.0 - tradeProps.getSlippageRate());
                double gross = mp.qty * fill;
                double fee = gross * strategyCfg.getFeeRate();
                double realized = (gross - fee) - (mp.qty * mp.avg);
                sellCount++;
                if (evalResult.isTpSl) {
                    if ("STOP_LOSS".equals(evalResult.patternType)) slSells++;
                    else tpSells++;
                } else { patternSells++; }
                if (realized > 0) winCount++;
                mp.reset();
                capital += (gross - fee);
            }
        }

        OptimizationMetrics m = new OptimizationMetrics();
        m.roi = (params.capitalKrw <= 0 ? 0.0 : ((capital - params.capitalKrw) / params.capitalKrw) * 100.0);
        m.winRate = (sellCount == 0 ? 0.0 : (winCount * 100.0 / sellCount));
        m.totalTrades = sellCount;
        m.wins = winCount;
        m.finalCapital = capital;
        m.totalPnl = capital - params.capitalKrw;
        m.tpSellCount = tpSells;
        m.slSellCount = slSells;
        m.patternSellCount = patternSells;
        return m;
    }

    // ===== 내부 클래스 =====

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

    /**
     * 최적화 결과 경량 메트릭.
     */
    public static class OptimizationMetrics {
        public double roi;
        public double winRate;
        public int totalTrades;
        public int wins;
        public double finalCapital;
        public double totalPnl;
        public int tpSellCount;
        public int slSellCount;
        public int patternSellCount;
    }

    private BacktestTradeRow makeRow(UpbitCandle c, String market, String action, String type,
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
