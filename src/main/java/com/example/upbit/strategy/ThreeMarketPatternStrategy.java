package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 쓰리 마켓 패턴 (Three Market Pattern) — 이중 가짜돌파 → 신고가 돌파 매수
 *
 * ═══════════════════════════════════════════════════════════
 *  횡보 구간에서 상/하 가짜돌파가 모두 발생한 뒤 신고가 돌파 시 매수
 * ═══════════════════════════════════════════════════════════
 *
 * ■ 원리:
 *   1) 일정 기간 횡보(박스권)를 형성하여 지지/저항이 확립됨
 *   2) 상방 가짜돌파(false breakout up): 저항 위로 잠깐 돌파 후 되돌아옴
 *      → 매수세(롱) 함정 → 스탑 사냥
 *   3) 하방 가짜돌파(false breakout down): 지지 아래로 잠깐 돌파 후 되돌아옴
 *      → 매도세(숏) 함정 → 스탑 사냥
 *   4) 양방향 스탑 사냥 완료 후 진짜 신고가 돌파 → 폭발적 상승
 *
 * ■ 진입:
 *   • 이중 가짜돌파 완료 확인 + 신고가 돌파 시 BUY
 *   • 거래량 확인 (평균 이상)
 *
 * ■ 청산:
 *   • ATR 기반 SL (진입가 - 2.0*ATR)
 *   • ATR 기반 TP (진입가 + 3.5*ATR)
 *   • 트레일링 스탑 (피크 - 2.5*ATR, 이익 구간에서만)
 *   • 박스권 하단 이탈 시 청산
 */
public class ThreeMarketPatternStrategy implements TradingStrategy {

    // ===== 박스권 감지 =====
    private static final int RANGE_LOOKBACK = 30;       // 박스권 감지 구간 (캔들 수) — 더 짧은 횡보도 감지
    private static final double RANGE_MAX_PCT = 0.18;    // 최대 박스 범위 (18% — 암호화폐 변동성 고려)
    private static final double RANGE_MIN_PCT = 0.008;   // 최소 박스 범위 (0.8% — 좁은 박스도 허용)

    // ===== 가짜돌파 감지 =====
    private static final double FALSE_BREAKOUT_MIN = 0.001;  // 지지/저항 이탈 최소 비율 (0.1%)
    private static final double FALSE_BREAKOUT_MAX = 0.06;   // 가짜돌파 최대 범위 (6% — 암호화폐에서는 꼬리가 길 수 있음)
    private static final int FALSE_BO_SCAN_WINDOW = 30;      // 가짜돌파 스캔 범위

    // ===== 신고가 돌파 =====
    private static final double NEW_HIGH_MIN_PCT = 0.001;    // 최소 신고가 돌파 비율 (0.1%)

    // ===== ATR 청산 =====
    private static final int ATR_PERIOD = 14;
    private static final double SL_ATR_MULT = 2.0;
    private static final double TP_ATR_MULT = 3.5;
    private static final double TRAIL_ATR_MULT = 2.5;

    // ===== 거래량 =====
    private static final int VOLUME_AVG_PERIOD = 20;
    private static final double VOLUME_THRESHOLD = 0.5;   // 평균의 50% 이상이면 OK

    // ===== 안전 =====
    private static final int MIN_CANDLES = 50;

    @Override
    public StrategyType type() {
        return StrategyType.THREE_MARKET_PATTERN;
    }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        List<UpbitCandle> candles = ctx.candles;
        if (candles == null || candles.size() < MIN_CANDLES) return Signal.none();

        int n = candles.size();
        UpbitCandle last = candles.get(n - 1);
        double close = last.trade_price;

        boolean hasPosition = ctx.position != null
                && ctx.position.getQty() != null
                && ctx.position.getQty().compareTo(BigDecimal.ZERO) > 0;

        double atr = Indicators.atr(candles, ATR_PERIOD);

        if (hasPosition) {
            return evaluateExit(ctx, candles, last, close, atr);
        }

