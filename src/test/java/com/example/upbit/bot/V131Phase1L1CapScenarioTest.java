package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.SharedPriceService;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.market.CandleService;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.upbit.UpbitPrivateClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * V131 Phase 1 — L1 강제 익절 캡 시나리오 테스트 (사용자 첫 의견 반영)
 *
 * 논리:
 *   armed=true 상태에서 ROI >= cachedL1CapPct (default 2.0%) → 강제 SPLIT_1ST 매도
 *   기존 drop 조건은 그대로 유지 (둘 중 하나만 충족해도 발동)
 *   l1CapPct=0 → 비활성 (V130 동작, drop만)
 *
 * 시나리오:
 *   S01-MR: armed + roi=+2.0% (캡 도달) + drop 0% → SPLIT_1ST_L1_CAP 발동
 *   S02-MR: armed + roi=+1.8% (캡 미도달) + drop 부족 → 매도 안 함
 *   S03-MR: armed + roi=+1.0% (캡 미도달) + drop 충족 → SPLIT_1ST_TRAIL
 *   S04-MR: l1CapPct=0 (비활성) + roi=+2.5% + drop 부족 → 매도 안 함 (V130 동작)
 *   S05-AD: armed + roi=+2.0% (캡 도달) → SPLIT_1ST_L1_CAP 발동
 *   S06-OP-detector: armed + roi=+2.0% + drop 부족 → SPLIT_1ST 발동 (L1 캡)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V131 Phase 1 L1 Cap Scenario Tests")
public class V131Phase1L1CapScenarioTest {

    @Mock private MorningRushConfigRepository mrConfigRepo;
    @Mock private BotConfigRepository botConfigRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private TradeRepository tradeLogRepo;
    @Mock private LiveOrderService liveOrders;
    @Mock private UpbitPrivateClient privateClient;
    @Mock private TransactionTemplate txTemplate;
    @Mock private UpbitMarketCatalogService catalogService;
    @Mock private TickerService tickerService;
    @Mock private SharedPriceService sharedPriceService;

    private MorningRushScannerService mrScanner;

    @Mock private AllDayScannerConfigRepository adConfigRepo;
    @Mock private CandleService candleService;

    private AllDayScannerService adScanner;

