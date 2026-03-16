package com.example.upbit.strategy;

/**
 * 유튜브(슈퍼트레이더) 스크립트 기준 캔들 패턴 매매유형.
 *
 * 주의:
 * - 업비트 현물(롱 only) 기준으로 구현합니다.
 * - 따라서 "하락 신호" 패턴은 '포지션 보유 중이면 청산(SELL)' 신호로 사용하고,
 *   포지션이 없을 때 공매도(숏)는 하지 않습니다.
 */
public enum StrategyType {

    // === 기존(백업) === [DEPRECATED: 백테스트 445건 검증 결과 거래 0건]
    @Deprecated
    CONSECUTIVE_DOWN_REBOUND,

    // [1] 장악형 캔들
    BULLISH_ENGULFING_CONFIRM,   // 상승 장악형 + 3번째 양봉 확인
    BEARISH_ENGULFING,           // 하락 장악형 (보유 중이면 청산)

    // [2] 모멘텀 캔들 + 페어밸류갭(FVG) 되돌림 매수
    MOMENTUM_FVG_PULLBACK,

    // [3] 핀바(핀바/핀바캔들)
    BULLISH_PINBAR_ORDERBLOCK,

    // [4] 인사이드바(마더바 내부) 돌파 + 거래량 필터
    INSIDE_BAR_BREAKOUT,

    // [5] 삼법형(3 Methods) + 20EMA 필터 [DEPRECATED: 백테스트 445건 검증 결과 거래 0건]
    @Deprecated
    THREE_METHODS_BULLISH,
    THREE_METHODS_BEARISH,       // 하락 삼법형 (보유 중이면 청산)

    // [6] 모닝스타/이브닝스타
    MORNING_STAR,
    EVENING_STAR_SELL,           // 보유 중이면 청산

    // [7] 적삼병/흑삼병 [DEPRECATED: 백테스트 445건 검증 결과 거래 0건]
    @Deprecated
    THREE_WHITE_SOLDIERS,
    THREE_BLACK_CROWS_SELL,      // 보유 중이면 청산

    // [8] 추세 필터 + 눌림 매수 + ATR 손절/익절
    REGIME_PULLBACK,             // Regime-Filtered Pullback (매수+매도 통합)

    // [9] 5중 확인 추세 모멘텀 + Chandelier Exit
    ADAPTIVE_TREND_MOMENTUM,      // Adaptive Trend Momentum (매수+매도 통합)

    // [10] 스캘핑 모멘텀 (BUY-ONLY: EMA8/21 + RSI + 거래량) [DEPRECATED: 승률 10~30%, 전 코인 손실]
    @Deprecated
    SCALP_MOMENTUM,               // Scalp Momentum (매수 전용 → TP/SL/매도전략에 청산 위임)

    // [11] EMA-RSI 추세 추종 (BUY-ONLY: RSI 눌림 + 돌파 매수)
    EMA_RSI_TREND,                // EMA-RSI Trend (매수 전용 → TP/SL/매도전략에 청산 위임)

    // [12] 볼린저 밴드 + RSI 평균회귀 (횡보장 특화, 자급자족) [DEPRECATED: 거래 1~2건, 암호화폐 부적합]
    @Deprecated
    BOLLINGER_RSI_MEAN_REVERSION, // BMR: BB Lower + RSI 과매도 매수 → BB Middle 익절

    // [13] 쓰리 마켓 패턴 (이중 가짜돌파 → 신고가 돌파 매수, 자급자족)
    THREE_MARKET_PATTERN,

    // [14] 볼린저 밴드 스퀴즈 돌파 (스퀴즈→확장 진입, 10SMA 청산, 자급자족)
    BOLLINGER_SQUEEZE_BREAKOUT,

    // [15] 삼각수렴 돌파 (삼각형 수렴 → 거래량 동반 돌파 진입, 자급자족)
    TRIANGLE_CONVERGENCE,

    // ===== 단타(스캘핑) 전략 =====

    // [16] RSI 과매도 반등 스캘핑 (5분봉, 자급자족)
    SCALP_RSI_BOUNCE,

    // [17] EMA 눌림 스캘핑 (5분봉, 자급자족)
    SCALP_EMA_PULLBACK,

    // [18] 레인지 돌파 스캘핑 (15분봉, 자급자족)
    SCALP_BREAKOUT_RANGE,

    // [19] 9시 오프닝 레인지 돌파 스캘핑 (5분봉, 자급자족)
    SCALP_OPENING_BREAK,

    // [20] 다중 확인 고확신 모멘텀 (15~60분봉, 자급자족)
    MULTI_CONFIRM_MOMENTUM;

    /**
     * 매도 전용 전략인지 판별.
     * 매도 전용 전략은 Strategy Lock 활성 시에도 매도가 허용됩니다.
     * (매수 기능이 없으므로 Lock으로 차단하면 영원히 매도 불가)
     */
    public boolean isSellOnly() {
        switch (this) {
            case BEARISH_ENGULFING:
            case EVENING_STAR_SELL:
            case THREE_BLACK_CROWS_SELL:
            case THREE_METHODS_BEARISH:
                return true;
            default:
                return false;
        }
    }

    /**
     * 자급자족(매수+매도 통합) 전략인지 판별.
     */
    public boolean isSelfContained() {
        switch (this) {
            case ADAPTIVE_TREND_MOMENTUM:
            case REGIME_PULLBACK:
            case CONSECUTIVE_DOWN_REBOUND:
            case BOLLINGER_RSI_MEAN_REVERSION:
            case THREE_MARKET_PATTERN:
            case BOLLINGER_SQUEEZE_BREAKOUT:
            case TRIANGLE_CONVERGENCE:
            case SCALP_RSI_BOUNCE:
            case SCALP_EMA_PULLBACK:
            case SCALP_BREAKOUT_RANGE:
            case SCALP_OPENING_BREAK:
            case MULTI_CONFIRM_MOMENTUM:
                return true;
            // SCALP_MOMENTUM, EMA_RSI_TREND는 BUY-ONLY (isBuyOnly=true)
            default:
                return false;
        }
    }

