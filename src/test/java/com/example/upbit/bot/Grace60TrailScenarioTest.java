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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * V129 Gap #4: MR/Opening Grace=60s 신 trail 경로.
 *
 * V129 변경: AllDay grace_period_sec 30→60, MR/Opening은 기존 60 유지.
 * 기존 테스트는 AllDay에만 Grace 경계 시나리오(V129-H/H2) 존재.
 * MR/Opening의 "Grace=60s 기간 내 SPLIT_1ST(armed/TRAIL) 차단" 경로는 별도 커버 필요.
 *
 * 이 테스트는 MR과 Opening detector 모두에서:
 *   (a) Grace 내 armed 진입도 차단되는가
 *   (b) Grace 내 peak drop trail도 차단되는가
 *   (c) Grace 경과 직후(65s) TRAIL 정상 발동
 * 을 명시적으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class Grace60TrailScenarioTest {

    // ── MR ──
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

    private MorningRushScannerService mr;
    private OpeningBreakoutDetector opDet;

    private final List<String> opSellTypes = new ArrayList<String>();

    @BeforeEach
    public void setUp() throws Exception {
        mr = new MorningRushScannerService(
                mrConfigRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, privateClient, txTemplate, catalogService, tickerService,
                sharedPriceService, new SharedTradeThrottle()
        );
        setField(mr, "running", new AtomicBoolean(true));
        setField(mr, "cachedTpPct", 2.3);
        setField(mr, "cachedSlPct", 3.0);
        setField(mr, "cachedTpTrailDropPct", 1.0);
        setField(mr, "cachedGracePeriodMs", 60_000L);
        setField(mr, "cachedWidePeriodMs", 5 * 60_000L);
        setField(mr, "cachedWideSlPct", 5.0);
        setField(mr, "cachedSplitExitEnabled", true);
        setField(mr, "cachedSplitTpPct", 1.5);
        setField(mr, "cachedSplitRatio", 0.60);
        setField(mr, "cachedSplit1stTrailDrop", 2.0);
        setField(mr, "cachedTrailDropAfterSplit", 2.0);
        setField(mr, "cachedSplit1stCooldownMs", 60_000L);

        MorningRushConfigEntity mrCfg = new MorningRushConfigEntity();
        mrCfg.setMode("PAPER");
        mrCfg.setSplitExitEnabled(true);
        mrCfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        mrCfg.setSplitRatio(BigDecimal.valueOf(0.60));
        mrCfg.setTrailDropAfterSplit(BigDecimal.valueOf(2.0));
        mrCfg.setSplit1stTrailDrop(BigDecimal.valueOf(2.0));
        when(mrConfigRepo.loadOrCreate()).thenReturn(mrCfg);

        // Opening detector
        opSellTypes.clear();
        opDet = new OpeningBreakoutDetector(mock(SharedPriceService.class));
        opDet.setConfirmMinIntervalMs(0);
        opDet.setListener(new OpeningBreakoutDetector.BreakoutListener() {
            @Override public void onBreakoutConfirmed(String m, double p, double h, double a) {}
            @Override public void onTpSlTriggered(String m, double p, String type, String reason) {
                opSellTypes.add(type);
            }
        });
        opDet.setSplitExitEnabled(true);
        opDet.setSplitTpPct(1.5);
        opDet.setSplitRatio(0.60);
        opDet.setSplit1stTrailDropPct(2.0);
        opDet.setTrailDropAfterSplit(2.0);
        opDet.setSplit1stCooldownSec(60);
        opDet.setTpActivatePct(2.0);
        opDet.setTrailFromPeakPct(1.0);
        // V129: grace=60s
        opDet.updateSlConfig(60, 15, 6.0, 5.0, 3.5, 3.0, 1.5);

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
    //  MR Grace 60s 경계 (신규 trail 경로)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-G60-MR-A: MR Grace 55s 경과 (아직 내) + peak+armed 조건 → SPLIT_1ST 차단")
    public void mr_graceBlocksArmedTrail() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrCache();
        // 55s 경과 (grace 내), armed=0, peak=100
        cache.put("KRW-MR-GR1", new double[]{100.0, 1000.0, nowMs - 55_000, 100.0, 100.0, 0, 0});

        // +1.6% → 통상 armed 전환 후 peak 갱신. Grace 내라면 매도는 물론, trail 설정도 block.
        invokeMrRealtime("KRW-MR-GR1", 101.6);
        invokeMrRealtime("KRW-MR-GR1", 103.0);
        invokeMrRealtime("KRW-MR-GR1", 100.83);  // peak 103에서 -2.1% drop

        double[] pos = cache.get("KRW-MR-GR1");
        assertEquals(0, (int) pos[5], "Grace 내: splitPhase=0 유지 (매도 차단)");
        verify(positionRepo, never()).save(any());
    }

    @Test
    @DisplayName("V129-G60-MR-B: MR Grace 65s 경과 (밖) + armed+drop 2.1% → SPLIT_1ST 발동")
    public void mr_graceExpiredAllowsTrail() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrCache();
        cache.put("KRW-MR-GR2", new double[]{100.0, 1000.0, nowMs - 65_000, 100.0, 100.0, 0, 0});
        PositionEntity pe = buildPosition("KRW-MR-GR2", 1000.0, 100.0, "MORNING_RUSH");
        when(positionRepo.findById("KRW-MR-GR2")).thenReturn(Optional.of(pe));

        invokeMrRealtime("KRW-MR-GR2", 101.6);  // armed
        invokeMrRealtime("KRW-MR-GR2", 103.0);  // peak 103
        invokeMrRealtime("KRW-MR-GR2", 100.83); // drop 2.1%
        Thread.sleep(500);

        double[] pos = cache.get("KRW-MR-GR2");
        assertEquals(1, (int) pos[5], "Grace 경과 후: SPLIT_1ST 발동 → splitPhase=1");
    }

    @Test
    @DisplayName("V129-G60-MR-C: MR Grace 내 splitPhase=1 포지션 + TRAIL 조건 → SPLIT_2ND 차단")
    public void mr_graceBlocksSecondTrail() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getMrCache();
        // 30s 경과 (grace 내), splitPhase=1, peak=105
        cache.put("KRW-MR-GR3", new double[]{100.0, 400.0, nowMs - 30_000, 105.0, 100.0, 1, 0});
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>)
                getField(mr, "split1stExecutedAtMap");
        execMap.put("KRW-MR-GR3", nowMs - 120_000);  // 쿨다운 만료

        // peak 105 → -2.1% drop (102.79)
        invokeMrRealtime("KRW-MR-GR3", 102.79);

        assertTrue(cache.containsKey("KRW-MR-GR3"),
                "Grace 내 SPLIT_2ND_TRAIL 차단 (쿨다운 만료 여부 무관)");
    }

    // ═══════════════════════════════════════════════════
    //  Opening detector Grace 60s 경계
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-G60-OP-A: Opening Grace 55s 내 + drop 2.1% → 매도 차단")
    public void op_graceBlocks() throws Exception {
        long nowMs = System.currentTimeMillis();
        opDet.addPosition("KRW-OP-GR1", 100.0, nowMs - 55_000, 5);

        invokeOp("KRW-OP-GR1", 101.6);  // armed attempt
        invokeOp("KRW-OP-GR1", 103.0);  // peak
        invokeOp("KRW-OP-GR1", 100.83); // drop 2.1%

        assertTrue(opSellTypes.isEmpty(), "Opening Grace 내: 매도 차단 (SL/SPLIT/TP 모두)");
    }

    @Test
    @DisplayName("V129-G60-OP-B: Opening Grace 65s 경과 + armed+drop 2.1% → SPLIT_1ST 콜백")
    public void op_graceExpiredAllowsSplit() throws Exception {
        long nowMs = System.currentTimeMillis();
        opDet.addPosition("KRW-OP-GR2", 100.0, nowMs - 65_000, 5);

        invokeOp("KRW-OP-GR2", 101.6);
        invokeOp("KRW-OP-GR2", 103.0);
        invokeOp("KRW-OP-GR2", 100.83);

        assertFalse(opSellTypes.isEmpty(), "Opening Grace 경과 후 SPLIT_1ST 발동");
        assertEquals("SPLIT_1ST", opSellTypes.get(0));
    }

    @Test
    @DisplayName("V129-G60-OP-C: Opening Grace 내 splitPhase=1 포지션 TRAIL 조건 → 차단")
    public void op_graceBlocksSecondTrail() throws Exception {
        long nowMs = System.currentTimeMillis();
        opDet.addPosition("KRW-OP-GR3", 100.0, nowMs - 30_000, 5);  // Grace 내
        opDet.setSplitPhase("KRW-OP-GR3", 1);
        // peak 105 수동 설정
        Field peakF = OpeningBreakoutDetector.class.getDeclaredField("peakPrices");
        peakF.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Double> peakMap = (ConcurrentHashMap<String, Double>) peakF.get(opDet);
        peakMap.put("KRW-OP-GR3", 105.0);
        // 쿨다운 만료
        Field execF = OpeningBreakoutDetector.class.getDeclaredField("split1stExecutedAtMap");
        execF.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>) execF.get(opDet);
        execMap.put("KRW-OP-GR3", nowMs - 120_000);

        invokeOp("KRW-OP-GR3", 102.79);  // drop 2.1%

        assertTrue(opSellTypes.isEmpty(),
                "Opening Grace 내 SPLIT_2ND 차단 (쿨다운 만료와 무관)");
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

    private void invokeOp(String market, double price) throws Exception {
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
