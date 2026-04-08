package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SharedTradeThrottle 교차 스캐너 시나리오 테스트.
 *
 * 사고 검증: 2026-04-08 KRW-TREE — 모닝러쉬와 오프닝 스캐너가 분리된
 * HourlyTradeThrottle 인스턴스를 가져 같은 코인을 동시에 매수했고,
 * 한쪽 매도 후 position 행이 삭제되어 업비트 잔고가 orphan 상태가 됨.
 *
 * 본 테스트는 SharedTradeThrottle을 단일 인스턴스로 공유했을 때
 * 두 스캐너 사이에서 throttle 상태가 제대로 공유되는지 검증한다.
 */
public class SharedThrottleCrossScannerTest {

    // ═══════════════════════════════════════════════════════════
    //  시나리오 1: A 스캐너 매수 후 B 스캐너가 같은 코인 차단
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 1: 모닝러쉬가 KRW-TREE 매수 → 오프닝이 같은 코인 차단 (20분 쿨다운)")
    public void scenario1_crossScannerCooldown() {
        SharedTradeThrottle sharedThrottle = new SharedTradeThrottle();

        // 모닝러쉬(가상)에서 매수 기록
        assertTrue(sharedThrottle.canBuy("KRW-TREE"), "초기엔 매수 가능");
        sharedThrottle.recordBuy("KRW-TREE");

        // 오프닝 스캐너(가상)가 같은 인스턴스로 즉시 시도 → 차단
        assertFalse(sharedThrottle.canBuy("KRW-TREE"),
                "다른 스캐너가 같은 코인 매수 시도 시 20분 쿨다운으로 차단되어야 함");

        // 남은 대기 시간 확인 (19~20분)
        long wait = sharedThrottle.remainingWaitMs("KRW-TREE");
        assertTrue(wait > 19 * 60_000L && wait <= 20 * 60_000L,
                "남은 대기 약 20분, 실제: " + (wait / 60_000) + "분");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 2: 1시간 2회 제한이 모든 스캐너에 합산 적용
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 2: 모닝러쉬 1회 + 오프닝 1회 = 2회 → 종일 스캐너의 3번째 차단")
    public void scenario2_crossScannerHourlyLimit() throws Exception {
        SharedTradeThrottle sharedThrottle = new SharedTradeThrottle();

        // 가짜 1시간 2회 시뮬레이션 (history에 25분 전, 21분 전 기록)
        HourlyTradeThrottle inner = sharedThrottle.getDelegate();
        java.lang.reflect.Field histF = HourlyTradeThrottle.class.getDeclaredField("tradeHistory");
        histF.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, java.util.Deque<Long>> history =
                (java.util.concurrent.ConcurrentHashMap<String, java.util.Deque<Long>>) histF.get(inner);

        java.util.Deque<Long> deque = new java.util.ArrayDeque<>();
        long now = System.currentTimeMillis();
        deque.addLast(now - 25 * 60_000L); // 25분 전 (모닝러쉬가 매수했다고 가정)
        deque.addLast(now - 21 * 60_000L); // 21분 전 (오프닝이 매수했다고 가정, 쿨다운 통과)
        history.put("KRW-PUMP", deque);

        // 종일 스캐너(가상)가 3번째 매수 시도 → 1시간 2회 제한으로 차단
        assertFalse(sharedThrottle.canBuy("KRW-PUMP"),
                "1시간 2회 제한이 스캐너 합산으로 적용되어 3번째 차단되어야 함");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 3: 다른 코인은 영향 없음
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 3: KRW-TREE 매수가 KRW-OTHER에는 영향 없음")
    public void scenario3_perMarketIsolation() {
        SharedTradeThrottle sharedThrottle = new SharedTradeThrottle();

        sharedThrottle.recordBuy("KRW-TREE");

        assertFalse(sharedThrottle.canBuy("KRW-TREE"), "KRW-TREE는 차단");
        assertTrue(sharedThrottle.canBuy("KRW-OTHER"), "KRW-OTHER는 영향 없음");
        assertTrue(sharedThrottle.canBuy("KRW-DIFFERENT"), "KRW-DIFFERENT도 영향 없음");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 4: 두 규칙(1시간 N회 + 20분 쿨다운) 둘 다 보장
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 4: 1시간 2회 + 20분 쿨다운 둘 다 적용 (생성자 기본값 검증)")
    public void scenario4_bothRulesEnforced() throws Exception {
        SharedTradeThrottle sharedThrottle = new SharedTradeThrottle();
        HourlyTradeThrottle inner = sharedThrottle.getDelegate();

        // 내부 필드 검증: maxTradesPerHour=2, cooldownMs=20*60_000
        java.lang.reflect.Field maxF = HourlyTradeThrottle.class.getDeclaredField("maxTradesPerHour");
        maxF.setAccessible(true);
        assertEquals(2, maxF.get(inner), "1시간 2회 제한");

        java.lang.reflect.Field cdF = HourlyTradeThrottle.class.getDeclaredField("cooldownMs");
        cdF.setAccessible(true);
        assertEquals(20L * 60_000L, cdF.get(inner), "20분 쿨다운");
    }
}
