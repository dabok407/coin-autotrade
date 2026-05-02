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
 * V133 Phase 3 — BEV Guard 시나리오 테스트
 *
 * 논리:
 *   peak >= bev_trigger_pct (5%) 도달했던 종목이 ROI < 0 → 즉시 BEV_GUARD 매도
 *   bev_guard_enabled=false → 비활성 (V130 동작)
 *
 * 시나리오:
 *   B01-MR: peak 7%, 현재 -0.5% → BEV_GUARD 발동
 *   B02-MR: peak 4% (trigger 미달), 현재 -0.5% → BEV 미발동 (SL 분기)
 *   B03-MR: bev_guard_enabled=false → 비활성, V130 동작
 *   B04-AD: peak 6%, 현재 -1.0% → BEV_GUARD 발동
 *   B05-OP-detector: peak 8%, 현재 -0.3% → BEV_GUARD 발동
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V133 Phase 3 BEV Guard Scenario Tests")
public class V133Phase3BevGuardScenarioTest {

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

        // configRepo mocks
        MorningRushConfigEntity mrCfg = new MorningRushConfigEntity();
        mrCfg.setMode("PAPER");
        mrCfg.setSplitExitEnabled(true);
        mrCfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        mrCfg.setSplitRatio(BigDecimal.valueOf(0.30));
        when(mrConfigRepo.loadOrCreate()).thenReturn(mrCfg);

