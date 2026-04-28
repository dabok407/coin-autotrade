package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.SharedPriceService;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.upbit.UpbitPrivateClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 모닝러쉬 시나리오 통합 테스트.
 *
 * 매수 → 시간 경과 → 가격 변동 → 매도 트리거의 전체 사이클을 시뮬레이션.
 * 각 시나리오는 가격 시퀀스 + 시간 경과를 직접 조작하여 SL 종합안의 모든 분기 검증.
 *
 * positionCache 포맷: [avgPrice, qty, openedAtEpochMs, _, _]
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class MorningRushScenarioIntegrationTest {

    @Mock private MorningRushConfigRepository configRepo;
    @Mock private BotConfigRepository botConfigRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private TradeRepository tradeLogRepo;
    @Mock private LiveOrderService liveOrders;
    @Mock private UpbitPrivateClient privateClient;
    @Mock private TransactionTemplate txTemplate;
    @Mock private UpbitMarketCatalogService catalogService;
    @Mock private TickerService tickerService;
    @Mock private SharedPriceService sharedPriceService;

    private MorningRushScannerService scanner;

    /** 가격 시퀀스 + 매수 후 경과시간(초)을 정의하는 시나리오. */
    static class PriceTick {
        final long elapsedSec;  // 매수 후 경과 시간 (초)
        final double price;
        PriceTick(long elapsedSec, double price) {
            this.elapsedSec = elapsedSec;
            this.price = price;
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        ScannerLockService scannerLockService = new ScannerLockService(botConfigRepo, positionRepo, tradeLogRepo);
        scanner = new MorningRushScannerService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, privateClient, txTemplate, catalogService, tickerService,
                sharedPriceService, new SharedTradeThrottle(), scannerLockService
        );
        setField("running", new AtomicBoolean(true));

        // SL 종합안 캐시 (DB 설정값 시뮬레이션)
        // 90일 백테스트 검증값: grace 60s, wide 30분, wide_sl 6%, tight_sl 3%, tp 2.3%
        setField("cachedTpPct", 2.3);
        setField("cachedSlPct", 3.0);
        setField("cachedTpTrailDropPct", 1.0);  // V110: 기존 테스트 기준값
        setField("cachedGracePeriodMs", 60_000L);
        setField("cachedWidePeriodMs", 30 * 60_000L);
        setField("cachedWideSlPct", 6.0);
    }

    /**
     * 시나리오 실행기: 매수 + 가격 시퀀스 시뮬레이션 → 매도 결과 반환.
     */
    private boolean runScenario(double avgPrice, long openedSecAgo, PriceTick[] ticks) throws Exception {
        long now = System.currentTimeMillis();
        long openedAt = now - openedSecAgo * 1000L;
        getPositionCache().put("KRW-TEST", new double[]{avgPrice, 1.0, openedAt, avgPrice, avgPrice, 0, 0});

        for (PriceTick tick : ticks) {
            // 시간 조작: openedAt을 elapsedSec 만큼 과거로 옮김
            long virtualNow = now;
            long virtualOpened = virtualNow - tick.elapsedSec * 1000L;
            double[] pos = getPositionCache().get("KRW-TEST");
            if (pos == null) return true; // 이미 매도됨
            pos[2] = virtualOpened;

            invokeCheckRealtimeTpSl("KRW-TEST", tick.price);
            if (!getPositionCache().containsKey("KRW-TEST")) {
                return true; // 매도 발생
            }
        }
        return false; // 매도 없음
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 1: 정상 TP 익절
    // ═══════════════════════════════════════════════════════════
    // 2026-04-09 변경: TP 즉시 매도 → TP trail 매도
    @Test
    @DisplayName("시나리오 1 (V129): Grace 이후 +2.5% → trail 활성 → peak drop → TP trail 매도")
    public void scenario1_normalTp() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(30, 100.5),   // 30초 후 +0.5% (Grace 내)
            new PriceTick(90, 102.5),   // 90초 후 +2.5% (Grace 통과) → trail 활성화 (peak=102.5)
            new PriceTick(100, 101.3),  // 100초 후 peak에서 -1.17% drop → TP_TRAIL 매도
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertTrue(sold, "V129: Grace 이후 TP trail 활성+peak drop 시 매도");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 2: 그레이스 보호 (1분 안에 -10% 흔들기 후 회복)
    // ═══════════════════════════════════════════════════════════
    // 2026-04-09 변경: TP 즉시 매도 → TP trail 매도 (peak 추적 후 drop)
    @Test
    @DisplayName("시나리오 2: 매수 후 30초 -10% 급락 → 그레이스 보호 → 회복 → TP trail 매도")
    public void scenario2_graceProtection() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(10, 95.0),    // 10초 후 -5% (그레이스 보호)
            new PriceTick(30, 90.0),    // 30초 후 -10% (그레이스 보호 - SL 무시)
            new PriceTick(50, 95.0),    // 50초 후 -5% (그레이스 보호)
            new PriceTick(120, 102.5),  // 2분 후 +2.5% → trail 활성화 (peak=102.5)
            new PriceTick(130, 101.3),  // peak에서 -1.17% drop → TP_TRAIL 매도
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertTrue(sold, "그레이스 동안 SL 무시되고 회복 후 TP trail 매도");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 3: SL_WIDE 발동 (1~30분, -6% 초과)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 3: 매수 후 5분 -7% → SL_WIDE 발동")
    public void scenario3_slWide() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 96.0),   // 2분 후 -4% (SL_WIDE 6% 미달, 유지)
            new PriceTick(300, 95.0),   // 5분 후 -5% (미달, 유지)
            new PriceTick(310, 93.0),   // 5분 10초 후 -7% → SL_WIDE 발동
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertTrue(sold, "-7%는 SL_WIDE -6% 초과로 발동");
        assertFalse(getPositionCache().containsKey("KRW-TEST"));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 4: SL_WIDE 미달 → 유지
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 4: 매수 후 -5% → SL_WIDE 6% 미달이라 유지")
    public void scenario4_slWideMissing() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 95.0),   // 2분 후 -5% (6% 미달)
            new PriceTick(600, 95.5),   // 10분 후 -4.5% (미달)
            new PriceTick(1200, 96.0),  // 20분 후 -4% (미달)
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertFalse(sold, "-5%는 SL_WIDE 6% 미달이라 유지");
        assertTrue(getPositionCache().containsKey("KRW-TEST"));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 5: SL_TIGHT 발동 (30분 이후, -3% 초과)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 5: 매수 후 31분 -4% → SL_TIGHT 발동")
    public void scenario5_slTight() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(60, 99.0),     // 1분 후 -1%
            new PriceTick(1800, 97.5),   // 30분 후 -2.5% (SL_TIGHT 3% 미달, 유지)
            new PriceTick(1860, 96.0),   // 31분 후 -4% → SL_TIGHT 발동
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertTrue(sold, "30분 이후 -4%는 SL_TIGHT 3% 발동");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 6: 흔들기 후 회복 (5분 -5% → 회복 → TP)
    // ═══════════════════════════════════════════════════════════
    // 2026-04-09 변경: TP 즉시 매도 → TP trail 매도
    @Test
    @DisplayName("시나리오 6: 매수 후 5분 -5% (SL_WIDE 미달) → 회복 → TP trail 매도")
    public void scenario6_shakeRecovery() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 95.0),    // 2분 후 -5% (보호)
            new PriceTick(300, 95.5),    // 5분 후 -4.5% (보호)
            new PriceTick(600, 99.0),    // 10분 후 -1% (회복 중)
            new PriceTick(900, 102.5),   // 15분 후 +2.5% → trail 활성화
            new PriceTick(910, 101.3),   // peak에서 -1.17% drop → TP_TRAIL 매도
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertTrue(sold, "흔들기 후 회복하여 TP trail 매도");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 7 (V129): 그레이스 구간 내 TP 차단 (꼬리 흔들기 흡수)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 7 (V129): 그레이스 60초 내 +2.5% + drop 발생해도 TP 차단")
    public void scenario7_tpDuringGrace() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(20, 102.5),   // 20초 후 +2.5% (그레이스 안, peak 갱신 차단)
            new PriceTick(30, 101.3),   // 30초 후 drop (그레이스 안, TP 차단)
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertFalse(sold, "V129: 그레이스 안에서는 TP도 차단 (꼬리 흡수)");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 8: TP 미달 + SL 미달 → 유지
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 8: 횡보 (-2% ~ +1%) → 어떤 매도도 없음")
    public void scenario8_sideways() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 99.0),    // 2분 후 -1%
            new PriceTick(600, 98.0),    // 10분 후 -2%
            new PriceTick(1200, 100.5),  // 20분 후 +0.5%
            new PriceTick(1800, 101.5),  // 30분 후 +1.5% (TP 미달)
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertFalse(sold, "횡보 구간은 어떤 매도도 없음");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 9: 1분 그레이스 직후 -7% 급락 → SL_WIDE 발동
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 9: 그레이스(60s) 직후 -7% → SL_WIDE 발동")
    public void scenario9_postGraceCrash() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(30, 98.0),    // 그레이스 안 -2% (보호)
            new PriceTick(70, 93.0),    // 그레이스 직후 -7% → SL_WIDE 발동
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertTrue(sold, "그레이스 직후 SL_WIDE 발동");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 10: SL_WIDE → SL_TIGHT 전환 시점
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 10: 30분 경계 — 29분 -5% 유지, 31분 -3.5% SL_TIGHT 발동")
    public void scenario10_widePeriodBoundary() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(1740, 95.0),   // 29분 후 -5% (SL_WIDE 6% 미달, 유지)
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertFalse(sold, "29분 -5%는 SL_WIDE 6% 미달");

        // 31분 후 -3.5% → SL_TIGHT 발동
        // (별도 시나리오로)
        getPositionCache().clear();
        long now = System.currentTimeMillis();
        getPositionCache().put("KRW-TEST", new double[]{100.0, 1.0, now - 1860_000L, 100.0, 100.0, 0, 0});
        invokeCheckRealtimeTpSl("KRW-TEST", 96.5);
        assertFalse(getPositionCache().containsKey("KRW-TEST"),
                "31분 -3.5%는 SL_TIGHT 3% 발동");
    }

    // ═══════════════════════════════════════════════════════════
    //  V129 시나리오 11: 전체 사이클 — BUY → Grace → armed → SPLIT_1ST → 쿨다운 차단 → 쿨다운 만료 → SPLIT_2ND_TRAIL
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("V129-11: SPLIT_1ST 체결 → 쿨다운 60s 동안 SPLIT_2ND_TRAIL 차단 → 쿨다운 만료 → SPLIT_2ND_TRAIL 매도")
    public void scenarioV129_11_splitFirstThenCooldownThenSecondTrail() throws Exception {
        enableV129Split();

        long now = System.currentTimeMillis();
        // Grace(60s) 밖으로 진입 (openedAt = 120초 전)
        getPositionCache().put("KRW-TEST",
                new double[]{100.0, 1.0, now - 120_000L, 100.0, 100.0, 0, 0});

        // 1) armed: +1.5%
        invokeCheckRealtimeTpSl("KRW-TEST", 101.5);
        double[] pos = getPositionCache().get("KRW-TEST");
        assertEquals(1.0, pos[6], 0.01, "armed=1 (SPLIT_1ST trail 준비)");

        // 2) peak 갱신: +2%
        invokeCheckRealtimeTpSl("KRW-TEST", 102.0);
        assertEquals(102.0, pos[3], 0.01, "peak=102");

        // 3) drop 0.69% >= 0.5% → SPLIT_1ST 체결 (isSplitFirst → 캐시 유지, splitPhase=1)
        invokeCheckRealtimeTpSl("KRW-TEST", 101.3);
        assertTrue(getPositionCache().containsKey("KRW-TEST"),
                "SPLIT_1ST 체결 — 캐시 유지 (부분 매도)");
        pos = getPositionCache().get("KRW-TEST");
        assertEquals(1.0, pos[5], 0.01, "splitPhase=1 (1차 매도 완료)");
        assertEquals(101.3, pos[3], 0.01, "peak 리셋 = 체결가");
        assertEquals(0.0, pos[6], 0.01, "armed 리셋");

        // 4) split1stExecutedAtMap 기록 확인 (쿨다운 기준점)
        assertTrue(getSplit1stExecMap().containsKey("KRW-TEST"),
                "쿨다운 기준점 기록");

        // executor async 해제 대기 (sellingMarkets 가드 회피)
        getSellingMarkets().clear();

        // 5) 쿨다운 중 peak 상승 + 1.2% drop → SPLIT_2ND_TRAIL 차단
        invokeCheckRealtimeTpSl("KRW-TEST", 102.5);  // 새 peak
        invokeCheckRealtimeTpSl("KRW-TEST", 101.0);  // drop (102.5-101)/102.5 = 1.46% > 1.2%
        assertTrue(getPositionCache().containsKey("KRW-TEST"),
                "쿨다운 중이라 SPLIT_2ND_TRAIL 차단");

        // 6) 쿨다운 만료 시뮬레이션 (execMap을 65초 전으로 수정)
        getSplit1stExecMap().put("KRW-TEST", now - 65_000L);

        // 7) 동일한 drop 재현 → SPLIT_2ND_TRAIL 발동 (전량 매도)
        invokeCheckRealtimeTpSl("KRW-TEST", 101.0);
        assertFalse(getPositionCache().containsKey("KRW-TEST"),
                "쿨다운 만료 → SPLIT_2ND_TRAIL 매도");
        assertFalse(getSplit1stExecMap().containsKey("KRW-TEST"),
                "전량 매도 시 쿨다운 맵도 제거");
    }

    // ═══════════════════════════════════════════════════════════
    //  V129 시나리오 12: 쿨다운 중 급락 → SL은 허용 (SL_WIDE 바이패스)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("V129-12: SPLIT_1ST 체결 직후 급락 -6% → 쿨다운 중이어도 SL_WIDE 발동")
    public void scenarioV129_12_splitFirstThenCrashSlBypassesCooldown() throws Exception {
        enableV129Split();
        // SL_WIDE 6% 유지 (기본값과 동일)

        long now = System.currentTimeMillis();
        getPositionCache().put("KRW-TEST",
                new double[]{100.0, 1.0, now - 120_000L, 100.0, 100.0, 0, 0});

        // armed + peak + SPLIT_1ST
        invokeCheckRealtimeTpSl("KRW-TEST", 101.5);  // armed
        invokeCheckRealtimeTpSl("KRW-TEST", 102.0);  // peak
        invokeCheckRealtimeTpSl("KRW-TEST", 101.3);  // SPLIT_1ST
        assertEquals(1.0, getPositionCache().get("KRW-TEST")[5], 0.01, "splitPhase=1");
        assertTrue(getSplit1stExecMap().containsKey("KRW-TEST"));

        // executor async 해제 (sellingMarkets 가드 회피)
        getSellingMarkets().clear();

        // 쿨다운 중 -6.5% 급락 → SL_WIDE (쿨다운은 SL 막지 않음)
        invokeCheckRealtimeTpSl("KRW-TEST", 93.5);
        assertFalse(getPositionCache().containsKey("KRW-TEST"),
                "쿨다운 중에도 SL_WIDE는 발동");
        assertFalse(getSplit1stExecMap().containsKey("KRW-TEST"),
                "SL 매도 시 쿨다운 맵도 제거");
    }

    // ═══════════════════════════════════════════════════════════
    //  V129 시나리오 13: 쿨다운 경계 — 59초 vs 61초
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("V129-13: 쿨다운 경계 — 59초(차단) vs 61초(허용)")
    public void scenarioV129_13_cooldownBoundary() throws Exception {
        enableV129Split();

        long now = System.currentTimeMillis();
        getPositionCache().put("KRW-TEST",
                new double[]{100.0, 1.0, now - 120_000L, 102.0, 100.0, 1, 0});
        // splitPhase=1 직접 설정 (1차 매도 완료 상태 재현)

        // 59초 전 체결 → 쿨다운 활성 (60초 미만)
        getSplit1stExecMap().put("KRW-TEST", now - 59_000L);
        invokeCheckRealtimeTpSl("KRW-TEST", 100.5);  // drop 1.47% > 1.2%
        assertTrue(getPositionCache().containsKey("KRW-TEST"),
                "59초 → 쿨다운 활성 → 차단");

        // 61초 전 체결 → 쿨다운 만료
        getSplit1stExecMap().put("KRW-TEST", now - 61_000L);
        invokeCheckRealtimeTpSl("KRW-TEST", 100.5);
        assertFalse(getPositionCache().containsKey("KRW-TEST"),
                "61초 → 쿨다운 만료 → SPLIT_2ND_TRAIL");
    }

    /** V129 Split 기본값 설정 (쿨다운 60s, split tp 1.5%, split1 trail 0.5%, split2 trail 1.2%). */
    private void enableV129Split() throws Exception {
        setField("cachedSplitExitEnabled", true);
        setField("cachedSplitTpPct", 1.5);
        setField("cachedSplitRatio", 0.60);
        setField("cachedSplit1stTrailDrop", 0.5);
        setField("cachedTrailDropAfterSplit", 1.2);
        setField("cachedSplit1stCooldownMs", 60_000L);
        // V130: Trail Ladder 비활성 (기존 단일값 테스트 유지)
        setField("cachedTrailLadderEnabled", false);
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private void setField(String name, Object value) throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(scanner, value);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getPositionCache() throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(scanner);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Long> getSplit1stExecMap() throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField("split1stExecutedAtMap");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Long>) f.get(scanner);
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<String> getSellingMarkets() throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField("sellingMarkets");
        f.setAccessible(true);
        return (java.util.Set<String>) f.get(scanner);
    }

    private void invokeCheckRealtimeTpSl(String market, double price) throws Exception {
        Method m = MorningRushScannerService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(scanner, market, price);
    }
}
