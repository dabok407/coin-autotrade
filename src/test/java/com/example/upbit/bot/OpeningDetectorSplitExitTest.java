package com.example.upbit.bot;

import com.example.upbit.market.SharedPriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpeningBreakoutDetector Split-Exit 시나리오 테스트.
 *
 * positionCache: [avgPrice, openedAtEpochMs, volumeRank]
 * peakPrices/troughPrices/tpActivated: 별도 ConcurrentHashMap
 * splitPhaseMap: 별도 ConcurrentHashMap
 *
 * 시나리오 8개:
 *   1. 정상 1차 매도 (+splitTpPct 도달, 60% 매도 콜백)
 *   2. 정상 2차 매도 (TP_TRAIL drop, splitPhase=1)
 *   3. 정상 2차 Breakeven SL (매수가 하락)
 *   4. 1차 미달 (splitTpPct 미도달)
 *   5. 재발동 차단 (splitPhase=1에서 1차 재발동 안 됨)
 *   6. 비활성 시 기존 TP_TRAIL 유지
 *   7. 1차 후 peak 리셋 + splitPhase=1 확인
 *   8. dust (잔량 < 최소금액) — detector는 1차 매도만 트리거, dust 판단은 서비스에서
 */
@ExtendWith(MockitoExtension.class)
public class OpeningDetectorSplitExitTest {

    private OpeningBreakoutDetector detector;

    // 콜백 기록
    private final List<String> sellMarkets = new ArrayList<String>();
    private final List<String> sellTypes = new ArrayList<String>();
    private final List<String> sellReasons = new ArrayList<String>();

    @BeforeEach
    void setUp() {
        detector = new OpeningBreakoutDetector(mock(SharedPriceService.class));
        detector.setConfirmMinIntervalMs(0);
        sellMarkets.clear();
        sellTypes.clear();
        sellReasons.clear();

        detector.setListener(new OpeningBreakoutDetector.BreakoutListener() {
            @Override
            public void onBreakoutConfirmed(String market, double price, double rangeHigh, double breakoutPctActual) {}

            @Override
            public void onTpSlTriggered(String market, double price, String sellType, String reason) {
                sellMarkets.add(market);
                sellTypes.add(sellType);
                sellReasons.add(reason);
            }
        });

        // Split-Exit 설정
        detector.setSplitExitEnabled(true);
        detector.setSplitTpPct(1.5);
        detector.setSplitRatio(0.60);
        detector.setTrailDropAfterSplit(1.0);
        detector.setSplit1stTrailDropPct(0.5);  // V115: 1차 TRAIL drop 0.5%
        detector.setSplit1stCooldownSec(0);     // V126: 기본 테스트는 쿨다운 없음 (V126-* 테스트에서 개별 설정)
        // V130: Trail Ladder 비활성 (기존 단일값 테스트 유지)
        detector.setTrailLadder(false, 0.5, 1.0, 1.5, 2.0, 1.0, 1.2, 1.5, 2.0);

        // 기존 TP 설정
        detector.setTpActivatePct(2.0);
        detector.setTrailFromPeakPct(1.0);

        // SL 설정 (Wide 15분 / Tight)
        detector.updateSlConfig(30, 15, 6.0, 5.0, 3.5, 3.0, 1.5);
    }

    // ═══════════════════════════════════════════════════
    //  S01: 정상 1차 매도
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S01: +1.6% armed → peak 102 → drop 0.6% → SPLIT_1ST 콜백")
    void s01_normalFirstSplit() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-A", 100.0, now - 120_000, 5);

        // V115: +1.6% → armed (매도 안 함)
        checkRealtimeTp("KRW-A", 101.6);
        assertTrue(sellMarkets.isEmpty(), "armed만, 매도 안 함");

        // peak 갱신
        checkRealtimeTp("KRW-A", 102.0);
        assertTrue(sellMarkets.isEmpty(), "peak 갱신 중");

        // drop 0.6% (peak 102 → 101.4) → SPLIT_1ST 발동
        checkRealtimeTp("KRW-A", 101.4);

