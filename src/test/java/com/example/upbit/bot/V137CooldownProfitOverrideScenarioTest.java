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
 * V137 — 사용자 의견 반영: 쿨다운은 ROI<0 일 때만 적용
 *
 * 04-29 KRW-OPEN 사고 분석:
 *   1차 매도 직후 가격 423→418→411(+1.48%)→404(-0.25%)→397(-1.98%) 폭락
 *   60초 쿨다운으로 411(+1.48%, drop 2.6%)에서 매도 못 함 → 397까지 떨어진 후 매도
 *   사용자 의도: 쿨다운은 마이너스 흔들림 견디는 용도. 양수 상승 중일 때는 즉시 매도.
 *
 * 시나리오:
 *   E01-MR: splitPhase=1 + 쿨다운 중 + ROI=+1.48% + drop 2.6% → 즉시 매도 (사용자 의도)
 *   E02-MR: splitPhase=1 + 쿨다운 중 + ROI=-0.5% + drop 2.6% → 차단 (V129 마이너스 견딤)
 *   E03-MR: splitPhase=1 + 쿨다운 풀림 + ROI=-1.98% + drop 5% → 매도 (정상)
 *   E04-AD: ROI=+1.0% + 쿨다운 중 + drop 2% → 즉시 매도
 *   E05-OP-detector: ROI=+1.5% + 쿨다운 중 + drop 1.5% → 즉시 매도
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V137 Cooldown Profit Override Scenario Tests")
public class V137CooldownProfitOverrideScenarioTest {

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
    //  E01-MR: 쿨다운 중 + ROI 양수 + drop 도달 → 즉시 매도
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("E01-MR: 쿨다운 중 + ROI=+1.48% + drop 2.6% → 즉시 매도 (사용자 의도)")
    public void e01_mr_cooldownButProfit_sellsImmediately() throws Exception {
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedTrailLadderEnabled", true);
        setMrField("cachedTrailAfterDropUnder2", 1.0);
        setMrField("cachedTrailAfterDropUnder3", 1.5);
        setMrField("cachedTrailAfterDropUnder5", 2.5);
        setMrField("cachedTrailAfterDropAbove5", 5.0);
        setMrField("cachedSplit1stCooldownMs", 60_000L);  // 60초 쿨다운
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 99.0);
        setMrField("cachedSlPct", 5.0);
        setMrField("cachedWideSlPct", 5.0);
        setMrField("cachedWidePeriodMs", 5 * 60_000L);
        setMrField("cachedBevGuardEnabled", false);
        setMrField("cachedSlAtrEnabled", false);

        // splitPhase=1로 cache, peak=104.4(+4.4%), now=101.48(+1.48%) → drop 2.8%, peak 4%대 → drop_under_5 2.5%
        long nowMs = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execAtMap = (ConcurrentHashMap<String, Long>) getMrField("split1stExecutedAtMap");
        execAtMap.put("KRW-E01", nowMs - 30_000);  // 30초 전 1차 매도 (쿨다운 30초 남음)

        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        cache.put("KRW-E01", new double[]{100.0, 700.0, nowMs - 600_000, 104.4, 100.0, 1.0, 0});

        PositionEntity pe = buildPosition("KRW-E01", 700.0, 100.0);
        pe.setSplitPhase(1);
        when(positionRepo.findById("KRW-E01")).thenReturn(Optional.of(pe));

        // now=101.48 → ROI=+1.48% (양수), drop=(104.4-101.48)/104.4=2.80% > 2.5% (peak<5)
        invokeMrCheck("KRW-E01", 101.48);
        Thread.sleep(800);

