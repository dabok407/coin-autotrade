package com.example.upbit.strategy;

import com.example.upbit.config.StrategyProperties;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.db.PositionEntity;

/**
 * 기존 전략: N연속 하락 후 매수 -> 추가 하락 시 추매 -> 평균단가 대비 takeProfitRate(기본 1%) 도달 시 매도.
 *
 * NOTE:
 * - 실제 매수/매도 실행(주문, 슬리피지, 수수료, 잔고, 중복주문 방지 등)은 TradingBotService(엔진)에서 담당.
 * - 이 클래스는 "신호"만 반환.
 */
public class ConsecutiveDownReboundStrategy implements TradingStrategy {

    private final StrategyProperties cfg;

    public ConsecutiveDownReboundStrategy(StrategyProperties cfg) {
        this.cfg = cfg;
    }

    @Override
    public StrategyType type() {
        return StrategyType.CONSECUTIVE_DOWN_REBOUND;
    }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        if (ctx.candles == null || ctx.candles.size() < 2) return Signal.none();

        UpbitCandle prev = ctx.candles.get(ctx.candles.size() - 2);
        UpbitCandle last = ctx.candles.get(ctx.candles.size() - 1);

        double prevClose = prev.trade_price;
        double close = last.trade_price;

        PositionEntity pos = ctx.position;
        boolean open = (pos != null && pos.getQty() != null
                && pos.getQty().compareTo(java.math.BigDecimal.ZERO) > 0);

        // 1) 포지션 있으면 익절 조건이면 SELL 신호
        if (open) {
            double target = (pos.getAvgPrice() == null ? 0.0 : pos.getAvgPrice().doubleValue())
                    * (1.0 + cfg.getTakeProfitRate());
            if (close >= target) {
                return Signal.of(SignalAction.SELL, type(), "TP reached: close>=avg*(1+tp)");
            }
        }

        // 2) 포지션 없으면 N연속하락 이상이면 BUY 신호
        if (!open && ctx.downStreak >= cfg.getConsecutiveDown()) {
            // EMA 트렌드 필터: 하락 추세에서 낙하 칼날 잡기 방지
            int emaPeriod = ctx.getEmaTrendPeriod(type());
            if (emaPeriod > 0 && ctx.candles.size() >= emaPeriod) {
                double emaVal = Indicators.ema(ctx.candles, emaPeriod);
                if (!Double.isNaN(emaVal) && close <= emaVal) return Signal.none();
            }
            // === Confidence Score ===
            double score = 4.0;
            int extra = ctx.downStreak - cfg.getConsecutiveDown();
            // 초과 연속 하락: 많을수록 반등 확률 높음
            if (extra >= 3) score += 2.5;
            else if (extra >= 2) score += 2.0;
            else if (extra >= 1) score += 1.5;
            // 마지막 봉이 양봉이면 반등 확인
            if (CandlePatterns.isBullish(last)) score += 2.0;
            // 마지막 봉 몸통 크기
            double bodyRatio = CandlePatterns.range(last) > 0 ? CandlePatterns.body(last) / CandlePatterns.range(last) : 0;
            if (bodyRatio >= 0.6) score += 1.5;
            else if (bodyRatio >= 0.4) score += 0.8;
            return Signal.of(SignalAction.BUY, type(), "DownStreak >= " + cfg.getConsecutiveDown(), Math.min(10.0, score));
        }

        // 3) 포지션 있고, 추가하락(이전 캔들 대비 하락)이며 추매 on이면 ADD_BUY 신호
        if (open && cfg.isAddBuyOnEachExtraDown() && close < prevClose) {
            return Signal.of(SignalAction.ADD_BUY, type(), "Extra down candle -> add buy");
        }

        return Signal.none();
    }
}
