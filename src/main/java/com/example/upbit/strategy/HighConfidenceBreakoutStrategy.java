package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

/**
 * [21] 고확신 돌파 전략 v2 (자급자족, 종일 스캐너)
 *
 * ═══════════════════════════════════════════════════════════
 *  6-Factor 스코어링 기반 돌파 진입 (10일 데이터 검증)
 *  Hard SL + Trailing + Session End 청산
 * ═══════════════════════════════════════════════════════════
 *
 * ■ 설계 철학:
 *   5분봉 종일 운용. 10일 50코인 데이터 기반 검증된 6개 팩터로
 *   실효성 높은 진입 판단. 예측력 없는 팩터(MACD/ADX/ATR/EMA50) 제거.
 *
 * ■ Hard Prerequisites:
 *   ① 마지막 캔들 양봉
 *
 * ■ 6-Factor Scoring (max 10.0, 내부 8.0을 ×1.25 환산):
 *   1. Volume Surge (1.875)  — 거래량 폭발
 *   2. Daily Change (1.875)  — 당일 상승률
 *   3. RSI (1.25)            — 모멘텀 상태
 *   4. EMA21 Slope (1.875)   — 추세 방향
 *   5. Body Quality (1.25)   — 캔들 품질
 *   6. New High Breakout (1.875) — 신고가 돌파
 *
 * ■ 청산:
 *   1. Hard SL  2. Trailing Stop  3. Session End
 *   (EMA/MACD/TimeStop 비활성화 가능)
 *
 * ■ 권장: 5분봉, minConfidence 8.1
 */
public class HighConfidenceBreakoutStrategy implements TradingStrategy {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MIN_CANDLES = 60;

    // ===== 지표 기간 =====
    private static final int RSI_PERIOD = 14;
    private static final int ADX_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int VOLUME_AVG_PERIOD = 20;
    private static final int EMA_FAST = 8;
    private static final int EMA_MID = 21;
    private static final int EMA_SLOW_PERIOD = 50;

    // ===== 조정 가능 파라미터 (Builder) =====
    private double slPct = 1.5;
    private double trailAtrMult = 0.8;
    private double trailActivatePct = 0.5;   // 트레일링 활성화 최소 수익률 (%)
    private int gracePeriodCandles = 0;       // 진입 후 보호 기간 (캔들 수, 0=비활성)
    private boolean emaExitEnabled = true;    // EMA Break 청산 활성화
    private boolean macdExitEnabled = true;   // MACD Fade 청산 활성화
    private double minConfidence = 9.4;
    private double volumeSurgeMult = 3.0;
    private double minBodyRatio = 0.60;
    private int sessionEndHour = 8;
    private int sessionEndMin = 0;
    private int timeStopCandles = 12;
    private double timeStopMinPnl = 0.3;

    @Override
    public StrategyType type() {
        return StrategyType.HIGH_CONFIDENCE_BREAKOUT;
    }

    // ===== Builder 세터 =====

    public HighConfidenceBreakoutStrategy withRisk(double slPct, double trailAtrMult) {
        this.slPct = slPct;
        this.trailAtrMult = trailAtrMult;
        return this;
    }

    public HighConfidenceBreakoutStrategy withFilters(double volumeSurgeMult, double minBodyRatio, double minConfidence) {
        this.volumeSurgeMult = volumeSurgeMult;
        this.minBodyRatio = minBodyRatio;
        this.minConfidence = minConfidence;
        return this;
    }

    public HighConfidenceBreakoutStrategy withTiming(int sessionEndHour, int sessionEndMin) {
        this.sessionEndHour = sessionEndHour;
        this.sessionEndMin = sessionEndMin;
        return this;
    }

    public HighConfidenceBreakoutStrategy withTimeStop(int candles, double minPnl) {
        this.timeStopCandles = candles;
        this.timeStopMinPnl = minPnl;
        return this;
    }

    public HighConfidenceBreakoutStrategy withTrailActivate(double pct) {
        this.trailActivatePct = pct;
        return this;
    }