        AllDayScannerConfigEntity adCfg = new AllDayScannerConfigEntity();
        adCfg.setMode("PAPER");
        adCfg.setSplitExitEnabled(true);
        adCfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        adCfg.setSplitRatio(BigDecimal.valueOf(0.60));
        when(adConfigRepo.loadOrCreate()).thenReturn(adCfg);
    }

    // ─────────────────────────────────────────────────────────────────
    //  B01-MR: BEV 발동
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("B01-MR: peak 7% (trigger 5% 도달) + 현재 -0.5% → BEV_GUARD 발동")
    public void b01_mr_bevHit() throws Exception {
        setMrField("cachedSplitExitEnabled", false);  // split 비활성으로 단순화
        setMrField("cachedTrailLadderEnabled", false);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 99.0);  // TP 비활성
        setMrField("cachedSlPct", 5.0);    // SL_TIGHT 5%
        setMrField("cachedWideSlPct", 5.0);
        setMrField("cachedWidePeriodMs", 5 * 60_000L);
        setMrField("cachedBevGuardEnabled", true);
        setMrField("cachedBevTriggerPct", 5.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // avg=100, peak=107(+7%), now=99.5 → roi=-0.5%, peak 5%+ 도달했음 → BEV_GUARD
        cache.put("KRW-B01", new double[]{100.0, 1000.0, nowMs - 600_000, 107.0, 99.0, 0, 0});

        PositionEntity pe = buildPosition("KRW-B01", 1000.0, 100.0);
        when(positionRepo.findById("KRW-B01")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-B01", 99.5);
        Thread.sleep(800);

        // BEV로 전량 매도 → 캐시 제거
        assertFalse(cache.containsKey("KRW-B01"), "BEV_GUARD 발동 → 전량 매도, 캐시 제거");
    }

    // ─────────────────────────────────────────────────────────────────
    //  B02-MR: BEV trigger 미달
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("B02-MR: peak 4% (trigger 5% 미달) + 현재 -0.5% → BEV 미발동, 보유 지속")
    public void b02_mr_peakBelowTrigger_noBev() throws Exception {
        setMrField("cachedSplitExitEnabled", false);
        setMrField("cachedTrailLadderEnabled", false);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 99.0);
        setMrField("cachedSlPct", 5.0);
        setMrField("cachedWideSlPct", 5.0);
        setMrField("cachedWidePeriodMs", 5 * 60_000L);
        setMrField("cachedBevGuardEnabled", true);
        setMrField("cachedBevTriggerPct", 5.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // avg=100, peak=104(+4% < 5% trigger), now=99.5 → BEV 미발동 + SL 미발동(-0.5% > -5%)
        cache.put("KRW-B02", new double[]{100.0, 1000.0, nowMs - 600_000, 104.0, 99.0, 0, 0});

        PositionEntity pe = buildPosition("KRW-B02", 1000.0, 100.0);
        when(positionRepo.findById("KRW-B02")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-B02", 99.5);

        assertTrue(cache.containsKey("KRW-B02"), "BEV trigger 미달 → 보유 지속");
    }

    // ─────────────────────────────────────────────────────────────────
    //  B03-MR: BEV 비활성
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("B03-MR: bev_guard_enabled=false → 비활성, V130 동작")
    public void b03_mr_bevDisabled_v130() throws Exception {
        setMrField("cachedSplitExitEnabled", false);
        setMrField("cachedTrailLadderEnabled", false);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 99.0);
        setMrField("cachedSlPct", 5.0);
        setMrField("cachedWideSlPct", 5.0);
        setMrField("cachedWidePeriodMs", 5 * 60_000L);
        setMrField("cachedBevGuardEnabled", false);  // ★ 비활성
        setMrField("cachedBevTriggerPct", 5.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // peak 7%, now -0.5% → BEV 발동 조건이지만 비활성 → 보유 지속
        cache.put("KRW-B03", new double[]{100.0, 1000.0, nowMs - 600_000, 107.0, 99.0, 0, 0});

        PositionEntity pe = buildPosition("KRW-B03", 1000.0, 100.0);
        when(positionRepo.findById("KRW-B03")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-B03", 99.5);

        assertTrue(cache.containsKey("KRW-B03"), "BEV 비활성 → V130 동작 (SL 미발동, 보유 지속)");
    }

    // ─────────────────────────────────────────────────────────────────
    //  B04-AD: BEV 발동
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("B04-AD: peak 6% + 현재 -1.0% → BEV_GUARD 발동")
    public void b04_ad_bevHit() throws Exception {
        setAdField("cachedSplitExitEnabled", false);
        setAdField("cachedTrailLadderEnabled", false);
        setAdField("cachedGracePeriodMs", 0L);
        setAdField("cachedTpTrailActivatePct", 99.0);
        setAdField("cachedTightSlPct", 5.0);
        setAdField("cachedWideSlPct", 5.0);
        setAdField("cachedWidePeriodMs", 5 * 60_000L);
        setAdField("cachedBevGuardEnabled", true);
        setAdField("cachedBevTriggerPct", 5.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getAdTpCache();
        // AD: [avg, peak, activated, trough, openedAt, splitPhase, armed]
        cache.put("KRW-B04AD", new double[]{100.0, 106.0, 0, 99.0, nowMs - 600_000, 0, 0});

        PositionEntity pe = buildPosition("KRW-B04AD", 1000.0, 100.0);
        when(positionRepo.findById("KRW-B04AD")).thenReturn(Optional.of(pe));

        invokeAdCheck("KRW-B04AD", 99.0);
        Thread.sleep(800);

        assertFalse(cache.containsKey("KRW-B04AD"), "AD BEV_GUARD 발동 → 전량 매도");
    }

    // ─────────────────────────────────────────────────────────────────
    //  B05-OP-detector: BEV 발동
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("B05-OP-detector: setBevGuard(true,5.0) + peak 8% + 현재 -0.3% → BEV_GUARD 발동")
    public void b05_op_detector_bevHit() throws Exception {
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

        detector.setSplitExitEnabled(false);
        detector.setTrailLadder(false, 0.5, 1.0, 1.5, 2.0, 1.0, 1.2, 1.5, 2.0);
        detector.setTpActivatePct(99.0);
        detector.setBevGuard(true, 5.0);  // ★ V133 BEV
        detector.updateSlConfig(0, 60, 6.0, 5.0, 3.5, 3.0, 5.0);  // SL 5%

        long nowMs = System.currentTimeMillis();
        detector.addPosition("KRW-B05", 100.0, nowMs - 600_000, 5);

        Method m = OpeningBreakoutDetector.class.getDeclaredMethod("checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);

        // Step1: peak 108 (+8%)
        m.invoke(detector, "KRW-B05", 108.0);
        // Step2: now 99.7 → roi=-0.3%, peak 8% > 5% trigger → BEV
        m.invoke(detector, "KRW-B05", 99.7);

        assertFalse(sellTypes.isEmpty(), "BEV 발동");
        assertEquals("BEV_GUARD", sellTypes.get(0), "sellType=BEV_GUARD");
        assertTrue(reasons.get(0).contains("BEV_GUARD"), "reason에 BEV_GUARD 포함: " + reasons.get(0));
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
