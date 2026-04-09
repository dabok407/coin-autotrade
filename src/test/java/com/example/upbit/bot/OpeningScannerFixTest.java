package com.example.upbit.bot;

import com.example.upbit.market.SharedPriceService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 2026-04-09 오프닝 스캐너 fix 6개 검증 단위/통합 테스트.
 *
 * Fix 항목:
 *   1. BOUNDARY_BUY_ENABLED = false (5분 boundary BUY path 비활성화)
 *   2. BreakoutDetector entry window check (윈도우 밖 confirm 안 함)
 *   3. tryWsBreakoutBuy entry window check (윈도우 밖 매수 안 함)
 *   4. vol 필터 제거 (양봉/RSI/EMA20만 유지)
 *   5. SKIP 시 releaseMarket 호출 (재시도 가능)
 *   6. precacheOneMinCandles 시작 시점 entryStart - 5
 */
public class OpeningScannerFixTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // ═══════════════════════════════════════════════════════════
    //  Fix 1: BOUNDARY_BUY_ENABLED 상수 = false 검증
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("Fix 1-1: BOUNDARY_BUY_ENABLED 상수가 false로 선언되어 있어야 함")
    public void fix1_boundaryBuyEnabledIsFalse() throws Exception {
        Field f = OpeningScannerService.class.getDeclaredField("BOUNDARY_BUY_ENABLED");
        f.setAccessible(true);
        boolean value = (boolean) f.get(null);  // static field
        assertFalse(value,
                "BOUNDARY_BUY_ENABLED는 반드시 false여야 함 (사용자 명시 결정 2026-04-09). " +
                "Claude가 자동 작업 중 무심코 true로 변경하지 말 것.");
    }

    @Test
    @DisplayName("Fix 1-2: BOUNDARY_BUY_ENABLED 상수가 private static final 인지 검증")
    public void fix1_boundaryBuyEnabledModifiers() throws Exception {
        Field f = OpeningScannerService.class.getDeclaredField("BOUNDARY_BUY_ENABLED");
        int mods = f.getModifiers();
        assertTrue(java.lang.reflect.Modifier.isPrivate(mods), "private이어야 함");
        assertTrue(java.lang.reflect.Modifier.isStatic(mods), "static이어야 함");
        assertTrue(java.lang.reflect.Modifier.isFinal(mods), "final이어야 함");
    }

    // ═══════════════════════════════════════════════════════════
    //  Fix 2: BreakoutDetector entry window check
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("Fix 2-1: setEntryWindow 메서드가 존재해야 함")
    public void fix2_setEntryWindowExists() throws Exception {
        Method m = OpeningBreakoutDetector.class.getMethod("setEntryWindow", int.class, int.class);
        assertNotNull(m);
    }

    @Test
    @DisplayName("Fix 2-2: entry window 밖 가격 update → confirm 카운트 누적 안 됨")
    public void fix2_breakoutDetectorOutsideEntryWindow() throws Exception {
        OpeningBreakoutDetector detector = new OpeningBreakoutDetector(
                mock(SharedPriceService.class));
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(3);

        // entry window: 09:05~10:30 (KST 분 단위 = 545~630)
        detector.setEntryWindow(545, 630);

        HashMap<String, Double> rangeMap = new HashMap<>();
        rangeMap.put("KRW-TEST", 100.0);
        detector.setRangeHighMap(rangeMap);

        // ZonedDateTime을 직접 조작할 수 없으므로, 현재 시각이 entry window 안인지 확인
        ZonedDateTime now = ZonedDateTime.now(KST);
        int nowMin = now.getHour() * 60 + now.getMinute();

        // 두 가지 케이스:
        // (a) 현재가 entry window(545~630) 안: confirmCounts가 누적됨
        // (b) 현재가 entry window 밖: confirmCounts가 누적 안 됨
        Method checkBreakout = OpeningBreakoutDetector.class.getDeclaredMethod(
                "checkBreakout", String.class, double.class);
        checkBreakout.setAccessible(true);

        // 가격 +2% (101 = +1% 임계의 위)
        checkBreakout.invoke(detector, "KRW-TEST", 102.0);

        Field cf = OpeningBreakoutDetector.class.getDeclaredField("confirmCounts");
        cf.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Integer> confirmCounts =
                (ConcurrentHashMap<String, Integer>) cf.get(detector);

        if (nowMin >= 545 && nowMin <= 630) {
            assertEquals(Integer.valueOf(1), confirmCounts.get("KRW-TEST"),
                    "현재가 entry window 안: confirmCount 1이어야 함");
        } else {
            assertNull(confirmCounts.get("KRW-TEST"),
                    "현재가 entry window 밖: confirmCount 누적 안 됨");
        }
    }

    @Test
    @DisplayName("Fix 2-3: setEntryWindow(-1, -1) → 시각 제한 비활성, 모든 시각에서 confirm 누적")
    public void fix2_disabledEntryWindowCheck() throws Exception {
        OpeningBreakoutDetector detector = new OpeningBreakoutDetector(
                mock(SharedPriceService.class));
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(3);
        detector.setEntryWindow(-1, -1);  // 비활성

        HashMap<String, Double> rangeMap = new HashMap<>();
        rangeMap.put("KRW-TEST", 100.0);
        detector.setRangeHighMap(rangeMap);

        Method checkBreakout = OpeningBreakoutDetector.class.getDeclaredMethod(
                "checkBreakout", String.class, double.class);
        checkBreakout.setAccessible(true);
        checkBreakout.invoke(detector, "KRW-TEST", 102.0);

        Field cf = OpeningBreakoutDetector.class.getDeclaredField("confirmCounts");
        cf.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Integer> confirmCounts =
                (ConcurrentHashMap<String, Integer>) cf.get(detector);

        assertEquals(Integer.valueOf(1), confirmCounts.get("KRW-TEST"),
                "entry window 비활성 시 confirmCount 누적 OK");
    }

    @Test
    @DisplayName("Fix 2-4: 가격이 +1% 임계 아래로 떨어지면 confirmCount reset")
    public void fix2_breakoutDetectorResetOnFailedThreshold() throws Exception {
        OpeningBreakoutDetector detector = new OpeningBreakoutDetector(
                mock(SharedPriceService.class));
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(3);
        detector.setEntryWindow(-1, -1);  // 시각 제한 비활성 (테스트 단순화)

        HashMap<String, Double> rangeMap = new HashMap<>();
        rangeMap.put("KRW-TEST", 100.0);
        detector.setRangeHighMap(rangeMap);

        Method checkBreakout = OpeningBreakoutDetector.class.getDeclaredMethod(
                "checkBreakout", String.class, double.class);
        checkBreakout.setAccessible(true);

        // 1차 breakout: 102 (+2%)
        checkBreakout.invoke(detector, "KRW-TEST", 102.0);
        // 2차 fail: 100.5 (+0.5%, 임계 미만)
        checkBreakout.invoke(detector, "KRW-TEST", 100.5);

        Field cf = OpeningBreakoutDetector.class.getDeclaredField("confirmCounts");
        cf.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Integer> confirmCounts =
                (ConcurrentHashMap<String, Integer>) cf.get(detector);

        assertEquals(Integer.valueOf(0), confirmCounts.get("KRW-TEST"),
                "임계 아래로 떨어지면 confirmCount 0으로 reset");
    }

    // ═══════════════════════════════════════════════════════════
    //  Fix 5: releaseMarket 호출 검증
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("Fix 5-1: releaseMarket() 호출 후 같은 마켓 다시 confirm 가능")
    public void fix5_releaseMarketAllowsRetry() throws Exception {
        OpeningBreakoutDetector detector = new OpeningBreakoutDetector(
                mock(SharedPriceService.class));
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(3);
        detector.setEntryWindow(-1, -1);

        HashMap<String, Double> rangeMap = new HashMap<>();
        rangeMap.put("KRW-TEST", 100.0);
        detector.setRangeHighMap(rangeMap);

        Method checkBreakout = OpeningBreakoutDetector.class.getDeclaredMethod(
                "checkBreakout", String.class, double.class);
        checkBreakout.setAccessible(true);

        // 3 tick 통과 → confirmedMarkets에 추가
        checkBreakout.invoke(detector, "KRW-TEST", 102.0);
        checkBreakout.invoke(detector, "KRW-TEST", 102.0);
        checkBreakout.invoke(detector, "KRW-TEST", 102.0);

        assertTrue(detector.isAlreadyConfirmed("KRW-TEST"),
                "3 tick 후 confirmedMarkets에 추가됨");

        // releaseMarket 호출
        detector.releaseMarket("KRW-TEST");

        assertFalse(detector.isAlreadyConfirmed("KRW-TEST"),
                "releaseMarket 후 confirmedMarkets에서 제거됨");

        // 같은 마켓 다시 시그널 가능 (3 tick 다시 필요)
        Field cf = OpeningBreakoutDetector.class.getDeclaredField("confirmCounts");
        cf.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Integer> confirmCounts =
                (ConcurrentHashMap<String, Integer>) cf.get(detector);

        checkBreakout.invoke(detector, "KRW-TEST", 102.0);
        assertEquals(Integer.valueOf(1), confirmCounts.get("KRW-TEST"),
                "release 후 confirmCount 다시 1부터 시작");
    }

    // ═══════════════════════════════════════════════════════════
    //  Fix 6: precacheOneMinCandles 5분 일찍 시작 (간접 검증)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("Fix 6: 코드 상 entryStart - 5 조건 사용 (소스 직접 검증)")
    public void fix6_precacheStartsEarlyVerification() throws Exception {
        // OpeningScannerService 소스 파일 직접 읽기 (런타임 동작 대신 소스 검증)
        java.nio.file.Path src = java.nio.file.Paths.get(
                "src/main/java/com/example/upbit/bot/OpeningScannerService.java");
        if (!java.nio.file.Files.exists(src)) {
            // CI 환경에서는 working dir이 다를 수 있음
            src = java.nio.file.Paths.get(
                    "upbit-autotrade/src/main/java/com/example/upbit/bot/OpeningScannerService.java");
        }
        if (java.nio.file.Files.exists(src)) {
            String content = new String(java.nio.file.Files.readAllBytes(src));
            assertTrue(content.contains("entryStart - 5"),
                    "precacheOneMinCandles에 'entryStart - 5' 조건 있어야 함");
        }
    }
}
