package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.util.List;

/**
 * [7] 적삼병(3연속 양봉) - 상승 전환/상승 지속 신호로 활용(진입)
 *
 * [수정] 스크립트: "거래량이 이동평균선 위에 있으면 진입"
 * 기존: 거래량 체크 없이 3연속 양봉이면 바로 신호
 * 변경: 마지막 양봉(c)의 거래량이 20봉 평균 거래량 이상일 때만 신호
 */
public class ThreeWhiteSoldiersStrategy implements TradingStrategy {
    @Override public StrategyType type() { return StrategyType.THREE_WHITE_SOLDIERS; }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        int emaPeriod = ctx.getEmaTrendPeriod(type());
        int minCandles = Math.max(20, emaPeriod > 0 ? emaPeriod + 5 : 0); // 20봉 볼륨 SMA 필요
        if (ctx.candles == null || ctx.candles.size() < minCandles) return Signal.none();
        if (ctx.position != null && ctx.position.getQty() != null
                && ctx.position.getQty().compareTo(java.math.BigDecimal.ZERO) > 0) return Signal.none();

        List<UpbitCandle> cs = ctx.candles;
        UpbitCandle a = cs.get(cs.size()-3);
        UpbitCandle b = cs.get(cs.size()-2);
        UpbitCandle c = cs.get(cs.size()-1);

        if (!CandlePatterns.isThreeWhiteSoldiers(a, b, c)) return Signal.none();

        // 트렌드 필터: 종가 > EMA (설정 가능, 0이면 비활성)
        if (emaPeriod > 0) {
            double emaVal = Indicators.ema(cs, emaPeriod);
            if (!Double.isNaN(emaVal) && c.trade_price <= emaVal) return Signal.none();
        }

        // 거래량 필터: 마지막 봉 거래량 >= 20봉 평균 거래량
        double volAvg = Indicators.smaVolume(cs, 20);
        if (!Double.isNaN(volAvg) && c.candle_acc_trade_volume < volAvg) {
            return Signal.none();
        }

        // === Confidence Score ===
        double score = 4.0;
        // (1) 상승 균일성: 3봉의 몸통 크기가 균일할수록 좋음
        double bodyA = CandlePatterns.body(a), bodyB = CandlePatterns.body(b), bodyC = CandlePatterns.body(c);
        double avgBody = (bodyA + bodyB + bodyC) / 3.0;
        if (avgBody > 0) {
            double maxDev = Math.max(Math.abs(bodyA - avgBody), Math.max(Math.abs(bodyB - avgBody), Math.abs(bodyC - avgBody)));
            double uniformity = 1.0 - (maxDev / avgBody);
            if (uniformity >= 0.7) score += 1.5;
            else if (uniformity >= 0.4) score += 1.0;
            else score += 0.3;
        }
        // (2) 위꼬리 짧음 (평균)
        double avgUwR = (CandlePatterns.upperWick(a)/CandlePatterns.range(a)
                + CandlePatterns.upperWick(b)/CandlePatterns.range(b)
                + CandlePatterns.upperWick(c)/CandlePatterns.range(c)) / 3.0;
        if (avgUwR <= 0.08) score += 2.0;
        else if (avgUwR <= 0.15) score += 1.5;
        else score += 0.5;
        // (3) 거래량 강도
        if (!Double.isNaN(volAvg) && volAvg > 0) {
            double volRatio = c.candle_acc_trade_volume / volAvg;
            if (volRatio >= 1.8) score += 1.5;
            else if (volRatio >= 1.3) score += 1.0;
            else score += 0.3;
        }

        return Signal.of(SignalAction.BUY, type(), "Three white soldiers + volume confirm", Math.min(10.0, score));
    }
}
