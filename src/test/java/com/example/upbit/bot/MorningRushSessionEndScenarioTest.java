package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 모닝러쉬 SESSION_END 시간 계산 시나리오 테스트.
 *
 * 모닝러쉬는 단순한 KST 시간 비교 (nowMinOfDay >= sessionEndMin) 사용.
 * 자정 넘기기 처리 없음 (sessionEnd가 항상 12:00 미만 = 오전 시간대).
 *
 * 11:30으로 변경된 후 모든 시간 케이스 검증.
 */
public class MorningRushSessionEndScenarioTest {

    /**
     * 모닝러쉬의 SESSION_END 판정 로직 그대로 복제.
     * (코드 자체가 단순해 별도 함수 추출 없이 검증 가능)
     */
    private boolean isSessionEnd(int hour, int minute, int sessionEndHour, int sessionEndMin) {
        int nowMinOfDay = hour * 60 + minute;
        int endMin = sessionEndHour * 60 + sessionEndMin;
        return nowMinOfDay >= endMin;
    }

    /** Hold phase 판정 */
    private boolean isHoldPhase(int hour, int minute, int sessionEndHour, int sessionEndMin) {
        int nowMinOfDay = hour * 60 + minute;
        int endMin = sessionEndHour * 60 + sessionEndMin;
        return nowMinOfDay >= 9 * 60 + 5 && nowMinOfDay < endMin;
    }

    /** Entry phase 판정 (09:00~09:05) */
    private boolean isEntryPhase(int hour, int minute) {
        int nowMinOfDay = hour * 60 + minute;
        return nowMinOfDay >= 9 * 60 && nowMinOfDay < 9 * 60 + 5;
    }

    /** Range phase 판정 (08:50~09:00) */
    private boolean isRangePhase(int hour, int minute) {
        int nowMinOfDay = hour * 60 + minute;
        return nowMinOfDay >= 8 * 60 + 50 && nowMinOfDay < 9 * 60;
    }

    // ═══════════════════════════════════════════════════════════
    //  SESSION_END = 11:30 시나리오 (V104 적용 후)
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 1: 08:50 KST → Range phase, SESSION_END 아님")
    public void scenario1_rangePhase_0850() {
        assertTrue(isRangePhase(8, 50));
        assertFalse(isSessionEnd(8, 50, 11, 30));
        assertFalse(isHoldPhase(8, 50, 11, 30));
    }

    @Test
    @DisplayName("시나리오 2: 09:00 KST → Entry phase 시작")
    public void scenario2_entryPhase_0900() {
        assertTrue(isEntryPhase(9, 0));
        assertFalse(isSessionEnd(9, 0, 11, 30));
    }

    @Test
    @DisplayName("시나리오 3: 09:05 KST → Hold phase 시작 (entry 직후)")
    public void scenario3_holdPhase_0905() {
        assertFalse(isEntryPhase(9, 5));
        assertTrue(isHoldPhase(9, 5, 11, 30));
        assertFalse(isSessionEnd(9, 5, 11, 30));
    }

    @Test
    @DisplayName("시나리오 4: 09:30 KST → Hold phase 정상")
    public void scenario4_holdPhase_0930() {
        assertTrue(isHoldPhase(9, 30, 11, 30));
        assertFalse(isSessionEnd(9, 30, 11, 30));
    }

    @Test
    @DisplayName("시나리오 5: 11:00 KST → Hold phase (sessionEnd 30분 전)")
    public void scenario5_holdPhase_1100() {
        assertTrue(isHoldPhase(11, 0, 11, 30));
        assertFalse(isSessionEnd(11, 0, 11, 30));
    }

    @Test
    @DisplayName("시나리오 6: 11:29 KST → Hold phase (sessionEnd 1분 전)")
    public void scenario6_holdPhase_1129() {
        assertTrue(isHoldPhase(11, 29, 11, 30));
        assertFalse(isSessionEnd(11, 29, 11, 30));
    }

    @Test
    @DisplayName("시나리오 7: ⭐ 11:30 KST → SESSION_END 정확히 발동")
    public void scenario7_sessionEnd_1130_exact() {
        assertTrue(isSessionEnd(11, 30, 11, 30), "11:30은 sessionEnd 정확히 발동");
        assertFalse(isHoldPhase(11, 30, 11, 30), "11:30은 hold phase 종료");
    }

    @Test
    @DisplayName("시나리오 8: 11:31 KST → SESSION_END 후")
    public void scenario8_after_sessionEnd_1131() {
        assertTrue(isSessionEnd(11, 31, 11, 30));
        assertFalse(isHoldPhase(11, 31, 11, 30));
    }

    @Test
    @DisplayName("시나리오 9: 12:00 KST → SESSION_END 후 (30분 경과)")
    public void scenario9_after_sessionEnd_1200() {
        assertTrue(isSessionEnd(12, 0, 11, 30));
    }

