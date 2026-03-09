package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

/**
 * [6] 이브닝스타: 상승 추세 끝자락에서 하락 반전 신호
 * - 현물 롱 only => 보유 중이면 SELL(청산)
 */
public class EveningStarSellStrategy implements TradingStrategy {
    @Override public StrategyType type() { return StrategyType.EVENING_STAR_SELL; }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        if (ctx.candles == null || ctx.candles.size() < 3) return Signal.none();
        if (ctx.position == null || ctx.position.getQty() == null
                || ctx.position.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) return Signal.none();

        UpbitCandle c1 = ctx.candles.get(ctx.candles.size()-3);
        UpbitCandle c2 = ctx.candles.get(ctx.candles.size()-2);
        UpbitCandle c3 = ctx.candles.get(ctx.candles.size()-1);

        if (CandlePatterns.isEveningStar(c1, c2, c3)) {
            double score = 4.0;
            // (1) c1 양봉 강도 (모닝스타와 대칭)
            double c1BodyR = CandlePatterns.range(c1) > 0 ? CandlePatterns.body(c1) / CandlePatterns.range(c1) : 0;
            if (c1BodyR >= 0.7) score += 1.5;
            else if (c1BodyR >= 0.5) score += 0.8;
            // (2) c2 도지 정도: body/range 작을수록 좋음
            double c2BodyR = CandlePatterns.range(c2) > 0 ? CandlePatterns.body(c2) / CandlePatterns.range(c2) : 0;
            if (c2BodyR <= 0.10) score += 1.5;
            else if (c2BodyR <= 0.20) score += 1.0;
            else score += 0.3;
            // (3) c3 음봉 몸통 강도
            double c3BodyR = CandlePatterns.range(c3) > 0 ? CandlePatterns.body(c3) / CandlePatterns.range(c3) : 0;
            if (c3BodyR >= 0.7) score += 2.0;
            else if (c3BodyR >= 0.5) score += 1.0;
            else score += 0.3;
            // (4) c3 하락 침투도: c1 몸통 50% 대비 얼마나 더 내려갔는지
            double mid = c1.opening_price + (CandlePatterns.body(c1) * 0.5);
            double coverPct = mid > 0 ? (mid - c3.trade_price) / mid * 100 : 0;
            if (coverPct >= 1.0) score += 2.0;
            else if (coverPct >= 0.3) score += 1.0;
            else score += 0.3;
            return Signal.of(SignalAction.SELL, type(), "Evening star -> exit", Math.min(10.0, score));
        }
        return Signal.none();
    }
}
