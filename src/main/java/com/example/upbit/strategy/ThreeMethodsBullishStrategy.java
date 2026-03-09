package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.util.List;

/**
 * [5] 상승 삼법형(Three Methods) + EMA50 트렌드 필터 + ATR 모멘텀 검증
 *
 * 패턴(단순화):
 * - 1번: 장대 양봉(모멘텀, ATR 대비 충분한 크기)
 * - 2~k: 작은 캔들(2~4개)로, 모두 1번 캔들의 고가/저가 범위 안
 * - 마지막: 장대 양봉(모멘텀) + 종가가 1번 종가보다 높음
 * - 추가 필터: 현재 종가 > EMA50 (상승 추세) + EMA20
 */
public class ThreeMethodsBullishStrategy implements TradingStrategy {

    private static final double MIN_MOMENTUM_ATR_MULT = 1.0; // 모멘텀 최소: body >= 1.0 * ATR

    @Override public StrategyType type() { return StrategyType.THREE_METHODS_BULLISH; }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        int emaPeriod = ctx.getEmaTrendPeriod(type());
        int minCandles = Math.max(10, emaPeriod > 0 ? emaPeriod + 5 : 0);
        if (ctx.candles == null || ctx.candles.size() < minCandles) return Signal.none();
        if (ctx.position != null && ctx.position.getQty() != null
                && ctx.position.getQty().compareTo(java.math.BigDecimal.ZERO) > 0) return Signal.none();

        List<UpbitCandle> cs = ctx.candles;

        // 트렌드 필터: 종가 > EMA (설정 가능, 0이면 비활성)
        UpbitCandle lastCandle = cs.get(cs.size() - 1);
        if (emaPeriod > 0) {
            double emaVal = Indicators.ema(cs, emaPeriod);
            if (!Double.isNaN(emaVal) && lastCandle.trade_price <= emaVal) return Signal.none();
        }

        double atr = Indicators.atr(cs, 14);

        // 마지막 6개를 대상으로 1 + (2~4) + 1 구조 탐색
        for (int pull = 3; pull <= 5; pull++) { // 2~4개 조정봉 => total 1+pull+1 => 4~6
            int total = pull + 2;
            if (cs.size() < total) continue;

            int base = cs.size() - total;
            UpbitCandle first = cs.get(base);
            UpbitCandle last = cs.get(cs.size()-1);

            if (!CandlePatterns.isBullish(first) || !CandlePatterns.isMomentum(first, atr, MIN_MOMENTUM_ATR_MULT)) continue;
            if (!CandlePatterns.isBullish(last) || !CandlePatterns.isMomentum(last, atr, MIN_MOMENTUM_ATR_MULT)) continue;
            if (last.trade_price <= first.trade_price) continue;

            boolean insideAll = true;
            for (int i = base+1; i < cs.size()-1; i++) {
                UpbitCandle mid = cs.get(i);
                if (mid.high_price > first.high_price || mid.low_price < first.low_price) {
                    insideAll = false; break;
                }
                // mid는 '작은 몸통' 쪽이 더 좋지만, 과도 필터는 제외
            }
            if (!insideAll) continue;

            double ema20 = Indicators.ema(cs, 20);
            if (!Double.isNaN(ema20) && last.trade_price <= ema20) continue;

            // === Confidence Score ===
            double score = 4.0;
            // (1) 첫 봉 모멘텀 강도
            double firstBodyR = CandlePatterns.body(first) / CandlePatterns.range(first);
            if (firstBodyR >= 0.85) score += 1.5;
            else score += 0.5;
            // (2) 마지막 봉 돌파 강도: first 종가 대비 초과율
            double breakPct = first.trade_price > 0 ? (last.trade_price - first.trade_price) / first.trade_price * 100 : 0;
            if (breakPct >= 1.0) score += 2.0;
            else if (breakPct >= 0.3) score += 1.5;
            else score += 0.5;
            // (3) 조정 깊이: 중간봉들이 first 범위 내에서 얕을수록 건강
            double midLow = Double.MAX_VALUE;
            for (int i = base+1; i < cs.size()-1; i++) midLow = Math.min(midLow, cs.get(i).low_price);
            double corrPct = first.low_price > 0 ? (midLow - first.low_price) / (first.high_price - first.low_price) : 0.5;
            if (corrPct >= 0.5) score += 1.5;  // 상위 50%에서 조정 → 강한 지지
            else score += 0.5;
            return Signal.of(SignalAction.BUY, type(), "Three methods bullish + EMA50/ATR filter", Math.min(10.0, score));
        }

        return Signal.none();
    }
}