    public HighConfidenceBreakoutStrategy withGracePeriod(int candles) {
        this.gracePeriodCandles = candles;
        return this;
    }

    public HighConfidenceBreakoutStrategy withExitFlags(boolean emaExit, boolean macdExit) {
        this.emaExitEnabled = emaExit;
        this.macdExitEnabled = macdExit;
        return this;
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

        if (hasPosition) {
            return evaluateExit(ctx, candles, last, close);
        }
        return evaluateEntry(candles, last, close, ctx.dailyOpenPrice);
    }

    // ═══════════════════════════════════════
    //  진입 로직 — 6-Factor 스코어링 v2 (10일 데이터 검증)
    // ═══════════════════════════════════════
    private Signal evaluateEntry(List<UpbitCandle> candles, UpbitCandle last, double close) {
        return evaluateEntry(candles, last, close, 0);
    }

    private Signal evaluateEntry(List<UpbitCandle> candles, UpbitCandle last, double close, double dailyOpenPrice) {

        // ── Hard prerequisite: 양봉만 ──
        if (!CandlePatterns.isBullish(last)) return Signal.none("NOT_BULLISH");

        // ── 명확한 하락세 필터: EMA50 기울기가 -0.3% 이상 하락이면 차단 ──
        // (횡보/약한 하락은 허용, 명확한 하락만 차단)
        if (candles.size() >= 60) {
            double ema50now = Indicators.ema(candles, 50);
            List<UpbitCandle> prevCandles = candles.subList(0, candles.size() - 10);
            double ema50prev = Indicators.ema(prevCandles, 50);
            if (ema50now > 0 && ema50prev > 0) {
                double slopePct = (ema50now - ema50prev) / ema50prev * 100;
                if (slopePct < -0.5) {
                    return Signal.none(String.format(Locale.ROOT, "DOWNTREND ema50slope=%.3f%%", slopePct));
                }
            }
        }

        // ── 6-Factor Scoring (내부 max 9.5 → ×1.053 → 10.0 환산) ──
        double rawScore = 0.0;

        // Factor 1 - Volume Surge (max 3.0, Hard Gate: <2x 차단)
        double curVol = last.candle_acc_trade_volume;
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double volRatio = avgVol > 0 ? curVol / avgVol : 0;
        if (volRatio < 2.0) return Signal.none(String.format(Locale.ROOT,
                "VOL_GATE vol=%.1fx < 2.0x", volRatio));
        double f1 = 0;
        if (volRatio >= 10.0) f1 = 3.0;
        else if (volRatio >= 7.0) f1 = 2.2;
        else if (volRatio >= 5.0) f1 = 1.5;
        else if (volRatio >= 3.0) f1 = 0.7;
        else f1 = 0.2; // 2.0~3.0x
        rawScore += f1;

        // Factor 2 - Daily Change: 당일 09:00 시가 대비 변동률 (max 1.5)
        double dailyChange = 0;
        if (dailyOpenPrice > 0) {
            // 외부에서 전달받은 캐시된 시가 사용 (버그 방지)
            dailyChange = (close - dailyOpenPrice) / dailyOpenPrice * 100;
        } else {
            // fallback: 캔들 리스트에서 09:00 시가 찾기
            ZonedDateTime lastKst = toKst(last.candle_date_time_utc);
            if (lastKst != null) {
                int today = lastKst.getDayOfYear();
                for (int i = 0; i < candles.size(); i++) {
                    ZonedDateTime ckst = toKst(candles.get(i).candle_date_time_utc);
                    if (ckst != null && ckst.getDayOfYear() == today
                            && ckst.getHour() == 9 && ckst.getMinute() < 5) {
                        double openPrice = candles.get(i).opening_price;
                        if (openPrice > 0) dailyChange = (close - openPrice) / openPrice * 100;
                        break;
                    }
                }
            }
        }
        if (dailyChange <= 0) return Signal.none(String.format(Locale.ROOT,
                "DAY_GATE day=%+.1f%% <= 0%%", dailyChange));
        double f2 = 0;
        if (dailyChange >= 1.0 && dailyChange < 4.0) f2 = 1.5;
        else if (dailyChange >= 4.0 && dailyChange < 7.0) f2 = 1.2;
        else if (dailyChange >= 0.3 && dailyChange < 1.0) f2 = 1.0;
        else if (dailyChange >= 7.0 && dailyChange < 15.0) f2 = 0.8;
        else if (dailyChange >= 15.0 && dailyChange < 30.0) f2 = 0.5;
        else if (dailyChange > 0 && dailyChange < 0.3) f2 = 0.3;
        else if (dailyChange >= 30.0) f2 = 0.2;
        rawScore += f2;

        // Factor 3 - RSI (max 1.0)
        double rsi = Indicators.rsi(candles, RSI_PERIOD);
        double f3 = 0;
        if (rsi >= 50 && rsi <= 65) f3 = 1.0;
        else if (rsi > 65 && rsi <= 75) f3 = 0.7;
        else if (rsi > 75 && rsi <= 85) f3 = 0.4;
        else if (rsi >= 40 && rsi < 50) f3 = 0.3;
        else if (rsi > 85) f3 = 0.1;
        rawScore += f3;

        // Factor 4 - EMA21 Slope (max 1.5)
        double ema8 = Indicators.ema(candles, EMA_FAST);
        double ema21 = Indicators.ema(candles, EMA_MID);
        int prevTail = candles.size() - 5;
        double ema21prev = prevTail > EMA_MID ? Indicators.ema(candles, EMA_MID, prevTail) : ema21;
        boolean ema21Rising = ema21 > ema21prev;
        boolean priceAboveEma21 = close > ema21;
        boolean ema8AboveEma21 = ema8 > ema21;
        double f4 = 0;
        if (priceAboveEma21 && ema8AboveEma21 && ema21Rising) f4 = 1.5;
        else if (priceAboveEma21 && ema21Rising) f4 = 1.2;
        else if (priceAboveEma21) f4 = 0.5;
        rawScore += f4;

        // Factor 5 - Body Quality (max 1.0)
        double bodyRatio = CandlePatterns.range(last) > 0
                ? CandlePatterns.body(last) / CandlePatterns.range(last) : 0;
        double f5 = 0;
        if (bodyRatio >= 0.70) f5 = 1.0;
        else if (bodyRatio >= 0.60) f5 = 0.7;
        else if (bodyRatio >= 0.50) f5 = 0.4;
        else if (bodyRatio >= 0.30) f5 = 0.2;
        rawScore += f5;

        // Factor 6 - Breakout Strength (max 2.0, Hard Gate: bo≤0% 차단)
        // bo%: 20봉 신고가 돌파율, candleSurge%: 이전봉 대비 상승률
        // 둘 중 높은 값으로 평가 → 횡보 미세돌파 차단 + 급등 포착
        double recentHigh = Indicators.recentHigh(candles, 20);
        double breakoutPct = recentHigh > 0 ? (close - recentHigh) / recentHigh * 100 : 0;
        if (breakoutPct <= 0) return Signal.none(String.format(Locale.ROOT,
                "BO_GATE bo=%.2f%% <= 0%%", breakoutPct));
        double candleSurge = 0;
        if (candles.size() >= 2) {
            double prevClose = candles.get(candles.size() - 2).trade_price;
            if (prevClose > 0) candleSurge = (close - prevClose) / prevClose * 100;
        }
        double effectiveBreakout = Math.max(breakoutPct, candleSurge);
        double f6 = 0;
        if (effectiveBreakout >= 3.3) f6 = 2.0;
        else if (effectiveBreakout >= 2.0) f6 = 1.5;
        else if (effectiveBreakout >= 1.0) f6 = 0.8;
        else if (effectiveBreakout >= 0.5) f6 = 0.3;
        else f6 = 0.1; // 0~0.5%
        rawScore += f6;

        // 10점 만점 (rawMax = f1:3.0 + f2:1.5 + f3:1.0 + f4:1.5 + f5:1.0 + f6:2.0 = 10.0)
        double score = Math.min(10.0, rawScore);

        if (score < minConfidence) return Signal.none(String.format(Locale.ROOT,
                "LOW_SCORE %.1f<%.1f vol=%.1fx day=%+.1f%% rsi=%.0f ema21=%s bo=%.2f%% surge=%.2f%% body=%.0f%%",
                score, minConfidence, volRatio, dailyChange, rsi,
                (ema21Rising ? "UP" : "DN") + (priceAboveEma21 ? "+ABV" : ""),
                breakoutPct, candleSurge, bodyRatio * 100));

        String reason = String.format(Locale.ROOT,
                "HC_BREAK close=%.2f score=%.1f vol=%.1fx day=%+.1f%% rsi=%.0f ema21=%s bo=%.2f%% surge=%.2f%% body=%.0f%%",
                close, score, volRatio, dailyChange, rsi,
                (ema21Rising ? "UP" : "DN") + (priceAboveEma21 ? "+ABV" : ""),
                breakoutPct, candleSurge, bodyRatio * 100);
        return Signal.of(SignalAction.BUY, type(), reason, score);
    }

