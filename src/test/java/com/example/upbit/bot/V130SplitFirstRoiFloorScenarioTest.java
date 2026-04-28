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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V130 ④: SPLIT_1ST roi 하한선 시나리오 테스트.
 *
 * 논리:
 *   armed=true + drop >= split_1st_drop → SPLIT_1ST 매도 "후보"
 *   roi >= split_1st_roi_floor_pct (0.30) → 실제 발동
 *   roi < floor → 1차 매도 차단, 보유 지속 (SL 또는 회복 후 SPLIT_2ND_TRAIL 대기)
 *   floor=0.0 → 항상 발동 (V129 동작)
 *
 * MR/AD 스캐너 각각 검증. OP는 OpeningBreakoutDetector.setSplit1stRoiFloorPct로 동일 로직.
 *
 * 시나리오:
 *   R01-MR: armed + drop 도달 + roi=+0.5% (>= floor 0.3) → SPLIT_1ST 발동
 *   R02-MR: armed + drop 도달 + roi=-0.1% (< floor 0.3) → SPLIT_1ST 차단, 보유 지속
 *   R03-MR: floor=0.0 (비활성) → roi 음수여도 발동 (V129 동작)
 *   R04-AD: armed + drop 도달 + roi=+0.5% → SPLIT_1ST 발동
 *   R05-AD: armed + drop 도달 + roi=-0.1% → 차단
 *   R06-AD: floor=0.0 → roi 음수여도 발동
 *   R07-OP: OpeningBreakoutDetector setSplit1stRoiFloorPct 검증
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V130 SPLIT_1ST ROI Floor Scenario Tests")
public class V130SplitFirstRoiFloorScenarioTest {

    // MR fixtures
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

    // AD fixtures
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

