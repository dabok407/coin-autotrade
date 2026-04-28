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
 * V130 ①: Trail Ladder A 시나리오 테스트.
 *
 * MR/AD 스캐너 각각에 대해 peak% 구간별 drop 임계값 동작 검증:
 *   - peak 1.5% (<2구간): SPLIT_1ST drop 0.5% → 발동
 *   - peak 4.0% (<5구간): SPLIT_1ST drop 1.5% → 발동
 *   - peak 6.0% (>=5구간): SPLIT_1ST drop 2.0% → 발동
 *   - trail_ladder_enabled=false: 단일값(0.5%) 사용 → 발동 조건이 달라짐
 *
 * OP는 OpeningBreakoutDetector 경유이므로 Entity 단위 테스트(TrailLadderEntityTest)에서 커버.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V130 Trail Ladder Scenario Tests (MR/AD)")
public class V130TrailLadderScenarioTest {

    // ─────────────────────────────────────────────────────────────────
    //  MorningRush fixtures
    // ─────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────
    //  AllDay fixtures
    // ─────────────────────────────────────────────────────────────────

    @Mock private AllDayScannerConfigRepository adConfigRepo;
    @Mock private CandleService candleService;

    private AllDayScannerService adScanner;

    @BeforeEach
    public void setUp() throws Exception {
        ScannerLockService lockService = new ScannerLockService(botConfigRepo, positionRepo, tradeLogRepo);

        // MorningRush setup
        mrScanner = new MorningRushScannerService(
                mrConfigRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, privateClient, txTemplate, catalogService, tickerService,
                sharedPriceService, new SharedTradeThrottle(), lockService
        );
        setMrField("running", new AtomicBoolean(true));

        // AllDay setup
        adScanner = new AllDayScannerService(
                adConfigRepo, botConfigRepo, positionRepo, tradeLogRepo,
                candleService, catalogService, liveOrders, privateClient, txTemplate,
                tickerService, sharedPriceService, new SharedTradeThrottle(), lockService
        );
        setAdField("running", new AtomicBoolean(true));
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        setAdField("scheduler", sched);

        // txTemplate mock (공통)
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

        // MR cfg mock
        MorningRushConfigEntity mrCfg = new MorningRushConfigEntity();
        mrCfg.setMode("PAPER");
        mrCfg.setSplitExitEnabled(true);
        mrCfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        mrCfg.setSplitRatio(BigDecimal.valueOf(0.60));
        mrCfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.0));
        mrCfg.setSplit1stTrailDrop(BigDecimal.valueOf(0.5));
        when(mrConfigRepo.loadOrCreate()).thenReturn(mrCfg);

        // AD cfg mock
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
    //  MR Scenarios
    // ─────────────────────────────────────────────────────────────────

    /**
     * MR: peak 1.5% (<2 구간) → SPLIT_1ST drop 임계값=0.5%
     * 평균가 100, peak 101.5 → 0.6% drop 발생 → 0.5% 초과 → 발동
     */
    @Test
    @DisplayName("MR: peak<2(1.5%) 구간 — drop 0.5% 임계값 → SPLIT_1ST 발동")
    public void mr_peakUnder2_splitFirstFires() throws Exception {
        // Trail Ladder 활성화, 구간 설정
        setMrField("cachedTrailLadderEnabled", true);
        setMrField("cachedSplit1stDropUnder2", 0.50);
        setMrField("cachedSplit1stDropUnder3", 1.00);
        setMrField("cachedSplit1stDropUnder5", 1.50);
        setMrField("cachedSplit1stDropAbove5", 2.00);
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedSplitTpPct", 1.5);
        setMrField("cachedSplitRatio", 0.60);
        setMrField("cachedTrailDropAfterSplit", 1.0);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedSplit1stRoiFloorPct", 0.0);  // roi 하한선 비활성
        setMrField("cachedTpPct", 5.0);  // TP_TRAIL은 이 테스트에서 발동 안 함

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // [avgPrice, qty, openedAtEpochMs, peakPrice, troughPrice, splitPhase, armed]
        cache.put("KRW-MR1", new double[]{100.0, 1000.0, nowMs - 300_000, 100.0, 100.0, 0, 0});

        PositionEntity pe = buildPosition("KRW-MR1", 1000.0, 100.0);
        when(positionRepo.findById("KRW-MR1")).thenReturn(Optional.of(pe));

        // +1.6% → armed 조건 만족 (cachedSplitTpPct=1.5)
        invokeMrCheck("KRW-MR1", 101.6);
        // peak 101.5% 수준 (실제 peak는 캐시에서 관리됨)
        invokeMrCheck("KRW-MR1", 101.5);
        // drop: 101.5 → 100.9 = 0.59% drop (> 0.5% 임계값)
        invokeMrCheck("KRW-MR1", 100.9);
        Thread.sleep(300);

        // SPLIT_1ST 발동: 캐시 유지 + splitPhase=1
        assertTrue(cache.containsKey("KRW-MR1"), "1차 매도 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-MR1")[5], 0.01, "splitPhase=1");
    }

    /**
     * MR: peak 4.0% (<5 구간) → SPLIT_1ST drop 임계값=1.5%
     * peak=104, 현재=102.4 → drop 1.54% > 1.5% → 발동
     */
    @Test
    @DisplayName("MR: peak<5(4.0%) 구간 — drop 1.5% 임계값 → SPLIT_1ST 발동")
    public void mr_peakUnder5_splitFirstFires() throws Exception {
        setMrField("cachedTrailLadderEnabled", true);
        setMrField("cachedSplit1stDropUnder2", 0.50);
        setMrField("cachedSplit1stDropUnder3", 1.00);
        setMrField("cachedSplit1stDropUnder5", 1.50);
        setMrField("cachedSplit1stDropAbove5", 2.00);
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedSplitTpPct", 1.5);
        setMrField("cachedSplitRatio", 0.60);
        setMrField("cachedTrailDropAfterSplit", 1.0);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedSplit1stRoiFloorPct", 0.0);
        setMrField("cachedTpPct", 10.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // 진입가 100, peak를 104로 미리 설정 (4% 상승), armed=1
        cache.put("KRW-MR5", new double[]{100.0, 1000.0, nowMs - 300_000, 104.0, 100.0, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-MR5", 1000.0, 100.0);
        when(positionRepo.findById("KRW-MR5")).thenReturn(Optional.of(pe));

        // peak=104에서 -1.55% drop → 102.39 → 임계값 1.5% 초과 → SPLIT_1ST
        invokeMrCheck("KRW-MR5", 102.38);
        Thread.sleep(300);

        assertTrue(cache.containsKey("KRW-MR5"), "1차 매도 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-MR5")[5], 0.01, "peak<5 구간 SPLIT_1ST 발동 → splitPhase=1");
    }

    /**
     * MR: peak 6.0% (>=5 구간) → SPLIT_1ST drop 임계값=2.0%
     * peak=106, 현재=103.9 → drop 1.98% < 2.0% → 미발동 (아직 보유)
     * peak=106, 현재=103.8 → drop 2.08% > 2.0% → 발동
     */
    @Test
    @DisplayName("MR: peak>=5(6.0%) 구간 — drop 2.0% 임계값 → 경계 하회(1.98%) 차단, 초과(2.08%) 발동")
    public void mr_peakAbove5_splitFirstFires_onlyWhenDropExceeds2() throws Exception {
        setMrField("cachedTrailLadderEnabled", true);
        setMrField("cachedSplit1stDropUnder2", 0.50);
        setMrField("cachedSplit1stDropUnder3", 1.00);
        setMrField("cachedSplit1stDropUnder5", 1.50);
        setMrField("cachedSplit1stDropAbove5", 2.00);
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedSplitTpPct", 1.5);
        setMrField("cachedSplitRatio", 0.60);
        setMrField("cachedTrailDropAfterSplit", 1.0);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedSplit1stRoiFloorPct", 0.0);
        setMrField("cachedTpPct", 10.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // peak=106 (6% 상승), armed=1
        cache.put("KRW-MR6", new double[]{100.0, 1000.0, nowMs - 300_000, 106.0, 100.0, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-MR6", 1000.0, 100.0);
        when(positionRepo.findById("KRW-MR6")).thenReturn(Optional.of(pe));

        // peak=106 → drop=1.98% (< 2.0%) → 미발동
        invokeMrCheck("KRW-MR6", 103.9);
        // splitPhase는 아직 0이어야 함
        assertTrue(cache.containsKey("KRW-MR6"), "1.98% drop → 미발동 (아직 보유)");
        assertEquals(0.0, cache.get("KRW-MR6")[5], 0.01, "splitPhase=0 유지");

        // drop=2.08% (> 2.0%) → 발동
        invokeMrCheck("KRW-MR6", 103.8);
        Thread.sleep(300);

        assertTrue(cache.containsKey("KRW-MR6"), "2.08% drop → 발동 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-MR6")[5], 0.01, "peak>=5 구간 → splitPhase=1");
    }

    /**
     * MR: V129 호환 (trail_ladder_enabled=false) → 단일값(0.5%) 사용
     * peak 6% 상황에서도 단일값 0.5% 임계값으로 발동
     */
    @Test
    @DisplayName("MR: trail_ladder_enabled=false (V129 호환) → 단일값 0.5% drop 적용")
    public void mr_ladderDisabled_usesSingleValue() throws Exception {
        setMrField("cachedTrailLadderEnabled", false);
        setMrField("cachedSplit1stTrailDrop", 0.5);
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedSplitTpPct", 1.5);
        setMrField("cachedSplitRatio", 0.60);
        setMrField("cachedTrailDropAfterSplit", 1.0);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedSplit1stRoiFloorPct", 0.0);
        setMrField("cachedTpPct", 10.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // peak=106 (6% 상승), armed=1 — ladder 비활성이라 단일값(0.5%) 적용
        cache.put("KRW-MR7", new double[]{100.0, 1000.0, nowMs - 300_000, 106.0, 100.0, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-MR7", 1000.0, 100.0);
        when(positionRepo.findById("KRW-MR7")).thenReturn(Optional.of(pe));

        // peak=106에서 drop=0.55% → 단일값 0.5% 초과 → 발동 (ladder라면 2.0% 미달)
        invokeMrCheck("KRW-MR7", 105.42);
        Thread.sleep(300);

        assertTrue(cache.containsKey("KRW-MR7"), "ladder 비활성 → 단일값 0.5% 임계값 적용, 발동 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-MR7")[5], 0.01, "V129 호환 → splitPhase=1");
    }

    // ─────────────────────────────────────────────────────────────────
    //  AD Scenarios
    // ─────────────────────────────────────────────────────────────────

    /**
     * AD: peak 1.5% (<2 구간) → SPLIT_1ST drop 임계값=0.5%
     */
    @Test
    @DisplayName("AD: peak<2(1.5%) 구간 — drop 0.5% 임계값 → SPLIT_1ST 발동")
    public void ad_peakUnder2_splitFirstFires() throws Exception {
        setAdField("cachedTrailLadderEnabled", true);
        setAdField("cachedSplit1stDropUnder2", 0.50);
        setAdField("cachedSplit1stDropUnder3", 1.00);
        setAdField("cachedSplit1stDropUnder5", 1.50);
        setAdField("cachedSplit1stDropAbove5", 2.00);
        setAdField("cachedSplitExitEnabled", true);
        setAdField("cachedSplitTpPct", 1.5);
        setAdField("cachedSplitRatio", 0.60);
        setAdField("cachedTrailDropAfterSplit", 1.0);
        setAdField("cachedGracePeriodMs", 0L);
        setAdField("cachedSplit1stRoiFloorPct", 0.0);
        setAdField("cachedTpTrailActivatePct", 5.0);  // TP_TRAIL 비활성화 수준

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getAdTpCache();
        // AD tpPositionCache: [avgPrice, peakPrice, trailActivated, troughPrice, openedAtEpochMs, splitPhase, armed]
        cache.put("KRW-AD1", new double[]{100.0, 101.5, 0, 100.0, nowMs - 300_000, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-AD1", 1000.0, 100.0);
        when(positionRepo.findById("KRW-AD1")).thenReturn(Optional.of(pe));

        // peak=101.5(1.5%), drop 0.6% → 100.9 → 임계값 0.5% 초과 → SPLIT_1ST
        invokeAdCheck("KRW-AD1", 100.9);
        Thread.sleep(300);

        assertTrue(cache.containsKey("KRW-AD1"), "AD 1차 매도 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-AD1")[5], 0.01, "AD splitPhase=1");
    }

    /**
     * AD: peak>=5 구간 → SPLIT_1ST drop 임계값=2.0%
     */
    @Test
    @DisplayName("AD: peak>=5(6.0%) 구간 — drop 2.0% 임계값 → SPLIT_1ST 발동")
    public void ad_peakAbove5_splitFirstFires() throws Exception {
        setAdField("cachedTrailLadderEnabled", true);
        setAdField("cachedSplit1stDropUnder2", 0.50);
        setAdField("cachedSplit1stDropUnder3", 1.00);
        setAdField("cachedSplit1stDropUnder5", 1.50);
        setAdField("cachedSplit1stDropAbove5", 2.00);
        setAdField("cachedSplitExitEnabled", true);
        setAdField("cachedSplitTpPct", 1.5);
        setAdField("cachedSplitRatio", 0.60);
        setAdField("cachedTrailDropAfterSplit", 1.0);
        setAdField("cachedGracePeriodMs", 0L);
        setAdField("cachedSplit1stRoiFloorPct", 0.0);
        setAdField("cachedTpTrailActivatePct", 10.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getAdTpCache();
        // peak=106 (6% 상승), armed=1
        cache.put("KRW-AD6", new double[]{100.0, 106.0, 0, 100.0, nowMs - 300_000, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-AD6", 1000.0, 100.0);
        when(positionRepo.findById("KRW-AD6")).thenReturn(Optional.of(pe));

        // peak=106에서 drop=2.08% > 2.0% → 발동
        invokeAdCheck("KRW-AD6", 103.8);
        Thread.sleep(300);

        assertTrue(cache.containsKey("KRW-AD6"), "AD peak>=5 1차 매도 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-AD6")[5], 0.01, "AD splitPhase=1");
    }

    /**
     * AD: V129 호환 (ladder=false) → 단일값(0.5%) 사용
     */
    @Test
    @DisplayName("AD: trail_ladder_enabled=false (V129 호환) → 단일값 0.5% drop 적용")
    public void ad_ladderDisabled_usesSingleValue() throws Exception {
        setAdField("cachedTrailLadderEnabled", false);
        setAdField("cachedSplit1stTrailDrop", 0.5);
        setAdField("cachedSplitExitEnabled", true);
        setAdField("cachedSplitTpPct", 1.5);
        setAdField("cachedSplitRatio", 0.60);
        setAdField("cachedTrailDropAfterSplit", 1.0);
        setAdField("cachedGracePeriodMs", 0L);
        setAdField("cachedSplit1stRoiFloorPct", 0.0);
        setAdField("cachedTpTrailActivatePct", 10.0);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getAdTpCache();
        // peak=106 (6% 상승), armed=1 — 비활성이라 단일값 0.5%
        cache.put("KRW-AD7", new double[]{100.0, 106.0, 0, 100.0, nowMs - 300_000, 0, 1.0});

        PositionEntity pe = buildPosition("KRW-AD7", 1000.0, 100.0);
        when(positionRepo.findById("KRW-AD7")).thenReturn(Optional.of(pe));

        // peak=106에서 drop=0.55% → 0.5% 초과 → 발동 (ladder 비활성)
        invokeAdCheck("KRW-AD7", 105.42);
        Thread.sleep(300);

        assertTrue(cache.containsKey("KRW-AD7"), "AD ladder 비활성 → 단일값 0.5% 임계값 발동 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-AD7")[5], 0.01, "AD V129 호환 → splitPhase=1");
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