        // 쿨다운 무시 → SPLIT_2ND_TRAIL 발동 → 캐시 제거 (전량 매도)
        assertFalse(cache.containsKey("KRW-E01"), "ROI 양수 + drop 도달 → 쿨다운 무시 매도");
    }

    // ─────────────────────────────────────────────────────────────────
    //  E02-MR: 쿨다운 중 + ROI 음수 + drop 도달 → 차단 (V129 견딤)
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("E02-MR: 쿨다운 중 + ROI=-0.5% + drop 2.6% → 차단 (V129 마이너스 견딤)")
    public void e02_mr_cooldownAndNegRoi_blocked() throws Exception {
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedTrailLadderEnabled", true);
        setMrField("cachedTrailAfterDropUnder2", 1.0);
        setMrField("cachedTrailAfterDropUnder3", 1.5);
        setMrField("cachedTrailAfterDropUnder5", 2.5);
        setMrField("cachedTrailAfterDropAbove5", 5.0);
        setMrField("cachedSplit1stCooldownMs", 60_000L);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 99.0);
        setMrField("cachedSlPct", 5.0);
        setMrField("cachedWideSlPct", 5.0);
        setMrField("cachedWidePeriodMs", 5 * 60_000L);
        setMrField("cachedBevGuardEnabled", false);
        setMrField("cachedSlAtrEnabled", false);

        long nowMs = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execAtMap = (ConcurrentHashMap<String, Long>) getMrField("split1stExecutedAtMap");
        execAtMap.put("KRW-E02", nowMs - 30_000);  // 쿨다운 중

        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        // peak=102(+2%), now=99.5(-0.5%, drop 2.45%, peak<3 → trail 1.5% 도달)
        cache.put("KRW-E02", new double[]{100.0, 700.0, nowMs - 600_000, 102.0, 99.5, 1.0, 0});

        PositionEntity pe = buildPosition("KRW-E02", 700.0, 100.0);
        pe.setSplitPhase(1);
        when(positionRepo.findById("KRW-E02")).thenReturn(Optional.of(pe));

        invokeMrCheck("KRW-E02", 99.5);

        // 쿨다운 차단 → 매도 안 함 (캐시 유지)
        assertTrue(cache.containsKey("KRW-E02"), "ROI 음수 + 쿨다운 → 차단 (V129 의도)");
    }

    // ─────────────────────────────────────────────────────────────────
    //  E03-MR: 쿨다운 풀림 + ROI 음수 + drop → 매도 정상
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("E03-MR: 쿨다운 풀림 + ROI=-1.98% + drop 5% → 매도 (정상)")
    public void e03_mr_cooldownExpired_sellsNormally() throws Exception {
        setMrField("cachedSplitExitEnabled", true);
        setMrField("cachedTrailLadderEnabled", true);
        setMrField("cachedTrailAfterDropUnder2", 1.0);
        setMrField("cachedTrailAfterDropUnder3", 1.5);
        setMrField("cachedTrailAfterDropUnder5", 2.5);
        setMrField("cachedTrailAfterDropAbove5", 5.0);
        setMrField("cachedSplit1stCooldownMs", 60_000L);
        setMrField("cachedGracePeriodMs", 0L);
        setMrField("cachedTpPct", 99.0);
        setMrField("cachedSlPct", 99.0);  // SL 비활성으로 단순화
        setMrField("cachedWideSlPct", 99.0);
        setMrField("cachedWidePeriodMs", 5 * 60_000L);
        setMrField("cachedBevGuardEnabled", false);
        setMrField("cachedSlAtrEnabled", false);

        long nowMs = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execAtMap = (ConcurrentHashMap<String, Long>) getMrField("split1stExecutedAtMap");
        execAtMap.put("KRW-E03", nowMs - 90_000);  // 90초 전 — 쿨다운 풀림

        ConcurrentHashMap<String, double[]> cache = getMrPositionCache();
        cache.put("KRW-E03", new double[]{100.0, 700.0, nowMs - 600_000, 103.0, 98.0, 1.0, 0});

        PositionEntity pe = buildPosition("KRW-E03", 700.0, 100.0);
        pe.setSplitPhase(1);
        when(positionRepo.findById("KRW-E03")).thenReturn(Optional.of(pe));

        // now=98.02 → ROI=-1.98%, drop=(103-98.02)/103=4.83% > 1.5% (peak<5)
        invokeMrCheck("KRW-E03", 98.02);
        Thread.sleep(800);

        assertFalse(cache.containsKey("KRW-E03"), "쿨다운 풀림 → 정상 매도");
    }

    // ─────────────────────────────────────────────────────────────────
    //  E04-AD: ROI 양수 + 쿨다운 중 → 즉시 매도
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("E04-AD: 쿨다운 중 + ROI=+1.0% + drop 2% → 즉시 매도")
    public void e04_ad_cooldownButProfit_sellsImmediately() throws Exception {
        setAdField("cachedSplitExitEnabled", true);
        setAdField("cachedTrailLadderEnabled", true);
        setAdField("cachedTrailAfterDropUnder2", 1.0);
        setAdField("cachedTrailAfterDropUnder3", 1.5);
        setAdField("cachedTrailAfterDropUnder5", 2.5);
        setAdField("cachedTrailAfterDropAbove5", 5.0);
        setAdField("cachedSplit1stCooldownMs", 60_000L);
        setAdField("cachedGracePeriodMs", 0L);
        setAdField("cachedTpTrailActivatePct", 99.0);
        setAdField("cachedTightSlPct", 5.0);
        setAdField("cachedWideSlPct", 5.0);
        setAdField("cachedWidePeriodMs", 5 * 60_000L);
        setAdField("cachedBevGuardEnabled", false);
        setAdField("cachedSlAtrEnabled", false);

        long nowMs = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execAtMap = (ConcurrentHashMap<String, Long>) getAdField("split1stExecutedAtMap");
        execAtMap.put("KRW-E04AD", nowMs - 30_000);

        ConcurrentHashMap<String, double[]> cache = getAdTpCache();
        // peak=102(+2%, peak<3 → trail_under_3=1.5%), now=100.4(+0.4%) → drop=(102-100.4)/102=1.57% > 1.5%
        cache.put("KRW-E04AD", new double[]{100.0, 102.0, 0, 100.0, nowMs - 600_000, 1.0, 0});

        PositionEntity pe = buildPosition("KRW-E04AD", 700.0, 100.0);
        pe.setSplitPhase(1);
        when(positionRepo.findById("KRW-E04AD")).thenReturn(Optional.of(pe));

        invokeAdCheck("KRW-E04AD", 100.4);
        Thread.sleep(800);

        assertFalse(cache.containsKey("KRW-E04AD"), "AD ROI 양수 + 쿨다운 무시 매도");
    }

    // ─────────────────────────────────────────────────────────────────
    //  E05-OP-detector: ROI 양수 + 쿨다운 중 → 즉시 매도
    // ─────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("E05-OP-detector: 쿨다운 중 + ROI=+2% + drop 1.5% → 즉시 매도")
    public void e05_op_detector_cooldownButProfit_sellsImmediately() throws Exception {
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
        detector.setTrailDropAfterSplit(1.5);
        detector.setSplit1stTrailDropPct(0.7);
        detector.setSplit1stCooldownSec(60);  // 60초 쿨다운
        detector.setTrailLadder(true, 0.7, 1.0, 2.5, 4.0, 1.0, 1.5, 2.5, 5.0);
        detector.setTpActivatePct(99.0);
        detector.updateSlConfig(0, 60, 99.0, 99.0, 99.0, 99.0, 99.0);

        long nowMs = System.currentTimeMillis();
        // 매수 + 1차 매도 후 splitPhase=1 상태로 만들기 위한 reflection
        detector.addPosition("KRW-E05", 100.0, nowMs - 600_000, 5);
        Field execAtField = OpeningBreakoutDetector.class.getDeclaredField("split1stExecutedAtMap");
        execAtField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execAtMap = (ConcurrentHashMap<String, Long>) execAtField.get(detector);
        execAtMap.put("KRW-E05", nowMs - 30_000);

        Field splitPhaseField = OpeningBreakoutDetector.class.getDeclaredField("splitPhaseMap");
        splitPhaseField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Integer> splitPhaseMap = (ConcurrentHashMap<String, Integer>) splitPhaseField.get(detector);
        splitPhaseMap.put("KRW-E05", 1);

        Method m = OpeningBreakoutDetector.class.getDeclaredMethod("checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);

        // peak를 102.5까지 올린 후 가격 100.95로 하락 → ROI=+0.95% (양수), drop=(102.5-100.95)/102.5=1.51% > 1.5%
        // peak<3 → trail_under_3=1.5%
        m.invoke(detector, "KRW-E05", 102.5);  // peak 102.5 갱신
        m.invoke(detector, "KRW-E05", 100.95);  // ROI +0.95%, drop 1.51%

        assertFalse(sellTypes.isEmpty(), "Detector ROI 양수 + 쿨다운 무시 매도");
        assertEquals("SPLIT_2ND_TRAIL", sellTypes.get(0));
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
