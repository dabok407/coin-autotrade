package com.example.upbit.bot;

import com.example.upbit.db.PositionEntity;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.strategy.HighConfidenceBreakoutStrategy;
import com.example.upbit.strategy.Signal;
import com.example.upbit.strategy.SignalAction;
import com.example.upbit.strategy.StrategyContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 종일 스캐너 (HighConfidenceBreakoutStrategy) SESSION_END 시간 계산 시나리오 테스트.
 *
 * HCB 종일 전략은 sessionEnd < 12:00 인 경우 "다음날 새벽" overnight 모드로 동작.
 *  - sessionEnd 시각 ~ 10:00 KST 사이 청산
 *  - 10:00 이후는 새 거래일 → 미청산
 *
 * 검증 항목:
 *  1. 정확히 sessionEnd 시각 → 청산 (08:00)
 *  2. sessionEnd 1분 전 → 미청산 (07:59)
 *  3. 윈도우 내 (09:30) → 청산
 *  4. 윈도우 마지막 (09:59) → 청산
 *  5. 윈도우 종료 직후 (10:00) → 미청산 (새 거래일)
 *  6. 윈도우 종료 후 (10:30) → 미청산
 *  7. 오후 시간 (14:00) → 미청산
 *  8. 밤 시간 (22:00) → 미청산
 *  9. 자정 (00:00) → 미청산
 *  10. sessionEnd=09:00 (다른 시각) → 09:00 청산, 08:59 미청산
 *  11. 비-overnight (sessionEnd=14:00) → 14:00 청산
 */
public class AllDayHcbSessionEndScenarioTest {

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    /** 마지막 캔들 시각만 의미있음 — 캔들 80개를 정상 가격으로 채움 */
    private List<UpbitCandle> buildCandlesEndingAtKst(int hour, int minute) {
        List<UpbitCandle> candles = new ArrayList<>();
        // 마지막 캔들 시각: 오늘 KST hour:minute → UTC -9시간
        ZonedDateTime endKst = ZonedDateTime.now(ZoneOffset.ofHours(9))
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        ZonedDateTime endUtc = endKst.withZoneSameInstant(ZoneOffset.UTC);
        ZonedDateTime startUtc = endUtc.minusMinutes(79 * 5);

        double price = 50100; // +0.2% 손실/이익 모두 아님
        for (int i = 0; i < 80; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            c.candle_date_time_utc = startUtc.plusMinutes((long) i * 5)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            c.opening_price = price - 1;
            c.trade_price = price + 1;
            c.high_price = price + 3;
            c.low_price = price - 3;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        return candles;
    }

    private PositionEntity pos(double avg) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket("KRW-TEST");
        pe.setQty(1.0);
        pe.setAvgPrice(avg);
        pe.setAddBuys(0);
        pe.setOpenedAt(Instant.now().minusSeconds(300));
        pe.setEntryStrategy("HIGH_CONFIDENCE_BREAKOUT");
        return pe;
    }

    private StrategyContext ctx(List<UpbitCandle> candles, PositionEntity p) {
        return new StrategyContext("KRW-TEST", 5, candles, p, 0);
    }

    private HighConfidenceBreakoutStrategy newStrategy(int sessionEndHour, int sessionEndMin) {
        return new HighConfidenceBreakoutStrategy()
                .withRisk(5.0, 0.8)
                .withExitFlags(false, false)
                .withTrailActivate(10.0)
                .withTimeStop(0, 0)
                .withTiming(sessionEndHour, sessionEndMin);
    }

