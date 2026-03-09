package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.util.List;

/**
 * [7] 흑삼병(3연속 음봉) - 하락 반전/하락 지속 신호
 * - 현물 롱 only => 보유중이면 청산
 *
 * [수정] 적삼병(ThreeWhiteSoldiers)과 대칭으로 거래량 필터 추가
 * - 마지막 음봉(c)의 거래량이 20봉 평균 거래량 이상일 때만 청산 신호
 * - 거래량 없는 하락(저유동성)은 거짓 신호일 가능성 높음
 */
public class ThreeBlackCrowsSellStrategy implements TradingStrategy {
    @Override public StrategyType type() { return StrategyType.THREE_BLACK_CROWS_SELL; }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        if (ctx.candles == null || ctx.candles.size() < 20) return Signal.none(); // 20봉 볼륨 SMA 필요
        if (ctx.position == null || ctx.position.getQty() == null
                || ctx.position.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) return Signal.none();

        List<UpbitCandle> cs = ctx.candles;
        UpbitCandle a = cs.get(cs.size()-3);
        UpbitCandle b = cs.get(cs.size()-2);
        UpbitCandle c = cs.get(cs.size()-1);

        if (!CandlePatterns.isThreeBlackCrows(a, b, c)) return Signal.none();

        // 거래량 필터: 마지막 봉 거래량 >= 20봉 평균 거래량
        double volAvg = Indicators.smaVolume(cs, 20);
        if (!Double.isNaN(volAvg) && c.candle_acc_trade_volume < volAvg) {
            return Signal.none();
        }

        // === Confidence Score ===
        double score = 4.0;
        // (1) 하락 균일성: 3봉의 몸통 크기가 균일할수록 좋음
        double bodyA = CandlePatterns.body(a), bodyB = CandlePatterns.body(b), bodyC = CandlePatterns.body(c);
        double avgBody = (bodyA + bodyB + bodyC) / 3.0;
        if (avgBody > 0) {
            double maxDev = Math.max(Math.abs(bodyA-avgBody), Math.max(Math.abs(bodyB-avgBody), Math.abs(bodyC-avgBody)));
            double uniformity = 1.0 - (maxDev / avgBody);
            if (uniformity >= 0.7) score += 1.5;
            else if (uniformity >= 0.4) score += 1.0;
            else score += 0.3;
        }
        // (2) 아래꼬리 짧음 (평균)
        double avgLwR = (CandlePatterns.lowerWick(a)/CandlePatterns.range(a)
                + CandlePatterns.lowerWick(b)/CandlePatterns.range(b)
                + CandlePatterns.lowerWick(c)/CandlePatterns.range(c)) / 3.0;
        if (avgLwR <= 0.08) score += 2.0;
        else if (avgLwR <= 0.15) score += 1.5;
        else score += 0.5;
        // (3) 총 하락률
        double dropPct = a.trade_price > 0 ? (a.trade_price - c.trade_price) / a.trade_price * 100 : 0;
        if (dropPct >= 2.0) score += 2.0;
        else if (dropPct >= 1.0) score += 1.0;
        else score += 0.3;
        // (4) 거래량 강도
        if (!Double.isNaN(volAvg) && volAvg > 0) {
            double volRatio = c.candle_acc_trade_volume / volAvg;
            if (volRatio >= 1.8) score += 1.5;
            else if (volRatio >= 1.3) score += 1.0;
            else score += 0.3;
        }
        return Signal.of(SignalAction.SELL, type(), "Three black crows + volume confirm -> exit", Math.min(10.0, score));
    }
}
