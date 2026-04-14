package com.example.upbit.bot;

import com.example.upbit.market.SharedPriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.mock;

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
    @DisplayName("S01: +1.5% 도달 → SPLIT_1ST 콜백, splitPhase=1로 갱신")
    void s01_normalFirstSplit() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-A", 100.0, now - 120_000, 5);

        // +1.6% → 1차 매도
        checkRealtimeTp("KRW-A", 101.6);

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
    //  S03: 정상 2차 Breakeven SL
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S03: splitPhase=1에서 매수가까지 하락 → SPLIT_2ND_BEV")
    void s03_normalSecondBreakeven() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-C", 100.0, now - 300_000, 5);
        detector.setSplitPhase("KRW-C", 1);

        // 매수가 100원까지 하락
        checkRealtimeTp("KRW-C", 100.0);

        assertEquals(1, sellMarkets.size());
        assertEquals("SPLIT_2ND_BEV", sellTypes.get(0));
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
    @DisplayName("S07: 1차 매도 후 peak 리셋, splitPhase=1, tpActivated=false")
    void s07_peakResetAfterFirstSplit() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-G", 100.0, now - 120_000, 5);

        // +1.6% → 1차 매도
        checkRealtimeTp("KRW-G", 101.6);

        assertEquals(1, sellTypes.size());
        assertEquals("SPLIT_1ST", sellTypes.get(0));
        assertEquals(1, detector.getSplitPhase("KRW-G"));

        // 1차 후 +2.0% → 2차 trail drop 조건 아직 미달 (peak=101.6에서 약간 상승)
        sellMarkets.clear();
        sellTypes.clear();
        checkRealtimeTp("KRW-G", 102.0);
        assertTrue(sellMarkets.isEmpty(), "아직 매도 안 됨 (trail drop 미달)");

        // 피크 102에서 -1.1% drop
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
    @DisplayName("S09: 소량 포지션에서도 1차 매도 SPLIT_1ST 트리거 (dust는 서비스에서 판단)")
    void s09_smallQtyStillTriggersFirst() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-DUST", 100.0, now - 120_000, 5);

        // +1.6% → 1차 매도 트리거 (qty 무관, detector는 qty를 모름)
        checkRealtimeTp("KRW-DUST", 101.6);

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
    //  S11: 2차 완료 후 removePosition → splitPhase 제거
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S11: 2차 매도 후 removePosition → splitPhase 제거")
    void s11_removePositionClearsSplitPhase() throws Exception {
        long now = System.currentTimeMillis();
        detector.addPosition("KRW-RM", 100.0, now - 300_000, 5);
        detector.setSplitPhase("KRW-RM", 1);

        // 2차 매도 (breakeven)
        checkRealtimeTp("KRW-RM", 100.0);

        assertEquals(1, sellTypes.size());
        assertEquals("SPLIT_2ND_BEV", sellTypes.get(0));
        // removePosition 호출 → splitPhase 제거
        assertEquals(0, detector.getSplitPhase("KRW-RM"), "제거 후 기본값 0");
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