        return evaluateEntry(candles, last, close, atr);
    }

    private Signal evaluateEntry(List<UpbitCandle> candles, UpbitCandle last, double close, double atr) {
        int n = candles.size();

        // ── STEP 1: 박스권(횡보) 감지 ──
        int rangeEnd = n - 2;  // 현재 봉 직전까지
        int rangeStart = Math.max(0, rangeEnd - RANGE_LOOKBACK);

        double rangeHigh = Double.MIN_VALUE;
        double rangeLow = Double.MAX_VALUE;
        for (int i = rangeStart; i <= rangeEnd; i++) {
            if (candles.get(i).high_price > rangeHigh) rangeHigh = candles.get(i).high_price;
            if (candles.get(i).low_price < rangeLow) rangeLow = candles.get(i).low_price;
        }

        double rangePct = (rangeHigh - rangeLow) / rangeLow;
        if (rangePct > RANGE_MAX_PCT || rangePct < RANGE_MIN_PCT) return Signal.none();

        // ── STEP 2: 이중 가짜돌파 감지 ──
        boolean falseBreakoutUp = false;
        boolean falseBreakoutDown = false;
        int scanStart = Math.max(rangeStart, rangeEnd - FALSE_BO_SCAN_WINDOW);

        // 박스 상단/하단 기준선 (내부 봉들의 중간 75% 구간의 고/저)
        double innerHigh = percentileHigh(candles, rangeStart, rangeEnd, 0.90);
        double innerLow = percentileLow(candles, rangeStart, rangeEnd, 0.10);

        for (int i = scanStart; i <= rangeEnd; i++) {
            UpbitCandle c = candles.get(i);

            // 상방 가짜돌파: 고가가 상단 위 → 종가가 상단 아래로 복귀
            if (c.high_price > innerHigh * (1 + FALSE_BREAKOUT_MIN)
                    && c.high_price < innerHigh * (1 + FALSE_BREAKOUT_MAX)
                    && c.trade_price < innerHigh) {
                falseBreakoutUp = true;
            }

            // 하방 가짜돌파: 저가가 하단 아래 → 종가가 하단 위로 복귀
            if (c.low_price < innerLow * (1 - FALSE_BREAKOUT_MIN)
                    && c.low_price > innerLow * (1 - FALSE_BREAKOUT_MAX)
                    && c.trade_price > innerLow) {
                falseBreakoutDown = true;
            }
        }

        // 적어도 하나의 가짜돌파 필요 (둘 다 있으면 보너스 점수)
        if (!falseBreakoutUp && !falseBreakoutDown) return Signal.none();

        // ── STEP 3: 신고가 돌파 확인 ──
        if (close <= rangeHigh * (1 + NEW_HIGH_MIN_PCT)) return Signal.none();

        // 현재 봉이 양봉인지 확인
        if (!CandlePatterns.isBullish(last)) return Signal.none();

        // ── STEP 4: 거래량 확인 ──
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double curVol = last.candle_acc_trade_volume;
        if (avgVol > 0 && curVol < avgVol * VOLUME_THRESHOLD) return Signal.none();

        // ── STEP 5: Confidence 산출 ──
        double score = 4.0; // 가짜돌파 + 신고가 → 기본 4점
        if (falseBreakoutUp && falseBreakoutDown) score += 2.0; // 이중 가짜돌파 보너스

        // (1) 돌파 강도
        double breakoutPct = (close - rangeHigh) / rangeHigh * 100;
        if (breakoutPct >= 1.5) score += 1.5;
        else if (breakoutPct >= 0.8) score += 1.0;
        else score += 0.3;

        // (2) 거래량 비율
        double volRatio = avgVol > 0 ? curVol / avgVol : 1.0;
        if (volRatio >= 2.0) score += 1.5;
        else if (volRatio >= 1.5) score += 1.0;
        else score += 0.3;

        // (3) 양봉 강도
        double bodyRatio = CandlePatterns.body(last) / CandlePatterns.range(last);
        if (bodyRatio >= 0.7) score += 1.0;
        else if (bodyRatio >= 0.5) score += 0.5;

        String reason = String.format(Locale.ROOT,
                "3MKT_BUY rangeHigh=%.2f rangeLow=%.2f false_BO_up=%b false_BO_down=%b close=%.2f breakout=%.2f%% vol=%.1fx",
                rangeHigh, rangeLow, true, true, close, breakoutPct, volRatio);
        return Signal.of(SignalAction.BUY, type(), reason, Math.min(10.0, score));
    }

    private Signal evaluateExit(StrategyContext ctx, List<UpbitCandle> candles,
                                UpbitCandle last, double close, double atr) {
        double avgPrice = ctx.position.getAvgPrice().doubleValue();
        if (avgPrice <= 0) return Signal.none();

        double hardSL = avgPrice - SL_ATR_MULT * atr;
        double hardTP = avgPrice + TP_ATR_MULT * atr;

        double peakHigh = Indicators.peakHighSinceEntry(candles, avgPrice);
        double trailStop = peakHigh - TRAIL_ATR_MULT * atr;

        double effectiveSL = (close > avgPrice && trailStop > hardSL) ? trailStop : hardSL;

        // 1. SL 히트
        if (last.low_price <= effectiveSL) {
            double pnl = ((close - avgPrice) / avgPrice) * 100.0;
            boolean isTrail = trailStop > hardSL;
            String reason = String.format(Locale.ROOT,
                    "3MKT_%s avg=%.2f peak=%.2f atr=%.4f sl=%.2f pnl=%.2f%%",
                    isTrail ? "TRAIL_STOP" : "HARD_STOP", avgPrice, peakHigh, atr, effectiveSL, pnl);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 2. TP 히트
        if (last.high_price >= hardTP) {
            String reason = String.format(Locale.ROOT,
                    "3MKT_TP avg=%.2f tp=%.2f pnl=%.2f%%",
                    avgPrice, hardTP, ((hardTP - avgPrice) / avgPrice) * 100.0);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        return Signal.none();
    }

    /** 캔들 고가의 percentile (예: 85% = 상위 15% 제외한 최고가) */
    private double percentileHigh(List<UpbitCandle> candles, int from, int to, double pct) {
        int count = to - from + 1;
        double[] highs = new double[count];
        for (int i = 0; i < count; i++) highs[i] = candles.get(from + i).high_price;
        java.util.Arrays.sort(highs);
        int idx = Math.min((int) (count * pct), count - 1);
        return highs[idx];
    }

    /** 캔들 저가의 percentile (예: 15% = 하위 15% 제외한 최저가) */
    private double percentileLow(List<UpbitCandle> candles, int from, int to, double pct) {
        int count = to - from + 1;
        double[] lows = new double[count];
        for (int i = 0; i < count; i++) lows[i] = candles.get(from + i).low_price;
        java.util.Arrays.sort(lows);
        int idx = Math.max((int) (count * pct), 0);
        return lows[idx];
    }
}
