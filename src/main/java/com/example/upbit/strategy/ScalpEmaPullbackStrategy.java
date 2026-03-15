package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * EMA Pullback Scalping Strategy (5-minute candles, LONG only)
 *
 * Core logic:
 * - Uptrend confirmation: EMA8 > EMA21
 * - Pullback entry: price touches EMA8 zone in last 3 candles, then bounces with bullish close
 * - Filters: MACD histogram > 0, volume >= 0.8x avg, RSI 40-65
 * - Exit: ATR-based hard SL/TP, trend break exit, trailing stop
 * - Add-buy: max 1, near EMA21 with RSI < 40
 *
 * Stateless design: candle history + PositionEntity only.
 */
public class ScalpEmaPullbackStrategy implements TradingStrategy {

    // ===== Parameters =====
    private static final int MIN_CANDLES = 45;

    private static final int EMA_FAST = 8;
    private static final int EMA_SLOW = 21;

    private static final int RSI_PERIOD = 14;
    private static final double RSI_MIN = 40.0;
    private static final double RSI_MAX = 65.0;

    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;

    private static final int ATR_PERIOD = 10;
    private static final double TP_ATR_MULT = 2.5;
    private static final double SL_ATR_MULT = 1.5;
    private static final double TRAIL_ATR_MULT = 1.5;

    private static final int VOLUME_AVG_PERIOD = 20;
    private static final double VOLUME_THRESHOLD = 0.8;

    private static final int PULLBACK_LOOKBACK = 3;

    private static final int MAX_ADD_BUYS = 1;

    @Override
    public StrategyType type() {
        return StrategyType.SCALP_EMA_PULLBACK;
    }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        List<UpbitCandle> candles = ctx.candles;
        if (candles == null || candles.size() < MIN_CANDLES) return Signal.none();

        UpbitCandle last = candles.get(candles.size() - 1);
        double close = last.trade_price;

        boolean hasPosition = ctx.position != null
                && ctx.position.getQty() != null
                && ctx.position.getQty().compareTo(BigDecimal.ZERO) > 0;

