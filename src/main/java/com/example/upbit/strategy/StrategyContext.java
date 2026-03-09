package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;
import com.example.upbit.db.PositionEntity;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 전략 판단에 필요한 최소 컨텍스트.
 * - candles: 오래된 -> 최신 순으로 정렬된 캔들 리스트
 * - position: 현재 포지션(DB 기준). 없으면 null
 * - downStreak: 런타임 연속하락 카운트(엔진이 계산)
 * - emaTrendFilterMap: 전략별 EMA 트렌드 필터 기간 (0=비활성, >0=EMA기간)
 */
public class StrategyContext {
    public final String market;
    public final int candleUnitMin;
    public final List<UpbitCandle> candles;
    public final PositionEntity position;
    public final int downStreak;
    /** 전략별 EMA 트렌드 필터 기간 맵. key=StrategyType.name(), value=EMA period (0=비활성) */
    public final Map<String, Integer> emaTrendFilterMap;

    /** 기존 호환 생성자 (EMA 맵 없음 → 기본값 50 사용) */
    public StrategyContext(String market, int candleUnitMin, List<UpbitCandle> candles, PositionEntity position, int downStreak) {
        this(market, candleUnitMin, candles, position, downStreak, Collections.<String, Integer>emptyMap());
    }

    /** EMA 트렌드 필터 맵 포함 생성자 */
    public StrategyContext(String market, int candleUnitMin, List<UpbitCandle> candles, PositionEntity position, int downStreak, Map<String, Integer> emaTrendFilterMap) {
        this.market = market;
        this.candleUnitMin = candleUnitMin;
        this.candles = candles;
        this.position = position;
        this.downStreak = downStreak;
        this.emaTrendFilterMap = (emaTrendFilterMap != null) ? emaTrendFilterMap : Collections.<String, Integer>emptyMap();
    }

    /**
     * 지정 전략의 EMA 트렌드 필터 기간을 반환합니다.
     * 맵에 없으면 기본값 50을 반환합니다 (기존 동작 유지).
     * 0이면 EMA 필터 비활성화입니다.
     */
    public int getEmaTrendPeriod(StrategyType st) {
        Integer v = emaTrendFilterMap.get(st.name());
        return (v != null) ? v.intValue() : 50;
    }
}
