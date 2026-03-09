package com.example.upbit.strategy;

import java.util.List;
import java.util.Locale;

/**
 * 공통 신호 평가 로직.
 *
 * BacktestService와 TradingBotService가 동일한 판단 로직을 사용하도록
 * STEP 1(TP/SL)과 STEP 2(전략 평가)를 한 곳에서 관리합니다.
 *
 * "판단"만 담당하고, "실행"(매도 처리, DB 저장, 주문)은 각 서비스가 처리합니다.
 */
public final class SignalEvaluator {

    private SignalEvaluator() {}

    /**
     * 평가 결과.
     */
    public static class Result {
        public final Signal signal;          // 최종 신호 (null이면 아무 것도 안 함)
        public final StrategyType strategyType; // 전략 타입 (TP/SL이면 null)
        public final String patternType;     // "TAKE_PROFIT", "STOP_LOSS", 또는 전략 enum name
        public final String reason;          // 사유 문자열
        public final boolean isTpSl;         // TP/SL에 의한 매도인지 여부
        public final double confidence;      // 패턴 명확도 점수 (1~10)

        public Result(Signal signal, StrategyType strategyType, String patternType, String reason, boolean isTpSl) {
            this.signal = signal;
            this.strategyType = strategyType;
            this.patternType = patternType;
            this.reason = reason;
            this.isTpSl = isTpSl;
            this.confidence = signal != null ? signal.confidence : 0;
        }

        public boolean isEmpty() { return signal == null || signal.action == SignalAction.NONE; }
        public boolean isSell() { return signal != null && signal.action == SignalAction.SELL; }
        public boolean isBuy() { return signal != null && signal.action == SignalAction.BUY; }
        public boolean isAddBuy() { return signal != null && signal.action == SignalAction.ADD_BUY; }
    }

    /** 아무 신호 없음 */
    private static final Result EMPTY = new Result(null, null, null, null, false);

    /**
     * STEP 1: TP/SL 체크.
     * 포지션이 열려 있고 TP/SL 조건에 도달하면 SELL 신호를 반환합니다.
     * 미도달이면 null을 반환합니다.
     *
     * @param open     포지션 보유 여부
     * @param avgPrice 평균 매수가
     * @param close    현재 종가
     * @param tpPct    Take Profit % (0이면 비활성)
     * @param slPct    Stop Loss % (0이면 비활성)
     */
    public static Result checkTpSl(boolean open, double avgPrice, double close, double tpPct, double slPct) {
        if (!open || avgPrice <= 0) return null;

        double pnlPct = ((close - avgPrice) / avgPrice) * 100.0;

        if (slPct > 0 && pnlPct <= -slPct) {
            String reason = String.format(Locale.ROOT, "STOP_LOSS %.2f%% (pnl=%.2f%%)", slPct, pnlPct);
            Signal sig = Signal.of(SignalAction.SELL, null, reason);
            return new Result(sig, null, "STOP_LOSS", reason, true);
        }

        if (tpPct > 0 && pnlPct >= tpPct) {
            String reason = String.format(Locale.ROOT, "TAKE_PROFIT %.2f%% (pnl=%.2f%%)", tpPct, pnlPct);
            Signal sig = Signal.of(SignalAction.SELL, null, reason);
            return new Result(sig, null, "TAKE_PROFIT", reason, true);
        }

        return null; // TP/SL 미발동
    }

    /**
     * STEP 2: 전략 평가.
     * 여러 전략을 동시에 평가하고 우선순위(SELL > ADD_BUY > BUY)로 1개 액션을 선택합니다.
     *
     * @param stypes          활성화된 전략 타입 목록
     * @param strategyFactory 전략 팩토리
     * @param ctx             전략 컨텍스트
     */
    public static Result evaluateStrategies(List<StrategyType> stypes, StrategyFactory strategyFactory, StrategyContext ctx) {
        Signal chosen = null;
        StrategyType chosenType = null;

        for (StrategyType t : stypes) {
            TradingStrategy s = strategyFactory.get(t);
            Signal sig = s.evaluate(ctx);
            if (sig == null || sig.action == SignalAction.NONE) continue;

            if (chosen == null) {
                chosen = sig;
                chosenType = t;
            } else {
                int pNew = priority(sig.action);
                int pCur = priority(chosen.action);
                // 동일 우선순위면 confidence가 높은 것을 선택
                if (pNew > pCur || (pNew == pCur && sig.confidence > chosen.confidence)) {
                    chosen = sig;
                    chosenType = t;
                }
            }

            // SELL을 발견하면 더 볼 필요 없음
            if (chosen.action == SignalAction.SELL) break;
        }

        if (chosen == null || chosen.action == SignalAction.NONE) return EMPTY;

        String patternType = (chosen.type == null
                ? (chosenType == null ? "UNKNOWN" : chosenType.name())
                : chosen.type.name());
        String reason = (chosenType == null ? "" : ("[" + chosenType.name() + "] "))
                + (chosen.reason == null ? "" : chosen.reason)
                + String.format(Locale.ROOT, " [score=%.1f]", chosen.confidence);

        return new Result(chosen, chosenType, patternType, reason, false);
    }

    /**
     * STEP 1 + STEP 2를 한번에 수행합니다.
     * TP/SL이 발동하면 전략 평가를 스킵합니다.
     */
    public static Result evaluate(boolean open, double avgPrice, double close,
                                  double tpPct, double slPct,
                                  List<StrategyType> stypes, StrategyFactory strategyFactory,
                                  StrategyContext ctx) {
        // STEP 1: TP/SL 최우선
        Result tpSlResult = checkTpSl(open, avgPrice, close, tpPct, slPct);
        if (tpSlResult != null) return tpSlResult;

        // STEP 2: 전략 평가
        return evaluateStrategies(stypes, strategyFactory, ctx);
    }

    /** 액션 우선순위: SELL(3) > ADD_BUY(2) > BUY(1) */
    private static int priority(SignalAction a) {
        if (a == null) return 0;
        switch (a) {
            case SELL: return 3;
            case ADD_BUY: return 2;
            case BUY: return 1;
            default: return 0;
        }
    }
}
