package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.util.List;
import java.util.Locale;

/**
 * Bollinger Band + RSI Mean Reversion Strategy (BMR) v2.0
 *
 * 횡보장(Range/Sideways) 특화 평균회귀 전략.
 * 추세추종 전략(ERT)이 실패하는 BTC/ADA 같은 횡보 코인에서 수익을 낸다.
 *
 * ■ 핵심 로직:
 *   - ADX < 25 (추세 없음 = 횡보장 확인) 시에만 진입
 *   - 볼린저 밴드 하단 터치/근접 + RSI 과매도 → 매수 (평균회귀 기대)
 *   - BB 중앙선 도달 + 수익 → 익절 (평균으로 복귀 완료)
 *   - RSI 회복 (> 55) + 수익 → 익절 (모멘텀 회복 = 평균회귀 완료)
 *   - ADX 급등 (> 40) → 추세 확정, 탈출
 *   - 나머지는 TP/SL 시스템에 위임
 *
 * ■ v2.0 변경사항 (v1.0 → v2.0):
 *   - MACD 모멘텀 로스 퇴장 제거 (진입 시 MACD 이미 음수이므로 조기 퇴장 발생)
 *   - ADX 퇴장 임계 30→40 (ADX 30은 횡보에서 자주 찍히므로)
 *   - RSI 회복 퇴장 추가 (RSI > 55 = 과매도에서 중립 복귀)
 *   - 진입 필터 완화: BB 근접 범위 확대 (1.005→1.01)
 *   - 양봉 바디 비율 완화 (0.20→0.15)
 *
 * ■ ERT와의 시너지:
 *   - ERT: 추세장(ADX > 20, EMA 정배열)에서 작동
 *   - BMR: 횡보장(ADX < 25)에서 작동
 *   → 서로 다른 시장 상태를 커버하여 전천후 대응 가능
 *
 * ■ 권장: 60분봉, TP 2%, SL 1.5%, minConfidence 6
 */
public class BollingerRsiMeanReversionStrategy implements TradingStrategy {

    // ── 볼린저 밴드 설정 ──
    private static final int BB_PERIOD = 20;
    private static final double BB_STD_MULT = 2.0;

    // ── 진입 필터 임계치 ──
    private static final double ADX_MAX = 25.0;         // 횡보 판단 기준
    private static final double RSI_MAX_ENTRY = 35.0;   // 과매도 확인
    private static final double BB_PROXIMITY = 1.01;     // BB 하단 근접 범위 (1%)
    private static final double MIN_BB_WIDTH_PCT = 0.02;  // 최소 BB 폭 (2% 이상이어야 수익 공간)
    private static final double MIN_VOL_RATIO = 0.5;     // 최소 거래량 비율
    private static final double MIN_BODY_RATIO = 0.15;   // 최소 양봉 바디

    // ── 청산 설정 ──
    private static final double ADX_EXIT_THRESHOLD = 40.0;  // ADX 40 이상 → 강한 추세전환 청산
    private static final double RSI_EXIT_RECOVERY = 55.0;   // RSI 55 이상 = 과매도 탈출 → 익절

    // ── 캔들 요구량 ──
    private static final int MIN_CANDLES = 55;

    @Override
    public StrategyType type() {
        return StrategyType.BOLLINGER_RSI_MEAN_REVERSION;
    }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        List<UpbitCandle> candles = ctx.candles;
        int n = candles.size();
        if (n < MIN_CANDLES) return Signal.none();

        UpbitCandle last = candles.get(n - 1);
        UpbitCandle prev = candles.get(n - 2);
        double close = last.trade_price;
        double open = last.opening_price;

        // ── 기술적 지표 계산 ──
        double[] bb = Indicators.bollinger(candles, BB_PERIOD, BB_STD_MULT);
        double bbLower = bb[0];
        double bbMiddle = bb[1];
        double bbUpper = bb[2];
        double rsi = Indicators.rsi(candles, 14);
        double adx = Indicators.adx(candles, 14);
        double atr = Indicators.atr(candles, 14);
        double volSma = Indicators.smaVolume(candles, 20);
        double volRatio = volSma > 0 ? last.candle_acc_trade_volume / volSma : 0;

        // BB 폭 (%) = (Upper - Lower) / Middle
        double bbWidth = bbMiddle > 0 ? (bbUpper - bbLower) / bbMiddle : 0;

        // MACD 히스토그램
        double[] macdHistRecent = Indicators.macdHistogramRecent(candles, 12, 26, 9, 3);

        // ══════════════════════════════════════════════
        // ■ 매도 로직 (포지션 보유 시)
        // ══════════════════════════════════════════════
        boolean hasPosition = ctx.position != null
                && ctx.position.getQty() != null
                && ctx.position.getQty().compareTo(java.math.BigDecimal.ZERO) > 0;

