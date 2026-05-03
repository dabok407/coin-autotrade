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
 * V134 Phase 2b — 동적 ATR SL Service 적용 시나리오 테스트
 *
 * 논리:
 *   매수 후 ATR(14, 1분봉) 측정 → dynamic SL = clamp(atr_pct * mult, min, max)
 *   dynamicSlMap에 저장 → checkRealtimeTpSl SL_TIGHT 분기에서 우선 사용
 *   cachedSlAtrEnabled=false면 기존 cachedSlPct/cachedTightSlPct fallback (V130 동작)
 *
 * 시나리오:
 *   A01-MR: dynamicSlMap에 1.8% 직접 set + pnl=-1.85% → SL_TIGHT_ATR 발동
 *   A02-MR: dynamicSlMap 없음 + pnl=-2.5% (cached SL 5%) → 매도 안 함 (SL 미달)
 *   A03-AD: dynamicSlMap에 2.5% set + pnl=-2.6% → SL_TIGHT_ATR 발동
 *   A04-OP-detector: setDynamicSlForMarket(market, 1.5) + pnl=-1.6% → SL_TIGHT_ATR 발동
 *   A05-OP-detector: dynamicSlMap 없음 + pnl=-1.6% (cached 5%) → 매도 안 함
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V134 Phase 2b ATR SL Scenario Tests")
public class V134Phase2bAtrSlScenarioTest {

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

        // V134: MR에 candleService 의존성 추가된 13-인자 생성자 사용
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

        // configRepo mocks
        MorningRushConfigEntity mrCfg = new MorningRushConfigEntity();
        mrCfg.setMode("PAPER");
        mrCfg.setSplitExitEnabled(false);
        when(mrConfigRepo.loadOrCreate()).thenReturn(mrCfg);

