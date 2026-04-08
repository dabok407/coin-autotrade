package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HourlyTradeThrottle 쿨다운 시나리오 통합 테스트.
 *
 * 시뮬레이션: 매수 시점을 직접 조작하여 1시간 N회 + 20분 쿨다운 동시 작동 검증.
 *
 * BARD 사고(21:25 1차 매수 → 21:30 재매수 → -7.47% 손실) 같은 케이스가
 * 새 throttle로 차단되는지 확인.
 */
public class HourlyThrottleScenarioTest {

    /** 매수 시점을 시간 조작으로 등록 (현재시각으로부터 elapsedSecAgo초 전) */
    @SuppressWarnings("unchecked")
    private void recordBuyAt(HourlyTradeThrottle throttle, String market, long elapsedSecAgo) throws Exception {
        // 직접 history에 시간 삽입 (private 필드 조작)
        Field f = HourlyTradeThrottle.class.getDeclaredField("tradeHistory");
        f.setAccessible(true);
        ConcurrentHashMap<String, Deque<Long>> history =
                (ConcurrentHashMap<String, Deque<Long>>) f.get(throttle);
        Deque<Long> deque = history.computeIfAbsent(market, k -> new ArrayDeque<Long>());
        synchronized (deque) {
            deque.addLast(System.currentTimeMillis() - elapsedSecAgo * 1000L);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 1: BARD 사고 재현 — 5분 후 재매수 차단
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 1: BARD 사고 재현 — 5분 전 매수 → 새 매수 차단 (20분 쿨다운)")
    public void scenario1_bardCase() throws Exception {
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(2, 20);
        // 21:25 BARD 1차 매수 시뮬레이션 (5분 전)
        recordBuyAt(throttle, "KRW-BARD", 5 * 60);

        // 21:30 BARD 2차 매수 시도
        assertFalse(throttle.canBuy("KRW-BARD"),
                "5분 후 재매수는 20분 쿨다운으로 차단되어야 함 (BARD 사고 방지)");

        // 남은 대기 시간: 약 15분
        long wait = throttle.remainingWaitMs("KRW-BARD");
        assertTrue(wait > 14 * 60_000L && wait <= 15 * 60_000L,
                "남은 대기 시간 ~15분, 실제: " + (wait / 60_000) + "분");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 2: 20분 정확히 경과 → 쿨다운 통과
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 2: 20분 경과 후 재매수 가능")
    public void scenario2_after20min() throws Exception {
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(2, 20);
        recordBuyAt(throttle, "KRW-BTC", 21 * 60); // 21분 전

        assertTrue(throttle.canBuy("KRW-BTC"),
                "20분 쿨다운 경과 후 재매수 가능");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 3: 1시간 2회 제한 — 3번째 차단
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 3: 25분 + 21분 전 매수 후 → 3번째 차단 (1시간 2회)")
    public void scenario3_hourlyLimit() throws Exception {
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(2, 20);
        // 25분 전 1차, 21분 전 2차 (둘 다 1시간 안)
        recordBuyAt(throttle, "KRW-ETH", 25 * 60);
        recordBuyAt(throttle, "KRW-ETH", 21 * 60);

        // 3번째 매수 시도 (가장 최근이 21분 전이라 쿨다운은 통과)
        assertFalse(throttle.canBuy("KRW-ETH"),
                "1시간 안에 2번 했으므로 3번째는 차단");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 4: 60분 경과 → 1차 만료, 새 매수 가능
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 4: 65분 + 21분 전 매수 → 65분은 만료, 21분만 카운트")
    public void scenario4_hourExpiry() throws Exception {
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(2, 20);
        recordBuyAt(throttle, "KRW-SOL", 65 * 60);  // 65분 전 (1시간 만료)
        recordBuyAt(throttle, "KRW-SOL", 21 * 60);  // 21분 전

        // 65분 전 거래는 만료, 21분 전만 유효 → 1회 카운트 → 매수 가능
        assertTrue(throttle.canBuy("KRW-SOL"),
                "65분 전 거래는 만료, 1회만 카운트되어 매수 가능");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 5: 다른 마켓 독립
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 5: BTC 쿨다운 중에도 ETH 매수 가능 (마켓 독립)")
    public void scenario5_marketIndependent() throws Exception {
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(2, 20);
        recordBuyAt(throttle, "KRW-BTC", 5 * 60);  // BTC 쿨다운 중

        assertFalse(throttle.canBuy("KRW-BTC"));
        assertTrue(throttle.canBuy("KRW-ETH"), "다른 마켓은 영향 없음");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 6: 매수 → 19분 후 (쿨다운 1분 남음) → 차단
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 6: 19분 후 (쿨다운 1분 부족) → 차단")
    public void scenario6_almostCooldown() throws Exception {
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(2, 20);
        recordBuyAt(throttle, "KRW-ADA", 19 * 60);

        assertFalse(throttle.canBuy("KRW-ADA"),
                "19분은 20분 쿨다운 1분 부족으로 차단");

        long wait = throttle.remainingWaitMs("KRW-ADA");
        assertTrue(wait > 0 && wait <= 60_000L * 2,
                "남은 대기 약 1분, 실제: " + (wait / 1000) + "초");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 7: 1시간 2회 + 20분 1회 둘 다 차단
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 7: 30분 전 + 5분 전 → 1시간 2회 OR 20분 쿨다운 둘 중 더 긴 시간")
    public void scenario7_bothBlock() throws Exception {
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(2, 20);
        recordBuyAt(throttle, "KRW-MASK", 30 * 60); // 30분 전 (1시간 안)
        recordBuyAt(throttle, "KRW-MASK", 5 * 60);  // 5분 전 (쿨다운 중)

        // 둘 다 차단 사유:
        // - 1시간 2회 제한: 1번째 30분 전이 30분 더 지나면 만료, 그때까지 대기
        // - 20분 쿨다운: 5분 전 + 20분 = 15분 더 대기
        // 둘 중 더 긴 시간 = 30분 (1시간 제한)
        assertFalse(throttle.canBuy("KRW-MASK"));

        long wait = throttle.remainingWaitMs("KRW-MASK");
        // 가장 긴 대기: 30분 전 거래가 1시간 만료까지 30분 = 1,800,000ms
        assertTrue(wait > 25 * 60_000L && wait <= 31 * 60_000L,
                "최대 대기 ~30분, 실제: " + (wait / 60_000) + "분");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 8: 모든 스캐너 동일 적용 검증 (생성자 일관성)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 8: new HourlyTradeThrottle(2) 생성자가 자동으로 20분 쿨다운 적용")
    public void scenario8_defaultConstructor() throws Exception {
        // 모닝/오프닝/올데이가 모두 사용하는 단일 인자 생성자
        HourlyTradeThrottle throttle = new HourlyTradeThrottle(2);

        recordBuyAt(throttle, "KRW-XRP", 5 * 60);
        assertFalse(throttle.canBuy("KRW-XRP"),
                "기본 생성자도 20분 쿨다운 자동 적용 (모닝/오프닝/올데이 모두 동일)");
    }
}
