package com.example.upbit.strategy;

/**
 * 전략이 판단한 신호.
 * - action: BUY/ADD_BUY/SELL/NONE
 * - type: 어떤 전략/패턴으로 판단했는지(화면의 '매매유형' 컬럼)
 * - reason: 사람이 이해할 수 있는 짧은 사유(로그)
 * - confidence: 패턴 명확도 점수 (1.0~10.0). 0이면 점수 미산출(TP/SL, 리스크 관리 등)
 */
public class Signal {
    public final SignalAction action;
    public final StrategyType type;
    public final String reason;
    /** 패턴 명확도 점수. 1.0~10.0 범위. 0이면 점수 미산출(TP/SL, ADD_BUY 등). */
    public final double confidence;

    private Signal(SignalAction action, StrategyType type, String reason, double confidence) {
        this.action = action;
        this.type = type;
        this.reason = reason;
        // confidence가 0이면 "미산출"로 유지, 0보다 크면 1~10 범위로 클램핑
        this.confidence = (confidence <= 0) ? 0 : Math.max(1.0, Math.min(10.0, confidence));
    }

    public static Signal none() {
        return new Signal(SignalAction.NONE, null, null, 0);
    }

    /** rejection reason 포함 NONE 신호 (디버깅/Decision Log용) */
    public static Signal none(String reason) {
        return new Signal(SignalAction.NONE, null, reason, 0);
    }

    /** confidence 없는 신호 (TP/SL, 리스크 관리 매도, ADD_BUY 등) */
    public static Signal of(SignalAction action, StrategyType type, String reason) {
        return new Signal(action, type, reason, 0);
    }

    /** confidence 포함 신호 (패턴 기반 진입/청산) */
    public static Signal of(SignalAction action, StrategyType type, String reason, double confidence) {
        return new Signal(action, type, reason, confidence);
    }
}
