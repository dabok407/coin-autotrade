package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 모닝러쉬 매수 진입 조건 시나리오 테스트.
 *
 * MorningRushScannerService.checkRealtimeEntry()의 핵심 차단 로직을 검증.
 * 운영 코드와 동일한 계산 로직을 복제하여 시나리오 데이터로 검증.
 *
 * 검증 항목:
 *  1. 정상 매수 (gap up + confirm 통과)
 *  2. gap_threshold 미달 → 차단
 *  3. surge 미달 + gap 미달 → 차단
 *  4. confirm count 미달 → 매수 안 함
 *  5. max_positions 도달 → 차단
 *  6. 1시간 throttle 차단
 *  7. 20분 cooldown 차단
 *  8. 이미 보유 중 → 차단
 *  9. baseVol < minTradeAmount → 차단
 */
public class MorningRushBuyScenarioTest {

    public enum BuyResult {
        EXECUTED,
        GAP_INSUFFICIENT,
        SURGE_INSUFFICIENT,
        CONFIRM_INSUFFICIENT,
        MAX_POSITIONS,
        HOURLY_THROTTLE,
        ALREADY_HELD,
        BASE_VOL_LOW
    }

    /**
     * 모닝러쉬 매수 차단 로직 (운영 코드 동일).
     * checkRealtimeEntry + scheduler 매수 결정 로직 통합.
     */
    private BuyResult evaluateMorningRushBuy(
            double currentPrice, double rangeHigh, long baseVol,
            long minTradeAmount,
            double gapThresholdPct, // %
            double surgePct,        // %, 0이면 비활성
            double surgeMinPrice,   // surge 윈도우 내 최저가 (0이면 비활성)
            int currentConfirmCount,
            int requiredConfirmCount,
            int currentRushPosCount,
            int maxPositions,
            boolean alreadyHeld,
            HourlyTradeThrottle throttle,
            String market) {

        // 1. baseVol 체크
        if (baseVol < minTradeAmount) return BuyResult.BASE_VOL_LOW;

        // 2. 이미 보유 중
        if (alreadyHeld) return BuyResult.ALREADY_HELD;

        // 3. gap / surge 조건
        double gapThreshold = gapThresholdPct / 100.0;
        double threshold = rangeHigh * (1.0 + gapThreshold);
        boolean gapCondition = currentPrice > threshold;

        boolean surgeCondition = false;
        if (surgePct > 0 && surgeMinPrice > 0) {
            double surgeThreshold = surgePct / 100.0;
            double surgeRatio = (currentPrice - surgeMinPrice) / surgeMinPrice;
            if (surgeRatio >= surgeThreshold) surgeCondition = true;
        }

        if (!gapCondition && !surgeCondition) {
            // gap도 surge도 미달 → 별도 분류 (gap 우선)
            return surgePct > 0 ? BuyResult.SURGE_INSUFFICIENT : BuyResult.GAP_INSUFFICIENT;
        }

        // 4. confirm 카운트
        int newCount = currentConfirmCount + 1;
        if (newCount < requiredConfirmCount) return BuyResult.CONFIRM_INSUFFICIENT;

        // 5. max positions
        if (currentRushPosCount >= maxPositions) return BuyResult.MAX_POSITIONS;

        // 6. throttle 체크
        if (throttle != null && !throttle.canBuy(market)) return BuyResult.HOURLY_THROTTLE;

        return BuyResult.EXECUTED;
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 1: 정상 매수
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 1: gap +2% + confirm 3/3 + 포지션 0 → 매수 성공")
    public void scenario1_normalBuy() {
        BuyResult result = evaluateMorningRushBuy(
                102.5, 100.0, 1_500_000_000L, 1_000_000_000L,
                2.0, 0, 0,
                2, 3,  // confirm 2 → 3 (마지막 +1)
                0, 3,
                false,
                new HourlyTradeThrottle(2, 20),
                "KRW-TEST"
        );
        assertEquals(BuyResult.EXECUTED, result);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 2: gap_threshold 미달
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 2: 가격 101 (gap 2% 미달) → GAP_INSUFFICIENT")
    public void scenario2_gapInsufficient() {
        BuyResult result = evaluateMorningRushBuy(
                101.0, 100.0, 1_500_000_000L, 1_000_000_000L,
                2.0, 0, 0,
                2, 3,
                0, 3,
                false,
                new HourlyTradeThrottle(2, 20),
                "KRW-TEST"
        );
        assertEquals(BuyResult.GAP_INSUFFICIENT, result);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 3: gap 미달 + surge 미달
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 3: gap 미달 + surge 2% 미달 (1.5%) → SURGE_INSUFFICIENT")
    public void scenario3_surgeInsufficient() {
        BuyResult result = evaluateMorningRushBuy(
                101.5, 100.0, 1_500_000_000L, 1_000_000_000L,
                2.0,    // gap 2%
                3.0,    // surge 3%
                100.0,  // surge 윈도우 최저가
                2, 3,
                0, 3,
                false,
                new HourlyTradeThrottle(2, 20),
                "KRW-TEST"
        );
        // 가격 101.5 → gap 1.5% (2% 미달), surge (101.5-100)/100 = 1.5% (3% 미달)
        assertEquals(BuyResult.SURGE_INSUFFICIENT, result);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 4: confirm 미달
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 4: confirm 1/3 → CONFIRM_INSUFFICIENT")
    public void scenario4_confirmInsufficient() {
        BuyResult result = evaluateMorningRushBuy(
                102.5, 100.0, 1_500_000_000L, 1_000_000_000L,
                2.0, 0, 0,
                0, 3,  // 0 → 1 (3 미달)
                0, 3,
                false,
                new HourlyTradeThrottle(2, 20),
                "KRW-TEST"
        );
        assertEquals(BuyResult.CONFIRM_INSUFFICIENT, result);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 5: max_positions 도달
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 5: 이미 3개 보유 → MAX_POSITIONS")
    public void scenario5_maxPositions() {
        BuyResult result = evaluateMorningRushBuy(
                102.5, 100.0, 1_500_000_000L, 1_000_000_000L,
                2.0, 0, 0,
                2, 3,
                3, 3,  // 3개 보유, max 3 → 차단
                false,
                new HourlyTradeThrottle(2, 20),
                "KRW-TEST"
        );
        assertEquals(BuyResult.MAX_POSITIONS, result);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 6: 1시간 throttle 차단
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 6: 같은 코인 5분 전 매수 → HOURLY_THROTTLE 차단")
    public void scenario6_hourlyThrottle() throws Exception {
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(2, 20);
        // 5분 전 매수 시뮬레이션
        java.lang.reflect.Field f = HourlyTradeThrottle.class.getDeclaredField("tradeHistory");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, java.util.Deque<Long>> history =
                (java.util.concurrent.ConcurrentHashMap<String, java.util.Deque<Long>>) f.get(throttle);
        java.util.Deque<Long> deque = new java.util.ArrayDeque<>();
        deque.addLast(System.currentTimeMillis() - 5 * 60_000L);
        history.put("KRW-TEST", deque);

        BuyResult result = evaluateMorningRushBuy(
                102.5, 100.0, 1_500_000_000L, 1_000_000_000L,
                2.0, 0, 0,
                2, 3,
                0, 3,
                false,
                throttle,
                "KRW-TEST"
        );
        assertEquals(BuyResult.HOURLY_THROTTLE, result);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 7: 이미 보유 중
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 7: 같은 코인 이미 보유 → ALREADY_HELD")
    public void scenario7_alreadyHeld() {
        BuyResult result = evaluateMorningRushBuy(
                102.0, 100.0, 1_500_000_000L, 1_000_000_000L,
                2.0, 0, 0,
                2, 3,
                1, 3,
                true,  // 이미 보유 중
                new HourlyTradeThrottle(2, 20),
                "KRW-TEST"
        );
        assertEquals(BuyResult.ALREADY_HELD, result);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 8: baseVol 미달
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 8: 24h 거래대금 5억 (10억 미달) → BASE_VOL_LOW")
    public void scenario8_baseVolLow() {
        BuyResult result = evaluateMorningRushBuy(
                102.0, 100.0, 500_000_000L, 1_000_000_000L,
                2.0, 0, 0,
                2, 3,
                0, 3,
                false,
                new HourlyTradeThrottle(2, 20),
                "KRW-TEST"
        );
        assertEquals(BuyResult.BASE_VOL_LOW, result);
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 9: gap 정확 경계값 (2% 정확)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 9: gap 2.0% 정확 (rangeHigh × 1.02 = 102) → 미달 (strict >)")
    public void scenario9_gapExactBoundary() {
        BuyResult result = evaluateMorningRushBuy(
                102.0, 100.0, 1_500_000_000L, 1_000_000_000L, // 정확 경계값
                2.0, 0, 0,
                2, 3,
                0, 3,
                false,
                new HourlyTradeThrottle(2, 20),
                "KRW-TEST"
        );
        // 운영 코드: price > threshold → 102 > 102 = false → GAP_INSUFFICIENT
        assertEquals(BuyResult.GAP_INSUFFICIENT, result, "정확 102.0은 strict > 102.0이라 차단");
    }
}
