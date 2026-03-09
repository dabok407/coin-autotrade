package com.example.upbit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "strategy")
public class StrategyProperties {
    // 4연속 하락 후 반등 시 매수 (7→4: 더 많은 진입 기회 확보)
    private int consecutiveDown = 4;
    // TP 2% (3%→2%: 더 빠른 수익 실현, 승률 향상)
    private double takeProfitRate = 0.02;
    // 추가매수 시 매 하락봉 자동 추매 비활성화 (마틴게일 위험 방지)
    private boolean addBuyOnEachExtraDown = false;
    private double feeRate = 0.0005;

    public int getConsecutiveDown() { return consecutiveDown; }
    public void setConsecutiveDown(int consecutiveDown) { this.consecutiveDown = consecutiveDown; }

    public double getTakeProfitRate() { return takeProfitRate; }
    public void setTakeProfitRate(double takeProfitRate) { this.takeProfitRate = takeProfitRate; }

    public boolean isAddBuyOnEachExtraDown() { return addBuyOnEachExtraDown; }
    public void setAddBuyOnEachExtraDown(boolean addBuyOnEachExtraDown) { this.addBuyOnEachExtraDown = addBuyOnEachExtraDown; }

    public double getFeeRate() { return feeRate; }
    public void setFeeRate(double feeRate) { this.feeRate = feeRate; }
}
