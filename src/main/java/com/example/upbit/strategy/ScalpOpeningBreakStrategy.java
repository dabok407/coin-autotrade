package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

/**
 * [19] 9시 오프닝 레인지 돌파 스캘핑 (자급자족)
 *
 * ═══════════════════════════════════════════════════════════
 *  08:00~08:59 KST 레인지 수집 → 09:05~10:30 KST 돌파 매수
 *  12:00 KST 이후 강제 청산 (오전 세션 종료)
 * ═══════════════════════════════════════════════════════════
 *
 * 원리:
 *   업비트 09:00 KST 전후는 변동성+거래량 피크 시간대.
 *   08:00~08:59에 형성된 레인지를 09:05 이후 돌파하면
 *   방향성 있는 움직임의 시작으로 판단하고 진입.
 *
 * 모든 파라미터를 외부(DB 설정, 백테스트)에서 오버라이드 가능.
 * 기본값은 전문가 권고 최적값.
 */
public class ScalpOpeningBreakStrategy implements TradingStrategy {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // ===== 시간 윈도우 (KST 시:분) — 기본값 =====
    private int rangeStartHour = 8;
    private int rangeStartMin = 0;      // 08:00
    private int rangeEndHour = 8;
    private int rangeEndMin = 59;       // 08:59
    private int entryStartHour = 9;
    private int entryStartMin = 5;      // 09:05
    private int entryEndHour = 10;
    private int entryEndMin = 30;       // 10:30
    private int sessionEndHour = 12;
    private int sessionEndMin = 0;      // 12:00 강제 청산

    // ===== 리스크 파라미터 — 기본값 =====
    private static final int ATR_PERIOD = 10;
    private double tpAtrMult = 1.2;
    private double slPct = 10.0;        // Hard SL: -10% (여유있게)
    private double trailAtrMult = 0.8;

    // ===== 진입 필터 — 기본값 =====
    private static final int VOLUME_AVG_PERIOD = 20;
    private double volumeMult = 1.5;
    private double minBodyRatio = 0.40;
    private static final double MIN_ATR_PCT = 0.001;

    // ===== 안전 =====
    private static final int MIN_CANDLES = 30;

    @Override
    public StrategyType type() {
        return StrategyType.SCALP_OPENING_BREAK;
    }

    // ===== 파라미터 오버라이드 세터 =====

    public ScalpOpeningBreakStrategy withTiming(int rsh, int rsm, int reh, int rem,
                                                int esh, int esm, int eeh, int eem,
                                                int seH, int seM) {
        this.rangeStartHour = rsh; this.rangeStartMin = rsm;
        this.rangeEndHour = reh; this.rangeEndMin = rem;
        this.entryStartHour = esh; this.entryStartMin = esm;
        this.entryEndHour = eeh; this.entryEndMin = eem;
        this.sessionEndHour = seH; this.sessionEndMin = seM;
        return this;
    }

    public ScalpOpeningBreakStrategy withRisk(double tpAtrMult, double slPct, double trailAtrMult) {
        this.tpAtrMult = tpAtrMult;
        this.slPct = slPct;
        this.trailAtrMult = trailAtrMult;
        return this;
    }

    public ScalpOpeningBreakStrategy withFilters(double volumeMult, double minBodyRatio) {
        this.volumeMult = volumeMult;
        this.minBodyRatio = minBodyRatio;
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

        return evaluateEntry(candles, last, close);
    }

    // ═══════════════════════════════════════
    //  진입 로직
    // ═══════════════════════════════════════
    private Signal evaluateEntry(List<UpbitCandle> candles, UpbitCandle last, double close) {
        ZonedDateTime lastKst = toKst(last.candle_date_time_utc);
        if (lastKst == null) return Signal.none();
        if (!isInWindow(lastKst, entryStartHour, entryStartMin, entryEndHour, entryEndMin)) {
            return Signal.none();
        }

        // 레인지 계산 (같은 날짜)
        double rangeHigh = Double.MIN_VALUE;
        double rangeLow = Double.MAX_VALUE;
        int rangeCount = 0;

        for (int i = 0; i < candles.size() - 1; i++) {
            ZonedDateTime kst = toKst(candles.get(i).candle_date_time_utc);
            if (kst == null) continue;

            if (kst.toLocalDate().equals(lastKst.toLocalDate())
                    && isInWindow(kst, rangeStartHour, rangeStartMin, rangeEndHour, rangeEndMin)) {
                UpbitCandle c = candles.get(i);
                if (c.high_price > rangeHigh) rangeHigh = c.high_price;
                if (c.low_price < rangeLow) rangeLow = c.low_price;
                rangeCount++;
            }
        }

        // 레인지 캔들 최소 4개
        if (rangeCount < 4) return Signal.none();
        if (rangeHigh <= rangeLow) return Signal.none();

        // 레인지 최소 폭: 0.3%
        double rangePct = (rangeHigh - rangeLow) / rangeLow * 100.0;
        if (rangePct < 0.3) return Signal.none();

        // 돌파: close > rangeHigh
        if (close <= rangeHigh) return Signal.none();

        // 2캔들 연속 돌파 확인
        int n = candles.size();
        if (n >= 2) {
            UpbitCandle prev = candles.get(n - 2);
            if (prev.trade_price <= rangeHigh) return Signal.none();
        }

        // 양봉 + body/range 필터
        if (!CandlePatterns.isBullish(last)) return Signal.none();
        double candleRange = CandlePatterns.range(last);
        if (candleRange <= 0) return Signal.none();
        double bodyRatio = CandlePatterns.body(last) / candleRange;
        if (bodyRatio < minBodyRatio) return Signal.none();

        // 거래량 필터
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double curVol = last.candle_acc_trade_volume;
        if (avgVol > 0 && curVol < avgVol * volumeMult) return Signal.none();

        // ATR 필터
        double atr = Indicators.atr(candles, ATR_PERIOD);
        if (Double.isNaN(atr) || atr / close < MIN_ATR_PCT) return Signal.none();

        // ───── Confidence ─────
        double score = 4.5;

        double breakoutPct = (close - rangeHigh) / rangeHigh * 100.0;
        if (breakoutPct >= 1.0) score += 2.0;
        else if (breakoutPct >= 0.5) score += 1.5;
        else score += 0.5;

        double volRatio = avgVol > 0 ? curVol / avgVol : 1.0;
        if (volRatio >= 2.5) score += 1.5;
        else if (volRatio >= 1.5) score += 1.0;
        else score += 0.3;

        if (rangePct >= 0.5 && rangePct <= 2.0) score += 1.0;
        else if (rangePct > 0 && rangePct < 3.0) score += 0.5;

        if (bodyRatio >= 0.70) score += 1.0;
        else if (bodyRatio >= 0.55) score += 0.5;

        score = Math.min(10.0, score);

        String reason = String.format(Locale.ROOT,
                "OPEN_BREAK close=%.2f rangeHigh=%.2f breakout=%.2f%% rangePct=%.2f%% vol=%.1fx body=%.0f%%",
                close, rangeHigh, breakoutPct, rangePct, volRatio, bodyRatio * 100);
        return Signal.of(SignalAction.BUY, type(), reason, score);
    }

