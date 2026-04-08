package com.example.upbit.bot;

import com.example.upbit.market.SharedPriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 오프닝 스캐너 OpeningBreakoutDetector 시나리오 통합 테스트.
 *
 * SL 종합안 + TOP-N 차등 + 트레일링의 전체 사이클 검증.
 *
 * positionCache 포맷: [avgPrice, openedAtEpochMs, volumeRank]
 */
@ExtendWith(MockitoExtension.class)
public class OpeningScannerScenarioIntegrationTest {

    private OpeningBreakoutDetector detector;
    private final List<String> sellTypes = new ArrayList<>();
    private final List<String> sellReasons = new ArrayList<>();

    /** 시나리오 가격 틱 (매수 후 elapsedSec, price) */
    static class PriceTick {
        final long elapsedSec;
        final double price;
        PriceTick(long elapsedSec, double price) {
            this.elapsedSec = elapsedSec;
            this.price = price;
        }
    }

    @BeforeEach
    void setUp() {
        detector = new OpeningBreakoutDetector(mock(SharedPriceService.class));
        sellTypes.clear();
        sellReasons.clear();

        detector.setListener(new OpeningBreakoutDetector.BreakoutListener() {
            @Override
            public void onBreakoutConfirmed(String market, double price, double rangeHigh, double pct) {}
            @Override
            public void onTpSlTriggered(String market, double price, String sellType, String reason) {
                sellTypes.add(sellType);
                sellReasons.add(reason);
            }
        });

        // SL 종합안 설정 (90일 백테스트 검증값)
        // grace 60s, wide 15분, wide_sl 6.0% (모두 동일), tight_sl 3.0%
        detector.updateSlConfig(60, 15, 6.0, 6.0, 6.0, 6.0, 3.0);
        // TP 트레일링 (오프닝만 활성화)
        detector.setTpActivatePct(2.3);
        detector.setTrailFromPeakPct(1.0);
    }

