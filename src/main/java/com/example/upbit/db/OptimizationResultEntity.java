package com.example.upbit.db;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "optimization_result")
public class OptimizationResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 50)
    private String runId;

    @Column(name = "strategy_type", nullable = false, length = 50)
    private String strategyType;

    @Column(name = "market", nullable = false, length = 20)
    private String market;

    @Column(name = "interval_min", nullable = false)
    private int intervalMin;

    @Column(name = "tp_pct", nullable = false)
    private double tpPct;

    @Column(name = "sl_pct", nullable = false)
    private double slPct;

    @Column(name = "max_add_buys", nullable = false)
    private int maxAddBuys;

    @Column(name = "min_confidence", nullable = false)
    private double minConfidence;

    @Column(name = "strategy_lock", nullable = false)
    private boolean strategyLock;

    @Column(name = "time_stop_minutes", nullable = false)
    private int timeStopMinutes;

    @Column(name = "ema_period", nullable = false)
    private int emaPeriod;

    @Column(name = "roi", nullable = false)
    private double roi;

    @Column(name = "win_rate", nullable = false)
    private double winRate;

    @Column(name = "total_trades", nullable = false)
    private int totalTrades;

    @Column(name = "wins", nullable = false)
    private int wins;

    @Column(name = "total_pnl", nullable = false)
    private double totalPnl;

    @Column(name = "final_capital", nullable = false)
    private double finalCapital;

    @Column(name = "tp_sell_count", nullable = false)
    private int tpSellCount;

    @Column(name = "sl_sell_count", nullable = false)
    private int slSellCount;

    @Column(name = "pattern_sell_count", nullable = false)
    private int patternSellCount;

    // Phase 2: 다중 전략 조합
    @Column(name = "strategies_csv", length = 500)
    private String strategiesCsv;

    @Column(name = "strategy_intervals_csv", length = 200)
    private String strategyIntervalsCsv;

    @Column(name = "ema_filter_csv", length = 200)
    private String emaFilterCsv;

    @Column(name = "phase")
    private int phase = 1;

    // 기간별 성과 (최근 3개월)
    @Column(name = "roi_3m")
    private Double roi3m;

    @Column(name = "win_rate_3m")
    private Double winRate3m;

    @Column(name = "total_trades_3m")
    private Integer totalTrades3m;

    @Column(name = "wins_3m")
    private Integer wins3m;

    // 기간별 성과 (최근 1개월)
    @Column(name = "roi_1m")
    private Double roi1m;

    @Column(name = "win_rate_1m")
    private Double winRate1m;

    @Column(name = "total_trades_1m")
    private Integer totalTrades1m;

    @Column(name = "wins_1m")
    private Integer wins1m;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public int getIntervalMin() { return intervalMin; }
    public void setIntervalMin(int intervalMin) { this.intervalMin = intervalMin; }
    public double getTpPct() { return tpPct; }
    public void setTpPct(double tpPct) { this.tpPct = tpPct; }
    public double getSlPct() { return slPct; }
    public void setSlPct(double slPct) { this.slPct = slPct; }
    public int getMaxAddBuys() { return maxAddBuys; }
    public void setMaxAddBuys(int maxAddBuys) { this.maxAddBuys = maxAddBuys; }
    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
    public boolean isStrategyLock() { return strategyLock; }
    public void setStrategyLock(boolean strategyLock) { this.strategyLock = strategyLock; }
    public int getTimeStopMinutes() { return timeStopMinutes; }
    public void setTimeStopMinutes(int timeStopMinutes) { this.timeStopMinutes = timeStopMinutes; }
    public int getEmaPeriod() { return emaPeriod; }
    public void setEmaPeriod(int emaPeriod) { this.emaPeriod = emaPeriod; }
    public double getRoi() { return roi; }
    public void setRoi(double roi) { this.roi = roi; }
    public double getWinRate() { return winRate; }
    public void setWinRate(double winRate) { this.winRate = winRate; }
    public int getTotalTrades() { return totalTrades; }
    public void setTotalTrades(int totalTrades) { this.totalTrades = totalTrades; }
    public int getWins() { return wins; }
    public void setWins(int wins) { this.wins = wins; }
    public double getTotalPnl() { return totalPnl; }
    public void setTotalPnl(double totalPnl) { this.totalPnl = totalPnl; }
    public double getFinalCapital() { return finalCapital; }
    public void setFinalCapital(double finalCapital) { this.finalCapital = finalCapital; }
    public int getTpSellCount() { return tpSellCount; }
    public void setTpSellCount(int tpSellCount) { this.tpSellCount = tpSellCount; }
    public int getSlSellCount() { return slSellCount; }
    public void setSlSellCount(int slSellCount) { this.slSellCount = slSellCount; }
    public int getPatternSellCount() { return patternSellCount; }
    public void setPatternSellCount(int patternSellCount) { this.patternSellCount = patternSellCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    // Phase 2 + 기간별 getter/setter
    public String getStrategiesCsv() { return strategiesCsv; }
    public void setStrategiesCsv(String strategiesCsv) { this.strategiesCsv = strategiesCsv; }
    public String getStrategyIntervalsCsv() { return strategyIntervalsCsv; }
    public void setStrategyIntervalsCsv(String strategyIntervalsCsv) { this.strategyIntervalsCsv = strategyIntervalsCsv; }
    public String getEmaFilterCsv() { return emaFilterCsv; }
    public void setEmaFilterCsv(String emaFilterCsv) { this.emaFilterCsv = emaFilterCsv; }
    public int getPhase() { return phase; }
    public void setPhase(int phase) { this.phase = phase; }

    public Double getRoi3m() { return roi3m; }
    public void setRoi3m(Double roi3m) { this.roi3m = roi3m; }
    public Double getWinRate3m() { return winRate3m; }
    public void setWinRate3m(Double winRate3m) { this.winRate3m = winRate3m; }
    public Integer getTotalTrades3m() { return totalTrades3m; }
    public void setTotalTrades3m(Integer totalTrades3m) { this.totalTrades3m = totalTrades3m; }
    public Integer getWins3m() { return wins3m; }
    public void setWins3m(Integer wins3m) { this.wins3m = wins3m; }

    public Double getRoi1m() { return roi1m; }
    public void setRoi1m(Double roi1m) { this.roi1m = roi1m; }
    public Double getWinRate1m() { return winRate1m; }
    public void setWinRate1m(Double winRate1m) { this.winRate1m = winRate1m; }
    public Integer getTotalTrades1m() { return totalTrades1m; }
    public void setTotalTrades1m(Integer totalTrades1m) { this.totalTrades1m = totalTrades1m; }
    public Integer getWins1m() { return wins1m; }
    public void setWins1m(Integer wins1m) { this.wins1m = wins1m; }
}
