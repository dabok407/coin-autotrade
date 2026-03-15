package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * [18] 레인지 돌파 스캘핑 (15분봉, 자급자족)
 *
 * 핵심 로직:
 * - 최근 20봉의 고/저 범위(박스)를 계산하고, 범위가 3% 미만인 압축 구간을 감지
 * - 현재 종가가 박스 상단을 돌파하면 진입 (강한 양봉 + 거래량 스파이크 필터)
 * - ATR 기반 SL/TP + 트레일링 스탑 + 실패 돌파 감지로 청산
 *
 * 설계 원칙:
 * - 완전 무상태(stateless): 캔들 히스토리 + PositionEntity만으로 판단
 * - 15분봉 스캘핑에 최적화
 */
public class ScalpBreakoutRangeStrategy implements TradingStrategy {

    // ===== 파라미터 =====
    private static final int MIN_CANDLES = 40;
    private static final int RANGE_LOOKBACK = 20;
    private static final double MAX_RANGE_PCT = 3.0;

    private static final int ATR_PERIOD = 14;
    private static final double TP_ATR_MULT = 3.0;
    private static final double SL_ATR_MULT = 1.5;
    private static final double TRAIL_ATR_MULT = 2.0;

    private static final int VOLUME_AVG_PERIOD = 20;
    private static final double VOLUME_SPIKE_MULT = 1.5;

    private static final double MIN_BODY_RATIO = 0.50;
    private static final double MIN_ATR_PCT = 0.0015;

    @Override
    public StrategyType type() {
        return StrategyType.SCALP_BREAKOUT_RANGE;
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

        double atr = Indicators.atr(candles, ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return Signal.none();

        // ===== 포지션 보유 중: 청산 판단 =====
        if (hasPosition) {
            double avgPrice = ctx.position.getAvgPrice().doubleValue();
            if (avgPrice <= 0) return Signal.none();

            // 1) Hard SL: 저가가 entry - 1.5*ATR 이하
            double slPrice = avgPrice - SL_ATR_MULT * atr;
            if (last.low_price <= slPrice) {
                String reason = String.format(Locale.ROOT,
                        "HARD_SL avg=%.2f sl=%.2f atr=%.4f low=%.2f",
                        avgPrice, slPrice, atr, last.low_price);
                return Signal.of(SignalAction.SELL, type(), reason);
            }

            // 2) Hard TP: 고가가 entry + 3.0*ATR 이상
            double tpPrice = avgPrice + TP_ATR_MULT * atr;
            if (last.high_price >= tpPrice) {
                String reason = String.format(Locale.ROOT,
                        "HARD_TP avg=%.2f tp=%.2f atr=%.4f high=%.2f",
                        avgPrice, tpPrice, atr, last.high_price);
                return Signal.of(SignalAction.SELL, type(), reason);
            }

            // 3) Failed breakout: pnl < -0.5% AND current candle is bearish
            double pnlPct = ((close - avgPrice) / avgPrice) * 100.0;
            boolean bearish = !CandlePatterns.isBullish(last);
            if (pnlPct < -0.5 && bearish) {
                String reason = String.format(Locale.ROOT,
                        "FAILED_BREAKOUT avg=%.2f close=%.2f pnl=%.2f%%",
                        avgPrice, close, pnlPct);
                return Signal.of(SignalAction.SELL, type(), reason);
            }

            // 4) Trailing stop: peak - 2.0*ATR (이익 구간에서만)
            if (close > avgPrice) {
                double peakHigh = Indicators.peakHighSinceEntry(candles, avgPrice);
                double trailStop = peakHigh - TRAIL_ATR_MULT * atr;
                if (trailStop > avgPrice && close <= trailStop) {
                    String reason = String.format(Locale.ROOT,
                            "TRAIL_STOP avg=%.2f peak=%.2f trail=%.2f atr=%.4f close=%.2f",
                            avgPrice, peakHigh, trailStop, atr, close);
                    return Signal.of(SignalAction.SELL, type(), reason);
                }
            }

            return Signal.none();
        }

        // ===== 포지션 없음: 진입 판단 =====

        // 1) 레인지 계산: 현재 캔들 제외, 직전 20봉의 최고가/최저가
        int end = candles.size() - 1; // exclusive: current candle
        int start = end - RANGE_LOOKBACK;
        if (start < 0) return Signal.none();

        double rangeHigh = Double.MIN_VALUE;
        double rangeLow = Double.MAX_VALUE;
        for (int i = start; i < end; i++) {
            UpbitCandle c = candles.get(i);
            if (c.high_price > rangeHigh) rangeHigh = c.high_price;
            if (c.low_price < rangeLow) rangeLow = c.low_price;
        }

        // 2) 레인지 폭 < 3%
        double rangePct = (rangeHigh - rangeLow) / rangeLow * 100.0;
        if (rangePct >= MAX_RANGE_PCT) return Signal.none();

        // 3) 현재 종가가 레인지 상단 돌파
        if (close <= rangeHigh) return Signal.none();

        // 4) 현재 캔들이 양봉
        if (!CandlePatterns.isBullish(last)) return Signal.none();

        // 5) body/range >= 50%
        double body = CandlePatterns.body(last);
        double candleRange = CandlePatterns.range(last);
        if (candleRange <= 0) return Signal.none();
        double bodyRatio = body / candleRange;
        if (bodyRatio < MIN_BODY_RATIO) return Signal.none();

        // 6) 거래량 >= 1.5 x 20기간 평균 거래량
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        if (Double.isNaN(avgVol) || avgVol <= 0) return Signal.none();
        double curVol = last.candle_acc_trade_volume;
        if (curVol < VOLUME_SPIKE_MULT * avgVol) return Signal.none();

        // 7) ATR / close >= 0.15%
        double atrPct = atr / close;
        if (atrPct < MIN_ATR_PCT) return Signal.none();

        // ===== Confidence Scoring =====
        double score = 4.5;

        // (1) 돌파 강도: (close - rangeHigh) / rangeHigh * 100
        double breakoutPct = (close - rangeHigh) / rangeHigh * 100.0;
        if (breakoutPct >= 1.5) score += 2.0;
        else if (breakoutPct >= 0.8) score += 1.5;
        else if (breakoutPct >= 0.3) score += 0.8;
        else score += 0.3;

        // (2) 거래량 스파이크
        double volRatio = curVol / avgVol;
        if (volRatio >= 3.0) score += 1.5;
        else if (volRatio >= 2.0) score += 1.0;
        else score += 0.3;

        // (3) 캔들 바디 강도
        if (bodyRatio >= 0.75) score += 1.0;
        else if (bodyRatio >= 0.6) score += 0.5;

        // (4) 레인지 압축도
        if (rangePct < 1.5) score += 1.0;
        else if (rangePct < 2.0) score += 0.5;

        score = Math.min(10.0, score);

        String reason = String.format(Locale.ROOT,
                "RANGE_BREAKOUT close=%.2f rangeHigh=%.2f breakout=%.2f%% vol=%.1fx body=%.0f%% range=%.2f%% atr=%.4f",
                close, rangeHigh, breakoutPct, volRatio, bodyRatio * 100, rangePct, atr);
        return Signal.of(SignalAction.BUY, type(), reason, score);
    }
}
