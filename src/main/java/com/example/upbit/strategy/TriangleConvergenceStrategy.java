package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 삼각수렴 돌파 전략 (Triangle Convergence Breakout)
 *
 * ═══════════════════════════════════════════════════════════
 *  삼각형 수렴 패턴 형성 후 거래량 동반 상방 돌파 시 매수
 * ═══════════════════════════════════════════════════════════
 *
 * ■ 원리:
 *   고점은 점점 낮아지고(하강 추세선), 저점은 점점 높아지는(상승 추세선)
 *   수렴 구간이 형성 → 에너지 압축 → 폭발적 돌파
 *
 * ■ 진입 3대 조건 (모두 충족):
 *   ① 방향: 기존 추세 방향 (상승 추세 → 상방 돌파)
 *   ② 캔들: 장대 양봉이 상단 추세선 위에서 마감
 *   ③ 거래량: 평균의 2배 이상
 *
 * ■ 골든 타임: 삼각형 길이의 2/3 이후 ~ 끝점 전
 *
 * ■ 청산:
 *   • 손절: 돌파 캔들 저가 기준 (ATR 보정)
 *   • 익절: 돌파가 + 삼각형 밑변 높이
 *   • 트레일링: 피크 - 2*ATR (이익 구간)
 */
public class TriangleConvergenceStrategy implements TradingStrategy {

    // ===== 삼각형 감지 =====
    private static final int TRI_MIN_SWINGS = 4;          // 최소 스윙 수 (고점2 + 저점2)
    private static final int TRI_LOOKBACK = 50;            // 삼각형 패턴 탐색 범위
    private static final int SWING_WINDOW = 2;             // 스윙 포인트 감지 윈도우 (좌우 N봉) — 더 민감하게
    private static final double CONVERGENCE_MIN_RATIO = 0.15; // 수렴 최소 비율 (거의 만나는 지점도 허용)
    private static final double CONVERGENCE_MAX_RATIO = 0.92; // 수렴 최대 비율 (약간의 수렴만으로도 OK)

    // ===== 돌파 조건 =====
    private static final double VOLUME_MULT = 1.0;         // 평균 대비 거래량 배수 (평균 이상이면 OK)
    private static final int VOLUME_AVG_PERIOD = 20;
    private static final double BODY_MIN_RATIO = 0.40;     // 양봉 최소 몸통 비율 (관대하게)

    // ===== ATR 기반 청산 =====
    private static final int ATR_PERIOD = 14;
    private static final double SL_ATR_MULT = 1.5;         // SL: 돌파 캔들 저가 - 0.5*ATR (타이트)
    private static final double TRAIL_ATR_MULT = 2.0;      // 트레일링: peak - 2*ATR

    // ===== 추세 판단 =====
    private static final int TREND_EMA_FAST = 20;
    private static final int TREND_EMA_SLOW = 50;

    // ===== 안전 =====
    private static final int MIN_CANDLES = 40;
    private static final double MIN_ATR_PCT = 0.001;

