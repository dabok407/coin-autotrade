package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

/**
 * [1] 하락 장악형 (3캔들 확인 패턴)
 * - c1: 양봉
 * - c2: c1 몸통을 완전히 덮는 음봉 (engulfing)
 * - c3: 확인 음봉 (c2 종가 아래로 마감)
 * 현물 롱 only이므로: 포지션 보유중이면 SELL 신호로 사용.
 */
public class BearishEngulfingStrategy implements TradingStrategy {
    @Override public StrategyType type() { return StrategyType.BEARISH_ENGULFING; }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        if (ctx.candles == null || ctx.candles.size() < 3) return Signal.none();
        if (ctx.position == null || ctx.position.getQty() == null
                || ctx.position.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) return Signal.none();

        int size = ctx.candles.size();
        UpbitCandle c1 = ctx.candles.get(size - 3);
        UpbitCandle c2 = ctx.candles.get(size - 2);
        UpbitCandle c3 = ctx.candles.get(size - 1);

        if (!CandlePatterns.isBearishEngulfing(c1, c2)) return Signal.none();

        // 확인 캔들: c3가 음봉이고 c2 종가 아래로 마감해야 함
        if (!CandlePatterns.isBearish(c3)) return Signal.none();
        if (c3.trade_price >= c2.trade_price) return Signal.none();

        double score = 4.0;
        double c1Body = CandlePatterns.body(c1);
        double c2Body = CandlePatterns.body(c2);
        if (c1Body > 0) {
            double engulfRatio = c2Body / c1Body;
            if (engulfRatio >= 2.0) score += 2.0;
            else if (engulfRatio >= 1.5) score += 1.2;
            else score += 0.4;
        }
        double c2BodyR = CandlePatterns.range(c2) > 0 ? c2Body / CandlePatterns.range(c2) : 0;
        if (c2BodyR >= 0.7) score += 1.5;
        else if (c2BodyR >= 0.5) score += 0.8;
        else score += 0.3;

        // c3 확인 강도: c3가 c2 종가 대비 얼마나 아래로 마감했는지
        double c3Body = CandlePatterns.body(c3);
        double c3BodyR = CandlePatterns.range(c3) > 0 ? c3Body / CandlePatterns.range(c3) : 0;
        if (c3BodyR >= 0.6) score += 1.5;
        else if (c3BodyR >= 0.4) score += 0.8;
        else score += 0.3;

        return Signal.of(SignalAction.SELL, type(), "Bearish engulfing confirmed -> exit", Math.min(10.0, score));
    }
}
