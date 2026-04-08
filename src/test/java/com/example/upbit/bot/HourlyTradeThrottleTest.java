package com.example.upbit.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HourlyTradeThrottle 단위 테스트.
 * 1시간 N회 + 짧은 쿨다운 동시 적용 검증.
 */
public class HourlyTradeThrottleTest {

    @Test
    public void testInitialState_canBuy() {
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(2, 20);
        assertTrue(throttle.canBuy("KRW-BTC"), "초기 상태는 매수 가능");
        assertEquals(0, throttle.remainingWaitMs("KRW-BTC"));
    }

    @Test
    public void testHourlyLimit() {
        // 1시간 2회 + 쿨다운 0분 (쿨다운 비활성화)
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(2, 0);

        assertTrue(throttle.canBuy("KRW-BTC"));
        throttle.recordBuy("KRW-BTC");
        assertTrue(throttle.canBuy("KRW-BTC"), "1회 후 가능");
        throttle.recordBuy("KRW-BTC");
        assertFalse(throttle.canBuy("KRW-BTC"), "2회 후 차단");
    }

    @Test
    public void testCooldownLimit() {
        // 1시간 10회 + 20분 쿨다운
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(10, 20);

        assertTrue(throttle.canBuy("KRW-BTC"));
        throttle.recordBuy("KRW-BTC");
        // 즉시 두 번째 매수 시도 → 쿨다운 차단
        assertFalse(throttle.canBuy("KRW-BTC"), "쿨다운 20분 안에 차단");

        // 다른 마켓은 영향 없음
        assertTrue(throttle.canBuy("KRW-ETH"), "다른 마켓 매수 가능");
    }

    @Test
    public void testBothConditionsMustBeSatisfied() {
        // 1시간 3회 + 1분 쿨다운 (테스트용)
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(3, 1);

        // 첫 매수
        assertTrue(throttle.canBuy("KRW-BTC"));
        throttle.recordBuy("KRW-BTC");

        // 즉시 두 번째 → 쿨다운 차단
        assertFalse(throttle.canBuy("KRW-BTC"));
    }

    @Test
    public void testRemainingWaitMs_cooldown() {
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(10, 20);
        throttle.recordBuy("KRW-BTC");

        long wait = throttle.remainingWaitMs("KRW-BTC");
        assertTrue(wait > 0 && wait <= 20 * 60_000L,
                "쿨다운 대기 시간이 0~20분 사이여야 함, 실제: " + wait);
    }

    @Test
    public void testIndependentMarkets() {
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(2, 20);

        throttle.recordBuy("KRW-BTC");
        throttle.recordBuy("KRW-ETH");

        // 각 마켓 독립적으로 쿨다운
        assertFalse(throttle.canBuy("KRW-BTC"));
        assertFalse(throttle.canBuy("KRW-ETH"));

        // 다른 마켓은 매수 가능
        assertTrue(throttle.canBuy("KRW-SOL"));
    }

    @Test
    public void testDefaultCooldown20Min() {
        // 단일 인자 생성자는 쿨다운 20분 기본
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(2);

        throttle.recordBuy("KRW-BTC");
        assertFalse(throttle.canBuy("KRW-BTC"), "기본 쿨다운 20분으로 즉시 차단");
    }
}
