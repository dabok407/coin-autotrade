package com.example.upbit.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UpbitCandle {
    public String market;
    public String candle_date_time_utc;
    public double opening_price;
    public double high_price;
    public double low_price;
    public double trade_price;
    public double candle_acc_trade_volume;
}
