package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.util.List;
import java.util.Locale;

/**
 * EMA-RSI Trend Strategy (ERT) v6.3 -- BUY-ONLY 추세 추종 (품질 중심)
 *
 * v6.3 변경:
 * - ADX >= 35 Pullback 보너스 강화 (+2.5): 강한 추세의 눌림은 고품질
 * v6.2 변경:
 * - 모멘텀 확인 필터: 최근 3봉 중 2봉 이상 양봉 필수
 * v6 변경:
 * - Mode C (EMA Cross) 제거: 약한 신호로 62% SL 히트
 * - Mode A (Pullback): ADX > 20 강화, RSI 35-55, body >= 0.30
 * - Mode B (Breakout): ADX > 15, 거래량 1.3x, RSI < 70
 * - 하드 필터: EMA9 > EMA21 + close > EMA21 (최소 추세 확인)
 * - close > EMA50이면 추가 보너스, 하드 필터 아님
 * - 이전 2봉 패턴 확인: 직전 캔들이 큰 음봉이면 진입 차단
 *
 * ■ 권장: 60분봉, TP 3%, SL 2%, minConfidence 8, PCT90
 * ■ 최적 마켓: KRW-SOL, KRW-ETH
 */
public class EmaRsiTrendStrategy implements TradingStrategy {

    private static final int EMA_FAST = 9;
    private static final int EMA_SLOW = 21;
    private static final int EMA_TREND = 50;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final int ADX_PERIOD = 14;
    private static final int VOLUME_AVG_PERIOD = 20;

    // 모드 A 필터
    private static final double ADX_MIN_PULLBACK = 20.0;
    private static final double RSI_PULLBACK_MIN = 35.0;
    private static final double RSI_PULLBACK_MAX = 55.0;
    private static final double MIN_BODY_RATIO = 0.30;

    // 모드 B 필터
    private static final double ADX_MIN_BREAKOUT = 15.0;
    private static final double RSI_BREAKOUT_MAX = 70.0;
    private static final int BREAKOUT_LOOKBACK = 20;
    private static final double BREAKOUT_VOL_THRESHOLD = 1.3;

    // 안전
    private static final double MIN_ATR_PCT = 0.002;
    private static final int MIN_CANDLES = 55;

    @Override
    public StrategyType type() {
        return StrategyType.EMA_RSI_TREND;
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

        // ATR 안전 체크
        if (atr / close < MIN_ATR_PCT) return Signal.none();

        // ===== 하드 필터: 최소 추세 확인 =====
        // EMA9 > EMA21 (단기 상승 추세 필수)
        if (emaFast <= emaSlow) return Signal.none();
        // close > EMA21 (가격이 중기 평균 위)
        if (close <= emaSlow) return Signal.none();

        // 모멘텀 확인: 최근 3봉 중 2봉 이상 양봉 (상승 모멘텀)
        if (n >= 4) {
            UpbitCandle c2 = candles.get(n - 3);
            int bullCount = 0;
            if (last.trade_price > last.opening_price) bullCount++;
            if (prev.trade_price > prev.opening_price) bullCount++;
            if (c2.trade_price > c2.opening_price) bullCount++;
            if (bullCount < 2) return Signal.none();
        }

        // EMA 계단 정렬 (보너스용)
        boolean emaStaircase = emaFast > emaSlow && emaSlow > emaTrend;
        boolean aboveTrend = close > emaTrend;

        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double curVol = last.candle_acc_trade_volume;
        double volRatio = avgVol > 0 ? curVol / avgVol : 1.0;
        double emaGap = (emaFast - emaSlow) / emaSlow * 100;

        // ===== 모드 A: RSI 눌림 매수 =====
        if (adx >= ADX_MIN_PULLBACK && close > emaFast) {
            if (rsi >= RSI_PULLBACK_MIN && rsi <= RSI_PULLBACK_MAX) {
                boolean currBullish = last.trade_price > last.opening_price;
                if (currBullish) {
                    double range = last.high_price - last.low_price;
                    double body = Math.abs(last.trade_price - last.opening_price);
                    double bodyRatio = range > 0 ? body / range : 0;
                    if (bodyRatio >= MIN_BODY_RATIO) {
                        boolean prevBullish = prev.trade_price > prev.opening_price;

                        double score = 4.5;
                        // EMA gap 보너스
                        if (emaGap >= 0.5) score += 1.0;
                        else if (emaGap >= 0.2) score += 0.5;
                        else score += 0.2;
                        // RSI sweet spot
                        double rsiDist = Math.abs(rsi - 45);
                        if (rsiDist <= 3) score += 1.5;
                        else if (rsiDist <= 7) score += 1.0;
                        else score += 0.3;
                        // 거래량
                        if (volRatio >= 1.5) score += 1.0;
                        else if (volRatio >= 1.0) score += 0.5;
                        else score += 0.1;
                        // ADX (강한 추세에서의 눌림은 고품질)
                        if (adx >= 35) score += 2.5;
                        else if (adx >= 30) score += 1.5;
                        else if (adx >= 25) score += 1.0;
                        else score += 0.3;
                        // 이전 양봉
                        if (prevBullish) score += 0.5;
                        // EMA 계단 + 트렌드 위
                        if (emaStaircase && aboveTrend) score += 1.0;
                        else if (emaStaircase || aboveTrend) score += 0.5;
                        // body ratio 보너스
                        if (bodyRatio >= 0.6) score += 0.5;

                        String reason = String.format(Locale.ROOT,
                                "ERT_PULLBACK ema%s adx=%.1f rsi=%.1f body=%.0f%% vol=%.1fx gap=%.2f%%",
                                emaStaircase ? "9>21>50" : "9>21", adx, rsi, bodyRatio * 100, volRatio, emaGap);
                        return Signal.of(SignalAction.BUY, type(), reason, Math.min(10.0, score));
                    }
                }
            }
        }

        // ===== 모드 B: 돌파 매수 =====
        if (adx >= ADX_MIN_BREAKOUT && candles.size() >= BREAKOUT_LOOKBACK + 1) {
            double highestHigh = 0;
            for (int i = candles.size() - 1 - BREAKOUT_LOOKBACK; i < candles.size() - 1; i++) {
                if (candles.get(i).high_price > highestHigh) highestHigh = candles.get(i).high_price;
            }

            if (close > highestHigh && rsi < RSI_BREAKOUT_MAX
                    && volRatio >= BREAKOUT_VOL_THRESHOLD) {
                // 양봉 확인
                boolean currBullish = last.trade_price > last.opening_price;

                double score = 5.0;
                double breakPct = (close - highestHigh) / highestHigh * 100;
                if (breakPct >= 1.0) score += 2.0;
                else if (breakPct >= 0.5) score += 1.5;
                else score += 0.5;
                if (volRatio >= 2.5) score += 1.5;
                else if (volRatio >= 1.8) score += 1.0;
                else score += 0.3;
                if (adx >= 25) score += 1.5;
                else if (adx >= 20) score += 1.0;
                else score += 0.3;
                if (emaStaircase) score += 1.0;
                else if (emaFast > emaSlow) score += 0.5;
                if (currBullish) score += 0.5;
                if (aboveTrend) score += 0.5;

                String reason = String.format(Locale.ROOT,
                        "ERT_BREAKOUT close>high20(+%.2f%%) adx=%.1f vol=%.1fx rsi=%.1f",
                        breakPct, adx, volRatio, rsi);
                return Signal.of(SignalAction.BUY, type(), reason, Math.min(10.0, score));
            }
        }

        return Signal.none();
    }
}
