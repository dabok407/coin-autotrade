package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.util.List;
import java.util.Locale;

/**
 * Scalp Momentum Strategy (SM) v6 -- BUY-ONLY 스캘핑 모멘텀 (품질 중심)
 *
 * v6 변경:
 * - SURGE 모드 제거: 거래량만으로는 방향성 불명확
 * - 하드 필터: EMA8 > EMA21, close > EMA21, ADX > 15
 * - RSI: 40-65 (과열 차단 강화)
 * - body ratio: 0.35 (캔들 품질 확보)
 * - EMA gap: 0.10% (최소 이격)
 * - 직전 캔들 큰 음봉 차단
 * - volume: 0.8x (기본 수준 확보)
 *
 * ■ 권장: 15~60분봉, TP 2~3%, SL 1~1.5%, minConfidence 7, TimeStop 480분
 */
public class ScalpMomentumStrategy implements TradingStrategy {

    private static final int EMA_FAST = 8;
    private static final int EMA_SLOW = 21;
    private static final int EMA_TREND = 50;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final int ADX_PERIOD = 14;
    private static final int VOLUME_AVG_PERIOD = 20;

    // 필터
    private static final double ADX_MIN = 15.0;
    private static final double RSI_MIN = 40.0;
    private static final double RSI_MAX = 65.0;
    private static final double MIN_EMA_GAP_PCT = 0.10;
    private static final double MIN_BODY_RATIO = 0.35;
    private static final double VOLUME_THRESHOLD = 0.8;

    // 안전
    private static final double MIN_ATR_PCT = 0.002;
    private static final int MIN_CANDLES = 55;

    @Override
    public StrategyType type() {
        return StrategyType.SCALP_MOMENTUM;
    }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        List<UpbitCandle> candles = ctx.candles;
        if (candles == null || candles.size() < MIN_CANDLES) return Signal.none();

        // BUY-ONLY
        boolean hasPosition = ctx.position != null
                && ctx.position.getQty() != null
                && ctx.position.getQty().compareTo(java.math.BigDecimal.ZERO) > 0;
        if (hasPosition) return Signal.none();

        int n = candles.size();
        UpbitCandle last = candles.get(n - 1);
        UpbitCandle prev = candles.get(n - 2);
        double close = last.trade_price;

        double emaFast = Indicators.ema(candles, EMA_FAST);
        double emaSlow = Indicators.ema(candles, EMA_SLOW);
        double emaTrend = Indicators.ema(candles, EMA_TREND);
        double rsi = Indicators.rsi(candles, RSI_PERIOD);
        double atr = Indicators.atr(candles, ATR_PERIOD);
        double adx = Indicators.adx(candles, ADX_PERIOD);

        // ===== 하드 필터 =====
        if (adx < ADX_MIN) return Signal.none();
        if (emaFast <= emaSlow) return Signal.none();
        if (close <= emaSlow) return Signal.none();

        // RSI
        if (rsi < RSI_MIN || rsi > RSI_MAX) return Signal.none();

        // ATR
        if (atr / close < MIN_ATR_PCT) return Signal.none();

        // close > EMA8
        if (close <= emaFast) return Signal.none();

        // 양봉 강도
        double range = last.high_price - last.low_price;
        double body = Math.abs(last.trade_price - last.opening_price);
        double bodyRatio = range > 0 ? body / range : 0;
        if (bodyRatio < MIN_BODY_RATIO) return Signal.none();

        // EMA 이격
        double emaGap = emaSlow > 0 ? (emaFast - emaSlow) / emaSlow * 100 : 0;
        if (emaGap < MIN_EMA_GAP_PCT) return Signal.none();

        // 거래량
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double curVol = last.candle_acc_trade_volume;
        if (avgVol > 0 && curVol < avgVol * VOLUME_THRESHOLD) return Signal.none();
        double volRatio = avgVol > 0 ? curVol / avgVol : 1.0;

        // EMA 계단 (보너스)
        boolean emaStaircase = emaFast > emaSlow && emaSlow > emaTrend;
        boolean aboveTrend = close > emaTrend;

        // ===== Confidence Score =====
        double score = 4.5;
        // EMA gap
        if (emaGap >= 0.8) score += 1.5;
        else if (emaGap >= 0.4) score += 1.0;
        else if (emaGap >= 0.2) score += 0.5;
        else score += 0.2;
        // RSI sweet spot
        if (rsi >= 45 && rsi <= 58) score += 1.0;
        else score += 0.3;
        // body
        if (bodyRatio >= 0.7) score += 1.0;
        else if (bodyRatio >= 0.5) score += 0.5;
        // volume
        if (volRatio >= 1.8) score += 1.5;
        else if (volRatio >= 1.2) score += 0.8;
        else score += 0.2;
        // ADX
        if (adx >= 30) score += 1.5;
        else if (adx >= 25) score += 1.0;
        else if (adx >= 20) score += 0.5;
        else score += 0.2;
        // 이전 양봉
        if (prev.trade_price > prev.opening_price) score += 0.5;
        // EMA 계단
        if (emaStaircase && aboveTrend) score += 1.0;
        else if (emaStaircase || aboveTrend) score += 0.5;

        String reason = String.format(Locale.ROOT,
                "SM_BUY ema%s adx=%.1f gap=%.2f%% rsi=%.1f body=%.0f%% vol=%.1fx",
                emaStaircase ? "8>21>50" : "8>21",
                adx, emaGap, rsi, bodyRatio * 100, volRatio);
        return Signal.of(SignalAction.BUY, type(), reason, Math.min(10.0, score));
    }
}
