package com.example.upbit.bot;

import java.util.Map;
import java.util.List;

/**
 * UI에 내려주는 "봇 상태" DTO.
 * (DB Entity와 분리하여 프론트 요구사항 변경에 유연하게 대응)
 */
public class BotStatus {

    private boolean running;
    private long startedAtEpochMillis;

    // UI 설정값
    private String mode; // PAPER/LIVE
    private int candleUnitMin;
    private double capitalKrw;

    // Risk limit (global): maximum number of add-buys allowed per position (per market)
    private int maxAddBuysGlobal;

    // Risk management (global): TP/SL percent (0 disables)
    private double takeProfitPct;
    private double stopLossPct;
    private boolean strategyLock;
    private double minConfidence;
    private int timeStopMinutes;
    private String strategyIntervalsCsv;
    private String emaFilterCsv;

    private String strategyType;
    private List<String> strategies;

    // Order sizing (global)
    private String orderSizingMode;  // FIXED/PCT
    private double orderSizingValue; // FIXED: KRW, PCT: percent
    private double baseOrderKrw;     // computed 1x buy amount (KRW)

    // 자본 현황
    private double usedCapitalKrw;      // 포지션에 투입된 자본 합계
    private double availableCapitalKrw; // 잔여 자본 (capitalKrw - usedCapitalKrw)

    // 손익/KPI
    private double realizedPnlKrw;
    private double unrealizedPnlKrw;
    private double totalPnlKrw;
    private double roiPercent;

    private int sellCountToday;
    private int sellCountWeek;
    private int sellCountMonth;

    private int totalTrades;

    // 승/승률(SELL 기준)
    private int wins;
    private double winRate;

    private Map<String, MarketStatus> markets;

    // 스캐너별 포지션 수 (프론트엔드 구분 표시용)
    private int mainBotPositionCount;
    private int openingScannerPositionCount;
    private int alldayScannerPositionCount;

    // Strategy Groups (null/empty = legacy mode using flat config)
    private List<StrategyGroupInfo> groups;

