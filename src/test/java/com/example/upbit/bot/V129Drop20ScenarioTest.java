package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.SharedPriceService;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.upbit.UpbitPrivateClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * V129 Gap #1: drop 2.0% 통일 전용 시나리오.
 *
 * V129 변경: split_1st_trail_drop 및 trail_drop_after_split 전부 2.0%로 통일
 *   - MR: 0.65 → 2.00
 *   - Opening: 0.45 → 2.00
 *   - AllDay: 0.85 → 2.00
 *
 * 기존 테스트는 가변값(1.0%) 동작만 간접 검증. 이 테스트는 2.0% 통일값으로
 * 1차/2차 TRAIL이 정확히 경계에서 발동/차단되는지 3 스캐너 모두 명시적으로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class V129Drop20ScenarioTest {

    // ── MR mocks ──
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
    // ── AllDay mocks ──
    @Mock private AllDayScannerConfigRepository adConfigRepo;
    @Mock private CandleService candleService;

    private MorningRushScannerService mr;
    private AllDayScannerService ad;
    private OpeningBreakoutDetector opDet;

    // Opening detector 콜백 수집
    private final List<String> opSellMarkets = new ArrayList<String>();
    private final List<String> opSellTypes = new ArrayList<String>();

    @BeforeEach
    public void setUp() throws Exception {
        // MR 인스턴스
        mr = new MorningRushScannerService(
                mrConfigRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, privateClient, txTemplate, catalogService, tickerService,
                sharedPriceService, new SharedTradeThrottle()
        );
        setField(mr, "running", new AtomicBoolean(true));
        setField(mr, "cachedTpPct", 2.3);
        setField(mr, "cachedSlPct", 3.0);
        setField(mr, "cachedTpTrailDropPct", 5.0);
        setField(mr, "cachedGracePeriodMs", 60_000L);
        setField(mr, "cachedWidePeriodMs", 5 * 60_000L);
        setField(mr, "cachedWideSlPct", 5.0);
        setField(mr, "cachedSplitExitEnabled", true);
        setField(mr, "cachedSplitTpPct", 1.5);
        setField(mr, "cachedSplitRatio", 0.60);
        // V129 통일값
        setField(mr, "cachedSplit1stTrailDrop", 2.0);
        setField(mr, "cachedTrailDropAfterSplit", 2.0);
        setField(mr, "cachedSplit1stCooldownMs", 60_000L);

        MorningRushConfigEntity mrCfg = new MorningRushConfigEntity();
        mrCfg.setMode("PAPER");
        mrCfg.setSplitExitEnabled(true);
        mrCfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        mrCfg.setSplitRatio(BigDecimal.valueOf(0.60));
        mrCfg.setSplit1stTrailDrop(BigDecimal.valueOf(2.0));
        mrCfg.setTrailDropAfterSplit(BigDecimal.valueOf(2.0));
        when(mrConfigRepo.loadOrCreate()).thenReturn(mrCfg);

        // AllDay 인스턴스
        ad = new AllDayScannerService(
                adConfigRepo, botConfigRepo, positionRepo, tradeLogRepo,
                candleService, catalogService, liveOrders, privateClient, txTemplate,
                tickerService, sharedPriceService, new SharedTradeThrottle()
        );
        setField(ad, "running", new AtomicBoolean(true));
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        setField(ad, "scheduler", sched);
        setField(ad, "cachedGracePeriodMs", 60_000L);
        setField(ad, "cachedWidePeriodMs", 15 * 60_000L);
        setField(ad, "cachedWideSlPct", 3.0);
        setField(ad, "cachedTightSlPct", 1.5);
        setField(ad, "cachedTpTrailActivatePct", 2.0);
        setField(ad, "cachedTpTrailDropPct", 5.0);
        setField(ad, "cachedSplitExitEnabled", true);
        setField(ad, "cachedSplitTpPct", 1.5);
        setField(ad, "cachedSplitRatio", 0.60);
        // V129 통일값
        setField(ad, "cachedSplit1stTrailDrop", 2.0);
        setField(ad, "cachedTrailDropAfterSplit", 2.0);
        setField(ad, "cachedSplit1stCooldownMs", 60_000L);

        AllDayScannerConfigEntity adCfg = new AllDayScannerConfigEntity();
        adCfg.setMode("PAPER");
        adCfg.setSplitExitEnabled(true);
        adCfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        adCfg.setSplitRatio(BigDecimal.valueOf(0.60));
        adCfg.setSplit1stTrailDrop(BigDecimal.valueOf(2.0));
        adCfg.setTrailDropAfterSplit(BigDecimal.valueOf(2.0));
        when(adConfigRepo.loadOrCreate()).thenReturn(adCfg);

        // Opening detector
        opSellMarkets.clear();
        opSellTypes.clear();
        opDet = new OpeningBreakoutDetector(mock(SharedPriceService.class));
        opDet.setConfirmMinIntervalMs(0);
        opDet.setListener(new OpeningBreakoutDetector.BreakoutListener() {
            @Override public void onBreakoutConfirmed(String m, double p, double h, double a) {}
            @Override public void onTpSlTriggered(String m, double p, String type, String reason) {
                opSellMarkets.add(m);
                opSellTypes.add(type);
            }
        });
        opDet.setSplitExitEnabled(true);
        opDet.setSplitTpPct(1.5);
        opDet.setSplitRatio(0.60);
        // V129 통일값
        opDet.setSplit1stTrailDropPct(2.0);
        opDet.setTrailDropAfterSplit(2.0);
        opDet.setSplit1stCooldownSec(60);
        opDet.setTpActivatePct(2.0);
        opDet.setTrailFromPeakPct(1.0);
        opDet.updateSlConfig(30, 15, 6.0, 5.0, 3.5, 3.0, 1.5);

        // txTemplate mock
        when(txTemplate.execute(any())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock inv) throws Throwable {
                Object cb = inv.getArgument(0);
                if (cb instanceof TransactionCallbackWithoutResult) {
                    ((TransactionCallbackWithoutResult) cb)
                            .doInTransaction(mock(TransactionStatus.class));
                }
                return null;
            }
        });
    }

    // ═══════════════════════════════════════════════════
    //  MR: drop 2.0% 통일 — 1차 TRAIL
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-D20-MR-1: MR split_1st_trail_drop=2.0 — peak 대비 1.9% drop 미달, 2.1% drop 발동")
    void v129d20_mr_firstTrailBoundary() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrCache();
        // [avgPrice, qty, openedAtEpochMs, peak, trough, splitPhase, armed]
        cache.put("KRW-MR", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0, 0});
        PositionEntity pe = buildPosition("KRW-MR", 1000.0, 100.0, "MORNING_RUSH");
        when(positionRepo.findById("KRW-MR")).thenReturn(Optional.of(pe));

        // +1.6% → armed
        invokeMrRealtime("KRW-MR", 101.6);
        // peak 103 (+3%)
        invokeMrRealtime("KRW-MR", 103.0);
        // drop 1.9% ≒ 101.043 → 미달 (2.0% 통일값 미도달)
        invokeMrRealtime("KRW-MR", 101.04);
        assertEquals(0, (int) cache.get("KRW-MR")[5], "drop 1.9% < 2.0% — SPLIT_1ST 미발동");

        // drop 2.1% ≒ 100.837 → 발동
        invokeMrRealtime("KRW-MR", 100.83);
        Thread.sleep(500);
        assertEquals(1, (int) cache.get("KRW-MR")[5], "drop 2.1% ≥ 2.0% — SPLIT_1ST 발동");
    }

    @Test
    @DisplayName("V129-D20-MR-2: MR trail_drop_after_split=2.0 — splitPhase=1 peak 대비 1.9% 미달, 2.1% 발동")
    void v129d20_mr_secondTrailBoundary() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrCache();
        // splitPhase=1, peak=105
        cache.put("KRW-MR2", new double[]{100.0, 400.0, nowMs - 300_000, 105.0, 100.0, 1, 0});
        PositionEntity pe = buildPosition("KRW-MR2", 400.0, 100.0, "MORNING_RUSH");
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));
        when(positionRepo.findById("KRW-MR2")).thenReturn(Optional.of(pe));
        // 쿨다운 만료
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>)
                getField(mr, "split1stExecutedAtMap");
        execMap.put("KRW-MR2", nowMs - 120_000);

        // drop 1.9% (105 → 103.005) → 미달
        invokeMrRealtime("KRW-MR2", 103.01);
        assertTrue(cache.containsKey("KRW-MR2"), "drop 1.9% < 2.0% — SPLIT_2ND_TRAIL 미발동");

        // drop 2.1% (105 → 102.795) → 발동
        invokeMrRealtime("KRW-MR2", 102.79);
        Thread.sleep(500);
        assertFalse(cache.containsKey("KRW-MR2"), "drop 2.1% ≥ 2.0% — SPLIT_2ND_TRAIL 발동");
    }

    // ═══════════════════════════════════════════════════
    //  AllDay: drop 2.0% 통일
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-D20-AD-1: AllDay split_1st_trail_drop=2.0 — 경계 미달/도달 검증")
    void v129d20_ad_firstTrailBoundary() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getAdCache();
        // [avgPrice, peak, trailActivated, trough, openedAt, splitPhase, armed]
        cache.put("KRW-AD", new double[]{100.0, 100.0, 0, 100.0, nowMs - 180_000, 0, 0});
        PositionEntity pe = buildPosition("KRW-AD", 1000.0, 100.0, "HIGH_CONFIDENCE_BREAKOUT");
        when(positionRepo.findById("KRW-AD")).thenReturn(Optional.of(pe));

        // +1.6% armed
        invokeAdRealtime("KRW-AD", 101.6);
        // peak 103
        invokeAdRealtime("KRW-AD", 103.0);
        // drop 1.9% → 미달
        invokeAdRealtime("KRW-AD", 101.04);
        assertEquals(0, (int) cache.get("KRW-AD")[5], "AD drop 1.9% < 2.0% — 미발동");

        // drop 2.1% → 발동
        invokeAdRealtime("KRW-AD", 100.83);
        Thread.sleep(500);
        assertEquals(1, (int) cache.get("KRW-AD")[5], "AD drop 2.1% ≥ 2.0% — SPLIT_1ST 발동");
    }

    @Test
    @DisplayName("V129-D20-AD-2: AllDay trail_drop_after_split=2.0 — 경계 미달/도달 검증")
    void v129d20_ad_secondTrailBoundary() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getAdCache();
        // splitPhase=1, peak=105, trailActivated=1
        cache.put("KRW-AD2", new double[]{100.0, 105.0, 1, 100.0, nowMs - 300_000, 1, 0});
        PositionEntity pe = buildPosition("KRW-AD2", 400.0, 100.0, "HIGH_CONFIDENCE_BREAKOUT");
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));
        when(positionRepo.findById("KRW-AD2")).thenReturn(Optional.of(pe));
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>)
                getField(ad, "split1stExecutedAtMap");
        execMap.put("KRW-AD2", nowMs - 120_000);  // 쿨다운 만료

        // drop 1.9% → 미달
        invokeAdRealtime("KRW-AD2", 103.01);
        assertTrue(cache.containsKey("KRW-AD2"), "AD drop 1.9% < 2.0% — 미발동");

        // drop 2.1% → 발동
        invokeAdRealtime("KRW-AD2", 102.79);
        Thread.sleep(500);
        assertFalse(cache.containsKey("KRW-AD2"), "AD drop 2.1% ≥ 2.0% — SPLIT_2ND_TRAIL 발동");
    }

    // ═══════════════════════════════════════════════════
    //  Opening Detector: drop 2.0% 통일
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-D20-OP-1: Opening split_1st_trail_drop=2.0 — 경계 검증")
    void v129d20_op_firstTrailBoundary() throws Exception {
        long nowMs = System.currentTimeMillis();
        opDet.addPosition("KRW-OP", 100.0, nowMs - 120_000, 5);

        invokeOpCheck("KRW-OP", 101.6);  // +1.6% armed
        invokeOpCheck("KRW-OP", 103.0);  // peak 103
        invokeOpCheck("KRW-OP", 101.04); // drop 1.9%
        assertTrue(opSellMarkets.isEmpty(), "OP drop 1.9% < 2.0% — 미발동");

        invokeOpCheck("KRW-OP", 100.83); // drop 2.1% → SPLIT_1ST
        assertEquals(1, opSellMarkets.size(), "OP drop 2.1% ≥ 2.0% — SPLIT_1ST 콜백");
        assertEquals("SPLIT_1ST", opSellTypes.get(0));
    }

    @Test
    @DisplayName("V129-D20-OP-2: Opening trail_drop_after_split=2.0 — splitPhase=1 경계")
    void v129d20_op_secondTrailBoundary() throws Exception {
        long nowMs = System.currentTimeMillis();
        opDet.addPosition("KRW-OP2", 100.0, nowMs - 300_000, 5);
        // 1차 매도 완료 상태 수동 세팅 (splitPhase=1, peak=105)
        opDet.setSplitPhase("KRW-OP2", 1);
        Field peakF = OpeningBreakoutDetector.class.getDeclaredField("peakPrices");
        peakF.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Double> peakMap = (ConcurrentHashMap<String, Double>) peakF.get(opDet);
        peakMap.put("KRW-OP2", 105.0);
        // 쿨다운 만료 (120s 전)
        Field execF = OpeningBreakoutDetector.class.getDeclaredField("split1stExecutedAtMap");
        execF.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>) execF.get(opDet);
        execMap.put("KRW-OP2", nowMs - 120_000);

        invokeOpCheck("KRW-OP2", 103.01); // drop 1.9% — 미달
        assertTrue(opSellMarkets.isEmpty(), "OP drop 1.9% — 미발동");

        invokeOpCheck("KRW-OP2", 102.79); // drop 2.1% — 발동
        assertFalse(opSellMarkets.isEmpty(), "OP drop 2.1% ≥ 2.0% — SPLIT_2ND_TRAIL 발동");
        assertTrue(opSellTypes.get(0).contains("SPLIT_2ND"),
                "OP SPLIT_2ND type 확인, actual=" + opSellTypes.get(0));
    }

    // ═══════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════
    private void invokeMrRealtime(String market, double price) throws Exception {
        Method m = MorningRushScannerService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(mr, market, price);
    }

    private void invokeAdRealtime(String market, double price) throws Exception {
        Method m = AllDayScannerService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(ad, market, price);
    }

    private void invokeOpCheck(String market, double price) throws Exception {
        Method m = OpeningBreakoutDetector.class.getDeclaredMethod(
                "checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);
        m.invoke(opDet, market, price);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getMrCache() throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(mr);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getAdCache() throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField("tpPositionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(ad);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private PositionEntity buildPosition(String market, double qty, double avg, String entry) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setQty(BigDecimal.valueOf(qty));
        pe.setAvgPrice(BigDecimal.valueOf(avg));
        pe.setAddBuys(0);
        pe.setOpenedAt(Instant.now());
        pe.setEntryStrategy(entry);
        return pe;
    }
}
