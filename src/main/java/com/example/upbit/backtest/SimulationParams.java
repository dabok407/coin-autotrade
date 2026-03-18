package com.example.upbit.backtest;

import com.example.upbit.strategy.StrategyType;

import java.util.List;
import java.util.Map;

/**
 * 백테스트 시뮬레이션 파라미터.
 * BacktestSimulator에 전달되는 순수 데이터 홀더.
 */
public class SimulationParams {

    public List<String> markets;
    public List<StrategyType> strategies;
    public double capitalKrw;
    public double tpPct;
    public double slPct;
    public double baseOrderKrw;
    public int maxAddBuys;
    public boolean strategyLock;
    public double minConfidence;
    public int timeStopMinutes;
    public int candleUnitMin;
    public String orderSizingMode;
    public double orderSizingValue;

    /** 전략별 EMA 트렌드 필터 기간: key=StrategyType.name(), value=EMA period (0=비활성) */
    public Map<String, Integer> emaTrendFilterMap;

    /** 인터벌별 전략 그룹: key=intervalMin, value=해당 인터벌에서 실행할 전략 목록 */
    public Map<Integer, List<StrategyType>> stratsByInterval;

    /** BTC 방향 필터 (오프닝 전략 백테스트용) */
    public boolean btcFilterEnabled;
    public int btcEmaPeriod;
    public List<com.example.upbit.market.UpbitCandle> btcCandles;

    /** 그룹 모드 여부 및 그룹별 설정 */
    public boolean hasGroups;
    public Map<String, MarketGroupSettings> marketGroupMap;

    /**
     * 그룹별 마켓 설정.
     */
    public static class MarketGroupSettings {
        public double tpPct;
        public double slPct;
        public int maxAddBuys;
        public boolean strategyLock;
        public double minConfidence;
        public int timeStopMinutes;
        public int candleUnitMin;
        public String orderSizingMode;
        public double orderSizingValue;
        public double baseOrderKrw;
        public String intervalsCsv;
        public String emaFilterCsv;
        public List<StrategyType> strategies;
        public java.util.Set<String> strategyNames;
    }
}
