package com.example.upbit.bot;

import com.example.upbit.db.AllDayScannerConfigEntity;
import com.example.upbit.db.AllDayScannerConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V139 시나리오 테스트 — AllDay entry_start 09:00 → 10:05 복구 검증
 *
 * 배경:
 *   V132가 entry_start를 09:00으로 변경했으나 session_end=08:59와 충돌하여
 *   09:00~09:59 매수가 즉시 HC_SESSION_END로 강제 청산되는 BUG 발생.
 *   V139 마이그레이션이 entry_start를 V120 이전 값(10:05)으로 복구했는지 검증.
 *
 * 검증 항목 (V139는 entry_start만 변경):
 *   F01 — entry_start_hour=10, entry_start_min=5 적용 확인
 *   F02 — 시간 바톤 구조 복원 검증 (entry_start > MR/OP 시간대)
 *   F03 — V139 후 09:00~09:59 매수는 entry window 외 (BUG 재발 차단)
 *   F04 — 10:05 이후 매수는 정상 entry window 내
 */
@SpringBootTest
@ActiveProfiles("h2")
public class V139FixAllDayEntryStartScenarioTest {

    @Autowired
    private AllDayScannerConfigRepository configRepo;

    /** F01: V139 적용 후 entry_start = 10:05 */
    @Test
    public void F01_entryStartRestoredTo1005() {
        AllDayScannerConfigEntity cfg = configRepo.findById(1).orElseThrow();
        assertEquals(10, cfg.getEntryStartHour(),
                "V139 적용 후 entry_start_hour는 10이어야 함 (V132 BUG 복구)");
        assertEquals(5, cfg.getEntryStartMin(),
                "V139 적용 후 entry_start_min은 5여야 함 (V120 시간 바톤 구조 복원)");
    }

    /**
     * F02: 시간 바톤 구조 검증
     *   - 08:50~08:59 MR Range
     *   - 09:00~09:04 MR Entry (5분)
     *   - 09:05~10:04 OP Entry (1시간)
     *   - 10:05~23:59 AD Entry (14시간) ← V139 복구 대상
     */
    @Test
    public void F02_timeBatonStructureRestored() {
        AllDayScannerConfigEntity cfg = configRepo.findById(1).orElseThrow();
        int adEntryStartMinutes = cfg.getEntryStartHour() * 60 + cfg.getEntryStartMin();
        int opEntryEndMinutes = 10 * 60 + 4; // OP entry_end_hour=10, entry_end_min=4

        assertTrue(adEntryStartMinutes > opEntryEndMinutes,
                String.format("AD entry_start(%d분)는 OP entry_end(%d분)보다 커야 시간 바톤 구조 유효 — V139 후 605 > 604",
                        adEntryStartMinutes, opEntryEndMinutes));
    }

    /**
     * F03: 09:00~09:59 시간대는 V139 후 AD entry window 밖
     *   - V132 BUG 시: 09:09 매수 → 즉시 HC_SESSION_END 발동
     *   - V139 fix 후: 09:09 매수 자체가 차단 (entry window 외)
     */
    @Test
    public void F03_morningTimeIsOutsideEntryWindow() {
        AllDayScannerConfigEntity cfg = configRepo.findById(1).orElseThrow();
        int entryStart = cfg.getEntryStartHour() * 60 + cfg.getEntryStartMin();

        // 5/7 ORCA BUG 발생 시점들 (모두 V139 후 entry window 외여야 함)
        int orca = 9 * 60 + 9;  // 09:09 (ORCA 매수 시점)
        int akt = 9 * 60 + 15;  // 09:15 (AKT 매수 시점)
        int nineAm = 9 * 60;    // 09:00

        assertTrue(orca < entryStart,
                String.format("09:09 ORCA는 V139 후 entry window 외 (entryStart=%d분)", entryStart));
        assertTrue(akt < entryStart,
                "09:15 AKT는 V139 후 entry window 외");
        assertTrue(nineAm < entryStart,
                "09:00은 V139 후 entry window 외");
    }

    /** F04: 10:05 이후는 V139 후 entry window 내 */
    @Test
    public void F04_legitimateEntryTimeInsideWindow() {
        AllDayScannerConfigEntity cfg = configRepo.findById(1).orElseThrow();
        int entryStart = cfg.getEntryStartHour() * 60 + cfg.getEntryStartMin();

        int tenOhFive = 10 * 60 + 5;     // 10:05
        int afternoon = 15 * 60;         // 15:00
        int evening = 22 * 60;           // 22:00

        assertTrue(tenOhFive >= entryStart,
                "10:05은 entry window 시작점 (포함)");
        assertTrue(afternoon >= entryStart,
                "15:00 오후는 entry window 내");
        assertTrue(evening >= entryStart,
                "22:00 저녁은 entry window 내");
    }
}