    private boolean triggersSessionEnd(int kstHour, int kstMin, int sessionEndHour, int sessionEndMin) {
        List<UpbitCandle> candles = buildCandlesEndingAtKst(kstHour, kstMin);
        Signal s = newStrategy(sessionEndHour, sessionEndMin).evaluate(ctx(candles, pos(50000)));
        return s.action == SignalAction.SELL && s.reason != null && s.reason.contains("HC_SESSION_END");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 1~9: sessionEnd = 08:00 (overnight 기본값)
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 1: 08:00 KST 정확 → SESSION_END 발동")
    public void scenario1_exact_0800() {
        assertTrue(triggersSessionEnd(8, 0, 8, 0));
    }

    @Test
    @DisplayName("시나리오 2: 07:59 KST → SESSION_END 미발동")
    public void scenario2_before_0759() {
        assertFalse(triggersSessionEnd(7, 59, 8, 0));
    }

    @Test
    @DisplayName("시나리오 3: 08:30 KST → SESSION_END 발동 (윈도우 내)")
    public void scenario3_window_0830() {
        assertTrue(triggersSessionEnd(8, 30, 8, 0));
    }

    @Test
    @DisplayName("시나리오 4: 09:59 KST → SESSION_END 발동 (윈도우 마지막)")
    public void scenario4_window_last_0959() {
        assertTrue(triggersSessionEnd(9, 59, 8, 0));
    }

    @Test
    @DisplayName("시나리오 5: ⭐ 10:00 KST → SESSION_END 미발동 (새 거래일)")
    public void scenario5_after_window_1000() {
        assertFalse(triggersSessionEnd(10, 0, 8, 0),
                "10:00은 새 거래일 시작이라 청산 불가");
    }

    @Test
    @DisplayName("시나리오 6: 10:30 KST → SESSION_END 미발동")
    public void scenario6_after_window_1030() {
        assertFalse(triggersSessionEnd(10, 30, 8, 0));
    }

    @Test
    @DisplayName("시나리오 7: 14:00 KST (오후) → SESSION_END 미발동")
    public void scenario7_afternoon_1400() {
        assertFalse(triggersSessionEnd(14, 0, 8, 0));
    }

    @Test
    @DisplayName("시나리오 8: 22:00 KST (밤) → SESSION_END 미발동")
    public void scenario8_night_2200() {
        assertFalse(triggersSessionEnd(22, 0, 8, 0));
    }

    @Test
    @DisplayName("시나리오 9: 00:00 KST (자정) → SESSION_END 미발동")
    public void scenario9_midnight_0000() {
        assertFalse(triggersSessionEnd(0, 0, 8, 0));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 10~11: 다른 sessionEnd 시각 호환성
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 10: sessionEnd=09:00 → 09:00 청산, 08:59 미청산")
    public void scenario10_compat_0900() {
        assertFalse(triggersSessionEnd(8, 59, 9, 0));
        assertTrue(triggersSessionEnd(9, 0, 9, 0));
        assertTrue(triggersSessionEnd(9, 30, 9, 0));
        assertFalse(triggersSessionEnd(10, 0, 9, 0)); // 10시 이후는 새거래일
    }

    @Test
    @DisplayName("시나리오 11: sessionEnd=11:00 → overnight (12시 미만)")
    public void scenario11_compat_1100_overnight() {
        // 11:00 ≥ 시작 시각 이지만 < 12:00 이므로 overnight 모드
        // 그래도 윈도우는 11:00~10:00... 이건 모순. 코드는 단순히 sessionEnd~10:00
        // 11:00 ≥ 11:00 && 11:00 < 10:00 → false → 미청산
        assertFalse(triggersSessionEnd(11, 0, 11, 0),
                "sessionEnd 11:00은 윈도우 11:00~10:00 (모순) 미발동");
    }

    @Test
    @DisplayName("시나리오 12: sessionEnd=14:00 (당일 모드) → 14:00 청산, 13:59 미청산")
    public void scenario12_compat_1400_sameday() {
        // 14:00 ≥ 12:00 → 당일 모드
        assertFalse(triggersSessionEnd(13, 59, 14, 0));
        assertTrue(triggersSessionEnd(14, 0, 14, 0));
        assertTrue(triggersSessionEnd(22, 0, 14, 0)); // 당일 14시 이후 모두 청산
        assertTrue(triggersSessionEnd(23, 59, 14, 0));
    }

    @Test
    @DisplayName("시나리오 13: sessionEnd=22:00 (당일 모드) → 22:00 청산, 21:59 미청산")
    public void scenario13_compat_2200_sameday() {
        assertFalse(triggersSessionEnd(21, 59, 22, 0));
        assertTrue(triggersSessionEnd(22, 0, 22, 0));
        assertTrue(triggersSessionEnd(22, 30, 22, 0));
    }
}