    // ═══════════════════════════════════════
    //  청산 로직 — 6단계 우선순위
    // ═══════════════════════════════════════
    private Signal evaluateExit(StrategyContext ctx, List<UpbitCandle> candles,
                                UpbitCandle last, double close) {
        if (ctx.position.getAvgPrice() == null) return Signal.none();
        double avgPrice = ctx.position.getAvgPrice().doubleValue();
        if (avgPrice <= 0) return Signal.none();

        double pnlPct = (close - avgPrice) / avgPrice * 100.0;
        double atr = Indicators.atr(candles, ATR_PERIOD);

        // Grace period 계산 (진입 후 N캔들 동안은 Hard SL만 적용)
        int candlesSinceEntry = 0;
        Instant openedAt = ctx.position.getOpenedAt();
        if (openedAt != null) {
            long openedEpochMs = openedAt.toEpochMilli();
            for (int i = candles.size() - 1; i >= 0; i--) {
                ZonedDateTime ckst = toKst(candles.get(i).candle_date_time_utc);
                if (ckst != null) {
                    long candleEpochMs = ckst.toInstant().toEpochMilli();
                    if (candleEpochMs > openedEpochMs) {
                        candlesSinceEntry++;
                    } else {
                        break;
                    }
                }
            }
        }
        boolean inGracePeriod = gracePeriodCandles > 0 && candlesSinceEntry <= gracePeriodCandles;

        // 1. Hard SL (항상 활성)
        if (pnlPct <= -slPct) {
            return Signal.of(SignalAction.SELL, type(),
                    String.format(Locale.ROOT, "HC_SL pnl=%.2f%%", pnlPct));
        }

        // Grace period 중에는 Hard SL + Session End만 체크
        if (!inGracePeriod) {

            // 2. EMA Trend Break (비활성화 가능)
            if (emaExitEnabled) {
                double ema8 = Indicators.ema(candles, EMA_FAST);
                double ema21 = Indicators.ema(candles, EMA_MID);
                if (!Double.isNaN(ema8) && !Double.isNaN(ema21) && ema8 < ema21) {
                    if (pnlPct <= 0.3) {
                        return Signal.of(SignalAction.SELL, type(),
                                String.format(Locale.ROOT, "HC_EMA_BREAK ema8=%.2f ema21=%.2f pnl=%.2f%%", ema8, ema21, pnlPct));
                    }
                    int prevTail = candles.size() - 1;
                    double prevEma8 = Indicators.ema(candles, EMA_FAST, prevTail);
                    double prevEma21 = Indicators.ema(candles, EMA_MID, prevTail);
                    if (!Double.isNaN(prevEma8) && !Double.isNaN(prevEma21) && prevEma8 < prevEma21) {
                        return Signal.of(SignalAction.SELL, type(),
                                String.format(Locale.ROOT, "HC_EMA_BREAK_2X ema8=%.2f ema21=%.2f pnl=%.2f%%",
                                        ema8, ema21, pnlPct));
                    }
                }
            }

            // 3. MACD Momentum Fade (비활성화 가능)
            if (macdExitEnabled && pnlPct > 0) {
                double[] hist = Indicators.macdHistogramRecent(candles, MACD_FAST, MACD_SLOW, MACD_SIGNAL, 2);
                if (hist.length >= 2 && hist[1] < 0) {
                    return Signal.of(SignalAction.SELL, type(),
                            String.format(Locale.ROOT, "HC_MACD_FADE hist=%.6f", hist[1]));
                }
            }

            // 4. Trailing Stop (trailActivatePct 이상 수익 시 활성화, 최소 거리 보장)
            if (pnlPct > trailActivatePct && !Double.isNaN(atr) && atr > 0) {
                java.time.Instant trailOpenedAt = ctx.position != null ? ctx.position.getOpenedAt() : null;
                double peak = Indicators.peakHighSinceEntry(candles, avgPrice, trailOpenedAt);
                double trailDist = trailAtrMult * atr;
                double minDist = avgPrice * 0.015; // 최소 1.5% 거리 보장
                trailDist = Math.max(trailDist, minDist);
                double trailStop = peak - trailDist;
                if (close <= trailStop) {
                    return Signal.of(SignalAction.SELL, type(),
                            String.format(Locale.ROOT, "HC_TRAIL peak=%.2f stop=%.2f dist=%.2f", peak, trailStop, trailDist));
                }
            }

            // 5. Time Stop (비활성화: timeStopCandles <= 0)
            if (timeStopCandles > 0 && candlesSinceEntry >= timeStopCandles && pnlPct < timeStopMinPnl) {
                return Signal.of(SignalAction.SELL, type(),
                        String.format(Locale.ROOT, "HC_TIME_STOP candles=%d", candlesSinceEntry));
            }
        }

        // 6. Session End — 캔들 시각 기준 (자정 넘기기 지원)
        // sessionEnd < 12:00이면 다음날 새벽으로 간주 (예: 08:00 = 다음날 아침)
        ZonedDateTime candleKst = toKst(last.candle_date_time_utc);
        ZonedDateTime nowKst = candleKst != null ? candleKst : ZonedDateTime.now(KST);
        int nowMinutes = nowKst.getHour() * 60 + nowKst.getMinute();
        int sessionEndMinutes = sessionEndHour * 60 + sessionEndMin;
        boolean isOvernightSession = sessionEndMinutes < 12 * 60; // 12:00 미만이면 다음날
        if (isOvernightSession) {
            // 다음날 새벽 세션 종료: sessionEnd~10:00 사이에만 청산
            // (08:00~09:59에 청산, 10:00 이후는 새 거래일이므로 미청산)
            if (nowMinutes >= sessionEndMinutes && nowMinutes < 10 * 60) {
                return Signal.of(SignalAction.SELL, type(), "HC_SESSION_END");
            }
        } else {
            // 당일 세션: sessionEnd 이후 청산
            if (nowMinutes >= sessionEndMinutes) {
                return Signal.of(SignalAction.SELL, type(), "HC_SESSION_END");
            }
        }

        return Signal.none();
    }

    // ═══════════════════════════════════════
    //  유틸리티
    // ═══════════════════════════════════════
    private static ZonedDateTime toKst(String utcStr) {
        if (utcStr == null || utcStr.isEmpty()) return null;
        try {
            LocalDateTime utcLdt = LocalDateTime.parse(utcStr);
            return utcLdt.atZone(ZoneOffset.UTC).withZoneSameInstant(KST);
        } catch (Exception e) {
            return null;
        }
    }
}
