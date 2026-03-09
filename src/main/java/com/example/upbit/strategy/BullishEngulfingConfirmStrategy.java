package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

/**
 * [1] 상승 장악형 + 3번째 양봉 확인형
 * - c1: 음봉
 * - c2: c1 몸통을 완전히 덮는 양봉(장악)
 * - c3: c2 종가 위에서 양봉 마감(확인)
 */
public class BullishEngulfingConfirmStrategy implements TradingStrategy {

    @Override public StrategyType type() { return StrategyType.BULLISH_ENGULFING_CONFIRM; }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        int emaPeriod = ctx.getEmaTrendPeriod(type());
        int minCandles = Math.max(10, emaPeriod > 0 ? emaPeriod + 5 : 0);
        if (ctx.candles == null || ctx.candles.size() < minCandles) return Signal.none();

        UpbitCandle c1 = ctx.candles.get(ctx.candles.size()-3);
        UpbitCandle c2 = ctx.candles.get(ctx.candles.size()-2);
        UpbitCandle c3 = ctx.candles.get(ctx.candles.size()-1);

        if (!CandlePatterns.isBullishEngulfing(c1, c2)) return Signal.none();
        if (!CandlePatterns.isBullish(c3)) return Signal.none();
        if (c3.trade_price <= c2.trade_price) return Signal.none(); // 확인: c2 종가 위

        // 트렌드 필터: 종가 > EMA (설정 가능, 0이면 비활성)
        if (emaPeriod > 0) {
            double emaVal = Indicators.ema(ctx.candles, emaPeriod);
            if (!Double.isNaN(emaVal) && c3.trade_price <= emaVal) return Signal.none();
        }

        // 포지션이 없다면 진입, 이미 있으면 신호만(엔진에서 cap/중복처리)
        if (ctx.position == null || ctx.position.getQty() == null
                || ctx.position.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            // === Confidence Score ===
            double score = 4.0;
            // (1) 장악 비율: c2가 c1을 얼마나 크게 덮는지
            double c1Body = CandlePatterns.body(c1);
            double c2Body = CandlePatterns.body(c2);
            if (c1Body > 0) {
                double engulfRatio = c2Body / c1Body;
                if (engulfRatio >= 2.0) score += 2.0;
                else if (engulfRatio >= 1.5) score += 1.5;
                else score += 0.5;
            }
            // (2) 확인봉(c3) 강도
            double c3Body = CandlePatterns.body(c3);
            double c3BodyRatio = CandlePatterns.range(c3) > 0 ? c3Body / CandlePatterns.range(c3) : 0;
            if (c3BodyRatio >= 0.7) score += 1.5;
            else if (c3BodyRatio >= 0.5) score += 1.0;
            else score += 0.3;
            // (3) c3 종가가 c2 고가를 넘으면 강한 확인
            if (c3.trade_price > c2.high_price) score += 1.5;
            else score += 0.5;
            return Signal.of(SignalAction.BUY, type(), "Bullish engulfing + 3rd candle confirm", Math.min(10.0, score));
        }
        return Signal.none();
    }
}
