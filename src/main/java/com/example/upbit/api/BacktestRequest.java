package com.example.upbit.api;

import java.util.List;
import java.util.Map;

public class BacktestRequest {
    // legacy single
    public String strategyType;

    // v2 multi (StrategyType enum names)
    public List<String> strategies;

    public String period; // 1d,7d,30d  (UI에서는 1주/1달 등도 들어올 수 있음)
    public int candleUnitMin;
    public String interval; // "5분" 등 (optional)
    public String market;
    // v3 multi markets (optional). If provided, 'market' is ignored.
    public List<String> markets;

    // v3 date range (optional): "YYYY-MM-DD" (KST 기준)
    public String fromDate;
    public String toDate;
    public double capitalKrw;

    // order sizing (optional)
    public String orderSizingMode;  // FIXED/PCT
    public double orderSizingValue; // FIXED: KRW, PCT: percent

    // risk limit (global)
    /**
     * Global maximum number of additional buys (averaging-down) allowed per open position.
     *
     * 0 means "no add-buys". We use Integer so that null can mean "not provided".
     */
    public Integer maxAddBuysGlobal; // null => use default
    // Risk management (global)
    public Double takeProfitPct; // TP percent (0 disables)
    public Double stopLossPct;   // SL percent (0 disables)
    public Boolean strategyLock; // strategy lock toggle
    public Double minConfidence; // min confidence score (0~10)
    public Integer timeStopMinutes; // time stop in minutes (0 = disabled)
    public String strategyIntervalsCsv; // per-strategy interval overrides
    public String emaFilterCsv; // per-strategy EMA trend filter overrides

    /**
     * 마켓별 전략 배정 (optional).
     * key = 마켓코드 (예: "KRW-SOL"), value = 해당 마켓에서 사용할 전략 이름 리스트
     * null이면 글로벌 strategies 리스트를 모든 마켓에 적용 (기존 동작).
     * 특정 마켓만 지정하면, 지정되지 않은 마켓은 글로벌 strategies를 사용.
     */
    public Map<String, List<String>> marketStrategies;

    /**
     * 전략 그룹 리스트 (optional).
     * null/empty이면 기존 flat 필드(strategies, markets, takeProfitPct 등)를 사용 (하위호환).
     * 제공되면 각 그룹이 독립적인 마켓+전략+리스크 설정을 가짐.
     * 마켓은 그룹 간 상호배제 (한 마켓은 하나의 그룹에만 소속).
     */
    public List<StrategyGroupDto> groups;

    /**
     * 전략 그룹 DTO: 그룹별 독립 설정.
     */
    public static class StrategyGroupDto {
        public String groupName;
        public List<String> strategies;
        public List<String> markets;
        public int candleUnitMin = 60;
        public String orderSizingMode = "PCT";
        public double orderSizingValue = 90;
        public Double takeProfitPct;
        public Double stopLossPct;
        public Integer maxAddBuys;
        public Boolean strategyLock;
        public Double minConfidence;
        public Integer timeStopMinutes;
        public String strategyIntervalsCsv;
        public String emaFilterCsv;
    }
}