        // ===== Indicator calculation =====
        double ema8 = Indicators.ema(candles, EMA_FAST);
        double ema21 = Indicators.ema(candles, EMA_SLOW);
        double rsi = Indicators.rsi(candles, RSI_PERIOD);
        double atr = Indicators.atr(candles, ATR_PERIOD);
        double[] macd = Indicators.macd(candles, MACD_FAST, MACD_SLOW, MACD_SIGNAL);
        double macdHist = macd[2];
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);

        boolean trendUp = ema8 > ema21;

        // ===== Position held: exit logic =====
        if (hasPosition) {
            double avgPrice = ctx.position.getAvgPrice().doubleValue();
            if (avgPrice <= 0) return Signal.none();

            double pnlPct = ((close - avgPrice) / avgPrice) * 100.0;

            // 1. Hard stop-loss
            double slPrice = avgPrice - SL_ATR_MULT * atr;
            if (last.low_price <= slPrice) {
                String reason = String.format(Locale.ROOT,
                        "HARD_SL avg=%.2f sl=%.2f atr=%.4f pnl=%.2f%%",
                        avgPrice, slPrice, atr, pnlPct);
                return Signal.of(SignalAction.SELL, type(), reason);
            }

            // 2. Hard take-profit
            double tpPrice = avgPrice + TP_ATR_MULT * atr;
            if (last.high_price >= tpPrice) {
                String reason = String.format(Locale.ROOT,
                        "HARD_TP avg=%.2f tp=%.2f atr=%.4f pnl=%.2f%%",
                        avgPrice, tpPrice, atr, ((tpPrice - avgPrice) / avgPrice) * 100.0);
                return Signal.of(SignalAction.SELL, type(), reason);
            }

            // 3. Trend break: EMA8 < EMA21 and (profit or loss > 1%)
            if (!trendUp && (pnlPct > 0 || pnlPct < -1.0)) {
                String reason = String.format(Locale.ROOT,
                        "TREND_BREAK ema8=%.2f ema21=%.2f pnl=%.2f%%",
                        ema8, ema21, pnlPct);
                return Signal.of(SignalAction.SELL, type(), reason);
            }

            // 4. Trailing stop (only when in profit)
            if (pnlPct > 0) {
                double peakHigh = Indicators.peakHighSinceEntry(candles, avgPrice);
                double trailStop = peakHigh - TRAIL_ATR_MULT * atr;
                if (trailStop > avgPrice && last.low_price <= trailStop) {
                    String reason = String.format(Locale.ROOT,
                            "TRAIL_STOP avg=%.2f peak=%.2f trail=%.2f atr=%.4f pnl=%.2f%%",
                            avgPrice, peakHigh, trailStop, atr, pnlPct);
                    return Signal.of(SignalAction.SELL, type(), reason);
                }
            }

            // Add-buy: max 1, near EMA21, RSI < 40, trend still up, volume >= 0.5x avg
            if (ctx.position.getAddBuys() < MAX_ADD_BUYS
                    && close <= ema21 * 1.01
                    && rsi < 40.0
                    && trendUp
                    && avgVol > 0 && last.candle_acc_trade_volume >= avgVol * 0.5) {
                String reason = String.format(Locale.ROOT,
                        "ADD_BUY close=%.2f ema21=%.2f rsi=%.1f vol_ratio=%.2f",
                        close, ema21, rsi, last.candle_acc_trade_volume / avgVol);
                return Signal.of(SignalAction.ADD_BUY, type(), reason);
            }

            return Signal.none();
        }

        // ===== No position: entry logic =====

        // 1. EMA8 > EMA21 (uptrend)
        if (!trendUp) return Signal.none();

        // 2. Pullback: at least one of the last 3 candles has low <= EMA8 * 1.003
        boolean pullbackFound = false;
        double pullbackThreshold = ema8 * 1.003;
        int size = candles.size();
        for (int i = 1; i <= PULLBACK_LOOKBACK && i < size; i++) {
            UpbitCandle c = candles.get(size - i);
            if (c.low_price <= pullbackThreshold) {
                pullbackFound = true;
                break;
            }
        }
        if (!pullbackFound) return Signal.none();

        // 3. Current candle is bullish AND close > EMA8
        if (!CandlePatterns.isBullish(last) || close <= ema8) return Signal.none();

        // 4. MACD histogram > 0
        if (macdHist <= 0) return Signal.none();

        // 5. Volume >= 0.8x average
        if (avgVol > 0 && last.candle_acc_trade_volume < avgVol * VOLUME_THRESHOLD) return Signal.none();

        // 6. RSI between 40 and 65
        if (rsi < RSI_MIN || rsi > RSI_MAX) return Signal.none();

        // ===== Confidence scoring =====
        double score = 4.5;

        // EMA margin: (ema8 - ema21) / ema21
        double emaMarginPct = (ema8 - ema21) / ema21 * 100.0;
        if (emaMarginPct >= 1.0) {
            score += 1.5;
        } else if (emaMarginPct >= 0.5) {
            score += 1.0;
        } else {
            score += 0.3;
        }

        // MACD histogram strength (bps = hist/close * 10000)
        double macdBps = macdHist / close * 10000.0;
        if (macdBps >= 5.0) {
            score += 1.0;
        } else if (macdBps >= 3.0) {
            score += 0.5;
        }

        // Volume ratio
        double volRatio = (avgVol > 0) ? last.candle_acc_trade_volume / avgVol : 1.0;
        if (volRatio >= 2.0) {
            score += 1.5;
        } else if (volRatio >= 1.3) {
            score += 1.0;
        } else {
            score += 0.3;
        }

        // RSI sweet spot (distance from 50)
        double rsiDist = Math.abs(rsi - 50.0);
        if (rsiDist <= 5.0) {
            score += 1.0;
        } else if (rsiDist <= 10.0) {
            score += 0.5;
        }

        score = Math.min(10.0, score);

        String reason = String.format(Locale.ROOT,
                "EMA_PULLBACK ema8=%.2f ema21=%.2f rsi=%.1f macd_h=%.4f vol_r=%.2f atr=%.4f",
                ema8, ema21, rsi, macdHist, volRatio, atr);
        return Signal.of(SignalAction.BUY, type(), reason, score);
    }
}
