package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.util.List;

/**
 * [2] 모멘텀 캔들 + 페어밸류갭(FVG) 되돌림 매수(스크립트 기반 단순화)
 *
 * 조건(단순화):
 * 1) 최근 N개 중 (pre, mom, post) 3연속 캔들에서 mom이 상승 모멘텀(장대양봉)
 * 2) FVG = [pre.high, post.low] (post.low > pre.high 인 경우만 gap으로 인정)
 * 3) 이후 현재 캔들이 FVG 구간까지 되돌림(저가가 zone 상단 이하) + 양봉 마감 시 BUY
 *
 * NOTE: FVG/구조 인식은 다양한 변형이 있어, 초기 버전은 보수적으로 설계했습니다.
 */
public class MomentumFvgPullbackStrategy implements TradingStrategy {

    @Override public StrategyType type() { return StrategyType.MOMENTUM_FVG_PULLBACK; }

    private static final double MIN_MOMENTUM_ATR_MULT = 1.5; // body ≥ ATR × 1.5 이상이어야 모멘텀 캔들
    private static final int ATR_LEN = 14;

    @Override
    public Signal evaluate(StrategyContext ctx) {
        int emaPeriod = ctx.getEmaTrendPeriod(type());
        int minCandles = Math.max(10, emaPeriod > 0 ? emaPeriod + 5 : 0);
        if (ctx.candles == null || ctx.candles.size() < minCandles) return Signal.none();
        if (ctx.position != null && ctx.position.getQty() != null
                && ctx.position.getQty().compareTo(java.math.BigDecimal.ZERO) > 0) return Signal.none(); // 진입 전용

        List<UpbitCandle> cs = ctx.candles;

        // 트렌드 필터: 종가 > EMA (설정 가능, 0이면 비활성)
        UpbitCandle lastCandle = cs.get(cs.size() - 1);
        if (emaPeriod > 0) {
            double emaVal = Indicators.ema(cs, emaPeriod);
            if (!Double.isNaN(emaVal) && lastCandle.trade_price <= emaVal) return Signal.none();
        }

        // ATR 계산 (모멘텀 캔들 크기 검증용)
        double atr = Indicators.atr(cs, ATR_LEN);
        if (atr <= 0) return Signal.none();

        // 최근 50개 내에서 가장 최신의 FVG 후보를 찾는다.
        int start = Math.max(0, cs.size() - 60);
        double zoneLow = Double.NaN;
        double zoneHigh = Double.NaN;
        int foundAt = -1;
        double momStrength = 0; // 모멘텀 캔들 ATR 대비 강도

        for (int i = cs.size() - 3; i >= start; i--) {
            UpbitCandle pre = cs.get(i);
            UpbitCandle mom = cs.get(i+1);
            UpbitCandle post = cs.get(i+2);

            if (!CandlePatterns.isBullish(mom)) continue;
            // ATR 기반 모멘텀 검증: body ≥ ATR × 1.5 이상이어야 장대양봉
            if (!CandlePatterns.isMomentum(mom, atr, MIN_MOMENTUM_ATR_MULT)) continue;

            double zl = pre.high_price;
            double zh = post.low_price;
            if (zh <= zl) continue; // gap 없음

            zoneLow = zl;
            zoneHigh = zh;
            foundAt = i;
            momStrength = CandlePatterns.momentumStrength(mom, atr);
            break;
        }

        if (foundAt < 0) return Signal.none();

        UpbitCandle last = cs.get(cs.size()-1);

        // 되돌림 조건: 저가가 zoneHigh 이하로 들어왔고, 양봉 마감
        if (last.low_price <= zoneHigh && CandlePatterns.isBullish(last)) {
            // === Confidence Score 계산 ===
            double score = 4.0; // 기본: 패턴 감지됨

            // (1) 모멘텀 캔들 강도 (ATR 대비)
            if (momStrength >= 3.0) score += 2.0;       // ATR × 3 이상: 매우 강한 장대양봉
            else if (momStrength >= 2.0) score += 1.5;   // ATR × 2 이상
            else score += 0.8;                            // ATR × 1.5 이상 (최소 기준)

            // (2) FVG 갭 크기 (ATR 대비)
            double gapAtrRatio = (zoneHigh - zoneLow) / atr;
            if (gapAtrRatio >= 1.0) score += 1.5;        // ATR 이상 갭
            else if (gapAtrRatio >= 0.5) score += 1.0;
            else score += 0.3;

            // (3) 되돌림 정확도: FVG 구간 안쪽으로 깊이 들어올수록 좋음
            double zoneSize = zoneHigh - zoneLow;
            if (zoneSize > 0) {
                double penetration = (zoneHigh - last.low_price) / zoneSize;
                if (penetration >= 0.5) score += 1.5;      // 구간 중간 이상 진입
                else if (penetration >= 0.2) score += 0.8;
                else score += 0.3;                           // 살짝 터치
            }

            // (4) FVG 신선도: 최근에 형성된 FVG일수록 유효
            int candlesSinceFvg = cs.size() - 1 - foundAt;
            if (candlesSinceFvg <= 5) score += 1.0;
            else if (candlesSinceFvg <= 15) score += 0.5;

            return Signal.of(SignalAction.BUY, type(),
                    String.format(java.util.Locale.US,
                            "FVG pullback buy (zone %.2f~%.2f) momATR=%.1fx gapATR=%.2fx",
                            zoneLow, zoneHigh, momStrength, gapAtrRatio),
                    Math.min(10.0, score));
        }

        return Signal.none();
    }

    private static String round(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