    // ═══════════════════════════════════════
    //  청산 로직
    // ═══════════════════════════════════════
    private Signal evaluateExit(StrategyContext ctx, List<UpbitCandle> candles,
                                UpbitCandle last, double close) {
        double avgPrice = ctx.position.getAvgPrice().doubleValue();
        if (avgPrice <= 0) return Signal.none();

        double atr = Indicators.atr(candles, ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return Signal.none();

        double pnlPct = ((close - avgPrice) / avgPrice) * 100.0;

        // 1. Hard SL: -slPct% (기본 10%)
        if (pnlPct <= -slPct) {
            String reason = String.format(Locale.ROOT,
                    "OPEN_HARD_SL avg=%.2f close=%.2f pnl=%.2f%% sl=%.1f%%", avgPrice, close, pnlPct, slPct);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 2. Hard TP: entry + tpAtrMult × ATR
        double tpPrice = avgPrice + tpAtrMult * atr;
        if (last.high_price >= tpPrice) {
            double tpPnl = ((tpPrice - avgPrice) / avgPrice) * 100.0;
            String reason = String.format(Locale.ROOT,
                    "OPEN_TP avg=%.2f tp=%.2f pnl=%.2f%%", avgPrice, tpPrice, tpPnl);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 3. 실패 돌파: pnl < -1.0% + 음봉 2연속
        boolean bearish2 = candles.size() >= 2
                && !CandlePatterns.isBullish(last)
                && !CandlePatterns.isBullish(candles.get(candles.size() - 2));
        if (pnlPct < -1.0 && bearish2) {
            String reason = String.format(Locale.ROOT,
                    "OPEN_FAILED avg=%.2f close=%.2f pnl=%.2f%%", avgPrice, close, pnlPct);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 4. 트레일링 스탑: peak - trailAtrMult × ATR (이익 구간)
        if (close > avgPrice) {
            double peakHigh = Indicators.peakHighSinceEntry(candles, avgPrice);
            double trailStop = peakHigh - trailAtrMult * atr;
            if (trailStop > avgPrice && close <= trailStop) {
                String reason = String.format(Locale.ROOT,
                        "OPEN_TRAIL avg=%.2f peak=%.2f trail=%.2f close=%.2f",
                        avgPrice, peakHigh, trailStop, close);
                return Signal.of(SignalAction.SELL, type(), reason);
            }
        }

        // 5. 시간 제한: sessionEnd KST 이후 강제 청산
        ZonedDateTime lastKst = toKst(last.candle_date_time_utc);
        if (lastKst != null) {
            int kstMinOfDay = lastKst.getHour() * 60 + lastKst.getMinute();
            int endMin = sessionEndHour * 60 + sessionEndMin;
            if (kstMinOfDay >= endMin) {
                String reason = String.format(Locale.ROOT,
                        "OPEN_TIME_EXIT kst=%02d:%02d avg=%.2f pnl=%.2f%%",
                        lastKst.getHour(), lastKst.getMinute(), avgPrice, pnlPct);
                return Signal.of(SignalAction.SELL, type(), reason);
            }
        }

        return Signal.none();
    }

    // ═══════════════════════════════════════
    //  유틸리티
    // ═══════════════════════════════════════

    static ZonedDateTime toKst(String utcStr) {
        if (utcStr == null || utcStr.isEmpty()) return null;
        try {
            LocalDateTime utcLdt = LocalDateTime.parse(utcStr);
            return utcLdt.atZone(ZoneOffset.UTC).withZoneSameInstant(KST);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isInWindow(ZonedDateTime kst, int startHour, int startMin,
                                      int endHour, int endMin) {
        int minuteOfDay = kst.getHour() * 60 + kst.getMinute();
        int windowStart = startHour * 60 + startMin;
        int windowEnd = endHour * 60 + endMin;
        return minuteOfDay >= windowStart && minuteOfDay <= windowEnd;
    }
}
