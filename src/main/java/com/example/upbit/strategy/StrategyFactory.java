package com.example.upbit.strategy;

import com.example.upbit.config.StrategyProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 전략 구현체를 관리/조회하는 팩토리.
 * - UI/백테스트에서 strategyType을 선택하면 여기서 구현체를 반환합니다.
 *
 * 전략 확장 방법:
 * 1) StrategyType enum에 항목 추가
 * 2) TradingStrategy 구현체 추가
 * 3) 여기 StrategyFactory에 등록
 *
 * 그러면 대시보드/백테스트/로그(매매유형 컬럼)에 자동으로 노출됩니다.
 */
@Component
public class StrategyFactory {

    private final Map<StrategyType, TradingStrategy> strategies = new EnumMap<>(StrategyType.class);

    @Autowired
    public StrategyFactory(StrategyProperties cfg) {
        // === Deprecated 전략 제거 (백테스트 검증 결과 성과 없음) ===
        // - CONSECUTIVE_DOWN_REBOUND: 거래 0건
        // - THREE_METHODS_BULLISH: 거래 0건
        // - THREE_WHITE_SOLDIERS: 거래 0건
        // - SCALP_MOMENTUM: 승률 10~30%, 전 코인 손실
        // - BOLLINGER_RSI_MEAN_REVERSION: 거래 1~2건, 암호화폐 부적합

        // 유튜브 스크립트 기반 전략들
        strategies.put(StrategyType.BULLISH_ENGULFING_CONFIRM, new BullishEngulfingConfirmStrategy());
        strategies.put(StrategyType.BEARISH_ENGULFING, new BearishEngulfingStrategy());

        strategies.put(StrategyType.MOMENTUM_FVG_PULLBACK, new MomentumFvgPullbackStrategy());
        strategies.put(StrategyType.BULLISH_PINBAR_ORDERBLOCK, new BullishPinbarOrderblockStrategy());
        strategies.put(StrategyType.INSIDE_BAR_BREAKOUT, new InsideBarBreakoutStrategy());
        strategies.put(StrategyType.THREE_METHODS_BEARISH, new ThreeMethodsBearishStrategy());

        strategies.put(StrategyType.MORNING_STAR, new MorningStarStrategy());
        strategies.put(StrategyType.EVENING_STAR_SELL, new EveningStarSellStrategy());

        strategies.put(StrategyType.THREE_BLACK_CROWS_SELL, new ThreeBlackCrowsSellStrategy());

        // 추세 필터 + 눌림 매수 + ATR 손절/익절
        strategies.put(StrategyType.REGIME_PULLBACK, new RegimeFilteredPullbackStrategy());

        // 5중 확인 추세 모멘텀 + Chandelier Exit
        strategies.put(StrategyType.ADAPTIVE_TREND_MOMENTUM, new AdaptiveTrendMomentumStrategy());

        // EMA-RSI 추세 추종
        strategies.put(StrategyType.EMA_RSI_TREND, new EmaRsiTrendStrategy());

        // 쓰리 마켓 패턴 (이중 가짜돌파 → 신고가 돌파)
        strategies.put(StrategyType.THREE_MARKET_PATTERN, new ThreeMarketPatternStrategy());

        // 볼린저 밴드 스퀴즈 돌파
        strategies.put(StrategyType.BOLLINGER_SQUEEZE_BREAKOUT, new BollingerSqueezeBreakoutStrategy());

        // 삼각수렴 돌파
        strategies.put(StrategyType.TRIANGLE_CONVERGENCE, new TriangleConvergenceStrategy());

        // 단타(스캘핑) 전략
        strategies.put(StrategyType.SCALP_RSI_BOUNCE, new ScalpRsiBounceStrategy());
        strategies.put(StrategyType.SCALP_EMA_PULLBACK, new ScalpEmaPullbackStrategy());
        strategies.put(StrategyType.SCALP_BREAKOUT_RANGE, new ScalpBreakoutRangeStrategy());

        // 오프닝 레인지 돌파 전략
        strategies.put(StrategyType.SCALP_OPENING_BREAK, new ScalpOpeningBreakStrategy());
    }

    public TradingStrategy get(StrategyType type) {
        TradingStrategy s = strategies.get(type);
        if (s == null) throw new IllegalArgumentException("Unknown or deprecated strategyType: " + type);
        return s;
    }

    /**
     * 팩토리에 등록된(활성) 전략인지 확인.
     * deprecated 전략은 등록되지 않으므로 false를 반환합니다.
     */
    public boolean isRegistered(StrategyType type) {
        return strategies.containsKey(type);
    }

    /**
     * 특정 전략을 오버라이드한 새 팩토리를 반환.
     * 원본은 변경하지 않음 (백테스트 파라미터 오버라이드용).
     *
     * 주의: no-arg 생성자를 사용하면 Spring이 그것을 선택하여
     * 빈 strategies 맵으로 빈을 생성하는 버그가 발생함.
     * 대신 EnumMap 파라미터를 받는 내부 생성자를 사용.
     */
    public StrategyFactory withOverride(StrategyType type, TradingStrategy override) {
        EnumMap<StrategyType, TradingStrategy> copy = new EnumMap<>(this.strategies);
        copy.put(type, override);
        return new StrategyFactory(copy);
    }

    /** 내부 복사용 — Spring이 사용할 수 없도록 EnumMap 파라미터 필수 */
    private StrategyFactory(EnumMap<StrategyType, TradingStrategy> source) {
        this.strategies.putAll(source);
    }
}