    @Override
    public StrategyType type() {
        return StrategyType.TRIANGLE_CONVERGENCE;
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

        // ── STEP 1: 스윙 포인트 찾기 ──
        int scanEnd = n - 2; // 현재 봉 제외
        int scanStart = Math.max(0, scanEnd - TRI_LOOKBACK);

        // 스윙 고점/저점 수집
        int[] swingHighIdx = new int[20];
        int[] swingLowIdx = new int[20];
        int shCount = 0, slCount = 0;

        for (int i = scanStart + SWING_WINDOW; i <= scanEnd - SWING_WINDOW && (shCount < 20 || slCount < 20); i++) {
            boolean isHigh = true, isLow = true;
            double hi = candles.get(i).high_price;
            double lo = candles.get(i).low_price;

            for (int j = 1; j <= SWING_WINDOW; j++) {
                if (candles.get(i - j).high_price >= hi || candles.get(i + j).high_price >= hi) isHigh = false;
                if (candles.get(i - j).low_price <= lo || candles.get(i + j).low_price <= lo) isLow = false;
            }

            if (isHigh && shCount < 20) swingHighIdx[shCount++] = i;
            if (isLow && slCount < 20) swingLowIdx[slCount++] = i;
        }

        if (shCount < 2 || slCount < 2) return Signal.none();

        // ── STEP 2: 수렴 패턴 확인 (고점 하강 + 저점 상승) ──
        // 최근 스윙 고점의 과반수가 하강하는지 (관대하게)
        int descendingCount = 0;
        for (int i = 1; i < shCount; i++) {
            if (candles.get(swingHighIdx[i]).high_price < candles.get(swingHighIdx[i - 1]).high_price) {
                descendingCount++;
            }
        }
        boolean highsDescending = descendingCount > 0; // 하나 이상 하강

        // 최근 스윙 저점의 과반수가 상승하는지 (관대하게)
        int ascendingCount = 0;
        for (int i = 1; i < slCount; i++) {
            if (candles.get(swingLowIdx[i]).low_price > candles.get(swingLowIdx[i - 1]).low_price) {
                ascendingCount++;
            }
        }
        boolean lowsAscending = ascendingCount > 0; // 하나 이상 상승

        if (!highsDescending || !lowsAscending) return Signal.none();

        // ── STEP 3: 수렴 비율 확인 ──
        double firstHigh = candles.get(swingHighIdx[0]).high_price;
        double firstLow = candles.get(swingLowIdx[0]).low_price;
        double lastHigh = candles.get(swingHighIdx[shCount - 1]).high_price;
        double lastLow = candles.get(swingLowIdx[slCount - 1]).low_price;

        double initialWidth = firstHigh - firstLow;
        double currentWidth = lastHigh - lastLow;

        if (initialWidth <= 0) return Signal.none();
        double convergenceRatio = currentWidth / initialWidth;

        if (convergenceRatio < CONVERGENCE_MIN_RATIO || convergenceRatio > CONVERGENCE_MAX_RATIO) {
            return Signal.none();
        }

        // ── STEP 4: 골든 타임 확인 (삼각형 길이 2/3 이후) ──
        int triStart = Math.min(swingHighIdx[0], swingLowIdx[0]);
        int triLength = scanEnd - triStart;
        int goldenTimeStart = triStart + (int) (triLength * 0.35); // 35%부터 (관대하게)
        int currentIdx = n - 1;
        if (currentIdx < goldenTimeStart) return Signal.none();

        // ── STEP 5: 상단 추세선 계산 + 돌파 확인 ──
        // 선형 보간으로 현재 시점의 상단 추세선 가격 추정
        double trendlineHigh = interpolate(
                swingHighIdx[0], candles.get(swingHighIdx[0]).high_price,
                swingHighIdx[shCount - 1], candles.get(swingHighIdx[shCount - 1]).high_price,
                currentIdx);

        // 종가가 추세선 위에서 마감
        if (close <= trendlineHigh) return Signal.none();

        // ── STEP 6: 방향 확인 (EMA 점수에 반영, 필터 아님) ──
        double emaFast = Indicators.ema(candles, TREND_EMA_FAST);
        double emaSlow = Indicators.ema(candles, TREND_EMA_SLOW);
        boolean bullishTrend = !Double.isNaN(emaFast) && !Double.isNaN(emaSlow) && emaFast > emaSlow;

        // ── STEP 7: 장대 양봉 확인 ──
        if (!CandlePatterns.isBullish(last)) return Signal.none();
        double bodyRatio = CandlePatterns.body(last) / CandlePatterns.range(last);
        if (bodyRatio < BODY_MIN_RATIO) return Signal.none();

        // ── STEP 8: 거래량 확인 (2배 이상) ──
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double curVol = last.candle_acc_trade_volume;
        if (avgVol > 0 && curVol < avgVol * VOLUME_MULT) return Signal.none();

        // ── STEP 9: ATR 안전 체크 ──
        if (atr / close < MIN_ATR_PCT) return Signal.none();

        // ── STEP 10: 목표가 계산 (삼각형 밑변 높이) ──
        double targetMove = initialWidth; // 삼각형 밑변 높이
        double targetPrice = close + targetMove;

        // ── Confidence 산출 ──
        double score = 5.0; // 삼각수렴 + 돌파 기본 5점

        // (1) 돌파 강도 (추세선 대비)
        double breakoutPct = (close - trendlineHigh) / trendlineHigh * 100;
        if (breakoutPct >= 1.5) score += 1.5;
        else if (breakoutPct >= 0.8) score += 1.0;
        else score += 0.3;

        // (2) 거래량 비율
        double volRatio = avgVol > 0 ? curVol / avgVol : 1.0;
        if (volRatio >= 3.0) score += 1.5;
        else if (volRatio >= 2.0) score += 1.0;
        else score += 0.3;

        // (3) 수렴 깊이 (더 좁을수록 에너지 압축)
        if (convergenceRatio <= 0.4) score += 1.0;
        else if (convergenceRatio <= 0.6) score += 0.5;

        // (4) 스윙 수 (더 많은 스윙 = 더 신뢰할 수 있는 패턴)
        int totalSwings = shCount + slCount;
        if (totalSwings >= 6) score += 1.0;
        else if (totalSwings >= 4) score += 0.5;

        // (5) EMA 추세 방향 보너스
        if (bullishTrend) score += 1.0;

        String reason = String.format(Locale.ROOT,
                "TRI_BUY trendline=%.2f close=%.2f breakout=%.2f%% conv=%.0f%% vol=%.1fx swings=%d target=%.2f",
                trendlineHigh, close, breakoutPct, convergenceRatio * 100, volRatio, totalSwings, targetPrice);
        return Signal.of(SignalAction.BUY, type(), reason, Math.min(10.0, score));
    }