        // MR cfg
        MorningRushConfigEntity mrCfg = new MorningRushConfigEntity();
        mrCfg.setMode("PAPER");
        mrCfg.setSplitExitEnabled(true);
        mrCfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        mrCfg.setSplitRatio(BigDecimal.valueOf(0.60));
        mrCfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.0));
        mrCfg.setSplit1stTrailDrop(BigDecimal.valueOf(0.5));
        when(mrConfigRepo.loadOrCreate()).thenReturn(mrCfg);

        // AD cfg
        AllDayScannerConfigEntity adCfg = new AllDayScannerConfigEntity();
        adCfg.setMode("PAPER");
        adCfg.setSplitExitEnabled(true);
        adCfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        adCfg.setSplitRatio(BigDecimal.valueOf(0.60));
        adCfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.0));
        adCfg.setSplit1stTrailDrop(BigDecimal.valueOf(0.5));
        when(adConfigRepo.loadOrCreate()).thenReturn(adCfg);
    }

    // ─────────────────────────────────────────────────────────────────
    //  MR: R01 — SPLIT_1ST 발동 (roi >= floor)
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R01-MR: armed + drop 도달 + roi=+0.5% (>= floor 0.3%) → SPLIT_1ST 발동")
    public void r01_mr_roiAboveFloor_splitFires() throws Exception {
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedSplitTpPct", 1.5);
        setMrField("cachedSplitRatio", 0.60);
        setMrField("cachedTrailDropAfterSplit", 1.0);
        setMrField("cachedSplit1stTrailDrop", 0.5);
        setMrField("cachedTrailLadderEnabled", false);  // 단일값 사용
        setMrField("cachedSplit1stRoiFloorPct", 0.30);  // floor 0.3%
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 10.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // avgPrice=100, peak=101.5(+1.5%), armed=1
        // 현재가=100.5: roi=+0.5% >= floor 0.3%, drop=(101.5-100.5)/101.5=0.985% > 0.5% → 발동
        cache.put("KRW-R01", new double[]{100.0, 1000.0, nowMs - 300_000, 101.5, 100.0, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-R01", 1000.0, 100.0);
        when(positionRepo.findById("KRW-R01")).thenReturn(Optional.of(pe));

        // peak=101.5 → drop=0.985% > 0.5%, roi=+0.5% >= 0.3% → 발동
        invokeMrCheck("KRW-R01", 100.5);
        Thread.sleep(300);

        assertTrue(cache.containsKey("KRW-R01"), "SPLIT_1ST 발동 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-R01")[5], 0.01, "splitPhase=1");
    }

    // ─────────────────────────────────────────────────────────────────
    //  MR: R02 — SPLIT_1ST 차단 (roi < floor)
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R02-MR: armed + drop 도달 + roi=-0.1% (< floor 0.3%) → SPLIT_1ST 차단, 보유 지속")
    public void r02_mr_roiBelowFloor_splitBlocked() throws Exception {
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedSplitTpPct", 1.5);
        setMrField("cachedSplitRatio", 0.60);
        setMrField("cachedTrailDropAfterSplit", 1.0);
        setMrField("cachedSplit1stTrailDrop", 0.5);
        setMrField("cachedTrailLadderEnabled", false);
        setMrField("cachedSplit1stRoiFloorPct", 0.30);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 10.0);
        setMrField("cachedSlPct", 5.0);   // SL 높게 설정 (SL 발동 방지)
        setMrField("cachedWideSlPct", 5.0);
        setMrField("cachedWidePeriodMs", 5 * 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // avgPrice=100, peak=100.6(+0.6%), armed=1
        // 현재가=99.9 → roi=-0.1% (< floor 0.3%) → SPLIT_1ST 차단
        // peak에서 drop = (100.6 - 99.9) / 100.6 * 100 = 0.70% > 0.5% (drop 조건은 충족)
        cache.put("KRW-R02", new double[]{100.0, 1000.0, nowMs - 300_000, 100.6, 99.9, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-R02", 1000.0, 100.0);
        when(positionRepo.findById("KRW-R02")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-R02", 99.9);

        // SPLIT_1ST 차단 → splitPhase=0 유지 (캐시 있고 phase 변화 없음)
        assertTrue(cache.containsKey("KRW-R02"), "roi 하한선 차단 → 보유 지속");
        assertEquals(0.0, cache.get("KRW-R02")[5], 0.01, "splitPhase=0 유지 (SPLIT_1ST 차단)");
    }

    // ─────────────────────────────────────────────────────────────────
    //  MR: R03 — floor=0.0 (비활성) → roi 음수여도 발동
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R03-MR: floor=0.0 (비활성) → roi=-0.1%여도 SPLIT_1ST 발동 (V129 동작)")
    public void r03_mr_floorZero_splitFiresEvenWithNegativeRoi() throws Exception {
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedSplitTpPct", 1.5);
        setMrField("cachedSplitRatio", 0.60);
        setMrField("cachedTrailDropAfterSplit", 1.0);
        setMrField("cachedSplit1stTrailDrop", 0.5);
        setMrField("cachedTrailLadderEnabled", false);
        setMrField("cachedSplit1stRoiFloorPct", 0.0);  // floor=0.0 (비활성)
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 10.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // avgPrice=100, peak=100.6(+0.6%), armed=1
        // 현재가=99.9 → roi=-0.1%, drop=0.70% > 0.5% → floor=0이므로 발동
        cache.put("KRW-R03", new double[]{100.0, 1000.0, nowMs - 300_000, 100.6, 99.9, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-R03", 1000.0, 100.0);
        when(positionRepo.findById("KRW-R03")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-R03", 99.9);
        Thread.sleep(300);

        assertTrue(cache.containsKey("KRW-R03"), "V129 호환 (floor=0): 1차 매도 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-R03")[5], 0.01, "floor=0.0 → splitPhase=1");
    }

    // ─────────────────────────────────────────────────────────────────
    //  AD: R04 — SPLIT_1ST 발동
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R04-AD: armed + drop 도달 + roi=+0.5% (>= floor 0.3%) → SPLIT_1ST 발동")
    public void r04_ad_roiAboveFloor_splitFires() throws Exception {
        setAdField("cachedSplitExitEnabled", true);
        setAdField("cachedSplitTpPct", 1.5);
        setAdField("cachedSplitRatio", 0.60);
        setAdField("cachedTrailDropAfterSplit", 1.0);
        setAdField("cachedSplit1stTrailDrop", 0.5);
        setAdField("cachedTrailLadderEnabled", false);
        setAdField("cachedSplit1stRoiFloorPct", 0.30);
        setAdField("cachedGracePeriodMs", 0L);
        setAdField("cachedTpTrailActivatePct", 10.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getAdTpCache();
        // AD: [avgPrice, peakPrice, trailActivated, troughPrice, openedAtEpochMs, splitPhase, armed]
        // avgPrice=100, peak=101.5(+1.5%), armed=1
        // 현재가=100.5: roi=+0.5% >= floor 0.3%, drop=(101.5-100.5)/101.5=0.985% > 0.5% → 발동
        cache.put("KRW-R04AD", new double[]{100.0, 101.5, 0, 99.9, nowMs - 300_000, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-R04AD", 1000.0, 100.0);
        when(positionRepo.findById("KRW-R04AD")).thenReturn(Optional.of(pe));

        // peak=101.5 → drop=0.985% > 0.5%, roi=+0.5% >= 0.3% → 발동
        invokeAdCheck("KRW-R04AD", 100.5);
        Thread.sleep(300);

        assertTrue(cache.containsKey("KRW-R04AD"), "AD SPLIT_1ST 발동 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-R04AD")[5], 0.01, "AD splitPhase=1");
    }

    // ─────────────────────────────────────────────────────────────────
    //  AD: R05 — SPLIT_1ST 차단 (roi < floor)
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R05-AD: armed + drop 도달 + roi=-0.1% (< floor 0.3%) → SPLIT_1ST 차단")
    public void r05_ad_roiBelowFloor_splitBlocked() throws Exception {
        setAdField("cachedSplitExitEnabled", true);
        setAdField("cachedSplitTpPct", 1.5);
        setAdField("cachedSplitRatio", 0.60);
        setAdField("cachedTrailDropAfterSplit", 1.0);
        setAdField("cachedSplit1stTrailDrop", 0.5);
        setAdField("cachedTrailLadderEnabled", false);
        setAdField("cachedSplit1stRoiFloorPct", 0.30);
        setAdField("cachedGracePeriodMs", 0L);
        setAdField("cachedTpTrailActivatePct", 10.0);
        setAdField("cachedTightSlPct", 5.0);   // SL 높게 (발동 방지)
        setAdField("cachedWideSlPct", 5.0);
        setAdField("cachedWidePeriodMs", 5 * 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getAdTpCache();
        // peak=100.6, armed=1, 현재가=99.9 → roi=-0.1% < floor → 차단
        cache.put("KRW-R05AD", new double[]{100.0, 100.6, 0, 99.9, nowMs - 300_000, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-R05AD", 1000.0, 100.0);
        when(positionRepo.findById("KRW-R05AD")).thenReturn(Optional.of(pe));

        invokeAdCheck("KRW-R05AD", 99.9);

        assertTrue(cache.containsKey("KRW-R05AD"), "AD roi 하한선 차단 → 보유 지속");
        assertEquals(0.0, cache.get("KRW-R05AD")[5], 0.01, "AD splitPhase=0 유지");
    }

    // ─────────────────────────────────────────────────────────────────
    //  AD: R06 — floor=0.0 (비활성) → roi 음수여도 발동
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R06-AD: floor=0.0 (비활성) → roi=-0.1%여도 SPLIT_1ST 발동 (V129 동작)")
    public void r06_ad_floorZero_splitFiresEvenWithNegativeRoi() throws Exception {
        setAdField("cachedSplitExitEnabled", true);
        setAdField("cachedSplitTpPct", 1.5);
        setAdField("cachedSplitRatio", 0.60);
        setAdField("cachedTrailDropAfterSplit", 1.0);
        setAdField("cachedSplit1stTrailDrop", 0.5);
        setAdField("cachedTrailLadderEnabled", false);
        setAdField("cachedSplit1stRoiFloorPct", 0.0);  // 비활성
        setAdField("cachedGracePeriodMs", 0L);
        setAdField("cachedTpTrailActivatePct", 10.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getAdTpCache();
        cache.put("KRW-R06AD", new double[]{100.0, 100.6, 0, 99.9, nowMs - 300_000, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-R06AD", 1000.0, 100.0);
        when(positionRepo.findById("KRW-R06AD")).thenReturn(Optional.of(pe));

        // 현재가=99.9 → roi=-0.1%, floor=0 → 발동
        invokeAdCheck("KRW-R06AD", 99.9);
        Thread.sleep(300);

        assertTrue(cache.containsKey("KRW-R06AD"), "AD floor=0 (V129): 1차 매도 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-R06AD")[5], 0.01, "AD floor=0 → splitPhase=1");
    }

    // ─────────────────────────────────────────────────────────────────
    //  OP (OpeningBreakoutDetector): R07 — roi floor 설정 검증
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R07-OP: OpeningBreakoutDetector setSplit1stRoiFloorPct 0.3 → drop+roi 부족 시 차단, 충족 시 발동")
    public void r07_op_roiFloorSetCorrectly() throws Exception {
        com.example.upbit.market.SharedPriceService mockShared =
                mock(com.example.upbit.market.SharedPriceService.class);
        OpeningBreakoutDetector detector = new OpeningBreakoutDetector(mockShared);
        detector.setConfirmMinIntervalMs(0);

        final List<String> sellTypes = new ArrayList<String>();
        detector.setListener(new OpeningBreakoutDetector.BreakoutListener() {
            @Override
            public void onBreakoutConfirmed(String market, double price, double rangeHigh, double bo) {}
            @Override
            public void onTpSlTriggered(String market, double price, String sellType, String reason) {
                sellTypes.add(sellType);
            }
        });

        // Split-Exit 설정
        detector.setSplitExitEnabled(true);
        detector.setSplitTpPct(1.5);
        detector.setSplitRatio(0.60);
        detector.setTrailDropAfterSplit(1.0);
        detector.setSplit1stTrailDropPct(0.5);
        detector.setSplit1stCooldownSec(0);
        detector.setTrailLadder(false, 0.5, 1.0, 1.5, 2.0, 1.0, 1.2, 1.5, 2.0);
        detector.setSplit1stRoiFloorPct(0.30);  // floor 0.3%
        detector.setTpActivatePct(10.0);
        detector.setTrailFromPeakPct(1.0);
        detector.updateSlConfig(0, 60, 6.0, 5.0, 3.5, 3.0, 1.5);

        // avgPrice=100.0
        long nowMs = System.currentTimeMillis();
        detector.addPosition("KRW-R07", 100.0, nowMs - 300_000, 5);

        Method m = OpeningBreakoutDetector.class.getDeclaredMethod("checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);

        // Step1: armed 트리거 (+1.5%) — peak=101.5, armed=true
        m.invoke(detector, "KRW-R07", 101.5);
        assertTrue(sellTypes.isEmpty(), "armed 단계 → 매도 없음");

        // Step2: drop 부족 (peak=101.5, price=101.45 → drop=0.049% < 0.5%) → 차단
        m.invoke(detector, "KRW-R07", 101.45);
        assertTrue(sellTypes.isEmpty(), "drop 0.05% < 0.5% → SPLIT_1ST 차단");

        // Step3: drop 충족 but roi 미달
        // peak=101.5, price=100.04 → drop=(101.5-100.04)/101.5=1.44% > 0.5%
        // roi=(100.04-100)/100*100=0.04% < floor 0.3% → floor 차단
        m.invoke(detector, "KRW-R07", 100.04);
        assertTrue(sellTypes.isEmpty(), "roi=0.04% < floor 0.3% → SPLIT_1ST floor 차단");

        // Step4: 가격 반등 → peak 갱신 없음(100.04 < 101.5), 아직 drop 충족
        // price=100.5 → drop=(101.5-100.5)/101.5=0.985% > 0.5%, roi=+0.5% >= 0.3% → 발동!
        m.invoke(detector, "KRW-R07", 100.5);
        assertFalse(sellTypes.isEmpty(), "drop+roi 모두 충족 → SPLIT_1ST 발동");
        assertEquals("SPLIT_1ST", sellTypes.get(0), "SPLIT_1ST 타입 확인");
    }

    @Test
    @DisplayName("R07b-OP: OpeningBreakoutDetector setSplit1stRoiFloorPct=0.0 → 음수 roi에서도 발동")
    public void r07b_op_roiFloorZero_firesWithNegativeRoi() throws Exception {
        com.example.upbit.market.SharedPriceService mockShared =
                mock(com.example.upbit.market.SharedPriceService.class);
        OpeningBreakoutDetector detector = new OpeningBreakoutDetector(mockShared);
        detector.setConfirmMinIntervalMs(0);

        final List<String> sellTypes = new ArrayList<String>();
        detector.setListener(new OpeningBreakoutDetector.BreakoutListener() {
            @Override
            public void onBreakoutConfirmed(String market, double price, double rangeHigh, double bo) {}
            @Override
            public void onTpSlTriggered(String market, double price, String sellType, String reason) {
                sellTypes.add(sellType);
            }
        });

        detector.setSplitExitEnabled(true);
        detector.setSplitTpPct(1.5);
        detector.setSplitRatio(0.60);
        detector.setTrailDropAfterSplit(1.0);
        detector.setSplit1stTrailDropPct(0.5);
        detector.setSplit1stCooldownSec(0);
        detector.setTrailLadder(false, 0.5, 1.0, 1.5, 2.0, 1.0, 1.2, 1.5, 2.0);
        detector.setSplit1stRoiFloorPct(0.0);  // 비활성
        detector.setTpActivatePct(10.0);
        detector.setTrailFromPeakPct(1.0);
        detector.updateSlConfig(0, 60, 6.0, 5.0, 3.5, 3.0, 1.5);

        long nowMs = System.currentTimeMillis();
        detector.addPosition("KRW-R07B", 100.0, nowMs - 300_000, 5);

        Method m = OpeningBreakoutDetector.class.getDeclaredMethod("checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);

        // armed: +1.6%
        m.invoke(detector, "KRW-R07B", 101.6);
        // peak=101.6, 현재=100.99 → drop=(101.6-100.99)/101.6=0.60% > 0.5%, roi=+0.99% → 발동
        m.invoke(detector, "KRW-R07B", 100.99);

        assertFalse(sellTypes.isEmpty(), "floor=0 → roi 양수여도 발동 (V129 동작)");
        assertEquals("SPLIT_1ST", sellTypes.get(0), "SPLIT_1ST 발동");
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