        assertEquals(1, sellMarkets.size(), "1차 매도 콜백 1회");
        assertEquals("SPLIT_1ST", sellTypes.get(0));
        assertEquals(1, detector.getSplitPhase("KRW-A"), "splitPhase=1");
    }

    // ═══════════════════════════════════════════════════
    //  S02: 정상 2차 매도 (TP_TRAIL drop)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S02: splitPhase=1에서 피크 대비 -1% drop → SPLIT_2ND_TRAIL")
    void s02_normalSecondTrail() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-B", 100.0, now - 300_000, 5);
        detector.setSplitPhase("KRW-B", 1);

        // 피크를 103으로 올리기
        checkRealtimeTp("KRW-B", 103.0);
        assertEquals(0, sellMarkets.size(), "피크 업데이트만, 매도 안 됨");

        // 피크 103에서 -1.1% → 101.87
        checkRealtimeTp("KRW-B", 101.8);

        assertEquals(1, sellMarkets.size(), "2차 매도 콜백");
        assertEquals("SPLIT_2ND_TRAIL", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════
    //  S03 (V129): BEV 제거 — 매수가 하락 시 매도 안 됨
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S03 (V129): splitPhase=1에서 매수가 하락해도 매도 안 됨 (BEV 제거, 반등 대기)")
    void s03_normalSecondBreakeven() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-C", 100.0, now - 300_000, 5);
        detector.setSplitPhase("KRW-C", 1);

        // 매수가 100원까지 하락 → peak=100, drop=0% → BEV 제거로 매도 없음
        checkRealtimeTp("KRW-C", 100.0);

        assertTrue(sellMarkets.isEmpty(), "V129: BEV 제거로 매수가 터치해도 매도 안 됨 (반등 기회 확보)");
    }

    // ═══════════════════════════════════════════════════
    //  S04: 1차 미달
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S04: +1.4%로 splitTpPct 미달 → 매도 안 됨")
    void s04_firstSplitNotReached() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-D", 100.0, now - 120_000, 5);

        checkRealtimeTp("KRW-D", 101.4);

        assertTrue(sellMarkets.isEmpty(), "1차 조건 미달");
        assertEquals(0, detector.getSplitPhase("KRW-D"), "splitPhase 유지=0");
    }

    // ═══════════════════════════════════════════════════
    //  S05: 재발동 차단
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S05: splitPhase=1에서 +1.5% 도달해도 1차 재발동 안 됨")
    void s05_noReFireAfterFirstSplit() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-E", 100.0, now - 300_000, 5);
        detector.setSplitPhase("KRW-E", 1);

        // +1.8% → splitPhase=1이므로 1차 재발동 안 됨
        // pnlPct > 0이므로 breakeven도 아님, trail drop도 아직 안 됨
        checkRealtimeTp("KRW-E", 101.8);

        assertTrue(sellMarkets.isEmpty(), "splitPhase=1에서 1차 재발동 없음");
    }

    // ═══════════════════════════════════════════════════
    //  S06: 비활성 시 기존 TP_TRAIL 유지
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S06: splitExitEnabled=false → 기존 TP_TRAIL 전량 매도")
    void s06_splitDisabledFallback() throws Exception {
        detector.setSplitExitEnabled(false);

        long now = System.currentTimeMillis();
        detector.addPosition("KRW-F", 100.0, now - 300_000, 5);

        // +2.1% → TP 활성화
        checkRealtimeTp("KRW-F", 102.1);
        assertEquals(0, sellMarkets.size(), "TP 활성화만, 매도 아님");

        // 피크 103으로 올리기
        checkRealtimeTp("KRW-F", 103.0);

        // 피크 대비 -1.1% drop
        checkRealtimeTp("KRW-F", 101.8);

        assertEquals(1, sellMarkets.size());
        assertEquals("TP_TRAIL", sellTypes.get(0), "기존 TP_TRAIL 매도");
    }

    // ═══════════════════════════════════════════════════
    //  S07: 1차 후 peak 리셋 + splitPhase 확인
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S07: 1차 매도 후 peak 리셋, splitPhase=1, tpActivated=false + 2차 trail")
    void s07_peakResetAfterFirstSplit() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-G", 100.0, now - 120_000, 5);

        // V115: +1.6% armed → peak 102 → drop 0.6% → 1차 매도
        checkRealtimeTp("KRW-G", 101.6);  // armed
        checkRealtimeTp("KRW-G", 102.0);  // peak 갱신
        checkRealtimeTp("KRW-G", 101.4);  // drop 0.59% → SPLIT_1ST

        assertEquals(1, sellTypes.size());
        assertEquals("SPLIT_1ST", sellTypes.get(0));
        assertEquals(1, detector.getSplitPhase("KRW-G"));

        // 1차 후 peak 리셋 = 101.4 (매도 체결가)
        sellMarkets.clear();
        sellTypes.clear();

        // 102까지 재상승 → 2차 peak 갱신
        checkRealtimeTp("KRW-G", 102.0);
        assertTrue(sellMarkets.isEmpty(), "2차 peak 갱신, 매도 안 됨");

        // 피크 102에서 -1.1% drop → 2차 TRAIL
        checkRealtimeTp("KRW-G", 100.9);
        assertEquals(1, sellTypes.size());
        assertEquals("SPLIT_2ND_TRAIL", sellTypes.get(0), "2차 trail 매도");
    }

    // ═══════════════════════════════════════════════════
    //  S08: SL은 splitPhase 무관하게 기존대로 작동
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S08: splitPhase=0에서 -1.5% SL_TIGHT 발동 (split보다 SL 우선)")
    void s08_slStillWorksWithSplit() throws Exception {
        long now = System.currentTimeMillis();
        // 20분 경과 (Tight SL 구간)
        detector.addPosition("KRW-H", 100.0, now - 20 * 60_000, 5);

        // -1.6% → SL_TIGHT 발동 (split과 무관)
        checkRealtimeTp("KRW-H", 98.4);

        assertEquals(1, sellMarkets.size());
        assertEquals("SL_TIGHT", sellTypes.get(0), "SL은 split 무관하게 작동");
    }

    // ═══════════════════════════════════════════════════
    //  S09: dust 판정은 서비스에서 하지만, detector는 1차 매도 트리거만
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S09: 소량 포지션에서도 armed+drop 후 SPLIT_1ST 트리거 (dust는 서비스에서 판단)")
    void s09_smallQtyStillTriggersFirst() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-DUST", 100.0, now - 120_000, 5);

        // V115: +1.6% armed → peak 102 → drop → SPLIT_1ST
        checkRealtimeTp("KRW-DUST", 101.6);  // armed
        checkRealtimeTp("KRW-DUST", 102.0);  // peak
        checkRealtimeTp("KRW-DUST", 101.4);  // drop 0.59% → SPLIT_1ST

        assertEquals(1, sellTypes.size());
        assertEquals("SPLIT_1ST", sellTypes.get(0), "detector는 항상 SPLIT_1ST 트리거");
        assertEquals(1, detector.getSplitPhase("KRW-DUST"), "splitPhase=1");
    }

    // ═══════════════════════════════════════════════════
    //  S10: updatePositionCache로 splitPhase 복원
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S10: updatePositionCache — splitPhase=1 복원 확인")
    void s10_cacheRebuildSplitPhase() throws Exception {
        Map<String, Double> positions = new HashMap<String, Double>();
        positions.put("KRW-RESTORE", 100.0);
        Map<String, Long> openedAt = new HashMap<String, Long>();
        openedAt.put("KRW-RESTORE", System.currentTimeMillis() - 300_000);
        Map<String, Integer> splitPhases = new HashMap<String, Integer>();
        splitPhases.put("KRW-RESTORE", 1);

        detector.updatePositionCache(positions, openedAt, splitPhases);

        assertEquals(1, detector.getSplitPhase("KRW-RESTORE"), "splitPhase=1 복원");
    }

    // ═══════════════════════════════════════════════════
    //  S11 (V129): 2차 매도 (TRAIL) 트리거 — BEV 제거 후 TRAIL만 경로
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S11 (V129): splitPhase=1 + peak 대비 1%+ drop → SPLIT_2ND_TRAIL 매도 콜백")
    void s11_removePositionClearsSplitPhase() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-RM", 100.0, now - 300_000, 5);
        detector.setSplitPhase("KRW-RM", 1);

        // peak 102 갱신
        checkRealtimeTp("KRW-RM", 102.0);
        assertTrue(sellMarkets.isEmpty(), "peak 갱신만");

        // peak 대비 -1.2% drop → SPLIT_2ND_TRAIL (trailDropAfterSplit=1.0%)
        checkRealtimeTp("KRW-RM", 100.8);

        assertEquals(1, sellTypes.size());
        assertEquals("SPLIT_2ND_TRAIL", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════
    //  N1 (V115): armed 전환만, 매도 없음
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N1: +1.6% armed → 매도 콜백 없음, splitPhase=0 유지")
    void n1_armedOnlyNoSell() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-N1", 100.0, now - 120_000, 5);

        checkRealtimeTp("KRW-N1", 101.6);

        assertTrue(sellMarkets.isEmpty(), "armed만, 매도 콜백 없음");
        assertEquals(0, detector.getSplitPhase("KRW-N1"), "splitPhase=0 유지");
    }

    // ═══════════════════════════════════════════════════
    //  N2 (V115): armed + drop 미달 → 매도 없음
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N2: armed 후 drop 0.3% (<0.5%) → 매도 없음")
    void n2_armedDropBelowThreshold() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-N2", 100.0, now - 120_000, 5);

        checkRealtimeTp("KRW-N2", 101.6);  // armed
        checkRealtimeTp("KRW-N2", 102.0);  // peak 102
        checkRealtimeTp("KRW-N2", 101.7);  // drop 0.29% < 0.5%

        assertTrue(sellMarkets.isEmpty(), "drop 미달, 매도 없음");
        assertEquals(0, detector.getSplitPhase("KRW-N2"), "splitPhase=0 유지");
    }

    // ═══════════════════════════════════════════════════
    //  N3 (V115): armed 후 peak 계속 상승 → 매도 없음
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N3: armed 후 peak 상승 (101.6→103→105) → 매도 콜백 없음")
    void n3_peakKeepsGrowing() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-N3", 100.0, now - 120_000, 5);

        checkRealtimeTp("KRW-N3", 101.6);
        checkRealtimeTp("KRW-N3", 103.0);
        checkRealtimeTp("KRW-N3", 105.0);

        assertTrue(sellMarkets.isEmpty(), "peak 갱신 중 매도 없음");
        assertEquals(0, detector.getSplitPhase("KRW-N3"), "splitPhase=0 유지");
    }

    // ═══════════════════════════════════════════════════
    //  N7 (V115): split_1st_trail_drop 가변값 (1.0%)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N7: split_1st_trail_drop=1.0% → drop 0.58% 미달, 1.08% 도달 시 매도")
    void n7_variableTrailDrop() throws Exception {
        detector.setSplit1stTrailDropPct(1.0);  // 0.5 → 1.0%

        long now = System.currentTimeMillis();
        detector.addPosition("KRW-N7", 100.0, now - 120_000, 5);

        checkRealtimeTp("KRW-N7", 101.6);
        checkRealtimeTp("KRW-N7", 102.5);
        checkRealtimeTp("KRW-N7", 101.9);  // drop 0.58% < 1.0%
        assertTrue(sellMarkets.isEmpty(), "drop 0.58% 미달");

        checkRealtimeTp("KRW-N7", 101.4);  // drop 1.08% >= 1.0%
        assertEquals(1, sellTypes.size(), "drop 1.08% → SPLIT_1ST");
        assertEquals("SPLIT_1ST", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════
    //  N4 (V115): armed 후 노이즈 패턴 (하락→재상승) 오발동 방지
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N4: armed 후 노이즈 (+1.6→+1.3→+2.5→+2.0→+1.9) → peak 갱신 후 drop 0.58% 도달 시 매도")
    void n4_noisePatternNoFalseTrigger() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-N4", 100.0, now - 120_000, 5);

        checkRealtimeTp("KRW-N4", 101.6);  // armed, peak=101.6
        checkRealtimeTp("KRW-N4", 101.3);  // drop 0.29% < 0.5% → 매도 없음
        assertTrue(sellMarkets.isEmpty(), "첫 노이즈 drop 0.29% 미달");

        checkRealtimeTp("KRW-N4", 102.5);  // peak 갱신 → 102.5
        assertTrue(sellMarkets.isEmpty(), "peak 갱신 중 매도 없음");

        checkRealtimeTp("KRW-N4", 102.0);  // drop 0.49% < 0.5% → 경계 미달
        assertTrue(sellMarkets.isEmpty(), "경계값 0.49% 미달");

        checkRealtimeTp("KRW-N4", 101.9);  // drop 0.58% >= 0.5% → SPLIT_1ST
        assertEquals(1, sellTypes.size(), "drop 0.58% → SPLIT_1ST 발동");
        assertEquals("SPLIT_1ST", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════
    //  N8 (V115): armed=1 상태 + 급락 → SL 발동 (OpeningDetector는 SL이 Split 앞에 배치되어 선행)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N8: armed 후 급락 → SL_TIGHT 발동 (SL이 Split 앞에 우선 처리)")
    void n8_armedPlusSlTight() throws Exception {
        long now = System.currentTimeMillis();
        // 20분 경과 (Tight SL 구간)
        detector.addPosition("KRW-N8", 100.0, now - 20 * 60_000, 5);

        checkRealtimeTp("KRW-N8", 101.6);  // armed
        sellMarkets.clear();
        sellTypes.clear();

        // -1.6% 급락 → SL_TIGHT 우선 발동
        checkRealtimeTp("KRW-N8", 98.4);

        assertEquals(1, sellTypes.size());
        assertEquals("SL_TIGHT", sellTypes.get(0), "SL이 Split보다 우선");
    }

    // ═══════════════════════════════════════════════════
    //  N11 (V115-A): 재시작 복구 — armed/peak 재판정
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N11 (재시작 복구): 현재가 +2% 상태에서 updatePositionCache → armed=1 복구")
    void n11_restartRestoresArmed() throws Exception {
        // SharedPriceService mock: 현재가 102 반환
        com.example.upbit.market.SharedPriceService sps = mock(com.example.upbit.market.SharedPriceService.class);
        when(sps.getPrice("KRW-RESTART")).thenReturn(102.0);

        OpeningBreakoutDetector detector2 = new OpeningBreakoutDetector(sps);
        detector2.setSplitExitEnabled(true);
        detector2.setSplitTpPct(1.5);
        detector2.setSplit1stTrailDropPct(0.5);

        Map<String, Double> positions = new HashMap<String, Double>();
        positions.put("KRW-RESTART", 100.0);  // avgPrice 100
        Map<String, Long> openedAt = new HashMap<String, Long>();
        openedAt.put("KRW-RESTART", System.currentTimeMillis() - 300_000);

        detector2.updatePositionCache(positions, openedAt, null);

        // 현재가 102 → pnl +2% >= splitTpPct 1.5% → armed=true 복구
        assertEquals(0, detector2.getSplitPhase("KRW-RESTART"), "splitPhase=0");
        // armed는 private field — 동작으로 검증
        // peak가 102로 복구되어야 함. drop 0.5% 유도 (101.4로 떨어뜨리면 SPLIT_1ST 발동)
        java.lang.reflect.Method m = OpeningBreakoutDetector.class.getDeclaredMethod(
                "checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);
        // detector2에 listener 없으니 콜백 없음. 예외 없이 실행만 확인
        m.invoke(detector2, "KRW-RESTART", 101.4);
    }

    // ═══════════════════════════════════════════════════
    //  V118-1: DB peak 우선 복원 (현재가 무시)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V118-1: DB peak=103.0, armed=true → getPeak()=103.0, isArmed()=true (DB 경로는 sps 미호출)")
    void v118_dbPeakTakesPriority() {
        com.example.upbit.market.SharedPriceService sps = mock(com.example.upbit.market.SharedPriceService.class);
        // DB peak가 있으면 sps.getPrice 미호출이라 stub 불필요

        OpeningBreakoutDetector det = new OpeningBreakoutDetector(sps);
        det.setSplitExitEnabled(true);
        det.setSplitTpPct(1.5);
        det.setSplit1stTrailDropPct(0.5);

        Map<String, Double> positions = new HashMap<String, Double>();
        positions.put("KRW-V118A", 100.0);
        Map<String, Long> openedAt = new HashMap<String, Long>();
        openedAt.put("KRW-V118A", System.currentTimeMillis() - 300_000);
        Map<String, Integer> splitPhases = new HashMap<String, Integer>();
        splitPhases.put("KRW-V118A", 0);

        // DB에 이전 peak=103.0 (+3%), armed=true 저장됨
        Map<String, Double> dbPeaks = new HashMap<String, Double>();
        dbPeaks.put("KRW-V118A", 103.0);
        Map<String, Boolean> dbArmed = new HashMap<String, Boolean>();
        dbArmed.put("KRW-V118A", true);

        det.updatePositionCache(positions, openedAt, splitPhases, dbPeaks, dbArmed);

        assertEquals(103.0, det.getPeak("KRW-V118A"), 0.0001, "DB peak 103.0 복원");
        assertTrue(det.isArmed("KRW-V118A"), "DB armed=true 복원");
    }

    // ═══════════════════════════════════════════════════
    //  V118-2: DB peak=null → V115 fallback (현재가 재판정)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V118-2: dbPeaks=null + 현재가 +2% → V115 fallback로 armed=true, peak=현재가")
    void v118_fallbackToV115WhenNoDbPeak() {
        com.example.upbit.market.SharedPriceService sps = mock(com.example.upbit.market.SharedPriceService.class);
        when(sps.getPrice("KRW-V118B")).thenReturn(102.0);  // +2% (splitTpPct 1.5 초과)

        OpeningBreakoutDetector det = new OpeningBreakoutDetector(sps);
        det.setSplitExitEnabled(true);
        det.setSplitTpPct(1.5);
        det.setSplit1stTrailDropPct(0.5);

        Map<String, Double> positions = new HashMap<String, Double>();
        positions.put("KRW-V118B", 100.0);
        Map<String, Long> openedAt = new HashMap<String, Long>();
        openedAt.put("KRW-V118B", System.currentTimeMillis() - 300_000);
        Map<String, Integer> splitPhases = new HashMap<String, Integer>();
        splitPhases.put("KRW-V118B", 0);

        // DB peak/armed 없음 → V115 fallback
        det.updatePositionCache(positions, openedAt, splitPhases, null, null);

        assertEquals(102.0, det.getPeak("KRW-V118B"), 0.0001, "V115 fallback: 현재가 102로 peak 설정");
        assertTrue(det.isArmed("KRW-V118B"), "V115 fallback: pnl +2% >= 1.5% → armed=true");
    }

    // ═══════════════════════════════════════════════════
    //  V118-3: DB peak 존재 + armed=false → getPeak=DB, isArmed=false
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V118-3: DB peak=101.0(+1%), armed=false (아직 splitTpPct 미도달) → armed=false 유지")
    void v118_dbPeakBelowTpPctKeepsArmedFalse() {
        com.example.upbit.market.SharedPriceService sps = mock(com.example.upbit.market.SharedPriceService.class);
        // DB 경로는 sps 미호출

        OpeningBreakoutDetector det = new OpeningBreakoutDetector(sps);
        det.setSplitExitEnabled(true);
        det.setSplitTpPct(1.5);

        Map<String, Double> positions = new HashMap<String, Double>();
        positions.put("KRW-V118C", 100.0);
        Map<String, Long> openedAt = new HashMap<String, Long>();
        openedAt.put("KRW-V118C", System.currentTimeMillis() - 300_000);
        Map<String, Integer> splitPhases = new HashMap<String, Integer>();
        splitPhases.put("KRW-V118C", 0);

        Map<String, Double> dbPeaks = new HashMap<String, Double>();
        dbPeaks.put("KRW-V118C", 101.0);  // +1% (splitTpPct 1.5 미달)
        Map<String, Boolean> dbArmed = new HashMap<String, Boolean>();
        dbArmed.put("KRW-V118C", false);

        det.updatePositionCache(positions, openedAt, splitPhases, dbPeaks, dbArmed);

        assertEquals(101.0, det.getPeak("KRW-V118C"), 0.0001, "DB peak 그대로 복원");
        assertFalse(det.isArmed("KRW-V118C"), "DB armed=false 복원");
    }

    // ═══════════════════════════════════════════════════
    //  V118-4: DB peak=0 (invalid) → V115 fallback
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V118-4: DB peak=0 (invalid) → V115 fallback 로직 적용")
    void v118_invalidDbPeakFallsBackToV115() {
        com.example.upbit.market.SharedPriceService sps = mock(com.example.upbit.market.SharedPriceService.class);
        when(sps.getPrice("KRW-V118D")).thenReturn(102.0);

        OpeningBreakoutDetector det = new OpeningBreakoutDetector(sps);
        det.setSplitExitEnabled(true);
        det.setSplitTpPct(1.5);

        Map<String, Double> positions = new HashMap<String, Double>();
        positions.put("KRW-V118D", 100.0);
        Map<String, Long> openedAt = new HashMap<String, Long>();
        openedAt.put("KRW-V118D", System.currentTimeMillis() - 300_000);
        Map<String, Integer> splitPhases = new HashMap<String, Integer>();
        splitPhases.put("KRW-V118D", 0);

        Map<String, Double> dbPeaks = new HashMap<String, Double>();
        dbPeaks.put("KRW-V118D", 0.0);  // invalid → fallback
        Map<String, Boolean> dbArmed = new HashMap<String, Boolean>();
        dbArmed.put("KRW-V118D", false);

        det.updatePositionCache(positions, openedAt, splitPhases, dbPeaks, dbArmed);

        // V115 fallback → 현재가 102로 peak, armed=true (+2%)
        assertEquals(102.0, det.getPeak("KRW-V118D"), 0.0001, "invalid DB → V115 fallback 현재가 사용");
        assertTrue(det.isArmed("KRW-V118D"), "V115 fallback 재판정 armed=true");
    }

    // ═══════════════════════════════════════════════════
    //  V118-5: splitPhase=1 상태 + DB peak → peak 복원 유지 (2차 TRAIL 계속)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V118-5: 이미 1차 매도 완료(phase=1) + DB peak → peak 복원 (2차 TRAIL 연속성)")
    void v118_phase1WithDbPeakPreserved() {
        com.example.upbit.market.SharedPriceService sps = mock(com.example.upbit.market.SharedPriceService.class);
        // DB 경로는 sps 미호출

        OpeningBreakoutDetector det = new OpeningBreakoutDetector(sps);
        det.setSplitExitEnabled(true);
        det.setSplitTpPct(1.5);
        det.setTrailDropAfterSplit(1.0);

        Map<String, Double> positions = new HashMap<String, Double>();
        positions.put("KRW-V118E", 100.0);
        Map<String, Long> openedAt = new HashMap<String, Long>();
        openedAt.put("KRW-V118E", System.currentTimeMillis() - 600_000);
        Map<String, Integer> splitPhases = new HashMap<String, Integer>();
        splitPhases.put("KRW-V118E", 1);  // 1차 완료 상태

        Map<String, Double> dbPeaks = new HashMap<String, Double>();
        dbPeaks.put("KRW-V118E", 105.0);  // 1차 매도 후 peak 계속 갱신된 상태
        Map<String, Boolean> dbArmed = new HashMap<String, Boolean>();
        dbArmed.put("KRW-V118E", false);  // 1차 후 armed 리셋됨

        det.updatePositionCache(positions, openedAt, splitPhases, dbPeaks, dbArmed);

        assertEquals(1, det.getSplitPhase("KRW-V118E"), "phase=1 유지");
        assertEquals(105.0, det.getPeak("KRW-V118E"), 0.0001, "phase=1에서도 DB peak 복원");
    }

    // ═══════════════════════════════════════════════════
    //  V126-1: SPLIT_2ND_BEV 쿨다운 내 차단
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V126-1: SPLIT_1ST 직후 쿨다운 중 -0.1%로 하락 → SPLIT_2ND_BEV 차단")
    void v126_bevBlockedDuringCooldown() throws Exception {
        detector.setSplit1stCooldownSec(60);  // 60s 쿨다운

        long now = System.currentTimeMillis();
        detector.addPosition("KRW-V126A", 100.0, now - 120_000, 5);

        // 1차 매도 트리거: armed → peak → drop
        checkRealtimeTp("KRW-V126A", 101.6);  // armed
        checkRealtimeTp("KRW-V126A", 102.0);  // peak
        checkRealtimeTp("KRW-V126A", 101.4);  // SPLIT_1ST
        assertEquals("SPLIT_1ST", sellTypes.get(0));
        sellMarkets.clear();
        sellTypes.clear();

        // 즉시 매수가 이하(-0.1%)로 하락 → BEV 조건 충족이나 쿨다운으로 차단
        checkRealtimeTp("KRW-V126A", 99.9);
        assertTrue(sellMarkets.isEmpty(), "쿨다운 중 SPLIT_2ND_BEV 차단");
    }

    // ═══════════════════════════════════════════════════
    //  V126-2: SPLIT_2ND_TRAIL 쿨다운 내 차단 + peak 갱신 유지
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V126-2: 쿨다운 중 peak 갱신 유지 + SPLIT_2ND_TRAIL drop 차단")
    void v126_trailBlockedDuringCooldownPeakKept() throws Exception {
        detector.setSplit1stCooldownSec(60);

        long now = System.currentTimeMillis();
        detector.addPosition("KRW-V126B", 100.0, now - 120_000, 5);
        detector.setSplitPhase("KRW-V126B", 1);
        // 쿨다운 기준 시점 설정 (현재 시각)
        java.lang.reflect.Field f = OpeningBreakoutDetector.class.getDeclaredField("split1stExecutedAtMap");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> execMap = (java.util.Map<String, Long>) f.get(detector);
        execMap.put("KRW-V126B", System.currentTimeMillis());

        // peak 103으로 갱신 (쿨다운 중)
        checkRealtimeTp("KRW-V126B", 103.0);
        assertTrue(sellMarkets.isEmpty(), "peak 갱신만 — 매도 없음");

        // peak 103에서 -1.5% drop (trailDropAfterSplit=1.0 초과) → 쿨다운으로 차단
        checkRealtimeTp("KRW-V126B", 101.4);
        assertTrue(sellMarkets.isEmpty(), "쿨다운 중 SPLIT_2ND_TRAIL 차단");

        // peak는 갱신 유지되었는지 확인 — getPeak 103
        assertEquals(103.0, detector.getPeak("KRW-V126B"), 0.0001, "쿨다운 중에도 peak 갱신 유지");
    }

    // ═══════════════════════════════════════════════════
    //  V126-3 (V129): 쿨다운 만료 후 SPLIT_2ND_TRAIL 허용 (BEV 제거)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V126-3 (V129): 쿨다운 만료 후 peak -1.2% drop → SPLIT_2ND_TRAIL 발동")
    void v126_bevAllowedAfterCooldown() throws Exception {
        detector.setSplit1stCooldownSec(60);

        long now = System.currentTimeMillis();
        detector.addPosition("KRW-V126C", 100.0, now - 120_000, 5);
        detector.setSplitPhase("KRW-V126C", 1);

        // 쿨다운 기준 시점을 과거로 설정 (이미 만료)
        java.lang.reflect.Field f = OpeningBreakoutDetector.class.getDeclaredField("split1stExecutedAtMap");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> execMap = (java.util.Map<String, Long>) f.get(detector);
        execMap.put("KRW-V126C", System.currentTimeMillis() - 120_000);  // 120초 전

        // peak 102 갱신 후 -1.2% drop → 쿨다운 만료 + TRAIL 조건 충족 → 매도
        checkRealtimeTp("KRW-V126C", 102.0);
        checkRealtimeTp("KRW-V126C", 100.8);
        assertEquals(1, sellTypes.size(), "쿨다운 만료 후 TRAIL 매도 발동");
        assertEquals("SPLIT_2ND_TRAIL", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════
    //  V126-4: SL_WIDE는 쿨다운 영향 없음 (별도 경로)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V126-4: 쿨다운 중이어도 SL_WIDE는 즉시 발동 (SL 경로 별개)")
    void v126_slWideNotAffectedByCooldown() throws Exception {
        detector.setSplit1stCooldownSec(60);

        long now = System.currentTimeMillis();
        // 2분 경과 (grace 30s 통과, SL_WIDE 구간)
        detector.addPosition("KRW-V126D", 100.0, now - 120_000, 5);
        detector.setSplitPhase("KRW-V126D", 1);

        java.lang.reflect.Field f = OpeningBreakoutDetector.class.getDeclaredField("split1stExecutedAtMap");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> execMap = (java.util.Map<String, Long>) f.get(detector);
        execMap.put("KRW-V126D", System.currentTimeMillis());  // 지금 막 1차 체결

        // -6.5% 급락 → SL_WIDE (TOP 5 = wideSlTop10 6.0%) 발동
        checkRealtimeTp("KRW-V126D", 93.5);
        assertEquals(1, sellTypes.size(), "SL_WIDE는 쿨다운 무관 즉시 발동");
        assertEquals("SL_WIDE", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════
    //  V126-5 (V129): 쿨다운 0초 → SPLIT_2ND_TRAIL 즉시 발동 (BEV 제거)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V126-5 (V129): cooldownSec=0 → 쿨다운 비활성 → SPLIT_2ND_TRAIL 즉시 발동")
    void v126_cooldownZeroAllowsImmediate() throws Exception {
        detector.setSplit1stCooldownSec(0);  // 쿨다운 끔

        long now = System.currentTimeMillis();
        detector.addPosition("KRW-V126E", 100.0, now - 120_000, 5);

        // 1차 매도 트리거
        checkRealtimeTp("KRW-V126E", 101.6);
        checkRealtimeTp("KRW-V126E", 102.0);
        checkRealtimeTp("KRW-V126E", 101.4);  // SPLIT_1ST (peak 리셋 = 101.4)
        sellMarkets.clear();
        sellTypes.clear();

        // 즉시 -1.5% 하락 → peak 101.4 기준 drop 1.48% ≥ 1.0% → TRAIL 즉시 발동
        checkRealtimeTp("KRW-V126E", 99.9);
        assertEquals(1, sellTypes.size(), "쿨다운 0초 → TRAIL 즉시 매도");
        assertEquals("SPLIT_2ND_TRAIL", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════
    //  V126-6: DB split1stExecutedAt 복원 — 쿨다운 이어가기
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V126-6: 재시작 시 DB split1stExecutedAt 복원 → 쿨다운 상태 유지")
    void v126_restartRestoresCooldown() throws Exception {
        com.example.upbit.market.SharedPriceService sps = mock(com.example.upbit.market.SharedPriceService.class);
        OpeningBreakoutDetector det = new OpeningBreakoutDetector(sps);
        det.setSplitExitEnabled(true);
        det.setSplitTpPct(1.5);
        det.setTrailDropAfterSplit(1.0);
        det.setSplit1stCooldownSec(60);

        det.setListener(new OpeningBreakoutDetector.BreakoutListener() {
            @Override
            public void onBreakoutConfirmed(String market, double price, double rangeHigh, double boPct) {}
            @Override
            public void onTpSlTriggered(String market, double price, String sellType, String reason) {
                sellMarkets.add(market);
                sellTypes.add(sellType);
            }
        });

        Map<String, Double> positions = new HashMap<String, Double>();
        positions.put("KRW-V126F", 100.0);
        Map<String, Long> openedAt = new HashMap<String, Long>();
        openedAt.put("KRW-V126F", System.currentTimeMillis() - 600_000);
        Map<String, Integer> splitPhases = new HashMap<String, Integer>();
        splitPhases.put("KRW-V126F", 1);
        Map<String, Double> dbPeaks = new HashMap<String, Double>();
        dbPeaks.put("KRW-V126F", 101.5);
        Map<String, Boolean> dbArmed = new HashMap<String, Boolean>();
        dbArmed.put("KRW-V126F", false);
        Map<String, Long> dbSplitExec = new HashMap<String, Long>();
        dbSplitExec.put("KRW-V126F", System.currentTimeMillis() - 10_000);  // 10초 전

        det.updatePositionCache(positions, openedAt, splitPhases, dbPeaks, dbArmed, dbSplitExec);

        // 쿨다운 남아있음 (60초 - 10초 = 50초 남음)
        java.lang.reflect.Method m = OpeningBreakoutDetector.class.getDeclaredMethod(
                "checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);
        m.invoke(det, "KRW-V126F", 99.9);  // BEV 조건이지만 쿨다운

        assertTrue(sellMarkets.isEmpty(), "재시작 후에도 쿨다운 이어가기 → 차단");
    }

    // ═══════════════════════════════════════════════════
    //  V129-GR1: Grace 내 SPLIT_1ST armed 차단 (Grace 60s 이내 +1.6% 돌파 시도)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-GR1: Grace 60s 내에서 +1.6% 도달해도 SPLIT_1ST armed 안 됨 (V129 확장)")
    void t_v129_gr1_graceBlocksSplitArmed() throws Exception {
        detector.updateSlConfig(60, 5, 5.0, 5.0, 5.0, 5.0, 3.0);
        long openedAt = System.currentTimeMillis() - 20_000L;  // Grace 20초 경과 (60s 이내)
        detector.addPosition("KRW-TEST", 100.0, openedAt, 999);

        // +1.6% (splitTpPct 1.5% 초과) — Grace 내 → armed 안 됨
        checkRealtimeTp("KRW-TEST", 101.6);

        assertFalse(detector.isArmed("KRW-TEST"),
                "Grace 내에서는 armed 조건 도달해도 차단");
        assertTrue(sellMarkets.isEmpty(), "Grace 내 매도 없음");
    }

    // ═══════════════════════════════════════════════════
    //  V129-GR2: Grace 내 SPLIT_1ST drop 차단 (armed 이후 drop이 Grace 안에서 발생)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-GR2: Grace 내에서는 armed 이후 drop 0.6% 발생해도 SPLIT_1ST 매도 차단")
    void t_v129_gr2_graceBlocksSplitDrop() throws Exception {
        detector.updateSlConfig(60, 5, 5.0, 5.0, 5.0, 5.0, 3.0);
        // Grace 아주 이내 (10초 경과)
        long openedAt = System.currentTimeMillis() - 10_000L;
        detector.addPosition("KRW-TEST", 100.0, openedAt, 999);

        checkRealtimeTp("KRW-TEST", 101.6);  // +1.6% — Grace 내 armed 차단
        checkRealtimeTp("KRW-TEST", 102.0);  // peak 시도
        checkRealtimeTp("KRW-TEST", 101.3);  // drop — Grace 내 매도 차단

        assertTrue(sellMarkets.isEmpty(),
                "V129: Grace 내 SPLIT_1ST drop도 차단 (꼬리 흡수)");
    }

    // ═══════════════════════════════════════════════════
    //  V129-GR3: Grace 내 TP_TRAIL drop 차단
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-GR3: Grace 내에서는 TP_TRAIL activate + drop 발생해도 매도 차단")
    void t_v129_gr3_graceBlocksTpTrail() throws Exception {
        // splitExit 끈 상태에서 기존 TP_TRAIL 경로 검증
        detector.setSplitExitEnabled(false);
        detector.updateSlConfig(60, 5, 5.0, 5.0, 5.0, 5.0, 3.0);
        long openedAt = System.currentTimeMillis() - 15_000L;  // Grace 15s (60s 이내)
        detector.addPosition("KRW-TEST", 100.0, openedAt, 999);

        checkRealtimeTp("KRW-TEST", 102.5);  // TP activate 시도 (+2.5%)
        checkRealtimeTp("KRW-TEST", 101.3);  // drop 시도

        assertTrue(sellMarkets.isEmpty(),
                "V129: Grace 내 TP_TRAIL도 차단");
    }

    // ═══════════════════════════════════════════════════
    //  V129-GR4: Grace 통과 직후 정상 armed 복구 (회귀 방지)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-GR4: Grace 60s 통과 직후 +1.6% 진입 시 정상 armed 동작")
    void t_v129_gr4_postGraceNormalOperation() throws Exception {
        detector.updateSlConfig(60, 5, 5.0, 5.0, 5.0, 5.0, 3.0);
        // Grace 지나서 진입 (65초 경과)
        long openedAt = System.currentTimeMillis() - 65_000L;
        detector.addPosition("KRW-TEST", 100.0, openedAt, 999);

        checkRealtimeTp("KRW-TEST", 101.6);  // armed 조건
        assertTrue(detector.isArmed("KRW-TEST"),
                "Grace 지난 후 armed 정상 동작");

        checkRealtimeTp("KRW-TEST", 102.0);  // peak
        checkRealtimeTp("KRW-TEST", 101.3);  // drop 0.69%

        assertEquals(1, sellTypes.size(), "Grace 후 SPLIT_1ST 정상 매도");
        assertEquals("SPLIT_1ST", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════
    //  Helper
    // ═══════════════════════════════════════════════════
    private void checkRealtimeTp(String market, double price) throws Exception {
        Method m = OpeningBreakoutDetector.class.getDeclaredMethod(
                "checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);
        m.invoke(detector, market, price);
    }
}