        if (hasPosition) {
            // 이 전략이 진입한 포지션만 매도 (다른 전략 포지션 간섭 방지)
            String entryStrat = ctx.position.getEntryStrategy();
            boolean isMyPosition = type().name().equals(entryStrat);
            if (!isMyPosition) return Signal.none();

            double avgPrice = ctx.position.getAvgPrice().doubleValue();
            double pnlPct = (close - avgPrice) / avgPrice;

            // 1) BB 중앙선 도달 + 수익 → 평균회귀 완료 = 익절
            if (close >= bbMiddle && pnlPct > 0.005) {
                String reason = String.format(Locale.ROOT,
                        "BMR_MEAN_REVERT close≥bbMid(%.0f) pnl=%.2f%%",
                        bbMiddle, pnlPct * 100);
                return Signal.of(SignalAction.SELL, type(), reason);
            }

            // 2) RSI 회복 (55 이상) + 수익 → 과매도에서 정상 복귀
            if (rsi >= RSI_EXIT_RECOVERY && pnlPct > 0.005) {
                String reason = String.format(Locale.ROOT,
                        "BMR_RSI_RECOVERY rsi=%.1f(>%.0f) pnl=%.2f%%",
                        rsi, RSI_EXIT_RECOVERY, pnlPct * 100);
                return Signal.of(SignalAction.SELL, type(), reason);
            }

            // 3) 강한 추세 전환 (ADX > 40) → 확실히 횡보가 아님, 탈출
            if (adx > ADX_EXIT_THRESHOLD) {
                String reason = String.format(Locale.ROOT,
                        "BMR_REGIME_SHIFT adx=%.1f(>%.0f) pnl=%.2f%%",
                        adx, ADX_EXIT_THRESHOLD, pnlPct * 100);
                return Signal.of(SignalAction.SELL, type(), reason);
            }

            // 4) BB 상단 돌파 → 평균회귀를 넘어서 과매수 진입, 수익 확정
            if (close >= bbUpper && pnlPct > 0) {
                String reason = String.format(Locale.ROOT,
                        "BMR_BB_UPPER close≥bbUp(%.0f) pnl=%.2f%%",
                        bbUpper, pnlPct * 100);
                return Signal.of(SignalAction.SELL, type(), reason);
            }

            return Signal.none();
        }

        // ══════════════════════════════════════════════
        // ■ 매수 로직 (포지션 없을 때)
        // ══════════════════════════════════════════════

        // ── 하드 필터 ──

        // 1) 횡보장 확인: ADX < 25
        if (adx >= ADX_MAX) return Signal.none();

        // 2) RSI 과매도: RSI < 35
        if (rsi >= RSI_MAX_ENTRY) return Signal.none();

        // 3) BB 하단 터치 또는 근접: close <= BB Lower * 1.01
        if (close > bbLower * BB_PROXIMITY) return Signal.none();

        // 4) 양봉 확인 (반등 시작)
        boolean isBullish = close > open;
        double bodyRange = last.high_price - last.low_price;
        double bodyRatio = bodyRange > 0 ? Math.abs(close - open) / bodyRange : 0;
        if (!isBullish || bodyRatio < MIN_BODY_RATIO) return Signal.none();

        // 5) BB 폭 체크 (너무 좁으면 수익 공간 없음)
        if (bbWidth < MIN_BB_WIDTH_PCT) return Signal.none();

        // 6) 거래량 체크
        if (volRatio < MIN_VOL_RATIO) return Signal.none();

        // 7) ATR 최소 변동성
        double atrPct = close > 0 ? atr / close : 0;
        if (atrPct < 0.002) return Signal.none();

        // 8) MACD 바닥 확인: MACD 히스토그램이 아직 하락 중이어야 함 (진짜 바닥)
        //    MACD가 이미 개선 중이면 = 반등이 이미 시작됨 → 데드캣 바운스 위험
        boolean macdImproving = macdHistRecent.length >= 2
                && macdHistRecent[macdHistRecent.length - 1] > macdHistRecent[macdHistRecent.length - 2];
        if (macdImproving) return Signal.none();

        // ── 추가 컨텍스트 ──

        // 9) 이전 캔들이 음봉이었는지 확인 (매도 압력 후 반전)
        boolean prevBearish = prev.trade_price < prev.opening_price;

        // 10) RSI가 최근 N봉 내 최저인지 (추가 과매도 확인)
        double prevRsi = 50;
        if (n >= 5) {
            // 3봉 전까지의 RSI를 대략적으로 비교 (현재 RSI가 더 낮으면 추가 점수)
            List<UpbitCandle> subPrev = candles.subList(0, n - 2);
            if (subPrev.size() >= 15) {
                prevRsi = Indicators.rsi(subPrev, 14);
            }
        }
        boolean rsiAtBottom = rsi < prevRsi;

        // ── 신뢰도 점수 ──
        double score = 5.0;

        // RSI 깊이 (더 과매도일수록 높은 점수)
        if (rsi < 20) score += 2.0;
        else if (rsi < 25) score += 1.5;
        else if (rsi < 30) score += 1.0;
        else score += 0.3;

        // BB 하단 대비 위치 (더 아래일수록 높은 점수)
        double bbDist = (bbLower - close) / close;  // 양수면 BB 아래
        if (bbDist > 0.01) score += 1.5;            // BB 1% 이상 아래
        else if (bbDist > 0.005) score += 1.0;
        else score += 0.5;                           // BB 근접 (0.5로 상향)

        // 거래량
        if (volRatio >= 2.0) score += 1.0;
        else if (volRatio >= 1.0) score += 0.5;
        else score += 0.1;

        // 이전 봉 음봉 (매도 후 반전 패턴)
        if (prevBearish) score += 0.5;

        // RSI 바닥 확인
        if (rsiAtBottom) score += 0.5;

        // BB 폭 적정 (3-8% = 적절한 변동성 범위)
        if (bbWidth >= 0.03 && bbWidth <= 0.08) score += 0.5;

        // ADX가 매우 낮으면 추가 점수 (완전한 횡보)
        if (adx < 15) score += 0.5;

        score = Math.min(10.0, score);

        String reason = String.format(Locale.ROOT,
                "BMR_BUY bb_low=%.0f bb_mid=%.0f adx=%.1f rsi=%.1f vol=%.1fx bbW=%.2f%% [score=%.1f]",
                bbLower, bbMiddle, adx, rsi, volRatio, bbWidth * 100, score);

        return Signal.of(SignalAction.BUY, type(), reason, score);
    }
}
