package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * RSI 과매도 반등 스캘핑 전략 (5분봉)
 *
 * ═══════════════════════════════════════════════════════════
 *  RSI(7) 극단적 과매도 → 볼린저 하단 반등 + 거래량 동반 양봉 매수
 *  평균 회귀(BB 중간선) 또는 ATR 기반 TP/SL/트레일링으로 청산
 * ═══════════════════════════════════════════════════════════
 *
 * ■ 원리:
 *   RSI가 극단적 과매도(28 이하)에 진입하면 단기 반등 확률이 높음.
 *   볼린저 하단 위에서 양봉이 형성되고 거래량이 동반되면 반등 시작으로 판단.
 *   EMA 스프레드가 2% 이하일 때만 진입하여 자유낙하 구간을 회피.
 *
 * ■ 진입 조건 (모두 충족):
 *   1) RSI(7) < 28
 *   2) 현재 봉 양봉
 *   3) 종가 > BB 하단밴드
 *   4) 거래량 >= 0.7 × 20봉 평균거래량
 *   5) |EMA8 - EMA21| / EMA21 < 2%
 *
 * ■ 청산 조건 (순서대로):
 *   1) 하드 SL: 저가 <= 진입가 - 1.0×ATR
 *   2) 하드 TP: 고가 >= 진입가 + 2.5×ATR
 *   3) 평균 회귀 완료: 종가 > BB 중간선 AND pnl > 0.3%
 *   4) 트레일링 스탑: 피크 - 1.5×ATR (이익 구간에서만)
 *   5) 타임아웃: 30봉 이상 보유 AND pnl < 0.3%
 */
public class ScalpRsiBounceStrategy implements TradingStrategy {

    // ===== 데이터 요구 =====
    private static final int MIN_CANDLES = 40;

    // ===== RSI =====
    private static final int RSI_PERIOD = 7;
    private static final double RSI_THRESHOLD = 28.0;

    // ===== 볼린저 밴드 =====
    private static final int BB_PERIOD = 20;
    private static final double BB_STD_MULT = 2.0;

    // ===== EMA =====
    private static final int EMA_FAST = 8;
    private static final int EMA_SLOW = 21;
    private static final double MAX_EMA_SPREAD_PCT = 2.0;

    // ===== ATR =====
    private static final int ATR_PERIOD = 10;

    // ===== TP/SL/트레일링 (ATR 배수) =====
    private static final double TP_ATR_MULT = 2.5;
    private static final double SL_ATR_MULT = 1.0;
    private static final double TRAIL_ATR_MULT = 1.5;

    // ===== 거래량 =====
    private static final int VOLUME_AVG_PERIOD = 20;
    private static final double VOLUME_THRESHOLD = 0.7;

    // ===== 타임아웃 =====
    private static final int TIMEOUT_CANDLES = 30;
    private static final double TIMEOUT_MIN_PNL = 0.3;

