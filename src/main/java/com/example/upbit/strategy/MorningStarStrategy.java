package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

/**
 * [6] 모닝스타: 하락 추세 끝자락에서 상승 반전 신호
 * - 최근 3봉이 모닝스타 조건이면 BUY
 */
public class MorningStarStrategy implements TradingStrategy {
    @Override public StrategyType type() { return StrategyType.MORNING_STAR; }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        int emaPeriod = ctx.getEmaTrendPeriod(type());
        int minCandles = Math.max(10, emaPeriod > 0 ? emaPeriod + 5 : 0);
        if (ctx.candles == null || ctx.candles.size() < minCandles) return Signal.none();
        if (ctx.position != null && ctx.position.getQty() != null
                && ctx.position.getQty().compareTo(java.math.BigDecimal.ZERO) > 0) return Signal.none();

        UpbitCandle c1 = ctx.candles.get(ctx.candles.size()-3);
        UpbitCandle c2 = ctx.candles.get(ctx.candles.size()-2);
        UpbitCandle c3 = ctx.candles.get(ctx.candles.size()-1);

        if (CandlePatterns.isMorningStar(c1, c2, c3)) {
            // 트렌드 필터: 종가 > EMA (설정 가능, 0이면 비활성)
            if (emaPeriod > 0) {
                double emaVal = Indicators.ema(ctx.candles, emaPeriod);
                if (!Double.isNaN(emaVal) && c3.trade_price <= emaVal) return Signal.none();
            }
            // === Confidence Score ===
            double score = 4.0;
            // (1) c1 음봉 강도
            double c1BodyR = CandlePatterns.range(c1) > 0 ? CandlePatterns.body(c1) / CandlePatterns.range(c1) : 0;
            if (c1BodyR >= 0.7) score += 1.5;
            else if (c1BodyR >= 0.5) score += 0.8;
            // (2) c2 도지 정도: body/range 작을수록 좋음
            double c2BodyR = CandlePatterns.range(c2) > 0 ? CandlePatterns.body(c2) / CandlePatterns.range(c2) : 0;
            if (c2BodyR <= 0.10) score += 1.5;
            else if (c2BodyR <= 0.20) score += 1.0;
            else score += 0.3;
            // (3) c3 양봉 회복: c1 고가 대비 얼마나 위에서 마감
            double c1High = Math.max(c1.opening_price, c1.trade_price);
            double recoveryPct = c1High > 0 ? (c3.trade_price - c1High) / c1High * 100 : 0;
            if (recoveryPct >= 0.5) score += 2.0;
            else if (recoveryPct >= 0.1) score += 1.0;
            else score += 0.3;
            // (4) c3 양봉 몸통 강도
            double c3BodyR = CandlePatterns.range(c3) > 0 ? CandlePatterns.body(c3) / CandlePatterns.range(c3) : 0;
            if (c3BodyR >= 0.7) score += 1.0;
            else score += 0.3;
            return Signal.of(SignalAction.BUY, type(), "Morning star reversal", Math.min(10.0, score));
        }
        return Signal.none();
    }
}