    /**
     * 시나리오 실행: 매수 + 가격 시퀀스 시뮬레이션 → 매도 결과
     * @return true = 매도 발생
     */
    private boolean runScenario(double avgPrice, int volumeRank, PriceTick[] ticks) throws Exception {
        long now = System.currentTimeMillis();
        // 첫 매수: 현재 시각으로 등록
        detector.addPosition("KRW-TEST", avgPrice, now, volumeRank);

        for (PriceTick tick : ticks) {
            // 시간 조작: positionCache의 openedAt 변경
            ConcurrentHashMap<String, double[]> cache = getPositionCache();
            double[] pos = cache.get("KRW-TEST");
            if (pos == null) return true; // 이미 매도

            pos[1] = now - tick.elapsedSec * 1000L;
            invokeCheckRealtimeTp("KRW-TEST", tick.price);
            if (!cache.containsKey("KRW-TEST")) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 1: 트레일링 정상 익절
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 1: TP 활성화(+2.3%) → peak 갱신 → -1% 트레일링 매도")
    public void scenario1_tpTrailing() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 102.5),  // 2분 후 +2.5% (TP 활성화)
            new PriceTick(180, 105.0),  // 3분 후 +5% (peak 갱신)
            new PriceTick(240, 103.5),  // 4분 후 peak 105 - 1.43% → 매도
        };
        boolean sold = runScenario(100.0, 30, ticks);
        assertTrue(sold);
        assertEquals("TP_TRAIL", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 2: 그레이스 보호 (매수 직후 -10%)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 2: 매수 후 30초 -10% → 그레이스 보호 → 회복 → 트레일링")
    public void scenario2_graceProtection() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(20, 95.0),    // 20초 후 -5% (그레이스)
            new PriceTick(40, 90.0),    // 40초 후 -10% (그레이스)
            new PriceTick(120, 100.0),  // 2분 후 회복
            new PriceTick(180, 102.5),  // 3분 후 +2.5% (TP 활성화)
            new PriceTick(240, 105.0),  // 4분 후 peak 105
            new PriceTick(300, 103.5),  // 5분 후 -1.43% from peak → 트레일링 매도
        };
        boolean sold = runScenario(100.0, 30, ticks);
        assertTrue(sold, "그레이스 보호 후 회복하여 트레일링 매도");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 3: SL_WIDE 발동 (1~15분, -7%)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 3: 매수 후 5분 -7% → SL_WIDE 6% 발동")
    public void scenario3_slWide() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 95.0),   // 2분 후 -5% (SL_WIDE 미달, 유지)
            new PriceTick(300, 93.0),   // 5분 후 -7% → SL_WIDE 발동
        };
        boolean sold = runScenario(100.0, 30, ticks);
        assertTrue(sold);
        assertEquals("SL_WIDE", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 4: SL_TIGHT 발동 (15분 이후, -3.5%)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 4: 매수 후 16분 -3.5% → SL_TIGHT 발동")
    public void scenario4_slTight() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 99.0),    // 2분 후 -1%
            new PriceTick(960, 96.5),    // 16분 후 -3.5% → SL_TIGHT 3% 발동
        };
        boolean sold = runScenario(100.0, 30, ticks);
        assertTrue(sold);
        assertEquals("SL_TIGHT", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 5: SL_WIDE 미달 → 회복
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 5: 매수 후 -5% (6% 미달) → 회복 → 트레일링")
    public void scenario5_shakeRecovery() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 95.0),    // 2분 후 -5% (6% 미달)
            new PriceTick(300, 96.0),    // 5분 후 -4%
            new PriceTick(600, 100.0),   // 10분 후 회복
            new PriceTick(800, 102.5),   // 13분 후 +2.5% (TP 활성화)
            new PriceTick(880, 105.0),   // 14분 40초 후 peak 105 (15분 wide_period 안)
            new PriceTick(890, 103.5),   // 14분 50초 후 -1.43% from peak → 트레일링 매도
        };
        boolean sold = runScenario(100.0, 30, ticks);
        assertTrue(sold, "흔들기 후 회복하여 트레일링 매도");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 6: 트레일링 미활성화 (+2.3% 미달)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 6: 매수 후 +2% (TP 미활성) + -2% → SL 미달 유지")
    public void scenario6_noTrailingNoSl() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 102.0),   // 2분 후 +2% (TP 활성 안 됨)
            new PriceTick(300, 100.5),   // 5분 후 +0.5%
            new PriceTick(600, 98.0),    // 10분 후 -2% (SL_WIDE 6% 미달)
        };
        boolean sold = runScenario(100.0, 30, ticks);
        assertFalse(sold, "TP 트레일링 미활성, SL 미달이라 유지");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 7: TOP 1~10 차등 (현재는 모두 6%로 동일)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 7: TOP 5위 코인 -6% → SL_WIDE 발동")
    public void scenario7_top10Coin() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(300, 94.0),   // 5분 후 -6% → SL_WIDE 발동 (-6.0%)
        };
        boolean sold = runScenario(100.0, 5, ticks);
        assertTrue(sold);
        assertEquals("SL_WIDE", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 8: 트레일링 활성화 후 peak 추가 갱신
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 8: 활성화 후 peak 계속 갱신 → 큰 수익 후 트레일링")
    public void scenario8_peakKeepGrowing() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 102.5),   // 2분 후 +2.5% (활성화)
            new PriceTick(180, 105.0),   // 3분 후 +5%
            new PriceTick(240, 110.0),   // 4분 후 +10% (peak 110)
            new PriceTick(300, 115.0),   // 5분 후 +15% (peak 115)
            new PriceTick(360, 113.0),   // 6분 후 peak 115 - 1.74% → 매도
        };
        boolean sold = runScenario(100.0, 30, ticks);
        assertTrue(sold, "peak 115까지 갱신 후 트레일링 매도");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 9: 그레이스 직후 -7% 급락
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 9: 그레이스 60초 직후 -7% → SL_WIDE 즉시 발동")
    public void scenario9_postGraceCrash() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(30, 98.0),    // 그레이스 안 -2% (보호)
            new PriceTick(70, 93.0),    // 그레이스 직후 -7% → SL_WIDE 발동
        };
        boolean sold = runScenario(100.0, 30, ticks);
        assertTrue(sold);
        assertEquals("SL_WIDE", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 10: 15분 경계 (SL_WIDE → SL_TIGHT 전환)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 10: 14분 -5.5% (SL_WIDE 미달) → 16분 -3.5% (SL_TIGHT 발동)")
    public void scenario10_widePeriodBoundary() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(840, 94.5),   // 14분 후 -5.5% (SL_WIDE 6% 미달)
        };
        boolean sold = runScenario(100.0, 30, ticks);
        assertFalse(sold, "14분 -5.5%는 SL_WIDE 6% 미달이라 유지");

        // 동일 포지션에서 시간만 16분으로 변경
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        double[] pos = cache.get("KRW-TEST");
        long now = System.currentTimeMillis();
        pos[1] = now - 960_000L; // 16분 전
        invokeCheckRealtimeTp("KRW-TEST", 96.5); // -3.5%
        assertFalse(cache.containsKey("KRW-TEST"));
        assertEquals("SL_TIGHT", sellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getPositionCache() throws Exception {
        Field f = OpeningBreakoutDetector.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(detector);
    }

    private void invokeCheckRealtimeTp(String market, double price) throws Exception {
        Method m = OpeningBreakoutDetector.class.getDeclaredMethod(
                "checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);
        m.invoke(detector, market, price);
    }
}
