package com.example.upbit.db;

import javax.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "bot_config")
public class BotConfigEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="mode", nullable=false)
    private String mode = "PAPER";

    @Column(name="candle_unit_min", nullable=false)
    private int candleUnitMin = 5;

    @Column(name="strategy_type", nullable=false)
    private String strategyType = "CONSECUTIVE_DOWN_REBOUND";

    // Multi strategy (CSV of StrategyType enum names). Empty -> use strategyType.
    @Column(name="strategy_types_csv", nullable=false, length=1024)
    private String strategyTypesCsv = "";

    // Use DECIMAL in DB (money) and BigDecimal in JPA to avoid schema validation issues
    // (H2 creates DECIMAL by default for the init migration) and to avoid floating errors.
    @Column(name="capital_krw", nullable=false, precision=19, scale=4)
    private BigDecimal capitalKrw = BigDecimal.ZERO;

    // Order sizing (global)
    // FIXED: orderSizingValue = KRW
    // PCT:   orderSizingValue = percent (0~100), baseOrderKrw = capitalKrw * pct/100
    @Column(name="order_sizing_mode", nullable=false)
    private String orderSizingMode = "FIXED";

    @Column(name="order_sizing_value", nullable=false, precision=19, scale=4)
    private BigDecimal orderSizingValue = BigDecimal.valueOf(10000);

    // Risk limit (global): maximum number of add-buys allowed per position (per market)
    @Column(name="max_add_buys_global", nullable=false)
    private int maxAddBuysGlobal = 2;

    // Risk management (global)
    // Take profit percent (e.g., 2.0 means +2% from avg price). 0 disables.
    @Column(name="take_profit_pct", nullable=false, precision=10, scale=4)
    private BigDecimal takeProfitPct = BigDecimal.valueOf(4.0);

    // Stop loss percent (e.g., 3.0 means -3% from avg price). 0 disables.
    @Column(name="stop_loss_pct", nullable=false, precision=10, scale=4)
    private BigDecimal stopLossPct = BigDecimal.valueOf(2.0);

    // Strategy Lock: only the strategy that opened the position can close it (TP/SL always allowed)
    @Column(name="strategy_lock", nullable=false, columnDefinition = "TINYINT(1)")
    @org.hibernate.annotations.Type(type = "org.hibernate.type.NumericBooleanType")
    private Boolean strategyLock = false;

    // Confidence score: minimum confidence (0~10) to accept entry signals. 0 = disabled.
    @Column(name="min_confidence", nullable=false)
    private double minConfidence = 0;

    // Time Stop: 매수 전용 전략의 포지션이 설정 시간(분) 초과 + 손실 상태이면 자동 청산. 0 = 비활성.
    @Column(name="time_stop_minutes", nullable=false)
    private int timeStopMinutes = 0;

    // 전략별 캔들 인터벌 오버라이드 (CSV: "TYPE:MIN,TYPE:MIN,..."). 비어있으면 글로벌 사용.
    @Column(name="strategy_intervals_csv", nullable=false, length=2048)
    private String strategyIntervalsCsv = "";

    // 전략별 EMA 트렌드 필터 오버라이드 (CSV: "TYPE:PERIOD,TYPE:PERIOD,...").
    // 비어있으면 기본값(50) 사용. PERIOD=0이면 해당 전략 EMA 필터 비활성화.
    @Column(name="ema_filter_csv", nullable=false, length=2048)
    private String emaFilterCsv = "";

    // 최대 드로다운 퍼센트 (예: 10.0 = 자본 대비 -10% 시 신규 매수 차단). 0 = 비활성.
    @Column(name="max_drawdown_pct", nullable=false, precision=10, scale=4)
    private BigDecimal maxDrawdownPct = BigDecimal.ZERO;

    // 글로벌 트레일링 스탑 퍼센트 (예: 3.0 = 고점 대비 -3% 하락 시 청산). 0 = 비활성.
    @Column(name="trailing_stop_pct", nullable=false, precision=10, scale=4)
    private BigDecimal trailingStopPct = BigDecimal.ZERO;

    // 일일 손실 한도 퍼센트 (예: 5.0 = 당일 -5% 시 신규 매수 차단). 0 = 비활성.
    @Column(name="daily_loss_limit_pct", nullable=false, precision=10, scale=4)
    private BigDecimal dailyLossLimitPct = BigDecimal.ZERO;

    public Long getId() { return id; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public int getCandleUnitMin() { return candleUnitMin; }
    public void setCandleUnitMin(int candleUnitMin) { this.candleUnitMin = candleUnitMin; }

    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }

    public String getStrategyTypesCsv() { return strategyTypesCsv; }
    public void setStrategyTypesCsv(String strategyTypesCsv) { this.strategyTypesCsv = strategyTypesCsv; }

    public BigDecimal getCapitalKrw() { return capitalKrw; }
    public void setCapitalKrw(BigDecimal capitalKrw) { this.capitalKrw = capitalKrw == null ? BigDecimal.ZERO : capitalKrw; }

    public String getOrderSizingMode() { return orderSizingMode; }
    public void setOrderSizingMode(String orderSizingMode) {
        if (orderSizingMode == null || orderSizingMode.trim().isEmpty()) return;
        this.orderSizingMode = orderSizingMode.trim().toUpperCase();
    }

    public BigDecimal getOrderSizingValue() { return orderSizingValue; }
    public void setOrderSizingValue(BigDecimal orderSizingValue) {
        this.orderSizingValue = (orderSizingValue == null ? BigDecimal.ZERO : orderSizingValue);
    }

    public int getMaxAddBuysGlobal() { return maxAddBuysGlobal; }
    public void setMaxAddBuysGlobal(int maxAddBuysGlobal) {
        this.maxAddBuysGlobal = Math.max(0, maxAddBuysGlobal);
    }

    public BigDecimal getTakeProfitPct() { return takeProfitPct; }
    public void setTakeProfitPct(BigDecimal takeProfitPct) {
        this.takeProfitPct = (takeProfitPct == null ? BigDecimal.ZERO : takeProfitPct);
    }

    public BigDecimal getStopLossPct() { return stopLossPct; }
    public void setStopLossPct(BigDecimal stopLossPct) {
        this.stopLossPct = (stopLossPct == null ? BigDecimal.ZERO : stopLossPct);
    }

    public boolean isStrategyLock() { return Boolean.TRUE.equals(strategyLock); }
    public void setStrategyLock(boolean strategyLock) { this.strategyLock = strategyLock; }

    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = Math.max(0, Math.min(10, minConfidence)); }

    public int getTimeStopMinutes() { return timeStopMinutes; }
    public void setTimeStopMinutes(int timeStopMinutes) { this.timeStopMinutes = Math.max(0, timeStopMinutes); }

    public String getStrategyIntervalsCsv() { return strategyIntervalsCsv; }
    public void setStrategyIntervalsCsv(String csv) { this.strategyIntervalsCsv = (csv == null ? "" : csv); }

    public String getEmaFilterCsv() { return emaFilterCsv; }
    public void setEmaFilterCsv(String csv) { this.emaFilterCsv = (csv == null ? "" : csv); }

    public BigDecimal getMaxDrawdownPct() { return maxDrawdownPct; }
    public void setMaxDrawdownPct(BigDecimal v) { this.maxDrawdownPct = (v == null ? BigDecimal.ZERO : v); }

    public BigDecimal getTrailingStopPct() { return trailingStopPct; }
    public void setTrailingStopPct(BigDecimal v) { this.trailingStopPct = (v == null ? BigDecimal.ZERO : v); }

    public BigDecimal getDailyLossLimitPct() { return dailyLossLimitPct; }
    public void setDailyLossLimitPct(BigDecimal v) { this.dailyLossLimitPct = (v == null ? BigDecimal.ZERO : v); }

    /**
     * 전략별 유효 인터벌을 계산합니다.
     * 우선순위: 사용자 오버라이드 > 글로벌 candleUnitMin
     * 반환값은 반드시 업비트 API가 허용하는 분봉 단위여야 합니다.
     */
    private static final int[] UPBIT_VALID_UNITS = {1, 3, 5, 10, 15, 30, 60, 240};

    private static int toValidUnit(int v) {
        for (int u : UPBIT_VALID_UNITS) { if (u == v) return v; }
        // 가장 가까운 유효값
        int best = UPBIT_VALID_UNITS[0]; int bestD = Math.abs(v - best);
        for (int i = 1; i < UPBIT_VALID_UNITS.length; i++) {
            int d = Math.abs(v - UPBIT_VALID_UNITS[i]);
            if (d < bestD) { bestD = d; best = UPBIT_VALID_UNITS[i]; }
        }
        return best;
    }

    public int getEffectiveInterval(com.example.upbit.strategy.StrategyType st) {
        if (strategyIntervalsCsv != null && !strategyIntervalsCsv.isEmpty()) {
            for (String pair : strategyIntervalsCsv.split(",")) {
                String[] kv = pair.trim().split(":");
                if (kv.length == 2 && kv[0].trim().equals(st.name())) {
                    try { return toValidUnit(Integer.parseInt(kv[1].trim())); } catch (Exception ignore) {}
                }
            }
        }
        return toValidUnit(candleUnitMin); // 오버라이드 없으면 글로벌 사용
    }

    /**
     * 전략별 EMA 트렌드 필터 기간을 반환합니다.
     * CSV에 오버라이드가 있으면 해당 값 반환, 없으면 기본값 50.
     * 0이면 EMA 필터 비활성화.
     */
    public int getEffectiveEmaPeriod(com.example.upbit.strategy.StrategyType st) {
        if (emaFilterCsv != null && !emaFilterCsv.isEmpty()) {
            for (String pair : emaFilterCsv.split(",")) {
                String[] kv = pair.trim().split(":");
                if (kv.length == 2 && kv[0].trim().equals(st.name())) {
                    try { return Math.max(0, Integer.parseInt(kv[1].trim())); } catch (Exception ignore) {}
                }
            }
        }
        return 0; // 기본값: EMA OFF (emaFilterCsv에 명시된 전략만 EMA 적용)
    }

    /**
     * 모든 활성 전략의 유효 인터벌 중 최소값 (tick 스케줄링용).
     */
    public int getMinEffectiveInterval(java.util.List<com.example.upbit.strategy.StrategyType> activeStrategies) {
        int min = candleUnitMin;
        for (com.example.upbit.strategy.StrategyType st : activeStrategies) {
            int eff = getEffectiveInterval(st);
            if (eff < min) min = eff;
        }
        return Math.max(1, min);
    }
}