    @Override
    public StrategyType type() {
        return StrategyType.SCALP_RSI_BOUNCE;
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

    private Signal evaluateEntry(List<UpbitCandle> candles, UpbitCandle last, double close) {

        // ── 1. RSI(7) < 28 ──
        double rsi = Indicators.rsi(candles, RSI_PERIOD);
        if (Double.isNaN(rsi) || rsi >= RSI_THRESHOLD) return Signal.none();

        // ── 2. 현재 봉 양봉 ──
        if (!CandlePatterns.isBullish(last)) return Signal.none();

        // ── 3. 종가 > BB 하단밴드 ──
        double[] bb = Indicators.bollinger(candles, BB_PERIOD, BB_STD_MULT);
        double bbLower = bb[0];
        double bbMiddle = bb[1];
        if (bbMiddle <= 0) return Signal.none();
        if (close <= bbLower) return Signal.none();

        // ── 4. 거래량 >= 0.7 × 20봉 평균 ──
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double curVol = last.candle_acc_trade_volume;
        if (avgVol > 0 && curVol < avgVol * VOLUME_THRESHOLD) return Signal.none();

        // ── 5. EMA 스프레드 < 2% (자유낙하 방지) ──
        double emaFast = Indicators.ema(candles, EMA_FAST);
        double emaSlow = Indicators.ema(candles, EMA_SLOW);
        if (emaSlow <= 0) return Signal.none();
        double emaSpreadPct = Math.abs(emaFast - emaSlow) / emaSlow * 100.0;
        if (emaSpreadPct >= MAX_EMA_SPREAD_PCT) return Signal.none();

        // ── Confidence 산출 ──
        double score = 4.0;

        // (1) RSI 극단성
        if (rsi < 20.0) {
            score += 1.5;
        } else if (rsi < 24.0) {
            score += 1.0;
        } else {
            score += 0.3;
        }

        // (2) 거래량 비율
        double volRatio = avgVol > 0 ? curVol / avgVol : 1.0;
        if (volRatio >= 2.0) {
            score += 1.5;
        } else if (volRatio >= 1.3) {
            score += 1.0;
        } else {
            score += 0.3;
        }

        // (3) 캔들 몸통 강도
        double range = CandlePatterns.range(last);
        double bodyRatio = range > 0 ? CandlePatterns.body(last) / range : 0;
        if (bodyRatio >= 0.6) {
            score += 1.0;
        } else if (bodyRatio >= 0.4) {
            score += 0.5;
        }

        // (4) EMA8 근접도 (종가가 EMA8의 0.5% 이내)
        if (emaFast > 0 && Math.abs(close - emaFast) / emaFast * 100.0 <= 0.5) {
            score += 1.0;
        }

        score = Math.min(10.0, score);

        String reason = String.format(Locale.ROOT,
                "SCALP_RSI_BUY rsi=%.1f bb_lower=%.2f close=%.2f vol=%.1fx ema_spread=%.2f%%",
                rsi, bbLower, close, volRatio, emaSpreadPct);
        return Signal.of(SignalAction.BUY, type(), reason, score);
    }

    private Signal evaluateExit(StrategyContext ctx, List<UpbitCandle> candles,
                                UpbitCandle last, double close) {
        double avgPrice = ctx.position.getAvgPrice().doubleValue();
        if (avgPrice <= 0) return Signal.none();

        double atr = Indicators.atr(candles, ATR_PERIOD);
        double pnlPct = ((close - avgPrice) / avgPrice) * 100.0;

        // ── 1. 하드 SL: 저가 <= 진입가 - 1.0×ATR ──
        double hardSL = avgPrice - SL_ATR_MULT * atr;
        if (last.low_price <= hardSL) {
            String reason = String.format(Locale.ROOT,
                    "SCALP_RSI_SL avg=%.2f close=%.2f pnl=%.2f%%", avgPrice, close, pnlPct);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // ── 2. 하드 TP: 고가 >= 진입가 + 2.5×ATR ──
        double hardTP = avgPrice + TP_ATR_MULT * atr;
        if (last.high_price >= hardTP) {
            double tpPnl = ((hardTP - avgPrice) / avgPrice) * 100.0;
            String reason = String.format(Locale.ROOT,
                    "SCALP_RSI_TP avg=%.2f close=%.2f pnl=%.2f%%", avgPrice, close, tpPnl);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // ── 3. 평균 회귀 완료: 종가 > BB 중간선 AND pnl > 0.3% ──
        double[] bb = Indicators.bollinger(candles, BB_PERIOD, BB_STD_MULT);
        double bbMiddle = bb[1];
        if (bbMiddle > 0 && close > bbMiddle && pnlPct > TIMEOUT_MIN_PNL) {
            String reason = String.format(Locale.ROOT,
                    "SCALP_RSI_MR avg=%.2f close=%.2f pnl=%.2f%%", avgPrice, close, pnlPct);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // ── 4. 트레일링 스탑: 피크 - 1.5×ATR (이익 구간에서만) ──
        if (pnlPct > 0) {
            double peak = Indicators.peakHighSinceEntry(candles, avgPrice);
            double trailStop = peak - TRAIL_ATR_MULT * atr;
            if (close <= trailStop) {
                String reason = String.format(Locale.ROOT,
                        "SCALP_RSI_TRAIL avg=%.2f close=%.2f pnl=%.2f%%", avgPrice, close, pnlPct);
                return Signal.of(SignalAction.SELL, type(), reason);
            }
        }

        // ── 5. 타임아웃: 30봉 이상 보유 AND pnl < 0.3% ──
        if (pnlPct < TIMEOUT_MIN_PNL) {
            // 진입가 이상인 캔들 수를 세서 보유 기간 근사
            int heldCandles = 0;
            for (int i = candles.size() - 1; i >= 0; i--) {
                if (candles.get(i).low_price <= avgPrice) {
                    break;
                }
                heldCandles++;
            }
            // 보수적 근사: 전체 캔들에서 진입 시점 추정
            if (heldCandles == 0) {
                // 진입가 근처 캔들을 역순으로 찾아 보유 기간 추정
                for (int i = candles.size() - 1; i >= 0; i--) {
                    double candleClose = candles.get(i).trade_price;
                    if (Math.abs(candleClose - avgPrice) / avgPrice < 0.005) {
                        heldCandles = candles.size() - 1 - i;
                        break;
                    }
                }
            }
            if (heldCandles >= TIMEOUT_CANDLES) {
                String reason = String.format(Locale.ROOT,
                        "SCALP_RSI_TIMEOUT avg=%.2f close=%.2f pnl=%.2f%%", avgPrice, close, pnlPct);
                return Signal.of(SignalAction.SELL, type(), reason);
            }
        }

        return Signal.none();
    }
}
