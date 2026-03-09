package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

/**
 * [3] 강세 핀바(오더블록/지지영역) 단순 진입
 *
 * 스크립트에서는 '오더블록 지지 재확인 + 핀바 재등장'을 말하지만,
 * 자동화 초안에서는 다음처럼 단순화합니다.
 * - 최근 캔들이 강세 핀바이고, 직전 N개 대비 저점이 상대적으로 낮은 구간(국지 저점)일 때 BUY.
 */
public class BullishPinbarOrderblockStrategy implements TradingStrategy {

    @Override public StrategyType type() { return StrategyType.BULLISH_PINBAR_ORDERBLOCK; }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        int emaPeriod = ctx.getEmaTrendPeriod(type());
        int minCandles = Math.max(10, emaPeriod > 0 ? emaPeriod + 5 : 0);
        if (ctx.candles == null || ctx.candles.size() < minCandles) return Signal.none();
        if (ctx.position != null && ctx.position.getQty() != null
                && ctx.position.getQty().compareTo(java.math.BigDecimal.ZERO) > 0) return Signal.none(); // 진입 전용

        UpbitCandle last = ctx.candles.get(ctx.candles.size()-1);
        if (!CandlePatterns.isBullishPinbar(last)) return Signal.none();

        // 트렌드 필터: 종가 > EMA (설정 가능, 0이면 비활성)
        if (emaPeriod > 0) {
            double emaVal = Indicators.ema(ctx.candles, emaPeriod);
            if (!Double.isNaN(emaVal) && last.trade_price <= emaVal) return Signal.none();
        }

        // 최근 10개 중 최저가 근처인지 확인(지지/방어 흔적)
        double minLow = Double.MAX_VALUE;
        for (int i = ctx.candles.size()-10; i < ctx.candles.size(); i++) {
            minLow = Math.min(minLow, ctx.candles.get(i).low_price);
        }

        if (last.low_price <= minLow * 1.002) { // 최저가 근처(0.2% 여유)
            // === Confidence Score ===
            double score = 4.0;
            // (1) 아래꼬리 비율: 길수록 강한 반등 의지
            double lwRatio = CandlePatterns.lowerWick(last) / CandlePatterns.range(last);
            if (lwRatio >= 0.75) score += 2.0;
            else if (lwRatio >= 0.65) score += 1.5;
            else score += 0.5;
            // (2) 몸통 작음: 작을수록 전형적인 핀바
            double bodyRatio = CandlePatterns.body(last) / CandlePatterns.range(last);
            if (bodyRatio <= 0.15) score += 1.5;
            else if (bodyRatio <= 0.25) score += 1.0;
            else score += 0.3;
            // (3) 지지 정확도: 정확히 최저점일수록 좋음
            double supportDiff = (last.low_price - minLow) / minLow * 100;
            if (supportDiff <= 0.05) score += 2.0;  // 거의 정확한 지지
            else if (supportDiff <= 0.1) score += 1.5;
            else score += 0.5;
            return Signal.of(SignalAction.BUY, type(), "Bullish pinbar near local support", Math.min(10.0, score));
        }

        return Signal.none();
    }
}
