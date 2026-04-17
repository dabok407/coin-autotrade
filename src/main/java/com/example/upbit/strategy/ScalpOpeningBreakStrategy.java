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
    private double slPct = 2.0;         // v2: 10%→2% (= tight SL fallback)
    private double trailAtrMult = 0.7;  // v3: 0.6→0.7 (노이즈 내성 강화)

    // ===== SL 종합안 (grace + wide + tight) =====
    // 기본값은 비활성. withSlAdvanced()로 활성화 시 grace/wide 적용
    private long slGracePeriodMs = 0L;
    private long slWidePeriodMs = 0L;
    private double slWidePct = 0.0;

    // ===== 진입 필터 (v2 합의: 완화 X, 강화) =====
    private static final int VOLUME_AVG_PERIOD = 20;
    private double volumeMult = 2.0;    // v3: 1.5→4.0 (1주일 실거래 분석 기반)
    private double minBodyRatio = 0.45; // v2 합의: 0.40→0.45 (강화)
    private static final double MIN_ATR_PCT = 0.001;
    private static final double MIN_BREAKOUT_PCT = 1.0;

    // ===== EMA 트렌드 필터 =====
    private static final int EMA_PERIOD = 20;

    // ===== RSI 과매수 필터 =====
    private static final int RSI_PERIOD = 14;
    private static final double RSI_OVERBOUGHT = 83.0;

    // ===== 시간 감쇠 파라미터 =====
    private static final int TIME_DECAY_CANDLES = 6; // 6캔들(=30분 @5min) 경과 시
    private static final double TIME_DECAY_MIN_PNL = 0.3; // 최소 수익 0.3%

    // ===== OPEN_FAILED 청산 옵션 =====
    private boolean openFailedEnabled = false;

    // ════════════════════════════════════════════════════════════════
    // OPEN_TRAIL (5분봉 boundary 트레일링) 비활성화 플래그
    // ════════════════════════════════════════════════════════════════
    // 비활성화 일시: 2026-04-10 (사용자 요청)
    // 비활성화 이유:
    //   - OPEN_TRAIL은 5분봉 '종가' 기준으로 peak 대비 하락 판단
    //   - 실시간 TP_TRAIL (BreakoutDetector.checkRealtimeTp)이 매 틱마다 판단하므로
    //     더 정밀하고 반등을 감안한 매도 가능
    //   - 2026-04-10 실거래 분석:
    //     KRW-ONG: OPEN_TRAIL이 09:35에 ±0% 매도 → TP_TRAIL이면 09:38에 +1.48% 매도
    //     KRW-CFG: OPEN_TRAIL이 10:15에 +0.97% 매도 → TP_TRAIL이면 10:17에 +1.94% 매도
    //   - OPEN_TRAIL이 TP_TRAIL보다 수 분 먼저 발동하여 수익 구간을 조기 절단
    //   - TP_TRAIL이 독립적으로 동작하므로 OPEN_TRAIL 비활성화해도 트레일링 매도 누락 없음
    //
    // 재활성화 조건:
    //   - TP_TRAIL(실시간)이 미작동하는 케이스 발견 시
    //   - 또는 5분봉 기반 트레일링이 더 적합한 전략적 판단이 있을 때
    //   - 재활성화 전 반드시 사용자 동의 필수
    // ════════════════════════════════════════════════════════════════
    private static final boolean OPEN_TRAIL_ENABLED = false;
    private static final boolean OPEN_TP_ENABLED = false;

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

    /**
     * SL 종합안 (grace + wide + tight) 활성화.
     * grace 동안: SL 미적용
     * grace ~ widePeriod: SL_WIDE 적용
     * widePeriod 이후: SL_TIGHT (= slPct, withRisk에서 설정)
     */
    public ScalpOpeningBreakStrategy withSlAdvanced(int gracePeriodSec, int widePeriodMin, double wideSlPct) {
        this.slGracePeriodMs = gracePeriodSec * 1000L;
        this.slWidePeriodMs = widePeriodMin * 60_000L;
        this.slWidePct = wideSlPct;
        return this;
    }

    public ScalpOpeningBreakStrategy withFilters(double volumeMult, double minBodyRatio) {
        this.volumeMult = volumeMult;
        this.minBodyRatio = minBodyRatio;
        return this;
    }

    public ScalpOpeningBreakStrategy withOpenFailedEnabled(boolean enabled) {
        this.openFailedEnabled = enabled;
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
        if (lastKst == null) return Signal.none("KST_PARSE_FAIL");
        if (!isInWindow(lastKst, entryStartHour, entryStartMin, entryEndHour, entryEndMin)) {
            return Signal.none("OUTSIDE_ENTRY_WINDOW");
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
        if (rangeCount < 4) return Signal.none("RANGE_CANDLES_" + rangeCount + "<4");
        if (rangeHigh <= rangeLow) return Signal.none("RANGE_INVALID");

        // 레인지 최소 폭: 0.3%
        double rangePct = (rangeHigh - rangeLow) / rangeLow * 100.0;
        if (rangePct < 0.3) return Signal.none(String.format(Locale.ROOT, "RANGE_NARROW_%.2f%%<0.3%%", rangePct));

        // 돌파: close > rangeHigh (v2: 1캔들 즉시 진입)
        if (close <= rangeHigh) {
            double gap = (close - rangeHigh) / rangeHigh * 100.0;
            return Signal.none(String.format(Locale.ROOT, "NO_BREAKOUT_close=%.0f_rH=%.0f(%.2f%%)", close, rangeHigh, gap));
        }

        // v2 합의: 최소 돌파 강도 0.2% (0.1%→0.2% 강화, 허위 돌파 방지)
        double breakoutPct = (close - rangeHigh) / rangeHigh * 100.0;
        if (breakoutPct < MIN_BREAKOUT_PCT) {
            return Signal.none(String.format(Locale.ROOT, "BREAKOUT_WEAK_%.2f%%<%.1f%%", breakoutPct, MIN_BREAKOUT_PCT));
        }

        // 양봉 + body/range 필터 (v2 합의: 0.45로 강화)
        if (!CandlePatterns.isBullish(last)) return Signal.none("BEARISH_CANDLE");
        double candleRange = CandlePatterns.range(last);
        if (candleRange <= 0) return Signal.none("ZERO_RANGE");
        double bodyRatio = CandlePatterns.body(last) / candleRange;
        if (bodyRatio < minBodyRatio) {
            return Signal.none(String.format(Locale.ROOT, "BODY_WEAK_%.0f%%<%.0f%%", bodyRatio * 100, minBodyRatio * 100));
        }

        // 거래량 필터 (v2 합의: 1.5x 유지)
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double curVol = last.candle_acc_trade_volume;
        if (avgVol > 0 && curVol < avgVol * volumeMult) {
            return Signal.none(String.format(Locale.ROOT, "VOLUME_LOW_%.1fx<%.1fx", curVol / avgVol, volumeMult));
        }

        // ATR 필터
        double atr = Indicators.atr(candles, ATR_PERIOD);
        if (Double.isNaN(atr) || atr / close < MIN_ATR_PCT) return Signal.none("ATR_TOO_LOW");

        // EMA 트렌드 필터
        double ema = Indicators.ema(candles, EMA_PERIOD);
        if (!Double.isNaN(ema) && close < ema) {
            return Signal.none(String.format(Locale.ROOT, "BELOW_EMA20_close=%.0f<ema=%.0f", close, ema));
        }

        // v2: RSI 과매수 필터
        double rsi = Indicators.rsi(candles, RSI_PERIOD);
        if (rsi >= RSI_OVERBOUGHT) return Signal.none(String.format(Locale.ROOT, "RSI_OVERBOUGHT_%.0f>=75", rsi));

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

        // 1. SL 종합안 (활성화 시) — grace + wide + tight
        if (slGracePeriodMs > 0 || slWidePeriodMs > 0) {
            java.time.Instant openedAt = ctx.position != null ? ctx.position.getOpenedAt() : null;
            long elapsedMs = openedAt != null
                    ? (System.currentTimeMillis() - openedAt.toEpochMilli())
                    : Long.MAX_VALUE;
            if (elapsedMs < slGracePeriodMs) {
                // grace — SL 무시
            } else if (elapsedMs < slWidePeriodMs) {
                // grace ~ wide_period: SL_WIDE
                if (pnlPct <= -slWidePct) {
                    String reason = String.format(Locale.ROOT,
                            "OPEN_SL_WIDE avg=%.2f close=%.2f pnl=%.2f%% wide=%.1f%% elapsed=%ds",
                            avgPrice, close, pnlPct, slWidePct, elapsedMs / 1000);
                    return Signal.of(SignalAction.SELL, type(), reason);
                }
            } else {
                // wide_period 이후: SL_TIGHT (= slPct)
                if (pnlPct <= -slPct) {
                    String reason = String.format(Locale.ROOT,
                            "OPEN_SL_TIGHT avg=%.2f close=%.2f pnl=%.2f%% tight=%.1f%% elapsed=%ds",
                            avgPrice, close, pnlPct, slPct, elapsedMs / 1000);
                    return Signal.of(SignalAction.SELL, type(), reason);
                }
            }
        } else {
            // 기존 단순 hard SL (호환성 - withSlAdvanced 미설정 시)
            if (pnlPct <= -slPct) {
                String reason = String.format(Locale.ROOT,
                        "OPEN_HARD_SL avg=%.2f close=%.2f pnl=%.2f%% sl=%.1f%%", avgPrice, close, pnlPct, slPct);
                return Signal.of(SignalAction.SELL, type(), reason);
            }
        }

        // 2. 범위 기반 SL — 2연속 확인 (v3: 노이즈 이탈 방지)
        //    1캔들만 rangeHigh 아래로 내려갔다 복귀하면 유지
        //    2캔들 연속 rangeHigh 아래면 진짜 돌파 실패 → 청산
        double rangeHigh = calcRangeHigh(candles, lastKst);
        if (rangeHigh > 0 && close < rangeHigh) {
            // 이전 캔들도 rangeHigh 아래였는지 확인 (2연속 확인)
            int sz = candles.size();
            if (sz >= 2) {
                UpbitCandle prev = candles.get(sz - 2);
                if (prev.trade_price < rangeHigh) {
                    // 2연속 이탈 → 진짜 돌파 실패
                    String reason = String.format(Locale.ROOT,
                            "OPEN_RANGE_SL avg=%.2f close=%.2f rH=%.2f pnl=%.2f%%",
                            avgPrice, close, rangeHigh, pnlPct);
                    return Signal.of(SignalAction.SELL, type(), reason);
                }
                // 1캔들만 이탈 → 노이즈, 다음 틱까지 관찰
            }
        }

        // 3. Hard TP: entry + tpAtrMult × ATR
        //    OPEN_TP_ENABLED=false 시 skip → 실시간 TP_TRAIL(BreakoutDetector)에 위임
        // ════════════════════════════════════════════════════════════════
        // OPEN_TP (5분봉 boundary hard TP) 비활성화 플래그
        // ════════════════════════════════════════════════════════════════
        // 비활성화 일시: 2026-04-11 (사용자 요청)
        // 비활성화 이유:
        //   - OPEN_TP는 5분봉 종가 기준 ATR×tpAtrMult 도달 시 즉시 매도
        //   - 실시간 TP_TRAIL (BreakoutDetector.checkRealtimeTp)이 peak 추적하여
        //     더 높은 가격에서 매도 가능
        //   - 2026-04-11 실거래:
        //     KRW-RED: OPEN_TP가 +3.40%에 매도 → TP_TRAIL peak=245 추적 중이었음
        //     KRW-LPT: OPEN_TP가 +2.06%에 매도 → peak=3534(+6.4%)까지 올랐음
        //   - OPEN_TP가 TP_TRAIL보다 먼저 발동하여 수익 극대화 차단
        //   - OPEN_TRAIL과 동일한 구조적 문제 (2026-04-10 비활성화)
        //
        // 재활성화 조건:
        //   - TP_TRAIL(실시간)이 미작동하는 케이스 발견 시
        //   - 재활성화 전 반드시 사용자 동의 필수
        // ════════════════════════════════════════════════════════════════
        if (OPEN_TP_ENABLED) {
        // V115: splitPhase=1(1차 매도 완료)이면 OPEN_TP skip — 2차 매도는 realtime TRAIL/BEV에 위임
        Integer pePhase = ctx.position != null ? ctx.position.getSplitPhase() : null;
        int splitPhase = pePhase != null ? pePhase : 0;
        if (splitPhase != 1) {
            double tpPrice = avgPrice + tpAtrMult * atr;
            if (last.high_price >= tpPrice) {
                double tpPnl = ((tpPrice - avgPrice) / avgPrice) * 100.0;
                String reason = String.format(Locale.ROOT,
                        "OPEN_TP avg=%.2f tp=%.2f pnl=%.2f%%", avgPrice, tpPrice, tpPnl);
                return Signal.of(SignalAction.SELL, type(), reason);
            }
        }
        }

        // 4. 빠른 실패 돌파: pnl < -1.0% + 2연속 음봉 (v3: 노이즈 내성 강화)
        //    openFailedEnabled=false 이면 이 청산 로직을 건너뜀
        if (openFailedEnabled) {
            int sz = candles.size();
            if (pnlPct < -1.0 && !CandlePatterns.isBullish(last) && sz >= 2) {
                UpbitCandle prev = candles.get(sz - 2);
                if (!CandlePatterns.isBullish(prev)) {
                    String reason = String.format(Locale.ROOT,
                            "OPEN_FAILED avg=%.2f close=%.2f pnl=%.2f%%", avgPrice, close, pnlPct);
                    return Signal.of(SignalAction.SELL, type(), reason);
                }
            }
        }

        // 5. 트레일링 스탑: peak - trailAtrMult × ATR (이익 구간)
        //    OPEN_TRAIL_ENABLED=false 시 skip → 실시간 TP_TRAIL(BreakoutDetector)에 위임
        if (OPEN_TRAIL_ENABLED && close > avgPrice) {
            java.time.Instant openedAt = ctx.position != null ? ctx.position.getOpenedAt() : null;
            double peakHigh = Indicators.peakHighSinceEntry(candles, avgPrice, openedAt);
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
        // sessionEnd < 12:00이면 오버나잇 (매수 후 익일 새벽까지 보유)
        // 예: sessionEnd 08:50 → 익일 08:50~10:00 사이에 청산
        // 주의: 진입 시간(09:00~10:30)과 청산 시간(익일 08:50~10:00)이 시간상 겹침
        //       → openedAt 기준 elapsed 시간 검증으로 당일 진입과 익일 청산 구분
        if (lastKst != null) {
            int kstMinOfDay = lastKst.getHour() * 60 + lastKst.getMinute();
            int endMin = sessionEndHour * 60 + sessionEndMin;
            boolean isOvernight = endMin < 12 * 60;
            boolean shouldExit = false;
            if (isOvernight) {
                // 오버나잇: 익일 새벽 endMin~10:00 사이 + 매수 후 5시간 이상 경과
                // 5시간 경과 조건으로 당일 진입(09:30 등)과 익일 청산(09:30 다음날) 구분
                if (kstMinOfDay >= endMin && kstMinOfDay < 10 * 60) {
                    Instant openedAt = ctx.position != null ? ctx.position.getOpenedAt() : null;
                    if (openedAt != null) {
                        long elapsedMs = System.currentTimeMillis() - openedAt.toEpochMilli();
                        long elapsedHours = elapsedMs / (60L * 60L * 1000L);
                        shouldExit = elapsedHours >= 5; // 매수 후 5시간 이상이면 익일 청산 가능
                    }
                }
            } else {
                // 당일 세션: 그냥 endMin 이후 청산
                shouldExit = kstMinOfDay >= endMin;
            }
            if (shouldExit) {
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
