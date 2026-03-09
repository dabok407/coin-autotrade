package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.util.List;

/**
 * [4] 인사이드바 돌파 + 거래량 필터
 *
 * 조건(단순화):
 * - mother = 전전 캔들, inside = 직전 캔들
 * - inside가 mother 범위 내
 * - 현재 캔들 종가가 mother 고가 상향 돌파
 * - 현재 캔들의 거래량이 mother 거래량보다 크고, 최근 20개 평균 거래량 이상
 */
public class InsideBarBreakoutStrategy implements TradingStrategy {

    @Override public StrategyType type() { return StrategyType.INSIDE_BAR_BREAKOUT; }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        int emaPeriod = ctx.getEmaTrendPeriod(type());
        int minCandles = Math.max(10, emaPeriod > 0 ? emaPeriod + 5 : 0);
        if (ctx.candles == null || ctx.candles.size() < minCandles) return Signal.none();
        if (ctx.position != null && ctx.position.getQty() != null
                && ctx.position.getQty().compareTo(java.math.BigDecimal.ZERO) > 0) return Signal.none(); // 진입 전용

        List<UpbitCandle> cs = ctx.candles;
        UpbitCandle mother = cs.get(cs.size()-3);
        UpbitCandle inside = cs.get(cs.size()-2);
        UpbitCandle breakout = cs.get(cs.size()-1);

        if (!CandlePatterns.isInsideBar(mother, inside)) return Signal.none();
        if (breakout.trade_price <= mother.high_price) return Signal.none();

        // 트렌드 필터: 종가 > EMA (설정 가능, 0이면 비활성)
        if (emaPeriod > 0) {
            double emaVal = Indicators.ema(cs, emaPeriod);
            if (!Double.isNaN(emaVal) && breakout.trade_price <= emaVal) return Signal.none();
        }

        double volAvg = Indicators.smaVolume(cs, 20);
        if (Double.isNaN(volAvg)) return Signal.none();

        if (breakout.candle_acc_trade_volume > mother.candle_acc_trade_volume &&
                breakout.candle_acc_trade_volume >= volAvg) {
            // === Confidence Score ===
            double score = 4.0;
            // (1) 돌파 강도: mother 고가 대비 초과 비율
            double breakoutPct = (breakout.trade_price - mother.high_price) / mother.high_price * 100;
            if (breakoutPct >= 1.0) score += 2.0;
            else if (breakoutPct >= 0.5) score += 1.5;
            else if (breakoutPct >= 0.2) score += 0.8;
            // (2) 거래량 비율: 평균 대비
            double volRatio = volAvg > 0 ? breakout.candle_acc_trade_volume / volAvg : 1.0;
            if (volRatio >= 2.0) score += 2.0;
            else if (volRatio >= 1.5) score += 1.5;
            else score += 0.5;
            // (3) 인사이드바 압축도: inside range / mother range (작을수록 압축 강함)
            double compression = CandlePatterns.range(inside) / CandlePatterns.range(mother);
            if (compression <= 0.3) score += 1.5;
            else if (compression <= 0.5) score += 1.0;
            else score += 0.3;
            return Signal.of(SignalAction.BUY, type(), "Inside bar breakout + volume filter", Math.min(10.0, score));
        }

        return Signal.none();
    }
}
