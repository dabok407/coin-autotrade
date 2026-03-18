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
 * v2 개선사항 (에이전트 합의 반영):
 *   - 1캔들 즉시 진입 + 최소 돌파 0.2% (강화)
 *   - SL 2% (스캘핑 타이트 SL)
 *   - 범위 기반 SL (close < rangeHigh → 즉시 청산)
 *   - 실패 돌파 빠른 청산 (-0.5% + 1음봉)
 *   - RSI 과매수 필터 (RSI < 75)
 *   - 시간 감쇠 청산 (캔들 타임스탬프 기반, 백테스트 호환)
 *   - 진입 필터 강화 (volume 1.5x, body 0.45)
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

    // ===== 리스크 파라미터 =====
    private static final int ATR_PERIOD = 10;
    private double tpAtrMult = 1.5;     // v2: 1.2→1.5
    private double slPct = 2.0;         // v2: 10%→2%
    private double trailAtrMult = 0.6;  // v2: 0.8→0.6

    // ===== 진입 필터 (v2 합의: 완화 X, 강화) =====
    private static final int VOLUME_AVG_PERIOD = 20;
    private double volumeMult = 1.5;    // v2 합의: 1.5 유지 (완화 금지)
    private double minBodyRatio = 0.45; // v2 합의: 0.40→0.45 (강화)
    private static final double MIN_ATR_PCT = 0.001;
    private static final double MIN_BREAKOUT_PCT = 0.2; // v2 합의: 0.1→0.2% (강화)

    // ===== EMA 트렌드 필터 =====
    private static final int EMA_PERIOD = 20;

    // ===== RSI 과매수 필터 =====
    private static final int RSI_PERIOD = 14;
    private static final double RSI_OVERBOUGHT = 75.0;

    // ===== 시간 감쇠 파라미터 =====
    private static final int TIME_DECAY_CANDLES = 6; // 6캔들(=30분 @5min) 경과 시
    private static final double TIME_DECAY_MIN_PNL = 0.3; // 최소 수익 0.3%

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
    //  진입 로직 (v2: 1캔들 진입 + 강화된 필터)
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

        // 돌파: close > rangeHigh (v2: 1캔들 즉시 진입)
        if (close <= rangeHigh) return Signal.none();

        // v2 합의: 최소 돌파 강도 0.2% (0.1%→0.2% 강화, 허위 돌파 방지)
        double breakoutPct = (close - rangeHigh) / rangeHigh * 100.0;
        if (breakoutPct < MIN_BREAKOUT_PCT) return Signal.none();

        // 양봉 + body/range 필터 (v2 합의: 0.45로 강화)
        if (!CandlePatterns.isBullish(last)) return Signal.none();
        double candleRange = CandlePatterns.range(last);
        if (candleRange <= 0) return Signal.none();
        double bodyRatio = CandlePatterns.body(last) / candleRange;
        if (bodyRatio < minBodyRatio) return Signal.none();

        // 거래량 필터 (v2 합의: 1.5x 유지)
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double curVol = last.candle_acc_trade_volume;
        if (avgVol > 0 && curVol < avgVol * volumeMult) return Signal.none();

        // ATR 필터
        double atr = Indicators.atr(candles, ATR_PERIOD);
        if (Double.isNaN(atr) || atr / close < MIN_ATR_PCT) return Signal.none();

        // EMA 트렌드 필터
        double ema = Indicators.ema(candles, EMA_PERIOD);
        if (!Double.isNaN(ema) && close < ema) return Signal.none();

        // v2: RSI 과매수 필터
        double rsi = Indicators.rsi(candles, RSI_PERIOD);
        if (rsi >= RSI_OVERBOUGHT) return Signal.none();

        // ───── Confidence ─────
        double score = 5.0;

        if (breakoutPct >= 1.0) score += 2.0;
        else if (breakoutPct >= 0.5) score += 1.5;
        else if (breakoutPct >= 0.2) score += 1.0;
        else score += 0.5;

        double volRatio = avgVol > 0 ? curVol / avgVol : 1.0;
        if (volRatio >= 3.0) score += 1.5;
        else if (volRatio >= 2.0) score += 1.2;
        else if (volRatio >= 1.5) score += 0.8;
        else score += 0.3;

        if (rangePct >= 0.5 && rangePct <= 2.0) score += 1.0;
        else if (rangePct > 0 && rangePct < 3.0) score += 0.5;

        if (bodyRatio >= 0.70) score += 1.0;
        else if (bodyRatio >= 0.55) score += 0.5;

        // v2: RSI 보너스
        if (rsi >= 50 && rsi <= 65) score += 0.5;

        score = Math.min(10.0, score);

        String reason = String.format(Locale.ROOT,
                "OPEN_BREAK close=%.2f rH=%.2f bo=%.2f%% rng=%.2f%% vol=%.1fx body=%.0f%% rsi=%.0f",
                close, rangeHigh, breakoutPct, rangePct, volRatio, bodyRatio * 100, rsi);
        return Signal.of(SignalAction.BUY, type(), reason, score);
    }

    // ═══════════════════════════════════════
    //  청산 로직 (v2 합의: 범위 기반 SL + 캔들 시간 감쇠)
    // ═══════════════════════════════════════
    private Signal evaluateExit(StrategyContext ctx, List<UpbitCandle> candles,
                                UpbitCandle last, double close) {
        double avgPrice = ctx.position.getAvgPrice().doubleValue();
        if (avgPrice <= 0) return Signal.none();

        double atr = Indicators.atr(candles, ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return Signal.none();

        double pnlPct = ((close - avgPrice) / avgPrice) * 100.0;

        ZonedDateTime lastKst = toKst(last.candle_date_time_utc);

        // 1. Hard SL: -slPct% (v2: 기본 2%)
        if (pnlPct <= -slPct) {
            String reason = String.format(Locale.ROOT,
                    "OPEN_HARD_SL avg=%.2f close=%.2f pnl=%.2f%% sl=%.1f%%", avgPrice, close, pnlPct, slPct);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 2. v2 합의: 범위 기반 SL — close < rangeHigh이면 즉시 청산
        //    (돌파 실패 = 범위 안으로 재진입 = 전략 근거 소멸)
        double rangeHigh = calcRangeHigh(candles, lastKst);
        if (rangeHigh > 0 && close < rangeHigh) {
            String reason = String.format(Locale.ROOT,
                    "OPEN_RANGE_SL avg=%.2f close=%.2f rH=%.2f pnl=%.2f%%",
                    avgPrice, close, rangeHigh, pnlPct);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 3. Hard TP: entry + tpAtrMult × ATR
        double tpPrice = avgPrice + tpAtrMult * atr;
        if (last.high_price >= tpPrice) {
            double tpPnl = ((tpPrice - avgPrice) / avgPrice) * 100.0;
            String reason = String.format(Locale.ROOT,
                    "OPEN_TP avg=%.2f tp=%.2f pnl=%.2f%%", avgPrice, tpPrice, tpPnl);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 4. 빠른 실패 돌파: pnl < -0.5% + 1음봉
        if (pnlPct < -0.5 && !CandlePatterns.isBullish(last)) {
            String reason = String.format(Locale.ROOT,
                    "OPEN_FAILED avg=%.2f close=%.2f pnl=%.2f%%", avgPrice, close, pnlPct);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 5. 트레일링 스탑: peak - trailAtrMult × ATR (이익 구간)
        if (close > avgPrice) {
            double peakHigh = Indicators.peakHighSinceEntry(candles, avgPrice);
            double trailStop = peakHigh - trailAtrMult * atr;
            if (trailStop > avgPrice && close <= trailStop) {
                double trailPnl = ((close - avgPrice) / avgPrice) * 100.0;
                String reason = String.format(Locale.ROOT,
                        "OPEN_TRAIL avg=%.2f peak=%.2f trail=%.2f close=%.2f pnl=%.2f%%",
                        avgPrice, peakHigh, trailStop, close, trailPnl);
                return Signal.of(SignalAction.SELL, type(), reason);
            }
        }

        // 6. v2 합의: 시간 감쇠 (캔들 타임스탬프 기반 — 백테스트 호환)
        //    진입가 근처 캔들을 찾아 그 이후 캔들 수로 경과 시간 추정
        int candlesSinceEntry = countCandlesSinceEntry(candles, avgPrice);
        if (candlesSinceEntry >= TIME_DECAY_CANDLES && pnlPct < TIME_DECAY_MIN_PNL) {
            String reason = String.format(Locale.ROOT,
                    "OPEN_TIME_DECAY avg=%.2f close=%.2f pnl=%.2f%% candles=%d",
                    avgPrice, close, pnlPct, candlesSinceEntry);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 7. 시간 제한: sessionEnd KST 이후 강제 청산
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

    /**
     * 당일 레인지 고가 재계산 (청산 시 범위 기반 SL에 사용).
     */
    private double calcRangeHigh(List<UpbitCandle> candles, ZonedDateTime lastKst) {
        if (lastKst == null) return -1;
        double rangeHigh = Double.MIN_VALUE;
        int count = 0;
        for (int i = 0; i < candles.size() - 1; i++) {
            ZonedDateTime kst = toKst(candles.get(i).candle_date_time_utc);
            if (kst == null) continue;
            if (kst.toLocalDate().equals(lastKst.toLocalDate())
                    && isInWindow(kst, rangeStartHour, rangeStartMin, rangeEndHour, rangeEndMin)) {
                if (candles.get(i).high_price > rangeHigh) rangeHigh = candles.get(i).high_price;
                count++;
            }
        }
        return count >= 4 ? rangeHigh : -1;
    }

    /**
     * 진입가 근처 캔들 이후 경과 캔들 수 (Instant.now() 대신 캔들 기반, 백테스트 호환).
     */
    private int countCandlesSinceEntry(List<UpbitCandle> candles, double avgPrice) {
        double threshold = avgPrice * 0.01;
        for (int i = candles.size() - 1; i >= 0; i--) {
            UpbitCandle c = candles.get(i);
            if (c.low_price <= avgPrice + threshold && c.high_price >= avgPrice - threshold) {
                return candles.size() - 1 - i;
            }
        }
        return 0; // 진입 캔들을 찾지 못하면 0 반환 (보수적)
    }

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