    /**
     * 매수 전용 전략인지 판별.
     * 매수 전용 전략으로 진입한 포지션은 Time Stop 적용 대상입니다.
     */
    public boolean isBuyOnly() {
        return !isSellOnly() && !isSelfContained();
    }

    /**
     * 백테스트 검증 결과 실전 성과가 없는 전략인지 판별.
     * - 445건 백테스트(13전략×5코인×7시장국면) 결과 거래 0건 또는 일관된 손실
     * - UI에서 "(삭제예정)" 표시 및 셀렉트박스 하단 배치
     */
    public boolean isDeprecated() {
        switch (this) {
            case CONSECUTIVE_DOWN_REBOUND:       // 5개 코인 전부 거래 0건
            case THREE_METHODS_BULLISH:          // 5개 코인 전부 거래 0건
            case THREE_WHITE_SOLDIERS:           // 5개 코인 전부 거래 0건
            case BOLLINGER_RSI_MEAN_REVERSION:   // 거의 거래 없음 (1~2건)
            case SCALP_MOMENTUM:                 // 거래 다수이나 승률 10~30%, 전 코인 손실
                return true;
            default:
                return false;
        }
    }

    /**
     * EMA 트렌드 필터 설정 모드.
     * CONFIGURABLE: 사용자가 EMA 기간을 선택 가능 (기본 50)
     * INTERNAL: 전략 내부에서 다중 EMA를 자체 관리 (설정 불가)
     * NONE: EMA 트렌드 필터 미사용 (매도 전용 또는 EMA 없음)
     */
    public String emaTrendFilterMode() {
        switch (this) {
            case BULLISH_ENGULFING_CONFIRM:
            case MOMENTUM_FVG_PULLBACK:
            case BULLISH_PINBAR_ORDERBLOCK:
            case INSIDE_BAR_BREAKOUT:
            case THREE_METHODS_BULLISH:
            case MORNING_STAR:
            case THREE_WHITE_SOLDIERS:
            case CONSECUTIVE_DOWN_REBOUND:
                return "CONFIGURABLE";
            case REGIME_PULLBACK:
            case ADAPTIVE_TREND_MOMENTUM:
            case SCALP_MOMENTUM:
            case EMA_RSI_TREND:
            case BOLLINGER_RSI_MEAN_REVERSION:
            case THREE_MARKET_PATTERN:
            case BOLLINGER_SQUEEZE_BREAKOUT:
            case TRIANGLE_CONVERGENCE:
            case SCALP_RSI_BOUNCE:
            case SCALP_EMA_PULLBACK:
            case SCALP_BREAKOUT_RANGE:
            case SCALP_OPENING_BREAK:
            case MULTI_CONFIRM_MOMENTUM:
                return "INTERNAL";
            default:
                return "NONE";
        }
    }

    /**
     * EMA 트렌드 필터 권장 기간 (CONFIGURABLE 전략 전용).
     */
    public int recommendedEmaPeriod() {
        return "CONFIGURABLE".equals(emaTrendFilterMode()) ? 50 : 0;
    }

    /**
     * 전략별 권장 캔들 인터벌(분).
     * - 캔들 패턴 전략: 4시간(240분) — 짧은 분봉에서는 노이즈가 많아 false positive 급증
     * - 지표 기반 자급자족 전략: 60분 — EMA/RSI/ADX 등 지표는 60분 이상에서 안정적
     * - 연속 하락 반등: 60분 — 단순 패턴이라 다양한 분봉에서 작동 가능
     *
     * ※ 이 값은 UI에서 "권장값"으로 표시되며, 실제 사용 인터벌은 사용자 설정에 따릅니다.
     */
    public int recommendedIntervalMin() {
        switch (this) {
            // 캔들 패턴 전략: 4시간봉 권장
            case BULLISH_ENGULFING_CONFIRM:
            case BEARISH_ENGULFING:
            case MOMENTUM_FVG_PULLBACK:
            case BULLISH_PINBAR_ORDERBLOCK:
            case INSIDE_BAR_BREAKOUT:
            case THREE_METHODS_BULLISH:
            case THREE_METHODS_BEARISH:
            case MORNING_STAR:
            case EVENING_STAR_SELL:
            case THREE_WHITE_SOLDIERS:
            case THREE_BLACK_CROWS_SELL:
                return 240;

            // 지표 기반 전략: 60분봉 권장
            case ADAPTIVE_TREND_MOMENTUM:
            case REGIME_PULLBACK:
            case CONSECUTIVE_DOWN_REBOUND:
            case EMA_RSI_TREND:
            case BOLLINGER_RSI_MEAN_REVERSION:
            case THREE_MARKET_PATTERN:
            case BOLLINGER_SQUEEZE_BREAKOUT:
            case TRIANGLE_CONVERGENCE:
                return 60;

            // 스캘핑 전략
            case SCALP_MOMENTUM:
                return 15;
            case SCALP_RSI_BOUNCE:
            case SCALP_EMA_PULLBACK:
                return 5;
            case SCALP_BREAKOUT_RANGE:
                return 15;
            case SCALP_OPENING_BREAK:
                return 5;

            // 다중 확인 고확신 모멘텀
            case MULTI_CONFIRM_MOMENTUM:
                return 30;

            default:
                return 60;
        }
    }
}