        AllDayScannerConfigEntity adCfg = new AllDayScannerConfigEntity();
        adCfg.setMode("PAPER");
        adCfg.setSplitExitEnabled(false);
        when(adConfigRepo.loadOrCreate()).thenReturn(adCfg);
    }

    // ─────────────────────────────────────────────────────────────────
    //  A01-MR: dynamic SL 발동
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("A01-MR: dynamicSlMap에 1.8% set + pnl=-1.85% → SL_TIGHT_ATR 발동")
    public void a01_mr_dynamicSlHit() throws Exception {
        setMrField("cachedSplitExitEnabled", false);
        setMrField("cachedTrailLadderEnabled", false);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 99.0);
        setMrField("cachedSlPct", 5.0);  // fallback SL 5%
        setMrField("cachedWideSlPct", 5.0);
        setMrField("cachedWidePeriodMs", 1L);  // immediately tight SL
        setMrField("cachedBevGuardEnabled", false);
        setMrField("cachedSlAtrEnabled", true);

        // dynamicSlMap에 1.8% 직접 주입 (실제로는 monitorPositions에서 갱신됨)
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Double> dynMap = (ConcurrentHashMap<String, Double>)
                getMrField("dynamicSlMap");
        dynMap.put("KRW-A01", 1.8);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // avg=100, now=98.15 → pnl=-1.85% < -1.8% (dynamic SL) → 매도 발동
        cache.put("KRW-A01", new double[]{100.0, 1000.0, nowMs - 600_000, 100.0, 98.0, 0, 0});

        PositionEntity pe = buildPosition("KRW-A01", 1000.0, 100.0);
        when(positionRepo.findById("KRW-A01")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-A01", 98.15);
        Thread.sleep(800);

        assertFalse(cache.containsKey("KRW-A01"), "dynamic SL 1.8% 발동 → 전량 매도, 캐시 제거");
    }

    // ─────────────────────────────────────────────────────────────────
    //  A02-MR: dynamic SL 없음 + cached SL 미달
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("A02-MR: dynamicSlMap 없음 + pnl=-2.5% (cached SL 5%) → 매도 안 함 (fallback)")
    public void a02_mr_noDynamicSl_cachedFallback() throws Exception {
        setMrField("cachedSplitExitEnabled", false);
        setMrField("cachedTrailLadderEnabled", false);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 99.0);
        setMrField("cachedSlPct", 5.0);
        setMrField("cachedWideSlPct", 5.0);
        setMrField("cachedWidePeriodMs", 1L);
        setMrField("cachedBevGuardEnabled", false);
        setMrField("cachedSlAtrEnabled", true);

        // dynamicSlMap에 없음 → cachedSlPct 5% fallback

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // pnl=-2.5% > -5% (cached SL) → 매도 안 함
        cache.put("KRW-A02", new double[]{100.0, 1000.0, nowMs - 600_000, 100.0, 97.5, 0, 0});

        PositionEntity pe = buildPosition("KRW-A02", 1000.0, 100.0);
        when(positionRepo.findById("KRW-A02")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-A02", 97.5);

        assertTrue(cache.containsKey("KRW-A02"), "cached fallback (5%) → SL 미달, 보유 지속");
    }

    // ─────────────────────────────────────────────────────────────────
    //  A03-AD: dynamic SL 발동
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("A03-AD: dynamicSlMap에 2.5% set + pnl=-2.6% → SL_TIGHT_ATR 발동")
    public void a03_ad_dynamicSlHit() throws Exception {
        setAdField("cachedSplitExitEnabled", false);
        setAdField("cachedTrailLadderEnabled", false);
        setAdField("cachedGracePeriodMs", 0L);
        setAdField("cachedTpTrailActivatePct", 99.0);
        setAdField("cachedTightSlPct", 5.0);  // fallback
        setAdField("cachedWideSlPct", 5.0);
        setAdField("cachedWidePeriodMs", 1L);
        setAdField("cachedBevGuardEnabled", false);
        setAdField("cachedSlAtrEnabled", true);

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Double> dynMap = (ConcurrentHashMap<String, Double>)
                getAdField("dynamicSlMap");
        dynMap.put("KRW-A03AD", 2.5);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getAdTpCache();
        // avg=100, now=97.4 → pnl=-2.6% < -2.5% → 매도 발동
        cache.put("KRW-A03AD", new double[]{100.0, 100.0, 0, 97.0, nowMs - 600_000, 0, 0});

        PositionEntity pe = buildPosition("KRW-A03AD", 1000.0, 100.0);
        when(positionRepo.findById("KRW-A03AD")).thenReturn(Optional.of(pe));

        invokeAdCheck("KRW-A03AD", 97.4);
        Thread.sleep(800);

        assertFalse(cache.containsKey("KRW-A03AD"), "AD dynamic SL 2.5% 발동 → 전량 매도");
    }

    // ─────────────────────────────────────────────────────────────────
    //  A04-OP-detector: setDynamicSlForMarket + 발동
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("A04-OP-detector: setDynamicSlForMarket(KRW,1.5) + pnl=-1.6% → SL_TIGHT_ATR 발동")
    public void a04_op_detector_dynamicSlHit() throws Exception {
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
        detector.setTpActivatePct(99.0);
        detector.updateSlConfig(0, 1, 6.0, 5.0, 3.5, 3.0, 5.0);  // SL_TIGHT 5% (cached)
        detector.setDynamicSlForMarket("KRW-A04", 1.5);  // ★ V134 dynamic SL

        long nowMs = System.currentTimeMillis();
        detector.addPosition("KRW-A04", 100.0, nowMs - 600_000, 5);

        Method m = OpeningBreakoutDetector.class.getDeclaredMethod("checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);
        // pnl=-1.6% < -1.5% (dynamic SL) → SL_TIGHT_ATR 발동
        m.invoke(detector, "KRW-A04", 98.4);

        assertFalse(sellTypes.isEmpty(), "dynamic SL 발동");
        assertEquals("SL_TIGHT", sellTypes.get(0));
        assertTrue(reasons.get(0).contains("SL_TIGHT_ATR"), "reason에 SL_TIGHT_ATR 포함: " + reasons.get(0));
    }

    // ─────────────────────────────────────────────────────────────────
    //  A05-OP-detector: dynamic SL 없음 → cached fallback
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("A05-OP-detector: dynamicSlMap 없음 + pnl=-1.6% (cached 5%) → 매도 안 함 (fallback)")
    public void a05_op_detector_noDynamicSl_fallback() throws Exception {
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

        detector.setSplitExitEnabled(false);
        detector.setTpActivatePct(99.0);
        detector.updateSlConfig(0, 1, 6.0, 5.0, 3.5, 3.0, 5.0);  // cached SL 5%

        long nowMs = System.currentTimeMillis();
        detector.addPosition("KRW-A05", 100.0, nowMs - 600_000, 5);

        Method m = OpeningBreakoutDetector.class.getDeclaredMethod("checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);
        // pnl=-1.6% > -5% (cached SL) → 매도 안 함
        m.invoke(detector, "KRW-A05", 98.4);

        assertTrue(sellTypes.isEmpty(), "cached SL 5% fallback → SL 미달, 매도 안 함");
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

    private Object getMrField(String name) throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(mrScanner);
    }

    private Object getAdField(String name) throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(adScanner);
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