    // 서버 재시작 시 자동 시작 여부
    private boolean autoStartEnabled;

    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }

    public long getStartedAtEpochMillis() { return startedAtEpochMillis; }
    public void setStartedAtEpochMillis(long startedAtEpochMillis) { this.startedAtEpochMillis = startedAtEpochMillis; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public int getCandleUnitMin() { return candleUnitMin; }
    public void setCandleUnitMin(int candleUnitMin) { this.candleUnitMin = candleUnitMin; }

    public double getCapitalKrw() { return capitalKrw; }
    public void setCapitalKrw(double capitalKrw) { this.capitalKrw = capitalKrw; }

    public int getMaxAddBuysGlobal() { return maxAddBuysGlobal; }
    public void setMaxAddBuysGlobal(int maxAddBuysGlobal) { this.maxAddBuysGlobal = Math.max(0, maxAddBuysGlobal); }

    public double getTakeProfitPct() { return takeProfitPct; }
    public void setTakeProfitPct(double takeProfitPct) { this.takeProfitPct = Math.max(0.0, takeProfitPct); }

    public double getStopLossPct() { return stopLossPct; }
    public void setStopLossPct(double stopLossPct) { this.stopLossPct = Math.max(0.0, stopLossPct); }

    public boolean isStrategyLock() { return strategyLock; }
    public void setStrategyLock(boolean strategyLock) { this.strategyLock = strategyLock; }

    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }

    public int getTimeStopMinutes() { return timeStopMinutes; }
    public void setTimeStopMinutes(int timeStopMinutes) { this.timeStopMinutes = timeStopMinutes; }

    public String getStrategyIntervalsCsv() { return strategyIntervalsCsv; }
    public void setStrategyIntervalsCsv(String csv) { this.strategyIntervalsCsv = csv; }

    public String getEmaFilterCsv() { return emaFilterCsv; }
    public void setEmaFilterCsv(String csv) { this.emaFilterCsv = csv; }

    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }

    public List<String> getStrategies() { return strategies; }
    public void setStrategies(List<String> strategies) { this.strategies = strategies; }

    public String getOrderSizingMode() { return orderSizingMode; }
    public void setOrderSizingMode(String orderSizingMode) { this.orderSizingMode = orderSizingMode; }

    public double getOrderSizingValue() { return orderSizingValue; }
    public void setOrderSizingValue(double orderSizingValue) { this.orderSizingValue = orderSizingValue; }

    public double getBaseOrderKrw() { return baseOrderKrw; }
    public void setBaseOrderKrw(double baseOrderKrw) { this.baseOrderKrw = baseOrderKrw; }

    public double getUsedCapitalKrw() { return usedCapitalKrw; }
    public void setUsedCapitalKrw(double usedCapitalKrw) { this.usedCapitalKrw = usedCapitalKrw; }

    public double getAvailableCapitalKrw() { return availableCapitalKrw; }
    public void setAvailableCapitalKrw(double availableCapitalKrw) { this.availableCapitalKrw = availableCapitalKrw; }

    public double getRealizedPnlKrw() { return realizedPnlKrw; }
    public void setRealizedPnlKrw(double realizedPnlKrw) { this.realizedPnlKrw = realizedPnlKrw; }

    public double getUnrealizedPnlKrw() { return unrealizedPnlKrw; }
    public void setUnrealizedPnlKrw(double unrealizedPnlKrw) { this.unrealizedPnlKrw = unrealizedPnlKrw; }

    public double getTotalPnlKrw() { return totalPnlKrw; }
    public void setTotalPnlKrw(double totalPnlKrw) { this.totalPnlKrw = totalPnlKrw; }

    // JSON compatibility: FE uses "roi"
    public double getRoi() { return roiPercent; }
    public void setRoi(double roi) { this.roiPercent = roi; }

    public double getRoiPercent() { return roiPercent; }
    public void setRoiPercent(double roiPercent) { this.roiPercent = roiPercent; }

    public int getSellCountToday() { return sellCountToday; }
    public void setSellCountToday(int sellCountToday) { this.sellCountToday = sellCountToday; }

    public int getSellCountWeek() { return sellCountWeek; }
    public void setSellCountWeek(int sellCountWeek) { this.sellCountWeek = sellCountWeek; }

    public int getSellCountMonth() { return sellCountMonth; }
    public void setSellCountMonth(int sellCountMonth) { this.sellCountMonth = sellCountMonth; }

    public int getTotalTrades() { return totalTrades; }
    public void setTotalTrades(int totalTrades) { this.totalTrades = totalTrades; }

    public int getWins() { return wins; }
    public void setWins(int wins) { this.wins = wins; }

    public double getWinRate() { return winRate; }
    public void setWinRate(double winRate) { this.winRate = winRate; }

    public Map<String, MarketStatus> getMarkets() { return markets; }
    public void setMarkets(Map<String, MarketStatus> markets) { this.markets = markets; }

    public int getMainBotPositionCount() { return mainBotPositionCount; }
    public void setMainBotPositionCount(int mainBotPositionCount) { this.mainBotPositionCount = mainBotPositionCount; }

    public int getOpeningScannerPositionCount() { return openingScannerPositionCount; }
    public void setOpeningScannerPositionCount(int openingScannerPositionCount) { this.openingScannerPositionCount = openingScannerPositionCount; }

    public int getAlldayScannerPositionCount() { return alldayScannerPositionCount; }
    public void setAlldayScannerPositionCount(int alldayScannerPositionCount) { this.alldayScannerPositionCount = alldayScannerPositionCount; }

    public List<StrategyGroupInfo> getGroups() { return groups; }
    public void setGroups(List<StrategyGroupInfo> groups) { this.groups = groups; }

    public boolean isAutoStartEnabled() { return autoStartEnabled; }
    public void setAutoStartEnabled(boolean autoStartEnabled) { this.autoStartEnabled = autoStartEnabled; }

    /**
     * 전략 그룹 요약 정보 (FE 표시용).
     */
    public static class StrategyGroupInfo {
        private Long id;
        private String groupName;
        private int sortOrder;
        private List<String> markets;
        private List<String> strategies;
        private int candleUnitMin;
        private String orderSizingMode;
        private double orderSizingValue;
        private double takeProfitPct;
        private double stopLossPct;
        private int maxAddBuys;
        private boolean strategyLock;
        private double minConfidence;
        private int timeStopMinutes;
        private String strategyIntervalsCsv;
        private String emaFilterCsv;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getGroupName() { return groupName; }
        public void setGroupName(String groupName) { this.groupName = groupName; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
        public List<String> getMarkets() { return markets; }
        public void setMarkets(List<String> markets) { this.markets = markets; }
        public List<String> getStrategies() { return strategies; }
        public void setStrategies(List<String> strategies) { this.strategies = strategies; }
        public int getCandleUnitMin() { return candleUnitMin; }
        public void setCandleUnitMin(int candleUnitMin) { this.candleUnitMin = candleUnitMin; }
        public String getOrderSizingMode() { return orderSizingMode; }
        public void setOrderSizingMode(String orderSizingMode) { this.orderSizingMode = orderSizingMode; }
        public double getOrderSizingValue() { return orderSizingValue; }
        public void setOrderSizingValue(double orderSizingValue) { this.orderSizingValue = orderSizingValue; }
        public double getTakeProfitPct() { return takeProfitPct; }
        public void setTakeProfitPct(double takeProfitPct) { this.takeProfitPct = takeProfitPct; }
        public double getStopLossPct() { return stopLossPct; }
        public void setStopLossPct(double stopLossPct) { this.stopLossPct = stopLossPct; }
        public int getMaxAddBuys() { return maxAddBuys; }
        public void setMaxAddBuys(int maxAddBuys) { this.maxAddBuys = maxAddBuys; }
        public boolean isStrategyLock() { return strategyLock; }
        public void setStrategyLock(boolean strategyLock) { this.strategyLock = strategyLock; }
        public double getMinConfidence() { return minConfidence; }
        public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
        public int getTimeStopMinutes() { return timeStopMinutes; }
        public void setTimeStopMinutes(int timeStopMinutes) { this.timeStopMinutes = timeStopMinutes; }
        public String getStrategyIntervalsCsv() { return strategyIntervalsCsv; }
        public void setStrategyIntervalsCsv(String csv) { this.strategyIntervalsCsv = csv; }
        public String getEmaFilterCsv() { return emaFilterCsv; }
        public void setEmaFilterCsv(String csv) { this.emaFilterCsv = csv; }
    }

    public static class MarketStatus {
        private String market;
        private boolean enabled;
        private double baseOrderKrw;
        private boolean positionOpen;
        private double avgPrice;
        private double qty;
        private int downStreak;
        private int addBuys;
        private double lastPrice;
        private double realizedPnlKrw;
        private String entryStrategy;
        /** V111: Split-Exit 분할 매도 상태. 0=미분할, 1=1차완료(잔량대기) */
        private int splitPhase;

        public String getMarket() { return market; }
        public void setMarket(String market) { this.market = market; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getBaseOrderKrw() { return baseOrderKrw; }
        public void setBaseOrderKrw(double baseOrderKrw) { this.baseOrderKrw = baseOrderKrw; }

        public boolean isPositionOpen() { return positionOpen; }
        public void setPositionOpen(boolean positionOpen) { this.positionOpen = positionOpen; }

        public double getAvgPrice() { return avgPrice; }
        public void setAvgPrice(double avgPrice) { this.avgPrice = avgPrice; }

        public double getQty() { return qty; }
        public void setQty(double qty) { this.qty = qty; }

        public int getDownStreak() { return downStreak; }
        public void setDownStreak(int downStreak) { this.downStreak = downStreak; }

        public int getAddBuys() { return addBuys; }
        public void setAddBuys(int addBuys) { this.addBuys = addBuys; }

        public double getLastPrice() { return lastPrice; }
        public void setLastPrice(double lastPrice) { this.lastPrice = lastPrice; }

        public double getRealizedPnlKrw() { return realizedPnlKrw; }
        public void setRealizedPnlKrw(double realizedPnlKrw) { this.realizedPnlKrw = realizedPnlKrw; }

        public String getEntryStrategy() { return entryStrategy; }
        public void setEntryStrategy(String entryStrategy) { this.entryStrategy = entryStrategy; }

        public int getSplitPhase() { return splitPhase; }
        public void setSplitPhase(int splitPhase) { this.splitPhase = splitPhase; }
    }
}
