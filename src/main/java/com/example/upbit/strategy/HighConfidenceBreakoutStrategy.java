package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

/**
 * [21] 고확신 돌파 전략 (자급자족, 종일 스캐너)
 *
 * ═══════════════════════════════════════════════════════════
 *  8-Factor 스코어링 기반 고확신 돌파 진입
 *  Hard SL + EMA Break + MACD Fade + Trailing + Time Stop + Session End 청산
 * ═══════════════════════════════════════════════════════════
 *
 * ■ 설계 철학:
 *   5분봉 종일 운용. 8개 요소(거래량, EMA, MACD, RSI, ADX, 신고가, 캔들, ATR)의
 *   합류 점수가 높을 때만 진입하여 허위 신호를 최소화.
 *
 * ■ Hard Prerequisites (하나라도 미충족 → 즉시 skip):
 *   ① 마지막 캔들 양봉
 *   ② RSI 40~75
 *   ③ ADX >= 18
 *   ④ MACD histogram > 0
 *
 * ■ 8-Factor Scoring (max 10.0):
 *   1. Volume Surge (1.5)  2. EMA Alignment (1.5)
 *   3. MACD Confirmation (1.3)  4. RSI Sweet Spot (1.0)
 *   5. ADX Strength (1.2)  6. New High Breakout (1.5)
 *   7. Body Quality (1.0)  8. ATR Range (1.0)
 *
 * ■ 청산 (6단계 우선순위):
 *   1. Hard SL  2. EMA Break  3. MACD Fade
 *   4. Trailing Stop  5. Time Stop  6. Session End
 *
 * ■ 권장: 5분봉, minConfidence 9.4
 */
public class HighConfidenceBreakoutStrategy implements TradingStrategy {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MIN_CANDLES = 60;

    // ===== 지표 기간 =====
    private static final int RSI_PERIOD = 14;
    private static final int ADX_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int VOLUME_AVG_PERIOD = 20;
    private static final int EMA_FAST = 8;
    private static final int EMA_MID = 21;
    private static final int EMA_SLOW_PERIOD = 50;

    // ===== 조정 가능 파라미터 (Builder) =====
    private double slPct = 1.5;
    private double trailAtrMult = 0.8;
    private double minConfidence = 9.4;
    private double volumeSurgeMult = 3.0;
    private double minBodyRatio = 0.60;
    private int sessionEndHour = 8;
    private int sessionEndMin = 0;
    private int timeStopCandles = 12;
    private double timeStopMinPnl = 0.3;

    @Override
    public StrategyType type() {
        return StrategyType.HIGH_CONFIDENCE_BREAKOUT;
    }

    // ===== Builder 세터 =====

    public HighConfidenceBreakoutStrategy withRisk(double slPct, double trailAtrMult) {
        this.slPct = slPct;
        this.trailAtrMult = trailAtrMult;
        return this;
    }

    public HighConfidenceBreakoutStrategy withFilters(double volumeSurgeMult, double minBodyRatio, double minConfidence) {
        this.volumeSurgeMult = volumeSurgeMult;
        this.minBodyRatio = minBodyRatio;
        this.minConfidence = minConfidence;
        return this;
    }

    public HighConfidenceBreakoutStrategy withTiming(int sessionEndHour, int sessionEndMin) {
        this.sessionEndHour = sessionEndHour;
        this.sessionEndMin = sessionEndMin;
        return this;
    }

    public HighConfidenceBreakoutStrategy withTimeStop(int candles, double minPnl) {
        this.timeStopCandles = candles;
        this.timeStopMinPnl = minPnl;
        return this;
    }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        List<UpbitCandle> candles = ctx.candles;
        if (candles == null || candles.size() < MIN_CANDLES) return Signal.none();

        int n = candles.size();
        UpbitCandle last = candles.get(n - 1);
        double close = last.trade_price;

        boolean hasPosition = ctx.position != null
                && ctx.position.getQty() != null
                && ctx.position.getQty().compareTo(BigDecimal.ZERO) > 0;

