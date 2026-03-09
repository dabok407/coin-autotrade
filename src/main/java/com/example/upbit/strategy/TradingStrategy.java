package com.example.upbit.strategy;

/**
 * 전략 인터페이스.
 * 다른 매매 알고리즘을 적용하고 싶으면 이 인터페이스 구현체만 추가/교체하면 됩니다.
 */
public interface TradingStrategy {
    StrategyType type();
    Signal evaluate(StrategyContext ctx);
}