    @Test
    @DisplayName("시나리오 10: 14:00 KST → SESSION_END 후 (오후)")
    public void scenario10_after_sessionEnd_1400() {
        assertTrue(isSessionEnd(14, 0, 11, 30));
    }

    @Test
    @DisplayName("시나리오 11: 22:00 KST → SESSION_END 후 (밤)")
    public void scenario11_after_sessionEnd_2200() {
        assertTrue(isSessionEnd(22, 0, 11, 30));
    }

    @Test
    @DisplayName("시나리오 12: 다음날 00:00 KST → 자정 → SESSION_END 아님 (새 거래일)")
    public void scenario12_midnight() {
        // nowMinOfDay = 0, sessionEndMin = 690
        // 0 >= 690 → false → SESSION_END 아님
        assertFalse(isSessionEnd(0, 0, 11, 30),
                "자정은 새 거래일 시작, SESSION_END 아님");
    }

    @Test
    @DisplayName("시나리오 13: 다음날 08:50 KST → 새 Range phase 시작")
    public void scenario13_nextday_range() {
        assertTrue(isRangePhase(8, 50));
        assertFalse(isSessionEnd(8, 50, 11, 30),
                "다음날 08:50은 새 Range phase, SESSION_END 아님");
    }

    @Test
    @DisplayName("시나리오 14: 다음날 09:00 KST → 새 Entry phase")
    public void scenario14_nextday_entry() {
        assertTrue(isEntryPhase(9, 0));
        assertFalse(isSessionEnd(9, 0, 11, 30));
    }

    // ═══════════════════════════════════════════════════════════
    //  Phase 전환 경계값 검증
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 15: 08:49 → 모든 phase 아님 (Range 1분 전)")
    public void scenario15_before_range() {
        assertFalse(isRangePhase(8, 49));
        assertFalse(isEntryPhase(8, 49));
        assertFalse(isHoldPhase(8, 49, 11, 30));
        assertFalse(isSessionEnd(8, 49, 11, 30));
    }

    @Test
    @DisplayName("시나리오 16: 08:59 → Range phase 마지막 1분")
    public void scenario16_range_last() {
        assertTrue(isRangePhase(8, 59));
        assertFalse(isEntryPhase(8, 59));
    }

    @Test
    @DisplayName("시나리오 17: 09:04 → Entry phase 마지막 1분")
    public void scenario17_entry_last() {
        assertTrue(isEntryPhase(9, 4));
        assertFalse(isHoldPhase(9, 4, 11, 30));
    }

    @Test
    @DisplayName("시나리오 18: SESSION_END 시간 변경 호환성 — 12:00 설정")
    public void scenario18_compat_1200() {
        assertFalse(isSessionEnd(11, 30, 12, 0));
        assertFalse(isSessionEnd(11, 59, 12, 0));
        assertTrue(isSessionEnd(12, 0, 12, 0));
        assertTrue(isSessionEnd(12, 1, 12, 0));
    }

    @Test
    @DisplayName("시나리오 19: SESSION_END 시간 변경 호환성 — 10:00 설정 (구버전)")
    public void scenario19_compat_1000() {
        assertFalse(isSessionEnd(9, 59, 10, 0));
        assertTrue(isSessionEnd(10, 0, 10, 0));
        assertTrue(isSessionEnd(10, 30, 10, 0));
    }

    @Test
    @DisplayName("시나리오 20: ⭐ 자정 넘기기 안전 검증 — sessionEnd가 오전 시간대만 사용 시")
    public void scenario20_no_overnight() {
        // 모닝러쉬 SESSION_END는 항상 12:00 미만 (오전)
        // 자정 넘기기 케이스 없음 → 단순 비교 안전
        for (int sessionEndH = 9; sessionEndH < 12; sessionEndH++) {
            for (int sessionEndM = 0; sessionEndM < 60; sessionEndM += 30) {
                // 그날 새벽 00:00은 SESSION_END 아님
                assertFalse(isSessionEnd(0, 0, sessionEndH, sessionEndM),
                        "자정은 SESSION_END 아님: " + sessionEndH + ":" + sessionEndM);
                // 같은 시각은 정확히 발동
                assertTrue(isSessionEnd(sessionEndH, sessionEndM, sessionEndH, sessionEndM),
                        "같은 시각은 정확히 발동: " + sessionEndH + ":" + sessionEndM);
                // 1분 후 발동
                int hAfter = sessionEndM == 59 ? sessionEndH + 1 : sessionEndH;
                int mAfter = sessionEndM == 59 ? 0 : sessionEndM + 1;
                if (hAfter < 24) {
                    assertTrue(isSessionEnd(hAfter, mAfter, sessionEndH, sessionEndM),
                            "1분 후 발동: " + sessionEndH + ":" + sessionEndM + " → " + hAfter + ":" + mAfter);
                }
            }
        }
    }
}