    @BeforeEach
    public void setUp() throws Exception {
        ScannerLockService lockService = new ScannerLockService(botConfigRepo, positionRepo, tradeLogRepo);

        mrScanner = new MorningRushScannerService(
                mrConfigRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, privateClient, txTemplate, catalogService, tickerService,
                sharedPriceService, new SharedTradeThrottle(), lockService
        );
        setMrField("running", new AtomicBoolean(true));

        adScanner = new AllDayScannerService(
                adConfigRepo, botConfigRepo, positionRepo, tradeLogRepo,
                candleService, catalogService, liveOrders, privateClient, txTemplate,
                tickerService, sharedPriceService, new SharedTradeThrottle(), lockService
        );
        setAdField("running", new AtomicBoolean(true));
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        setAdField("scheduler", sched);

        when(txTemplate.execute(any())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object cb = invocation.getArgument(0);
                if (cb instanceof TransactionCallbackWithoutResult) {
                    ((TransactionCallbackWithoutResult) cb)
                            .doInTransaction(mock(TransactionStatus.class));
                }
                return null;
            }
        });

        // V131: configRepo mock — 비동기 매도(executeSplitFirstSellInner)에서 cfg.loadOrCreate() 호출됨.
        // V130 R04 패턴 추종. 누락 시 NPE → 매도 실행 안 됨 → splitPhase 갱신 안 됨.
        MorningRushConfigEntity mrCfg = new MorningRushConfigEntity();
        mrCfg.setMode("PAPER");
        mrCfg.setSplitExitEnabled(true);
        mrCfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        mrCfg.setSplitRatio(BigDecimal.valueOf(0.30));
        mrCfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.5));
        mrCfg.setSplit1stTrailDrop(BigDecimal.valueOf(0.7));
        when(mrConfigRepo.loadOrCreate()).thenReturn(mrCfg);

        AllDayScannerConfigEntity adCfg = new AllDayScannerConfigEntity();
        adCfg.setMode("PAPER");
        adCfg.setSplitExitEnabled(true);
        adCfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        adCfg.setSplitRatio(BigDecimal.valueOf(0.60));
        adCfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.5));
        adCfg.setSplit1stTrailDrop(BigDecimal.valueOf(0.7));
        when(adConfigRepo.loadOrCreate()).thenReturn(adCfg);
    }

    // ─────────────────────────────────────────────────────────────────
    //  S01-MR: L1 캡 도달 → SPLIT_1ST_L1_CAP 발동
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("S01-MR: armed + roi=+2.0% (캡 도달) + drop 0% → SPLIT_1ST 발동 (L1 CAP)")
    public void s01_mr_l1CapHit_fires() throws Exception {
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedSplitTpPct", 1.5);
        setMrField("cachedSplitRatio", 0.30);
        setMrField("cachedTrailDropAfterSplit", 1.5);
        setMrField("cachedSplit1stTrailDrop", 0.7);
        setMrField("cachedTrailLadderEnabled", true);
        setMrField("cachedSplit1stDropUnder2", 0.7);
        setMrField("cachedSplit1stDropUnder3", 1.0);
        setMrField("cachedSplit1stDropUnder5", 2.5);
        setMrField("cachedSplit1stDropAbove5", 4.0);
        setMrField("cachedSplit1stRoiFloorPct", 0.30);
        setMrField("cachedL1CapPct", 2.0);  // ★ V131 캡 +2.0%
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 10.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // avg=100, peak=102 (+2.0%), now=102 → roi=+2.0%, drop=0% (캡으로 매도해야)
        // MR positionCache: [avg, qty, openedAt, peak, trough, splitPhase, armed]
        cache.put("KRW-S01", new double[]{100.0, 1000.0, nowMs - 300_000, 102.0, 100.0, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-S01", 1000.0, 100.0);
        when(positionRepo.findById("KRW-S01")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-S01", 102.0);
        Thread.sleep(300);

        assertTrue(cache.containsKey("KRW-S01"), "L1 CAP 발동 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-S01")[5], 0.01, "splitPhase=1 (L1 CAP 발동)");
    }

    // ─────────────────────────────────────────────────────────────────
    //  S02-MR: 캡 미도달 + drop 부족 → 매도 안 함
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("S02-MR: armed + roi=+1.8% (캡 미도달) + drop 0% → 매도 안 함")
    public void s02_mr_capMiss_dropMiss_noSell() throws Exception {
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedSplitTpPct", 1.5);
        setMrField("cachedSplitRatio", 0.30);
        setMrField("cachedTrailDropAfterSplit", 1.5);
        setMrField("cachedSplit1stTrailDrop", 0.7);
        setMrField("cachedTrailLadderEnabled", true);
        setMrField("cachedSplit1stDropUnder2", 0.7);
        setMrField("cachedSplit1stDropUnder3", 1.0);
        setMrField("cachedSplit1stDropUnder5", 2.5);
        setMrField("cachedSplit1stDropAbove5", 4.0);
        setMrField("cachedSplit1stRoiFloorPct", 0.30);
        setMrField("cachedL1CapPct", 2.0);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 10.0);
        setMrField("cachedSlPct", 5.0);
        setMrField("cachedWideSlPct", 5.0);
        setMrField("cachedWidePeriodMs", 5 * 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // avg=100, peak=101.8 (+1.8%), now=101.8 → roi=+1.8% (< 2.0 캡), drop=0% → 매도 안 함
        cache.put("KRW-S02", new double[]{100.0, 1000.0, nowMs - 300_000, 101.8, 100.0, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-S02", 1000.0, 100.0);
        when(positionRepo.findById("KRW-S02")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-S02", 101.8);

        assertEquals(0.0, cache.get("KRW-S02")[5], 0.01, "splitPhase=0 유지 (캡 미도달, drop 부족)");
    }

    // ─────────────────────────────────────────────────────────────────
    //  S03-MR: 캡 미도달 + drop 충족 → SPLIT_1ST_TRAIL
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("S03-MR: armed + roi=+1.0% (캡 미도달) + drop 충족 → SPLIT_1ST_TRAIL 발동")
    public void s03_mr_capMiss_dropHit_splitTrail() throws Exception {
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedSplitTpPct", 1.5);
        setMrField("cachedSplitRatio", 0.30);
        setMrField("cachedTrailDropAfterSplit", 1.5);
        setMrField("cachedSplit1stTrailDrop", 0.7);
        setMrField("cachedTrailLadderEnabled", true);
        setMrField("cachedSplit1stDropUnder2", 0.7);  // peak<2 → drop 0.7%
        setMrField("cachedSplit1stDropUnder3", 1.0);
        setMrField("cachedSplit1stDropUnder5", 2.5);
        setMrField("cachedSplit1stDropAbove5", 4.0);
        setMrField("cachedSplit1stRoiFloorPct", 0.30);
        setMrField("cachedL1CapPct", 2.0);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 10.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // avg=100, peak=101.7 (+1.7%), now=101.0 → roi=+1.0% (< 2.0 캡)
        // drop=(101.7-101.0)/101.7=0.688% > 0.7%? NO. drop 정확 계산: 0.688... < 0.7 → drop 미충족
        // 변경: now=100.95 → roi=+0.95%, drop=(101.7-100.95)/101.7=0.737% > 0.7% → 발동
        cache.put("KRW-S03", new double[]{100.0, 1000.0, nowMs - 300_000, 101.7, 100.0, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-S03", 1000.0, 100.0);
        when(positionRepo.findById("KRW-S03")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-S03", 100.95);
        Thread.sleep(300);

        assertEquals(1.0, cache.get("KRW-S03")[5], 0.01, "splitPhase=1 (drop으로 발동, L1 CAP 미도달)");
    }

    // ─────────────────────────────────────────────────────────────────
    //  S04-MR: 캡 비활성 (0) → V130 동작
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("S04-MR: cachedL1CapPct=0 (비활성) + roi=+2.5% + drop 부족 → 매도 안 함 (V130)")
    public void s04_mr_capDisabled_dropMiss_noSell() throws Exception {
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedSplitTpPct", 1.5);
        setMrField("cachedSplitRatio", 0.30);
        setMrField("cachedTrailDropAfterSplit", 1.5);
        setMrField("cachedSplit1stTrailDrop", 0.7);
        setMrField("cachedTrailLadderEnabled", true);
        setMrField("cachedSplit1stDropUnder2", 0.7);
        setMrField("cachedSplit1stDropUnder3", 1.0);
        setMrField("cachedSplit1stDropUnder5", 2.5);
        setMrField("cachedSplit1stDropAbove5", 4.0);
        setMrField("cachedSplit1stRoiFloorPct", 0.30);
        setMrField("cachedL1CapPct", 0.0);  // ★ 비활성
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 10.0);
        setMrField("cachedSlPct", 5.0);
        setMrField("cachedWideSlPct", 5.0);
        setMrField("cachedWidePeriodMs", 5 * 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // avg=100, peak=102.5 (+2.5%), now=102.5 → roi=+2.5%, drop=0%
        // 캡 비활성이라 발동 안 됨 (V130 동작)
        cache.put("KRW-S04", new double[]{100.0, 1000.0, nowMs - 300_000, 102.5, 100.0, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-S04", 1000.0, 100.0);
        when(positionRepo.findById("KRW-S04")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-S04", 102.5);

        assertEquals(0.0, cache.get("KRW-S04")[5], 0.01, "L1 캡 비활성 → V130 drop만 적용");
    }

    // ─────────────────────────────────────────────────────────────────
    //  S05-AD: L1 캡 도달 → SPLIT_1ST 발동
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("S05-AD: armed + roi=+2.0% (캡 도달) + drop 0% → SPLIT_1ST 발동 (L1 CAP)")
    public void s05_ad_l1CapHit_fires() throws Exception {
        setAdField("cachedSplitExitEnabled", true);
        setAdField("cachedSplitTpPct", 1.5);
        setAdField("cachedSplitRatio", 0.60);  // V130 default와 동일하게 (R04 패턴 추종)
        setAdField("cachedTrailDropAfterSplit", 1.5);
        setAdField("cachedSplit1stTrailDrop", 0.7);
        setAdField("cachedTrailLadderEnabled", true);
        setAdField("cachedSplit1stDropUnder2", 0.7);
        setAdField("cachedSplit1stDropUnder3", 1.0);
        setAdField("cachedSplit1stDropUnder5", 2.5);
        setAdField("cachedSplit1stDropAbove5", 4.0);
        setAdField("cachedSplit1stRoiFloorPct", 0.30);
        setAdField("cachedL1CapPct", 2.0);  // ★ 캡 +2.0%
        setAdField("cachedGracePeriodMs", 0L);
        setAdField("cachedTpTrailActivatePct", 10.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getAdTpCache();
        // AD: [avg, peak, trailActivated, trough, openedAtEpochMs, splitPhase, armed]
        cache.put("KRW-S05AD", new double[]{100.0, 102.0, 0, 99.9, nowMs - 300_000, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-S05AD", 1000.0, 100.0);
        when(positionRepo.findById("KRW-S05AD")).thenReturn(Optional.of(pe));

        invokeAdCheck("KRW-S05AD", 102.0);
        // wsTpExecutor 비동기 매도가 끝날 때까지 대기 (최대 3초 폴링)
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            double[] p = cache.get("KRW-S05AD");
            if (p != null && p[5] >= 0.99) break;
            Thread.sleep(100);
        }

        assertEquals(1.0, cache.get("KRW-S05AD")[5], 0.01, "AD splitPhase=1 (L1 CAP 발동)");
    }

    // ─────────────────────────────────────────────────────────────────
    //  S06-OP-detector: L1 캡 발동
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("S06-OP-detector: setL1CapPct(2.0) + armed + roi=+2.0% + drop 0% → SPLIT_1ST 발동")
    public void s06_op_detector_l1CapHit() throws Exception {
        SharedPriceService mockShared = mock(SharedPriceService.class);
        OpeningBreakoutDetector detector = new OpeningBreakoutDetector(mockShared);
        detector.setConfirmMinIntervalMs(0);

        final List<String> sellTypes = new ArrayList<String>();
        final List<String> reasons = new ArrayList<String>();
        detector.setListener(new OpeningBreakoutDetector.BreakoutListener() {
            @Override
            public void onBreakoutConfirmed(String market, double price, double rangeHigh, double bo) {}
            @Override
            public void onTpSlTriggered(String market, double price, String sellType, String reason) {
                sellTypes.add(sellType);
                reasons.add(reason);
            }
        });

        detector.setSplitExitEnabled(true);
        detector.setSplitTpPct(1.5);
        detector.setSplitRatio(0.30);
        detector.setTrailDropAfterSplit(1.5);
        detector.setSplit1stTrailDropPct(0.7);
        detector.setSplit1stCooldownSec(0);
        detector.setTrailLadder(true, 0.7, 1.0, 2.5, 4.0, 1.0, 1.5, 2.5, 5.0);
        detector.setSplit1stRoiFloorPct(0.30);
        detector.setL1CapPct(2.0);  // ★ V131 L1 캡 2.0%
        detector.setTpActivatePct(10.0);
        detector.setTrailFromPeakPct(1.0);
        detector.updateSlConfig(0, 60, 6.0, 5.0, 3.5, 3.0, 1.5);

        long nowMs = System.currentTimeMillis();
        detector.addPosition("KRW-S06", 100.0, nowMs - 300_000, 5);

        Method m = OpeningBreakoutDetector.class.getDeclaredMethod("checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);

        // armed: +1.5%
        m.invoke(detector, "KRW-S06", 101.5);
        assertTrue(sellTypes.isEmpty(), "armed 단계 → 매도 없음");

        // peak=102.0 (+2.0%), now=102.0 → roi=+2.0% (캡 도달), drop=0% → L1 CAP 발동
        m.invoke(detector, "KRW-S06", 102.0);
        assertFalse(sellTypes.isEmpty(), "L1 CAP 도달 → SPLIT_1ST 발동");
        assertEquals("SPLIT_1ST", sellTypes.get(0), "SPLIT_1ST 타입 확인");
        assertTrue(reasons.get(0).contains("L1_CAP") || reasons.get(0).contains("SPLIT_1ST_L1_CAP"),
                "reason에 L1_CAP 표시: " + reasons.get(0));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────

    private void invokeMrCheck(String market, double price) throws Exception {
        Method m = MorningRushScannerService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(mrScanner, market, price);
    }

    private void invokeAdCheck(String market, double price) throws Exception {
        Method m = AllDayScannerService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(adScanner, market, price);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getMrPositionCache() throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(mrScanner);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getAdTpCache() throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField("tpPositionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(adScanner);
    }

    private void setMrField(String name, Object value) throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(mrScanner, value);
    }

    private void setAdField(String name, Object value) throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(adScanner, value);
    }

    private PositionEntity buildPosition(String market, double qty, double avgPrice) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setQty(BigDecimal.valueOf(qty));
        pe.setAvgPrice(BigDecimal.valueOf(avgPrice));
        pe.setAddBuys(0);
        pe.setOpenedAt(Instant.now());
        pe.setEntryStrategy("TEST");
        return pe;
    }
}
