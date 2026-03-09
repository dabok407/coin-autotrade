package com.example.upbit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "bot")
public class BotProperties {
    private int pollSeconds = 5;
    private List<String> defaultMarkets = new ArrayList<String>();
    private int defaultCandleUnitMinutes = 5;
    // Candle-boundary scheduler (recommended for production)
    private boolean boundarySchedulerEnabled = true;
    private int boundaryBufferSeconds = 2;
    private int boundaryMaxRetry = 5;
    private int boundaryRetrySleepMs = 1000;
    private int catchUpMaxCandles = 3;
    // If latest candle processing is delayed beyond this TTL, block ENTRY orders (buy/add-buy)
    private int staleEntryTtlSeconds = 60;
    // Real-time TP/SL ticker monitoring
    private boolean tpSlTickerEnabled = true;
    private int tpSlPollIntervalSeconds = 15;
    // Upbit public API backoff
    private int apiMaxRetries = 5;
    private int apiBaseBackoffMs = 250;

    public int getPollSeconds() { return pollSeconds; }
    public void setPollSeconds(int pollSeconds) { this.pollSeconds = pollSeconds; }

    public List<String> getDefaultMarkets() { return defaultMarkets; }
    public void setDefaultMarkets(List<String> defaultMarkets) { this.defaultMarkets = defaultMarkets; }

    public int getDefaultCandleUnitMinutes() { return defaultCandleUnitMinutes; }
    public void setDefaultCandleUnitMinutes(int defaultCandleUnitMinutes) { this.defaultCandleUnitMinutes = defaultCandleUnitMinutes; }

public boolean isBoundarySchedulerEnabled() { return boundarySchedulerEnabled; }
public void setBoundarySchedulerEnabled(boolean boundarySchedulerEnabled) { this.boundarySchedulerEnabled = boundarySchedulerEnabled; }

public int getBoundaryBufferSeconds() { return boundaryBufferSeconds; }
public void setBoundaryBufferSeconds(int boundaryBufferSeconds) { this.boundaryBufferSeconds = boundaryBufferSeconds; }

public int getBoundaryMaxRetry() { return boundaryMaxRetry; }
public void setBoundaryMaxRetry(int boundaryMaxRetry) { this.boundaryMaxRetry = boundaryMaxRetry; }

public int getBoundaryRetrySleepMs() { return boundaryRetrySleepMs; }
public void setBoundaryRetrySleepMs(int boundaryRetrySleepMs) { this.boundaryRetrySleepMs = boundaryRetrySleepMs; }

public int getCatchUpMaxCandles() { return catchUpMaxCandles; }
public void setCatchUpMaxCandles(int catchUpMaxCandles) { this.catchUpMaxCandles = catchUpMaxCandles; }

public int getStaleEntryTtlSeconds() { return staleEntryTtlSeconds; }
public void setStaleEntryTtlSeconds(int staleEntryTtlSeconds) { this.staleEntryTtlSeconds = staleEntryTtlSeconds; }

public boolean isTpSlTickerEnabled() { return tpSlTickerEnabled; }
public void setTpSlTickerEnabled(boolean tpSlTickerEnabled) { this.tpSlTickerEnabled = tpSlTickerEnabled; }

public int getTpSlPollIntervalSeconds() { return tpSlPollIntervalSeconds; }
public void setTpSlPollIntervalSeconds(int tpSlPollIntervalSeconds) { this.tpSlPollIntervalSeconds = Math.max(5, tpSlPollIntervalSeconds); }

public int getApiMaxRetries() { return apiMaxRetries; }
public void setApiMaxRetries(int apiMaxRetries) { this.apiMaxRetries = apiMaxRetries; }

public int getApiBaseBackoffMs() { return apiBaseBackoffMs; }
public void setApiBaseBackoffMs(int apiBaseBackoffMs) { this.apiBaseBackoffMs = apiBaseBackoffMs; }

}
