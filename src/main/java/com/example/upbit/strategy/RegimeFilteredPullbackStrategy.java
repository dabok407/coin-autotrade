package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.util.List;
import java.util.Locale;

/**
 * Regime-Filtered Pullback Strategy (LONG only, 현물)
 *
 * 핵심 로직:
 * - 상승 추세(레짐) 확인: close > EMA200, EMA50 > EMA200, ADX > 임계값
 * - 눌림 진입: RSI(2)≤10 + close<EMA20 또는 close≤볼린저하단 + RSI(14)<45
 * - 청산: ATR 기반 SL/TP + ATR 트레일링 스탑
 * - 추가매수: ATR 스텝만큼 하락 시 1회
 *
 * 설계 원칙:
 * - 완전 무상태(stateless): 캔들 히스토리 + PositionEntity만으로 판단
 *   → 백테스트/실시간 봇 동일 동작 보장
 * - 기존 TP/SL(SignalEvaluator)은 최종 안전장치로 병행 가능
 *
 * 보완 사항 (원본 대비):
 * - ADX: 단순 DX → Wilder smoothing 적용
 * - EMA: 전체 히스토리 기반 계산 (정확도 향상)
 * - 피크 가격: 캔들 히스토리에서 동적 계산 (상태 저장 불필요)
 * - 거래량 필터: 최근 20봉 평균 대비 현재 거래량이 극도로 낮으면 진입 회피
 * - 최소 손절폭 검증: SL이 0.3% 미만이면 노이즈/수수료 리스크로 진입 스킵
 */
public class RegimeFilteredPullbackStrategy implements TradingStrategy {

    // ===== 파라미터 =====
    private static final int EMA_FAST = 20;
    private static final int EMA_MID = 50;
    private static final int EMA_SLOW = 200;

    private static final int RSI_SHORT = 3; // RSI(2) -> RSI(3): 극단적 민감도 완화
    private static final int RSI_STD = 14;

    private static final int ATR_LEN = 14;
    private static final int ADX_LEN = 14;

    private static final int BB_LEN = 20;
    private static final double BB_STD = 2.0;

    private static final double ADX_MIN = 18.0;       // 약한 트렌드 필터링 (22→18: 더 많은 추세 인식)

    private static final double SL_ATR_MULT = 2.5;     // SL = entry - 2.5 * ATR
    private static final double TP_ATR_MULT = 3.5;     // TP = entry + 3.5 * ATR (R:R = 1.4:1)
    private static final double TRAIL_ATR_MULT = 2.0;   // trail = peak - 2.0 * ATR

    private static final double ADD_BUY_ATR_STEP = 1.0; // 추가매수: entry - 1.0*ATR 하락 시
    private static final int MAX_ADD_BUYS = 1;

    private static final double MIN_SL_DISTANCE_PCT = 0.003; // 최소 손절폭 0.3%
    private static final double MIN_VOLUME_RATIO = 0.3;      // 평균 거래량의 30% 미만이면 진입 금지

    // 최소 필요 캔들 수 (EMA200 워밍업: 250이면 충분)
    private static final int MIN_CANDLES = 250;

    @Override
    public StrategyType type() {
        return StrategyType.REGIME_PULLBACK;
    }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        List<UpbitCandle> candles = ctx.candles;
        if (candles == null || candles.size() < MIN_CANDLES) return Signal.none();

        UpbitCandle last = candles.get(candles.size() - 1);
        double close = last.trade_price;
        boolean hasPosition = ctx.position != null
                && ctx.position.getQty() != null
                && ctx.position.getQty().compareTo(java.math.BigDecimal.ZERO) > 0;

        // ===== 지표 계산 =====
        double ema20 = Indicators.ema(candles, EMA_FAST);
        double ema50 = Indicators.ema(candles, EMA_MID);
        double ema200 = Indicators.ema(candles, EMA_SLOW);

        double rsi2 = Indicators.rsi(candles, RSI_SHORT);
        double rsi14 = Indicators.rsi(candles, RSI_STD);

        double atr = Indicators.atr(candles, ATR_LEN);
        double adx = Indicators.adx(candles, ADX_LEN);

        double[] bb = Indicators.bollinger(candles, BB_LEN, BB_STD);
        double bbLower = bb[0];

        // ===== 레짐 필터: 상승 추세인가? =====
        boolean regimeUp = close > ema200 && ema50 > ema200 && adx > ADX_MIN;

