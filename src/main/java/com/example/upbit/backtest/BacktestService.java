package com.example.upbit.backtest;

import com.example.upbit.api.BacktestRequest;
import com.example.upbit.api.BacktestResponse;
import com.example.upbit.config.StrategyProperties;
import com.example.upbit.config.TradeProperties;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.strategy.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

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
            stypes.add(StrategyType.ADAPTIVE_TREND_MOMENTUM);
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
        // 그룹 모드에서는 첫 번째 그룹의 TP/SL 값 표시 (단일 그룹이 대부분)
        if (hasGroups && !marketGroupMap.isEmpty()) {
            MarketGroupSettings firstGs = marketGroupMap.values().iterator().next();
            res.usedTpPct = firstGs.tpPct;
            res.usedSlPct = firstGs.slPct;
        } else {
            res.usedTpPct = tpPct;
            res.usedSlPct = slPct;
        }
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
        // ===== TradingEngine으로 위임: 동일한 매매 로직 사용 =====
        SimulationParams params = new SimulationParams();
        params.markets = markets;
        params.strategies = stypes;
        params.capitalKrw = capital;
        params.tpPct = tpPct;
        params.slPct = slPct;
        params.baseOrderKrw = baseOrderKrw;
        params.maxAddBuys = maxAddBuysGlobal;
        params.strategyLock = strategyLockEnabled;
        params.minConfidence = minConfidence;
        params.timeStopMinutes = timeStopMinutes;
        params.candleUnitMin = unit;
        params.emaTrendFilterMap = emaTrendFilterMap;
        params.stratsByInterval = stratsByInterval;
        params.hasGroups = hasGroups;

        // MarketGroupSettings 변환 (BacktestService 내부 클래스 → SimulationParams 클래스)
        if (hasGroups && !marketGroupMap.isEmpty()) {
            java.util.Map<String, SimulationParams.MarketGroupSettings> simGroupMap =
                    new java.util.HashMap<String, SimulationParams.MarketGroupSettings>();
            for (java.util.Map.Entry<String, MarketGroupSettings> e : marketGroupMap.entrySet()) {
                MarketGroupSettings src = e.getValue();
                SimulationParams.MarketGroupSettings dst = new SimulationParams.MarketGroupSettings();
                dst.tpPct = src.tpPct;
                dst.slPct = src.slPct;
                dst.maxAddBuys = src.maxAddBuys;
                dst.strategyLock = src.strategyLock;
                dst.minConfidence = src.minConfidence;
                dst.timeStopMinutes = src.timeStopMinutes;
                dst.candleUnitMin = src.candleUnitMin;
                dst.orderSizingMode = src.orderSizingMode;
                dst.orderSizingValue = src.orderSizingValue;
                dst.baseOrderKrw = src.baseOrderKrw;
                dst.intervalsCsv = src.intervalsCsv;
                dst.emaFilterCsv = src.emaFilterCsv;
                dst.strategies = src.strategies;
                dst.strategyNames = src.strategyNames;
                simGroupMap.put(e.getKey(), dst);
            }
            params.marketGroupMap = simGroupMap;
        }

        // 오프닝 파라미터 오버라이드: openingParams가 있으면 ScalpOpeningBreakStrategy를 재생성
        StrategyFactory effectiveFactory = strategyFactory;
        if (req.openingParams != null) {
            BacktestRequest.OpeningParams op = req.openingParams;
            com.example.upbit.strategy.ScalpOpeningBreakStrategy customOpening =
                    new com.example.upbit.strategy.ScalpOpeningBreakStrategy()
                            .withTiming(op.rangeStartHour, op.rangeStartMin,
                                    op.rangeEndHour, op.rangeEndMin,
                                    op.entryStartHour, op.entryStartMin,
                                    op.entryEndHour, op.entryEndMin,
                                    op.sessionEndHour, op.sessionEndMin)
                            .withRisk(op.tpAtrMult, op.slPct, op.trailAtrMult)
                            .withFilters(op.volumeMult, op.minBodyRatio);
            effectiveFactory = strategyFactory.withOverride(
                    com.example.upbit.strategy.StrategyType.SCALP_OPENING_BREAK, customOpening);
        }

        TradingEngine engine = new TradingEngine(effectiveFactory, strategyCfg, tradeProps);
        BacktestResponse coreRes = engine.simulate(params, candlesByMI);

        // TradingEngine 결과를 기존 res에 머지 (캔들 카운트/마켓/전략 정보는 이미 세팅됨)
        res.trades = coreRes.trades;
        res.tradesCount = coreRes.tradesCount;
        res.wins = coreRes.wins;
        res.winRate = coreRes.winRate;
        res.finalCapital = coreRes.finalCapital;
        res.tpSellCount = coreRes.tpSellCount;
        res.slSellCount = coreRes.slSellCount;
        res.patternSellCount = coreRes.patternSellCount;
        res.totalReturn = coreRes.totalReturn;
        res.roi = coreRes.roi;
        res.totalPnl = coreRes.totalPnl;
        res.totalRoi = coreRes.totalRoi;
        res.totalTrades = coreRes.totalTrades;

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
        // UI friendly (한국어)
        if (p.contains("1일")) return 1;
        if (p.contains("1달")) return 30;
        if (p.contains("3달")) return 90;
        if (p.contains("6달")) return 180;
        if (p.contains("1주")) return 7;
        // 숫자+d 형식 범용 파싱 (예: 90d, 180d, 365d)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*d").matcher(p);
        if (m.find()) {
            try { return Math.max(1, Integer.parseInt(m.group(1))); } catch (Exception ignore) {}
        }
        // 순수 숫자
        try { return Math.max(1, Integer.parseInt(p)); } catch (Exception ignore) {}
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
