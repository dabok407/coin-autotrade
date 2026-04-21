package com.example.upbit.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 업비트 현재가(Ticker) API 응답 DTO.
 * GET /v1/ticker?markets=KRW-BTC,KRW-SOL
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpbitTicker {
    public String market;
    public double trade_price;
    public double high_price;
    public double low_price;
    public double opening_price;
}
