package com.example.upbit.strategy;

import com.example.upbit.config.StrategyProperties;
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

    public StrategyFactory(StrategyProperties cfg) {
        // 기존 전략
        strategies.put(StrategyType.CONSECUTIVE_DOWN_REBOUND, new ConsecutiveDownReboundStrategy(cfg));

        // 유튜브 스크립트 기반 전략들
        strategies.put(StrategyType.BULLISH_ENGULFING_CONFIRM, new BullishEngulfingConfirmStrategy());
        strategies.put(StrategyType.BEARISH_ENGULFING, new BearishEngulfingStrategy());

        strategies.put(StrategyType.MOMENTUM_FVG_PULLBACK, new MomentumFvgPullbackStrategy());
        strategies.put(StrategyType.BULLISH_PINBAR_ORDERBLOCK, new BullishPinbarOrderblockStrategy());
        strategies.put(StrategyType.INSIDE_BAR_BREAKOUT, new InsideBarBreakoutStrategy());
        strategies.put(StrategyType.THREE_METHODS_BULLISH, new ThreeMethodsBullishStrategy());
        strategies.put(StrategyType.THREE_METHODS_BEARISH, new ThreeMethodsBearishStrategy());

        strategies.put(StrategyType.MORNING_STAR, new MorningStarStrategy());
        strategies.put(StrategyType.EVENING_STAR_SELL, new EveningStarSellStrategy());

        strategies.put(StrategyType.THREE_WHITE_SOLDIERS, new ThreeWhiteSoldiersStrategy());
        strategies.put(StrategyType.THREE_BLACK_CROWS_SELL, new ThreeBlackCrowsSellStrategy());

        // 추세 필터 + 눌림 매수 + ATR 손절/익절
        strategies.put(StrategyType.REGIME_PULLBACK, new RegimeFilteredPullbackStrategy());

        // 5중 확인 추세 모멘텀 + Chandelier Exit
        strategies.put(StrategyType.ADAPTIVE_TREND_MOMENTUM, new AdaptiveTrendMomentumStrategy());

        // 고빈도 스캘핑 모멘텀
        strategies.put(StrategyType.SCALP_MOMENTUM, new ScalpMomentumStrategy());

        // EMA-RSI 추세 추종
        strategies.put(StrategyType.EMA_RSI_TREND, new EmaRsiTrendStrategy());

        // 볼린저 밴드 + RSI 평균회귀 (횡보장 특화)
        strategies.put(StrategyType.BOLLINGER_RSI_MEAN_REVERSION, new BollingerRsiMeanReversionStrategy());

        // 쓰리 마켓 패턴 (이중 가짜돌파 → 신고가 돌파)
        strategies.put(StrategyType.THREE_MARKET_PATTERN, new ThreeMarketPatternStrategy());

        // 볼린저 밴드 스퀴즈 돌파
        strategies.put(StrategyType.BOLLINGER_SQUEEZE_BREAKOUT, new BollingerSqueezeBreakoutStrategy());

        // 삼각수렴 돌파
        strategies.put(StrategyType.TRIANGLE_CONVERGENCE, new TriangleConvergenceStrategy());
    }

    public TradingStrategy get(StrategyType type) {
        TradingStrategy s = strategies.get(type);
        if (s == null) throw new IllegalArgumentException("Unknown strategyType: " + type);
        return s;
    }
}
