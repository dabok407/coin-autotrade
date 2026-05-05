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
 * V138 권고 2 — split_1st_drop_above_5: 5.0 → 3.0 시나리오 테스트
 *
 * Agent H 백테 결과 반영:
 *   AXL 5/4 케이스(peak +7.14%)에서 split_1st 5%→3%로 좁히면 1차 매도 시점이 달라짐
 *   trail_after는 5.0% 유지 → PRL/ENSO 같은 큰 추세 보존
 *
 * 시나리오:
 *   F01-MR: peak +7%(108원) + drop 3% → split_1st 3.0%로 발동 (V138 의도)
 *   F02-MR: peak +7%(108원) + drop 4% → split_1st 발동 (3% 초과)
 *   F03-MR: peak +14%(116원) + drop 3% → split_1st 발동 (early), trail_after 5%는 따로
 *   F04-MR: split_1st 3% 발동 후 trail_after 5%로 잔여 trail (큰 추세 보존)
 *   F05-AD: 동일 패턴
 *   F06-OP-detector: 동일 패턴
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V138 Rec2 Split1st Above5 3% Scenario Tests")
public class V138Rec2Split1stAbove5ScenarioTest {

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
    @Mock private CandleService candleService;

    private MorningRushScannerService mrScanner;
    @Mock private AllDayScannerConfigRepository adConfigRepo;
    private AllDayScannerService adScanner;

