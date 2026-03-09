package com.example.upbit.backtest;

import com.example.upbit.api.BacktestRequest;
import com.example.upbit.api.BacktestResponse;
import com.example.upbit.api.BacktestTradeRow;
import com.example.upbit.config.StrategyProperties;
import com.example.upbit.config.TradeProperties;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.strategy.*;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * 요청 시점 계산형 백테스트(저장/캐시 없음).
 *
 * - 단일 마켓/단일 포지션(현물 롱 only) 시뮬레이션
 * - 멀티 전략 선택 시: 동일 포지션에 대해 여러 전략을 평가한 뒤 1개 액션만 수행
 *   우선순위: SELL > ADD_BUY > BUY
 *
 * 주의:
 * - 업비트 캔들 API 레이트리밋/200개 제한은 CandleService.fetchLookback()에서 페이징 처리합니다.
 * - 실전 주문/체결과 백테스트는 괴리가 있습니다(슬리피지/수수료/호가단위/부분체결 등).
 */
@Service
public class BacktestService {

    private final CandleService candleService;
    private final StrategyFactory strategyFactory;
    private final StrategyProperties strategyCfg;
    private final TradeProperties tradeProps;

    public BacktestService(
            CandleService candleService,
            StrategyFactory strategyFactory,
            StrategyProperties strategyCfg,
            TradeProperties tradeProps
    ) {
        this.candleService = candleService;
        this.strategyFactory = strategyFactory;
        this.strategyCfg = strategyCfg;
        this.tradeProps = tradeProps;
    }