        if (hasPosition) {
            return evaluateExit(ctx, candles, last, close);
        }
        return evaluateEntry(candles, last, close);
    }

    // ═══════════════════════════════════════
    //  진입 로직 — 4 필수 게이트 + 8-Factor 스코어링
    // ═══════════════════════════════════════
    private Signal evaluateEntry(List<UpbitCandle> candles, UpbitCandle last, double close) {

        // ── Hard prerequisite gates ──

        // 1. 마지막 캔들 양봉
        if (!CandlePatterns.isBullish(last)) return Signal.none("NOT_BULLISH");

        // 2. RSI 범위
        double rsi = Indicators.rsi(candles, RSI_PERIOD);
        if (rsi < 40 || rsi > 75) {
            return Signal.none(String.format(Locale.ROOT, "RSI_OUT_OF_RANGE rsi=%.1f", rsi));
        }

        // 3. ADX 최소
        double adx = Indicators.adx(candles, ADX_PERIOD);
        if (adx < 18) {
            return Signal.none(String.format(Locale.ROOT, "ADX_TOO_LOW adx=%.1f", adx));
        }

        // 4. MACD histogram > 0
        double[] hist = Indicators.macdHistogramRecent(candles, MACD_FAST, MACD_SLOW, MACD_SIGNAL, 3);
        if (hist.length < 3) return Signal.none("MACD_INSUFFICIENT");
        if (hist[2] <= 0) {
            return Signal.none(String.format(Locale.ROOT, "MACD_NEGATIVE hist=%.6f", hist[2]));
        }

        // ── 8-Factor Scoring ──
        double score = 0.0;

        // Factor 1 - Volume Surge (max 1.5)
        double curVol = last.candle_acc_trade_volume;
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double volRatio = avgVol > 0 ? curVol / avgVol : 0;
        if (volRatio >= 3.0) score += 1.5;
        else if (volRatio >= 2.5) score += 1.2;
        else if (volRatio >= 2.0) score += 0.8;

        // Factor 2 - EMA Alignment (max 1.5)
        double ema8 = Indicators.ema(candles, EMA_FAST);
        double ema21 = Indicators.ema(candles, EMA_MID);
        double ema50 = Indicators.ema(candles, EMA_SLOW_PERIOD);
        boolean emaFullAlign = ema8 > ema21 && ema21 > ema50;
        if (emaFullAlign) score += 1.5;
        else if (ema8 > ema21) score += 0.5;

        // Factor 3 - MACD Confirmation (max 1.3)
        boolean positive = hist[2] > 0;
        boolean expanding = hist[2] > hist[1] && hist[1] > hist[0];
        if (positive && expanding) score += 1.3;
        else if (positive) score += 0.7;

        // Factor 4 - RSI Sweet Spot (max 1.0)
        if (rsi >= 55 && rsi <= 65) score += 1.0;
        else if (rsi >= 50 && rsi <= 70) score += 0.7;

        // Factor 5 - ADX Strength (max 1.2)
        if (adx > 30) score += 1.2;
        else if (adx > 25) score += 0.9;
        else if (adx > 20) score += 0.5;

        // Factor 6 - New High Breakout (max 1.5)
        double recentHigh = Indicators.recentHigh(candles, 20);
        double breakoutPct = recentHigh > 0 ? (close - recentHigh) / recentHigh * 100 : 0;
        if (breakoutPct >= 0.5) score += 1.5;
        else if (breakoutPct >= 0.3) score += 1.0;
        else if (breakoutPct > 0) score += 0.5;

        // Factor 7 - Body Quality (max 1.0)
        double bodyRatio = CandlePatterns.range(last) > 0
                ? CandlePatterns.body(last) / CandlePatterns.range(last) : 0;
        if (bodyRatio >= 0.70) score += 1.0;
        else if (bodyRatio >= 0.60) score += 0.7;
        else if (bodyRatio >= 0.50) score += 0.3;

        // Factor 8 - ATR Range (max 1.0)
        double atr = Indicators.atr(candles, ATR_PERIOD);
        double atrPct = close > 0 ? atr / close * 100 : 0;
        if (atrPct >= 0.5 && atrPct <= 1.5) score += 1.0;
        else if (atrPct >= 0.3 && atrPct <= 2.0) score += 0.7;

        score = Math.min(10.0, score);

        if (score < minConfidence) return Signal.none();

        String reason = String.format(Locale.ROOT,
                "HC_BREAK close=%.2f score=%.1f vol=%.1fx ema=%s macd=%s rsi=%.0f adx=%.0f bo=%.2f%% body=%.0f%% atr=%.2f%%",
                close, score, volRatio,
                emaFullAlign ? "OK" : "PARTIAL",
                expanding ? "EXP" : "POS",
                rsi, adx, breakoutPct, bodyRatio * 100, atrPct);
        return Signal.of(SignalAction.BUY, type(), reason, score);
    }

    // ═══════════════════════════════════════
    //  청산 로직 — 6단계 우선순위
    // ═══════════════════════════════════════
    private Signal evaluateExit(StrategyContext ctx, List<UpbitCandle> candles,
                                UpbitCandle last, double close) {
        if (ctx.position.getAvgPrice() == null) return Signal.none();
        double avgPrice = ctx.position.getAvgPrice().doubleValue();
        if (avgPrice <= 0) return Signal.none();

        double pnlPct = (close - avgPrice) / avgPrice * 100.0;
        double atr = Indicators.atr(candles, ATR_PERIOD);

        // 1. Hard SL
        if (pnlPct <= -slPct) {
            return Signal.of(SignalAction.SELL, type(),
                    String.format(Locale.ROOT, "HC_SL pnl=%.2f%%", pnlPct));
        }

        // 2. EMA Trend Break
        double ema8 = Indicators.ema(candles, EMA_FAST);
        double ema21 = Indicators.ema(candles, EMA_MID);
        if (!Double.isNaN(ema8) && !Double.isNaN(ema21) && ema8 < ema21) {
            return Signal.of(SignalAction.SELL, type(),
                    String.format(Locale.ROOT, "HC_EMA_BREAK ema8=%.2f ema21=%.2f", ema8, ema21));
        }

        // 3. MACD Momentum Fade (only when in profit)
        if (pnlPct > 0) {
            double[] hist = Indicators.macdHistogramRecent(candles, MACD_FAST, MACD_SLOW, MACD_SIGNAL, 2);
            if (hist.length >= 2 && hist[1] < 0) {
                return Signal.of(SignalAction.SELL, type(),
                        String.format(Locale.ROOT, "HC_MACD_FADE hist=%.6f", hist[1]));
            }
        }

        // 4. Trailing Stop (activate when profit > 0.5%)
        if (pnlPct > 0.5 && !Double.isNaN(atr) && atr > 0) {
            double peak = Indicators.peakHighSinceEntry(candles, avgPrice);
            double trailStop = peak - trailAtrMult * atr;
            if (close <= trailStop) {
                return Signal.of(SignalAction.SELL, type(),
                        String.format(Locale.ROOT, "HC_TRAIL peak=%.2f stop=%.2f", peak, trailStop));
            }
        }

        // 5. Time Stop (12 candles + PnL < 0.3%)
        Instant openedAt = ctx.position.getOpenedAt();
        if (openedAt != null) {
            long openedEpochMs = openedAt.toEpochMilli();
            int candlesSinceEntry = 0;
            for (int i = candles.size() - 1; i >= 0; i--) {
                ZonedDateTime candleKst = toKst(candles.get(i).candle_date_time_utc);
                if (candleKst != null) {
                    long candleEpochMs = candleKst.toInstant().toEpochMilli();
                    if (candleEpochMs > openedEpochMs) {
                        candlesSinceEntry++;
                    } else {
                        break;
                    }
                }
            }
            if (candlesSinceEntry >= timeStopCandles && pnlPct < timeStopMinPnl) {
                return Signal.of(SignalAction.SELL, type(),
                        String.format(Locale.ROOT, "HC_TIME_STOP candles=%d", candlesSinceEntry));
            }
        }

        // 6. Session End — 캔들 시각 기준 (백테스트 호환)
        ZonedDateTime candleKst = toKst(last.candle_date_time_utc);
        ZonedDateTime nowKst = candleKst != null ? candleKst : ZonedDateTime.now(KST);
        int nowMinutes = nowKst.getHour() * 60 + nowKst.getMinute();
        int sessionEndMinutes = sessionEndHour * 60 + sessionEndMin;
        if (nowMinutes >= sessionEndMinutes) {
            return Signal.of(SignalAction.SELL, type(), "HC_SESSION_END");
        }

        return Signal.none();
    }

    // ═══════════════════════════════════════
    //  유틸리티
    // ═══════════════════════════════════════
    private static ZonedDateTime toKst(String utcStr) {
        if (utcStr == null || utcStr.isEmpty()) return null;
        try {
            LocalDateTime utcLdt = LocalDateTime.parse(utcStr);
            return utcLdt.atZone(ZoneOffset.UTC).withZoneSameInstant(KST);
        } catch (Exception e) {
            return null;
        }
    }
}