    @BeforeEach
    public void setUp() throws Exception {
        ScannerLockService lockService = new ScannerLockService(botConfigRepo, positionRepo, tradeLogRepo);

        mrScanner = new MorningRushScannerService(
                mrConfigRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, privateClient, txTemplate, catalogService, tickerService,
                sharedPriceService, new SharedTradeThrottle(), lockService, candleService
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
    //  F01-MR: peak +7%(108원) + drop 3% → V138 split_1st 3.0% 발동
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("F01-MR: peak +7%(108) + drop 3% → split_1st 3.0% 발동 (V138)")
    public void f01_mr_peak7_drop3_split1stFires() throws Exception {
        setMrLadderV138();
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // avg=100, peak=108(+7%), now=104.76 → drop=(108-104.76)/108=3.0% > 3.0% (V138 split_1st_above_5=3.0)
        cache.put("KRW-F01", new double[]{100.0, 1000.0, nowMs - 600_000, 108.0, 100.0, 0, 1.0});  // armed=1

        PositionEntity pe = buildPosition("KRW-F01", 1000.0, 100.0);
        when(positionRepo.findById("KRW-F01")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-F01", 104.5);  // drop=(108-104.5)/108=3.24% > 3.0%
        Thread.sleep(800);

        // splitPhase=1로 갱신 = SPLIT_1ST 발동 성공
        double[] pos = cache.get("KRW-F01");
        assertEquals(1.0, pos[5], 0.01, "V138 split_1st 3.0%로 발동");
    }

    // ─────────────────────────────────────────────────────────────────
    //  F02-MR: peak +7% + drop 2.5% → split_1st 미발동 (3% 미달)
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("F02-MR: peak +7% + drop 2.5% (3% 미달) → split_1st 미발동")
    public void f02_mr_peak7_drop25_noFire() throws Exception {
        setMrLadderV138();
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // avg=100, peak=108, now=105.30 → drop=(108-105.30)/108=2.50% < 3.0%
        cache.put("KRW-F02", new double[]{100.0, 1000.0, nowMs - 600_000, 108.0, 100.0, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-F02", 1000.0, 100.0);
        when(positionRepo.findById("KRW-F02")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-F02", 105.30);

        // splitPhase=0 유지 = 미발동
        assertEquals(0.0, cache.get("KRW-F02")[5], 0.01, "V138 drop 2.5% < 3.0% → 미발동");
    }

    // ─────────────────────────────────────────────────────────────────
    //  F03-MR: peak +14% (PRL 케이스) + drop 3% → split_1st early 발동
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("F03-MR: peak +14%(114) + drop 3% → split_1st 3.0% early 발동 (PRL 케이스)")
    public void f03_mr_peak14_drop3_split1stEarlyFire() throws Exception {
        setMrLadderV138();
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // avg=100, peak=114(+14%), now=110.58 → drop=(114-110.58)/114=3.0%
        cache.put("KRW-F03", new double[]{100.0, 1000.0, nowMs - 600_000, 114.0, 100.0, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-F03", 1000.0, 100.0);
        when(positionRepo.findById("KRW-F03")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-F03", 110.0);  // drop=(114-110)/114=3.51% > 3.0%
        Thread.sleep(800);

        assertEquals(1.0, cache.get("KRW-F03")[5], 0.01, "V138 +14% 케이스에서 1차 매도 빨리 챙김 (+10.58% ROI)");
    }

    // ─────────────────────────────────────────────────────────────────
    //  F04-MR: 1차 매도 후 trail_after 5% (큰 추세 보존)
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("F04-MR: split_1st 3% 발동 후, trail_after 5%로 잔여 trail (큰 추세 보존)")
    public void f04_mr_afterSplit_trailAfter5pct() throws Exception {
        setMrLadderV138();
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // splitPhase=1, peak=120(+20%), now=114 → drop=5.0% (trail_after_above_5 5.0% 유지)
        cache.put("KRW-F04", new double[]{100.0, 700.0, nowMs - 600_000, 120.0, 100.0, 1.0, 0});

        PositionEntity pe = buildPosition("KRW-F04", 700.0, 100.0);
        pe.setSplitPhase(1);
        when(positionRepo.findById("KRW-F04")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-F04", 114.0);
        Thread.sleep(800);

        // 잔여 70% 매도 (trail_after_above_5 5.0% 유지 → drop 5%서 매도)
        assertFalse(cache.containsKey("KRW-F04"), "trail_after 5.0% 유지 → 잔여 매도");
    }

    // ─────────────────────────────────────────────────────────────────
    //  F05-AD: 동일 패턴
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("F05-AD: peak +7% + drop 3% → split_1st 3.0% 발동")
    public void f05_ad_peak7_drop3_split1stFires() throws Exception {
        setAdLadderV138();
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getAdTpCache();
        // AD: [avg, peak, activated, trough, openedAt, splitPhase, armed]
        cache.put("KRW-F05AD", new double[]{100.0, 108.0, 0, 100.0, nowMs - 600_000, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-F05AD", 1000.0, 100.0);
        when(positionRepo.findById("KRW-F05AD")).thenReturn(Optional.of(pe));

        invokeAdCheck("KRW-F05AD", 104.5);  // drop=3.24%
        Thread.sleep(800);

        assertEquals(1.0, cache.get("KRW-F05AD")[5], 0.01, "AD V138 split_1st 3.0% 발동");
    }

    // ─────────────────────────────────────────────────────────────────
    //  F06-OP-detector: 동일 패턴
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("F06-OP-detector: setTrailLadder split_1st_above_5=3.0 + peak +7% + drop 3% → SPLIT_1ST 발동")
    public void f06_op_detector_v138() throws Exception {
        SharedPriceService mockShared = mock(SharedPriceService.class);
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
        detector.setSplitRatio(0.30);
        // V138: split_1st_above_5 = 3.0, trail_after_above_5 = 5.0 (유지)
        detector.setTrailLadder(true, 0.7, 1.0, 2.5, 3.0, 1.0, 1.5, 2.5, 5.0);
        detector.setSplit1stRoiFloorPct(0.30);
        detector.setTpActivatePct(99.0);
        detector.updateSlConfig(0, 60, 99.0, 99.0, 99.0, 99.0, 99.0);

        long nowMs = System.currentTimeMillis();
        detector.addPosition("KRW-F06", 100.0, nowMs - 600_000, 5);

        Method m = OpeningBreakoutDetector.class.getDeclaredMethod("checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);

        // armed: +1.5% peak 갱신 (peak 108 도달)
        m.invoke(detector, "KRW-F06", 108.0);
        // peak 108 → drop 3% = 104.76 → V138 split_1st 3.0%로 발동
        m.invoke(detector, "KRW-F06", 104.5);  // drop=3.24%

        assertFalse(sellTypes.isEmpty(), "V138 split_1st 3.0% 발동");
        assertEquals("SPLIT_1ST", sellTypes.get(0));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────

    private void setMrLadderV138() throws Exception {
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedTrailLadderEnabled", true);
        // V138: split_1st_above_5 = 3.0 (변경), 나머지는 V133 유지
        setMrField("cachedSplit1stDropUnder2", 0.7);
        setMrField("cachedSplit1stDropUnder3", 1.0);
        setMrField("cachedSplit1stDropUnder5", 2.5);
        setMrField("cachedSplit1stDropAbove5", 3.0);  // ★ V138
        setMrField("cachedTrailAfterDropUnder2", 1.0);
        setMrField("cachedTrailAfterDropUnder3", 1.5);
        setMrField("cachedTrailAfterDropUnder5", 2.5);
        setMrField("cachedTrailAfterDropAbove5", 5.0);  // 유지
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 99.0);
        setMrField("cachedSlPct", 99.0);
        setMrField("cachedWideSlPct", 99.0);
        setMrField("cachedWidePeriodMs", 5 * 60_000L);
        setMrField("cachedBevGuardEnabled", false);
        setMrField("cachedSlAtrEnabled", false);
        setMrField("cachedSplit1stCooldownMs", 0L);
    }

    private void setAdLadderV138() throws Exception {
        setAdField("cachedSplitExitEnabled", true);
        setAdField("cachedTrailLadderEnabled", true);
        setAdField("cachedSplit1stDropUnder2", 0.7);
        setAdField("cachedSplit1stDropUnder3", 1.0);
        setAdField("cachedSplit1stDropUnder5", 2.5);
        setAdField("cachedSplit1stDropAbove5", 3.0);  // ★ V138
        setAdField("cachedTrailAfterDropUnder2", 1.0);
        setAdField("cachedTrailAfterDropUnder3", 1.5);
        setAdField("cachedTrailAfterDropUnder5", 2.5);
        setAdField("cachedTrailAfterDropAbove5", 5.0);
        setAdField("cachedGracePeriodMs", 0L);
        setAdField("cachedTpTrailActivatePct", 99.0);
        setAdField("cachedTightSlPct", 99.0);
        setAdField("cachedWideSlPct", 99.0);
        setAdField("cachedWidePeriodMs", 5 * 60_000L);
        setAdField("cachedBevGuardEnabled", false);
        setAdField("cachedSlAtrEnabled", false);
        setAdField("cachedSplit1stCooldownMs", 0L);
    }

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