    public BacktestResponse run(BacktestRequest req) {
        int unit = req.candleUnitMin;
        if (unit <= 0 && req.interval != null) {
            unit = parseIntervalToMin(req.interval);
        }
        if (unit <= 0) unit = 5;

        // market(s)
        List<String> markets = new ArrayList<String>();
        if (req.markets != null) {
            for (String m : req.markets) {
                if (m == null) continue;
                String mm = m.trim();
                if (!mm.isEmpty()) markets.add(mm);
            }
        }
        if (markets.isEmpty() && req.market != null && !req.market.trim().isEmpty()) markets.add(req.market.trim());
        if (markets.isEmpty()) markets.add("KRW-BTC");

        int days = parseDays(req.period);

        // ===== 전략 파싱 + 파라미터 =====
        List<StrategyType> stypes = parseStrategies(req);
        if (stypes.isEmpty()) {
            stypes.add(StrategyType.CONSECUTIVE_DOWN_REBOUND);
        }

        double capital = req.capitalKrw;
        double tpPct = (req.takeProfitPct != null ? req.takeProfitPct.doubleValue() : 4.0);
        double slPct = (req.stopLossPct != null ? req.stopLossPct.doubleValue() : 2.0);
        double baseOrderKrw = resolveBaseOrderKrw(req.capitalKrw, req.orderSizingMode, req.orderSizingValue, slPct);
        if (tpPct < 0) tpPct = 0;
        if (slPct < 0) slPct = 0;

        // IMPORTANT: allow explicit 0 ("no add-buys"). Use default only when not provided.
        int maxAddBuysGlobal = (req.maxAddBuysGlobal == null ? 2 : Math.max(0, req.maxAddBuysGlobal.intValue()));
        boolean strategyLockEnabled = Boolean.TRUE.equals(req.strategyLock);
        double minConfidence = (req.minConfidence != null ? req.minConfidence.doubleValue() : 0);
        int timeStopMinutes = (req.timeStopMinutes != null ? Math.max(0, req.timeStopMinutes.intValue()) : 0);
        String intervalsCsv = (req.strategyIntervalsCsv != null ? req.strategyIntervalsCsv : "");
        String emaFilterCsv = (req.emaFilterCsv != null ? req.emaFilterCsv : "");

        // ===== 전략 그룹 파싱 =====
        // 그룹이 있으면 마켓/전략/TP/SL 등을 그룹별로 오버라이드
        boolean hasGroups = req.groups != null && !req.groups.isEmpty();
        java.util.Map<String, MarketGroupSettings> marketGroupMap = new java.util.HashMap<String, MarketGroupSettings>();

        if (hasGroups) {
            // 그룹에서 마켓/전략 수집 (기존 flat 필드 오버라이드)
            markets.clear();
            stypes.clear();
            java.util.Set<String> allStratNames = new java.util.LinkedHashSet<String>();
            for (BacktestRequest.StrategyGroupDto g : req.groups) {
                MarketGroupSettings gs = new MarketGroupSettings();
                gs.tpPct = (g.takeProfitPct != null ? g.takeProfitPct : tpPct);
                gs.slPct = (g.stopLossPct != null ? g.stopLossPct : slPct);
                gs.maxAddBuys = (g.maxAddBuys != null ? Math.max(0, g.maxAddBuys) : maxAddBuysGlobal);
                gs.strategyLock = Boolean.TRUE.equals(g.strategyLock);
                gs.minConfidence = (g.minConfidence != null ? g.minConfidence : minConfidence);
                gs.timeStopMinutes = (g.timeStopMinutes != null ? Math.max(0, g.timeStopMinutes) : timeStopMinutes);
                gs.candleUnitMin = (g.candleUnitMin > 0 ? g.candleUnitMin : unit);
                gs.orderSizingMode = (g.orderSizingMode != null ? g.orderSizingMode : req.orderSizingMode);
                gs.orderSizingValue = (g.orderSizingValue > 0 ? g.orderSizingValue : req.orderSizingValue);
                gs.baseOrderKrw = resolveBaseOrderKrw(req.capitalKrw, gs.orderSizingMode, gs.orderSizingValue, gs.slPct);
                gs.intervalsCsv = (g.strategyIntervalsCsv != null ? g.strategyIntervalsCsv : "");
                gs.emaFilterCsv = (g.emaFilterCsv != null ? g.emaFilterCsv : "");

                // 전략 파싱
                gs.strategies = new java.util.ArrayList<StrategyType>();
                gs.strategyNames = new java.util.HashSet<String>();
                if (g.strategies != null) {
                    for (String sn : g.strategies) {
                        if (sn == null || sn.trim().isEmpty()) continue;
                        try {
                            StrategyType st = StrategyType.valueOf(sn.trim());
                            gs.strategies.add(st);
                            gs.strategyNames.add(st.name());
                            allStratNames.add(st.name());
                        } catch (Exception ignore) {}
                    }
                }

                // 마켓별 그룹 설정 매핑
                if (g.markets != null) {
                    for (String m : g.markets) {
                        if (m == null || m.trim().isEmpty()) continue;
                        String mk = m.trim();
                        if (!markets.contains(mk)) markets.add(mk);
                        marketGroupMap.put(mk, gs);
                    }
                }
            }
            // 글로벌 전략 목록 = 모든 그룹의 전략 합집합
            for (String sn : allStratNames) {
                try { stypes.add(StrategyType.valueOf(sn)); } catch (Exception ignore) {}
            }
        }

        // ===== 마켓별 전략 배정 파싱 (legacy, 그룹 모드가 아닐 때만) =====
        java.util.Map<String, java.util.Set<String>> marketStrategyMap = null;
        if (!hasGroups && req.marketStrategies != null && !req.marketStrategies.isEmpty()) {
            marketStrategyMap = new java.util.HashMap<String, java.util.Set<String>>();
            for (java.util.Map.Entry<String, java.util.List<String>> e : req.marketStrategies.entrySet()) {
                java.util.Set<String> set = new java.util.HashSet<String>();
                if (e.getValue() != null) {
                    for (String s : e.getValue()) set.add(s);
                }
                marketStrategyMap.put(e.getKey(), set);
            }
        }
        final java.util.Map<String, java.util.Set<String>> mktStratMap = marketStrategyMap;

        // ===== 전략별 유효 인터벌 + EMA 필터 계산 =====
        com.example.upbit.db.BotConfigEntity tmpBc = new com.example.upbit.db.BotConfigEntity();
        tmpBc.setCandleUnitMin(unit);
        tmpBc.setStrategyIntervalsCsv(intervalsCsv);
        tmpBc.setEmaFilterCsv(emaFilterCsv);

        // EMA 트렌드 필터 맵 빌드
        java.util.Map<String, Integer> emaTrendFilterMap = new java.util.HashMap<String, Integer>();
        for (StrategyType st : stypes) {
            emaTrendFilterMap.put(st.name(), tmpBc.getEffectiveEmaPeriod(st));
        }

        // 그룹 모드에서 추가 EMA 맵 빌드 (그룹별 ema_filter_csv 반영)
        if (hasGroups) {
            for (BacktestRequest.StrategyGroupDto g : req.groups) {
                if (g.strategies == null) continue;
                com.example.upbit.db.BotConfigEntity gBc = new com.example.upbit.db.BotConfigEntity();
                gBc.setCandleUnitMin(g.candleUnitMin > 0 ? g.candleUnitMin : unit);
                gBc.setEmaFilterCsv(g.emaFilterCsv != null ? g.emaFilterCsv : "");
                for (String sn : g.strategies) {
                    if (sn == null || sn.trim().isEmpty()) continue;
                    try {
                        StrategyType st = StrategyType.valueOf(sn.trim());
                        emaTrendFilterMap.put(st.name(), gBc.getEffectiveEmaPeriod(st));
                    } catch (Exception ignore) {}
                }
            }
        }

        java.util.Map<Integer, java.util.List<StrategyType>> stratsByInterval =
                new java.util.LinkedHashMap<Integer, java.util.List<StrategyType>>();
        for (StrategyType st : stypes) {
            int effInterval = tmpBc.getEffectiveInterval(st);
            // 그룹 모드에서는 그룹별 인터벌 사용
            if (hasGroups) {
                // 해당 전략이 속한 그룹의 인터벌 사용
                for (MarketGroupSettings gs : marketGroupMap.values()) {
                    if (gs.strategyNames.contains(st.name())) {
                        com.example.upbit.db.BotConfigEntity gBc = new com.example.upbit.db.BotConfigEntity();
                        gBc.setCandleUnitMin(gs.candleUnitMin);
                        gBc.setStrategyIntervalsCsv(gs.intervalsCsv);
                        effInterval = gBc.getEffectiveInterval(st);
                        break;
                    }
                }
            }
            java.util.List<StrategyType> group = stratsByInterval.get(effInterval);
            if (group == null) { group = new java.util.ArrayList<StrategyType>(); stratsByInterval.put(effInterval, group); }
            if (!group.contains(st)) group.add(st);
        }

        java.util.Set<Integer> allIntervals = new java.util.LinkedHashSet<Integer>(stratsByInterval.keySet());

        BacktestResponse res = new BacktestResponse();
        res.candleUnitMin = unit;
        res.periodDays = days;
        res.usedTpPct = tpPct;
        res.usedSlPct = slPct;
        for (StrategyType t : stypes) res.strategies.add(t.name());

        // ===== 멀티 인터벌 캔들 조회: (마켓, 인터벌) 조합별 조회 =====
        Map<String, Map<Integer, List<UpbitCandle>>> candlesByMI = new HashMap<String, Map<Integer, List<UpbitCandle>>>();
        int totalCandleCount = 0;

        for (String m : markets) {
            Map<Integer, List<UpbitCandle>> byInterval = new HashMap<Integer, List<UpbitCandle>>();
            for (int intv : allIntervals) {
                List<UpbitCandle> cs;
                if (req.fromDate != null && req.toDate != null && !req.fromDate.trim().isEmpty() && !req.toDate.trim().isEmpty()) {
                    String fromUtc = toUpbitUtcIsoStart(req.fromDate.trim());
                    String toUtcExclusive = toUpbitUtcIsoExclusive(req.toDate.trim());
                    cs = candleService.fetchBetweenUtc(m, intv, fromUtc, toUtcExclusive);
                } else {
                    cs = candleService.fetchLookback(m, intv, days);
                }
                if (cs == null) cs = new ArrayList<UpbitCandle>();
                byInterval.put(intv, cs);
                totalCandleCount += cs.size();
            }
            candlesByMI.put(m, byInterval);
            res.markets.add(m);
        }
        res.candleCount = totalCandleCount;

        int sellCount = 0;
        int winCount = 0;
        int tpSellCount = 0;
        int slSellCount = 0;
        int patternSellCount = 0;
        int tpMissCount = 0;

        // ===== 멀티 마켓 + 멀티 인터벌: (마켓, 인터벌)별 타임라인 병합 =====
        class Pos {
            // 인터벌별 캔들 시리즈와 인덱스
            Map<Integer, List<UpbitCandle>> seriesByInterval = new HashMap<Integer, List<UpbitCandle>>();
            Map<Integer, Integer> idxByInterval = new HashMap<Integer, Integer>();

            // 공유 포지션 상태
            double qty = 0.0;
            double avg = 0.0;
            int addBuys = 0;
            int downStreak = 0;
            String entryStrategy = null;
            long entryTsMs = 0;

            // 마지막 가격 (downStreak 추적용)
            double lastClose = 0.0;
        }
        Map<String, Pos> posByMarket = new HashMap<String, Pos>();
        for (String m : markets) {
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
            // 모든 (마켓, 인터벌) 조합 중 가장 빠른 다음 캔들 찾기
            String nextMarket = null;
            int nextInterval = 0;
            UpbitCandle nextCur = null;
            UpbitCandle nextPrev = null;

            for (String m : markets) {
                Pos p = posByMarket.get(m);
                if (p == null) continue;
                for (Map.Entry<Integer, Integer> ie : p.idxByInterval.entrySet()) {
                    int intv = ie.getKey();
                    int idx = ie.getValue();
                    List<UpbitCandle> series = p.seriesByInterval.get(intv);
                    if (series == null || idx >= series.size()) continue;
                    if (idx < 1) continue; // 최소 1개 이전 캔들 필요
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

            double prevClose = (nextPrev == null ? nextCur.trade_price : nextPrev.trade_price);
            double close = nextCur.trade_price;
            if (close < mp.lastClose && mp.lastClose > 0) mp.downStreak++;
            else if (close >= mp.lastClose || mp.lastClose == 0) mp.downStreak = 0;
            mp.lastClose = close;

            boolean open = mp.qty > 0;

            // ===== 그룹별 유효 설정 해석 =====
            MarketGroupSettings mgs = hasGroups ? marketGroupMap.get(nextMarket) : null;
            double effTpPct = (mgs != null ? mgs.tpPct : tpPct);
            double effSlPct = (mgs != null ? mgs.slPct : slPct);
            int effMaxAddBuys = (mgs != null ? mgs.maxAddBuys : maxAddBuysGlobal);
            boolean effStrategyLock = (mgs != null ? mgs.strategyLock : strategyLockEnabled);
            double effMinConfidence = (mgs != null ? mgs.minConfidence : minConfidence);
            int effTimeStopMin = (mgs != null ? mgs.timeStopMinutes : timeStopMinutes);
            double effBaseOrderKrw = (mgs != null ? mgs.baseOrderKrw : baseOrderKrw);

            // ===== TP/SL 체크: 그룹별 TP/SL 적용 =====
            SignalEvaluator.Result tpSlResult = SignalEvaluator.checkTpSl(open, mp.avg, close, effTpPct, effSlPct);
            if (tpSlResult != null) {
                double fill = close * (1.0 - tradeProps.getSlippageRate());
                double gross = mp.qty * fill;
                double fee = gross * strategyCfg.getFeeRate();
                double realized = (gross - fee) - (mp.qty * mp.avg);
                sellCount++;
                if (tpSlResult.patternType != null && tpSlResult.patternType.equals("STOP_LOSS")) slSellCount++; else tpSellCount++;
                if (realized > 0) winCount++;
                BacktestTradeRow tpSlRow = row(nextCur, nextMarket, "SELL", tpSlResult.patternType, fill, mp.qty, realized, tpSlResult.reason, mp.avg);
                tpSlRow.confidence = 0;
                tpSlRow.candleUnitMin = nextInterval;
                res.trades.add(tpSlRow);
                mp.qty = 0.0; mp.avg = 0.0; mp.addBuys = 0; mp.downStreak = 0; mp.entryStrategy = null; mp.entryTsMs = 0;
                capital += (gross - fee);
                continue;
            }

            // ===== Time Stop 체크 =====
            if (effTimeStopMin > 0 && open && mp.entryTsMs > 0 && nextCur.candle_date_time_utc != null) {
                long curTsMs = java.time.LocalDateTime.parse(nextCur.candle_date_time_utc).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
                long elapsedMin = (curTsMs - mp.entryTsMs) / 60000L;
                if (elapsedMin >= effTimeStopMin) {
                    boolean isBuyOnlyEntry = false;
                    if (mp.entryStrategy != null && !mp.entryStrategy.isEmpty()) {
                        try { isBuyOnlyEntry = StrategyType.valueOf(mp.entryStrategy).isBuyOnly(); } catch (Exception ignore) {}
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
                            String tsReason = String.format(java.util.Locale.ROOT,
                                    "TIME_STOP %dmin elapsed=%dmin entry=%s pnl=%.2f%%",
                                    effTimeStopMin, elapsedMin, mp.entryStrategy, pnlPct);
                            BacktestTradeRow tsRow = row(nextCur, nextMarket, "SELL", "TIME_STOP", fill, mp.qty, realized, tsReason, mp.avg);
                            tsRow.confidence = 0;
                            tsRow.candleUnitMin = nextInterval;
                            res.trades.add(tsRow);
                            mp.qty = 0.0; mp.avg = 0.0; mp.addBuys = 0; mp.downStreak = 0; mp.entryStrategy = null; mp.entryTsMs = 0;
                            capital += (gross - fee);
                            continue;
                        }
                    }
                }
            }

            // ===== 전략 평가: 현재 인터벌 그룹의 전략만 평가 =====
            java.util.List<StrategyType> groupStrats = stratsByInterval.get(nextInterval);
            if (groupStrats == null || groupStrats.isEmpty()) continue;

            // 그룹 모드: 해당 마켓의 그룹 전략만 필터링
            if (hasGroups && mgs != null && mgs.strategyNames != null) {
                java.util.List<StrategyType> filtered = new java.util.ArrayList<StrategyType>();
                for (StrategyType st : groupStrats) {
                    if (mgs.strategyNames.contains(st.name())) filtered.add(st);
                }
                groupStrats = filtered;
                if (groupStrats.isEmpty()) continue;
            }

            // 레거시 마켓별 전략 필터링 (그룹 모드가 아닐 때)
            if (!hasGroups && mktStratMap != null) {
                java.util.Set<String> allowed = mktStratMap.get(nextMarket);
                if (allowed != null && !allowed.isEmpty()) {
                    java.util.List<StrategyType> filtered = new java.util.ArrayList<StrategyType>();
                    for (StrategyType st : groupStrats) {
                        if (allowed.contains(st.name())) filtered.add(st);
                    }
                    groupStrats = filtered;
                    if (groupStrats.isEmpty()) continue;
                }
            }

            List<UpbitCandle> series = mp.seriesByInterval.get(nextInterval);
            int windowEnd = Math.min(curIdx + 1, series.size());
            List<UpbitCandle> window = series.subList(0, windowEnd);

            com.example.upbit.db.PositionEntity syntheticPos = null;
            if (mp.qty > 0) {
                syntheticPos = new com.example.upbit.db.PositionEntity();
                syntheticPos.setQty(java.math.BigDecimal.valueOf(mp.qty));
                syntheticPos.setAvgPrice(java.math.BigDecimal.valueOf(mp.avg));
                syntheticPos.setAddBuys(mp.addBuys);
                syntheticPos.setEntryStrategy(mp.entryStrategy);
            }
            StrategyContext ctx = new StrategyContext(nextMarket, nextInterval, window, syntheticPos, mp.downStreak, emaTrendFilterMap);
            SignalEvaluator.Result evalResult = SignalEvaluator.evaluateStrategies(groupStrats, strategyFactory, ctx);

            if (evalResult.isEmpty()) continue;

            String patternType = evalResult.patternType;
            String reason = evalResult.reason;

            if (evalResult.signal.action == SignalAction.BUY && !open) {
                // Confidence filter: BUY 신호가 최소 점수 미달이면 스킵
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
                // 캔들 시간을 epoch ms로 변환
                mp.entryTsMs = (nextCur.candle_date_time_utc != null)
                        ? java.time.LocalDateTime.parse(nextCur.candle_date_time_utc).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                        : 0;

                capital -= orderKrw;
                BacktestTradeRow buyRow = row(nextCur, nextMarket, "BUY", patternType, fill, addQtyVal, 0.0, reason);
                buyRow.confidence = evalResult.confidence;
                buyRow.candleUnitMin = nextInterval;
                res.trades.add(buyRow);
                continue;
            }

            if (evalResult.signal.action == SignalAction.ADD_BUY && open && mp.addBuys < effMaxAddBuys) {
                // Strategy Lock: 매수전략과 다른 전략의 추가매수 차단
                if (effStrategyLock && mp.entryStrategy != null
                        && !mp.entryStrategy.isEmpty() && !mp.entryStrategy.equals(patternType)) {
                    continue; // 전략 불일치 → 추가매수 차단
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
                BacktestTradeRow abRow = row(nextCur, nextMarket, "ADD_BUY", patternType, fill, addQtyVal, 0.0, reason);
                abRow.confidence = evalResult.confidence;
                abRow.candleUnitMin = nextInterval;
                res.trades.add(abRow);
                continue;
            }

            if (evalResult.signal.action == SignalAction.SELL && open) {
                // Strategy Lock: TP/SL은 항상 허용, 매도 전용 전략(하락 장악형 등)도 항상 허용
                if (effStrategyLock && !evalResult.isTpSl && mp.entryStrategy != null
                        && !mp.entryStrategy.isEmpty() && !mp.entryStrategy.equals(patternType)) {
                    boolean sellOnlyStrategy = false;
                    try { sellOnlyStrategy = StrategyType.valueOf(patternType).isSellOnly(); } catch (Exception ignore) {}
                    if (!sellOnlyStrategy) {
                        continue; // 전략 불일치 + 매도 전용 아님 → 매도 차단
                    }
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

                BacktestTradeRow sellRow = row(nextCur, nextMarket, "SELL", patternType, fill, mp.qty, realized, reason, mp.avg);
                sellRow.confidence = evalResult.confidence;
                sellRow.candleUnitMin = nextInterval;
                res.trades.add(sellRow);

                mp.qty = 0.0;
                mp.avg = 0.0;
                mp.addBuys = 0;
                mp.downStreak = 0;
                mp.entryStrategy = null;
                mp.entryTsMs = 0;

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
        res.tpMissCount = tpMissCount;

        double start = req.capitalKrw;
        res.totalReturn = capital - start;
        res.roi = (start <= 0 ? 0.0 : (res.totalReturn / start) * 100.0);

        // legacy compatibility
        res.totalPnl = res.totalReturn;
        res.totalRoi = res.roi;
        res.totalTrades = res.tradesCount;

        return res;
    }

    private double resolveBaseOrderKrw(double capitalKrw, String mode, double value) {
        return resolveBaseOrderKrw(capitalKrw, mode, value, 2.0);
    }

    private double resolveBaseOrderKrw(double capitalKrw, String mode, double value, double slPct) {
        String m = (mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT));
        double base;
        if ("PCT".equals(m) || "PERCENT".equals(m) || "PERCENTAGE".equals(m)) {
            base = capitalKrw * (value / 100.0);
        } else if ("ATR_RISK".equals(m)) {
            double targetRiskPct = value > 0 ? value : 1.0;
            double effectiveSlPct = slPct > 0 ? slPct : 2.0;
            base = capitalKrw * (targetRiskPct / effectiveSlPct);
        } else if (value > 0) {
            base = value;
        } else {
            base = tradeProps.getGlobalBaseOrderKrw();
        }
        if (base <= 0) base = tradeProps.getGlobalBaseOrderKrw();
        if (base < tradeProps.getMinOrderKrw()) base = tradeProps.getMinOrderKrw();
        return base;
    }

    private int parseIntervalToMin(String interval) {
        if (interval == null) return 5;
        String s = interval.trim();

        // FE(v3) 권장: "1m", "3m", "60m", "240m", "1d"
        if (s.equalsIgnoreCase("1d")) return 1440;
        if (s.toLowerCase(Locale.ROOT).endsWith("m")) {
            try { return Integer.parseInt(s.substring(0, s.length() - 1)); } catch (Exception ignore) { return 5; }
        }

        // Legacy: "5분" 등
        s = s.replace("분", "");
        try { return Integer.parseInt(s); } catch (Exception ignore) {}
        return 5;
    }

    private int parseDays(String period) {
        if (period == null) return 7;
        String p = period.trim().toLowerCase();
        // UI friendly
        if (p.contains("1일")) return 1;
        if (p.contains("1달") || p.contains("30")) return 30;
        if (p.contains("3달")) return 90;
        if (p.contains("1주") || p.contains("7")) return 7;
        if (p.contains("1d")) return 1;
        if (p.contains("7d")) return 7;
        if (p.contains("30d")) return 30;
        return 7;
    }

    // UI datepicker (KST yyyy-MM-dd or yyyy-MM-ddTHH:mm) -> Upbit API UTC ISO (always with seconds)
    private static final DateTimeFormatter UPBIT_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static boolean hasTime(String s) {
        return s != null && s.contains("T");
    }

    private static LocalDateTime parseKstDateTime(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        if (hasTime(v)) {
            // datetime-local: yyyy-MM-ddTHH:mm (seconds optional)
            if (v.length() == 16) v = v + ":00";
            return LocalDateTime.parse(v);
        }
        // date only
        return LocalDate.parse(v).atStartOfDay();
    }

    private static String toUpbitUtcIsoStart(String kst) {
        LocalDateTime ldt = parseKstDateTime(kst);
        if (ldt == null) return null;
        ZonedDateTime z = ldt.atZone(ZoneId.of("Asia/Seoul"));
        ZonedDateTime u = z.withZoneSameInstant(ZoneOffset.UTC);
        return u.toLocalDateTime().format(UPBIT_ISO);
    }

    // toDate inclusive upper bound -> exclusive
    // - date only: (to + 1day 00:00 KST)
    // - datetime: (to + 1minute) so the candle stamped exactly at 'to' minute is included
    private static String toUpbitUtcIsoExclusive(String kst) {
        if (kst == null) return null;
        String v = kst.trim();
        if (v.isEmpty()) return null;

        LocalDateTime ldt;
        if (hasTime(v)) {
            ldt = parseKstDateTime(v).plusMinutes(1);
        } else {
            ldt = LocalDate.parse(v).plusDays(1).atStartOfDay();
        }
        ZonedDateTime z = ldt.atZone(ZoneId.of("Asia/Seoul"));
        ZonedDateTime u = z.withZoneSameInstant(ZoneOffset.UTC);
        return u.toLocalDateTime().format(UPBIT_ISO);
    }

    private List<StrategyType> parseStrategies(BacktestRequest req) {
        List<StrategyType> out = new ArrayList<StrategyType>();
        if (req.strategies != null && !req.strategies.isEmpty()) {
            for (String s : req.strategies) {
                if (s == null) continue;
                try { out.add(StrategyType.valueOf(s.trim())); } catch (Exception ignore) {}
            }
        } else if (req.strategyType != null && !req.strategyType.trim().isEmpty()) {
            try { out.add(StrategyType.valueOf(req.strategyType.trim())); } catch (Exception ignore) {}
        }
        return out;
    }

    private BacktestTradeRow row(UpbitCandle c, String market, String action, String type, double price, double qty, double pnl, String note) {
        return row(c, market, action, type, price, qty, pnl, note, 0);
    }

    private BacktestTradeRow row(UpbitCandle c, String market, String action, String type, double price, double qty, double pnl, String note, double avgBuyPrice) {
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
        // candleUnitMin은 호출부에서 설정 (nextInterval)
        return r;
    }

    /**
     * 그룹별 마켓 설정: 백테스트 시 마켓별로 TP/SL/전략/인터벌 등을 독립 적용.
     */
    private static class MarketGroupSettings {
        double tpPct;
        double slPct;
        int maxAddBuys;
        boolean strategyLock;
        double minConfidence;
        int timeStopMinutes;
        int candleUnitMin;
        String orderSizingMode;
        double orderSizingValue;
        double baseOrderKrw;
        String intervalsCsv;
        String emaFilterCsv;
        java.util.List<StrategyType> strategies;
        java.util.Set<String> strategyNames;
    }
}