    private Signal evaluateExit(StrategyContext ctx, List<UpbitCandle> candles,
                                UpbitCandle last, double close, double atr) {
        double avgPrice = ctx.position.getAvgPrice().doubleValue();
        if (avgPrice <= 0) return Signal.none();

        // SL: 진입가 - SL_ATR_MULT * ATR (삼각수렴은 타이트한 SL 권장)
        double hardSL = avgPrice - SL_ATR_MULT * atr;

        // TP: 삼각형 밑변 높이만큼 (근사: 3.5*ATR 이용)
        double hardTP = avgPrice + 3.5 * atr;

        // 트레일링 스탑: peak - 2*ATR (이익 구간)
        double peakHigh = Indicators.peakHighSinceEntry(candles, avgPrice);
        double trailStop = peakHigh - TRAIL_ATR_MULT * atr;

        double effectiveSL = (close > avgPrice && trailStop > hardSL) ? trailStop : hardSL;

        // 1. SL 히트
        if (last.low_price <= effectiveSL) {
            double pnl = ((close - avgPrice) / avgPrice) * 100.0;
            boolean isTrail = trailStop > hardSL && close > avgPrice;
            String reason = String.format(Locale.ROOT,
                    "TRI_%s avg=%.2f sl=%.2f peak=%.2f pnl=%.2f%%",
                    isTrail ? "TRAIL_STOP" : "HARD_STOP", avgPrice, effectiveSL, peakHigh, pnl);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 2. TP 히트
        if (last.high_price >= hardTP) {
            String reason = String.format(Locale.ROOT,
                    "TRI_TP avg=%.2f tp=%.2f pnl=%.2f%%",
                    avgPrice, hardTP, ((hardTP - avgPrice) / avgPrice) * 100.0);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 3. 추세 붕괴 (EMA20 < EMA50) — 이익 중 또는 손실 2% 초과 시
        double emaFast = Indicators.ema(candles, TREND_EMA_FAST);
        double emaSlow = Indicators.ema(candles, TREND_EMA_SLOW);
        double pnlPct = ((close - avgPrice) / avgPrice) * 100.0;
        if (!Double.isNaN(emaFast) && !Double.isNaN(emaSlow) && emaFast < emaSlow) {
            if (pnlPct > 0 || pnlPct < -2.0) {
                String reason = String.format(Locale.ROOT,
                        "TRI_TREND_BREAK ema20=%.2f < ema50=%.2f pnl=%.2f%%",
                        emaFast, emaSlow, pnlPct);
                return Signal.of(SignalAction.SELL, type(), reason);
            }
        }

        return Signal.none();
    }

    /** 두 점을 잇는 직선에서 targetIdx 위치의 값을 선형 보간 */
    private double interpolate(int idx1, double val1, int idx2, double val2, int targetIdx) {
        if (idx2 == idx1) return val1;
        double slope = (val2 - val1) / (idx2 - idx1);
        return val1 + slope * (targetIdx - idx1);
    }
}