        // ===== 포지션 보유 중: 청산 판단 =====
        if (hasPosition) {
            double avgPrice = ctx.position.getAvgPrice().doubleValue();
            if (avgPrice <= 0) return Signal.none();

            // ATR 기반 동적 SL/TP
            double slPrice = avgPrice - SL_ATR_MULT * atr;
            double tpPrice = avgPrice + TP_ATR_MULT * atr;

            // 트레일링 스탑: 진입 이후 최고가 - k*ATR
            double peakHigh = Indicators.peakHighSinceEntry(candles, avgPrice);
            double trailStop = peakHigh - TRAIL_ATR_MULT * atr;

            // 트레일링 스탑은 이익 구간에서만 활성화 (손실 구간에서 손실 고정 방지)
            double effectiveSL;
            if (trailStop > slPrice && close > avgPrice) {
                effectiveSL = trailStop;  // 이익 중: 트레일링 사용
            } else {
                effectiveSL = slPrice;    // 손실 중: 하드 SL만 사용
            }

            // SL 히트 (현재 캔들의 저가가 SL 이하)
            if (last.low_price <= effectiveSL) {
                boolean isTrail = trailStop > slPrice;
                String reason = String.format(Locale.ROOT,
                        "%s avg=%.2f peak=%.2f atr=%.4f sl=%.2f trail=%.2f",
                        isTrail ? "TRAIL_STOP" : "ATR_STOP_LOSS",
                        avgPrice, peakHigh, atr, slPrice, trailStop);
                return Signal.of(SignalAction.SELL, type(), reason);
            }

            // TP 히트 (현재 캔들의 고가가 TP 이상)
            if (last.high_price >= tpPrice) {
                String reason = String.format(Locale.ROOT,
                        "ATR_TAKE_PROFIT avg=%.2f tp=%.2f atr=%.4f pnl=%.2f%%",
                        avgPrice, tpPrice, atr, ((tpPrice - avgPrice) / avgPrice) * 100.0);
                return Signal.of(SignalAction.SELL, type(), reason);
            }

            // 레짐 붕괴 시 청산 (추세 깨지면 빠르게 탈출)
            if (!regimeUp && close < ema50) {
                double pnlPct = ((close - avgPrice) / avgPrice) * 100.0;
                String reason = String.format(Locale.ROOT,
                        "REGIME_BREAK close<EMA50 ema50=%.2f pnl=%.2f%%", ema50, pnlPct);
                return Signal.of(SignalAction.SELL, type(), reason);
            }

            // 추가매수 (레짐 유지 + 가격이 ATR 스텝만큼 하락)
            if (MAX_ADD_BUYS > 0 && ctx.position.getAddBuys() < MAX_ADD_BUYS && regimeUp) {
                double addBuyThreshold = avgPrice - ADD_BUY_ATR_STEP * atr;
                if (close <= addBuyThreshold) {
                    String reason = String.format(Locale.ROOT,
                            "ADD_BUY_ATR avg=%.2f threshold=%.2f atr=%.4f",
                            avgPrice, addBuyThreshold, atr);
                    return Signal.of(SignalAction.ADD_BUY, type(), reason);
                }
            }

            return Signal.none();
        }

        // ===== 포지션 없음: 진입 판단 =====

        // 레짐 필터 미충족 → 매수 안 함
        if (!regimeUp) return Signal.none();

        // 거래량 필터: 최근 봉 거래량이 평균의 30% 미만이면 스킵
        double avgVol = Indicators.smaVolume(candles, 20);
        if (avgVol > 0 && last.candle_acc_trade_volume < avgVol * MIN_VOLUME_RATIO) {
            return Signal.none();
        }

        // 눌림 진입 조건
        boolean entryA = rsi2 <= 25.0 && close < ema20; // RSI(3) ≤ 25 (15→25: 더 많은 진입 기회)
        boolean entryB = close <= bbLower && rsi14 < 50.0; // RSI(14) < 50 (45→50: 완화)

        if (!entryA && !entryB) return Signal.none();

        // 최소 손절폭 검증 (ATR이 너무 작으면 수수료에 잡아먹힘)
        double slDistance = SL_ATR_MULT * atr;
        if (slDistance / close < MIN_SL_DISTANCE_PCT) {
            return Signal.none();
        }

        String entryType = entryA ? "RSI2_EMA20" : "BB_LOW_RSI14";
        // === Confidence Score ===
        double score = 4.0;
        // (1) ADX 강도: 18이 최소, 25+ 강한 추세
        if (adx >= 30) score += 2.0;
        else if (adx >= 25) score += 1.5;
        else if (adx >= 21) score += 0.8;
        // (2) RSI 극값: 더 과매도일수록 반등 기대 높음
        double rsiScore = entryA ? rsi2 : rsi14;
        if (entryA && rsi2 <= 3) score += 1.5;
        else if (entryA && rsi2 <= 6) score += 1.0;
        else if (entryB && rsi14 < 30) score += 1.5;
        else if (entryB && rsi14 < 38) score += 1.0;
        else score += 0.3;
        // (3) EMA 정렬 마진: ema50-ema200 차이 클수록 강한 추세
        double emaMargin = (ema50 - ema200) / ema200 * 100;
        if (emaMargin >= 3.0) score += 1.5;
        else if (emaMargin >= 1.5) score += 1.0;
        else score += 0.3;
        // (4) 두 조건 동시 충족
        if (entryA && entryB) score += 1.0;

        String reason = String.format(Locale.ROOT,
                "PULLBACK_%s rsi2=%.1f rsi14=%.1f adx=%.1f atr=%.4f ema20=%.2f bb_low=%.2f",
                entryType, rsi2, rsi14, adx, atr, ema20, bbLower);
        return Signal.of(SignalAction.BUY, type(), reason, Math.min(10.0, score));
    }
}
