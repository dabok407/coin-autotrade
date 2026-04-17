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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V109 올데이 스캐너 매도 구조 재설계 시나리오 테스트.
 *
 * positionCache: [avgPrice, peakPrice, trailActivated(0/1), troughPrice, openedAtEpochMs]
 *
 * 시나리오:
 *  1. Grace 구간 (30초): SL 무시, -5% 비상만 작동
 *  2. Wide SL 구간 (30초~15분): -3% SL 작동
 *  3. Tight SL 구간 (15분+): -1.5% SL 작동
 *  4. TP_TRAIL 활성화 후: SL 비활성, TP_TRAIL 단독 관리
 *  5. TP_TRAIL 활성 + SL 비활성 확인 (돌파 성공 후 throwback에서 SL 안 걸림)
 *  6. Grace 비상 SL -5% 작동
 *  7. trough(최저가) 추적 정확성
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AllDayTieredSlScenarioTest {

    @Mock private AllDayScannerConfigRepository configRepo;
    @Mock private BotConfigRepository botConfigRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private TradeRepository tradeLogRepo;
    @Mock private CandleService candleService;
    @Mock private UpbitMarketCatalogService catalogService;
    @Mock private LiveOrderService liveOrders;
    @Mock private UpbitPrivateClient privateClient;
    @Mock private TransactionTemplate txTemplate;
    @Mock private TickerService tickerService;
    @Mock private SharedPriceService sharedPriceService;

    private AllDayScannerService scanner;

    @BeforeEach
    public void setUp() throws Exception {
        scanner = new AllDayScannerService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                candleService, catalogService, liveOrders, privateClient, txTemplate,
                tickerService, sharedPriceService, new SharedTradeThrottle()
        );
        setField("running", new AtomicBoolean(true));

        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        setField("scheduler", sched);

        // 티어드 SL cached 변수 설정 (DB 기본값과 동일)
        setField("cachedGracePeriodMs", 30_000L);      // 30초
        setField("cachedWidePeriodMs", 15 * 60_000L);   // 15분
        setField("cachedWideSlPct", 3.0);
        setField("cachedTightSlPct", 1.5);
        setField("cachedTpTrailActivatePct", 2.0);
        setField("cachedTpTrailDropPct", 1.0);

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
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 1: Grace 구간 — SL -2% 무시
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("Grace 구간 (30초 이내): -2% 하락해도 SL 무시")
    public void scenario1_graceIgnoresSl() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        // 매수 직후 (10초 전)
        cache.put("KRW-A", new double[]{100.0, 100.0, 0, 100.0, nowMs - 10_000, 0, 0});

        // -2% 하락 → Grace 구간이므로 SL 무시
        invoke("KRW-A", 98.0);

        assertTrue(cache.containsKey("KRW-A"), "Grace 구간에서 SL 미발동");
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 2: Grace 구간 — 비상 SL -5% 작동
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("Grace 구간이라도 -5% 이하 비상 SL 발동")
    public void scenario2_graceEmergencySlFires() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        cache.put("KRW-B", new double[]{100.0, 100.0, 0, 100.0, nowMs - 10_000, 0, 0});

        // PAPER 모드 설정
        when(positionRepo.findById("KRW-B")).thenReturn(Optional.of(buildPosition("KRW-B", 100.0)));

        // -5.5% 급락 → 비상 SL
        invoke("KRW-B", 94.5);
        Thread.sleep(500);

        assertFalse(cache.containsKey("KRW-B"), "비상 SL -5% 발동으로 캐시 제거");
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 3: Wide SL 구간 — -3% 발동
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("Wide SL 구간 (30초~15분): -3% 하락 시 SL_WIDE 발동")
    public void scenario3_wideSlFires() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        // 매수 2분 전 (Grace 통과, Wide 구간)
        cache.put("KRW-C", new double[]{100.0, 100.0, 0, 100.0, nowMs - 120_000, 0, 0});

        when(positionRepo.findById("KRW-C")).thenReturn(Optional.of(buildPosition("KRW-C", 100.0)));

        // -3.1% → SL_WIDE 발동
        invoke("KRW-C", 96.9);
        Thread.sleep(500);

        assertFalse(cache.containsKey("KRW-C"), "SL_WIDE -3% 발동으로 캐시 제거");
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 4: Wide 구간 — -2.5% 미발동 (throwback 허용)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("Wide SL 구간: -2.5% 하락은 허용 (throwback 보호)")
    public void scenario4_wideSlNotTriggered() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        cache.put("KRW-D", new double[]{100.0, 100.0, 0, 100.0, nowMs - 120_000, 0, 0});

        // -2.5% → Wide SL -3% 미달 → 유지
        invoke("KRW-D", 97.5);

        assertTrue(cache.containsKey("KRW-D"), "Wide SL -3% 미달이므로 유지");
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 5: Tight SL 구간 — -1.5% 발동
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("Tight SL 구간 (15분+): -1.5% 하락 시 SL_TIGHT 발동")
    public void scenario5_tightSlFires() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        // 매수 20분 전 (Tight 구간)
        cache.put("KRW-E", new double[]{100.0, 100.0, 0, 100.0, nowMs - 20 * 60_000, 0, 0});

        when(positionRepo.findById("KRW-E")).thenReturn(Optional.of(buildPosition("KRW-E", 100.0)));

        // -1.6% → SL_TIGHT 발동
        invoke("KRW-E", 98.4);
        Thread.sleep(500);

        assertFalse(cache.containsKey("KRW-E"), "SL_TIGHT -1.5% 발동으로 캐시 제거");
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 6: TP_TRAIL 활성 후 — SL 비활성 확인
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("TP_TRAIL 활성 후 throwback — Tight SL 구간이지만 SL 안 걸림 (TP_TRAIL 단독)")
    public void scenario6_tpTrailActiveThenSlDisabled() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        // 매수 20분 전, +2% 도달해서 trail 활성화됨
        // [avgPrice=100, peakPrice=103, activated=1, troughPrice=100, openedAtMs]
        cache.put("KRW-F", new double[]{100.0, 103.0, 1.0, 100.0, nowMs - 20 * 60_000, 0, 0});

        // 가격 102.5원 (피크 103 대비 -0.49%, trail drop 1.0% 미달)
        // Tight SL 구간(20분)이지만 TP_TRAIL 활성이므로 SL 비활성
        invoke("KRW-F", 102.5);

        assertTrue(cache.containsKey("KRW-F"),
                "TP_TRAIL 활성 상태: SL 비활성, trail drop 0.49% < 1.0%이므로 유지");
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 7: TP_TRAIL 활성 후 피크 대비 -1% drop → 매도
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("TP_TRAIL 활성 후 피크 대비 -1% drop → TP_TRAIL 매도")
    public void scenario7_tpTrailDropSell() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        cache.put("KRW-G", new double[]{100.0, 105.0, 1.0, 100.0, nowMs - 60_000, 0, 0});

        when(positionRepo.findById("KRW-G")).thenReturn(Optional.of(buildPosition("KRW-G", 100.0)));

        // 피크 105에서 -1.1% → 103.85 → TP_TRAIL 발동
        invoke("KRW-G", 103.8);
        Thread.sleep(500);

        assertFalse(cache.containsKey("KRW-G"), "TP_TRAIL drop 발동으로 캐시 제거");
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 8: 전체 사이클 — Grace → 회복 → TP_TRAIL 활성 → 매도
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("전체 사이클: throwback → 회복 → TP_TRAIL 활성 → 피크 추적 → 매도")
    public void scenario8_fullCycle() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        // Grace 구간 (5초 전 매수)
        cache.put("KRW-H", new double[]{100.0, 100.0, 0, 100.0, nowMs - 5_000, 0, 0});

        // Step 1: Grace 중 -2% throwback → 무시
        invoke("KRW-H", 98.0);
        assertTrue(cache.containsKey("KRW-H"), "Grace에서 SL 무시");

        // Step 2: openedAt을 2분 전으로 조정 (Wide 구간 진입 시뮬레이션)
        cache.get("KRW-H")[4] = nowMs - 120_000;

        // Step 3: 가격 회복 102원 → TP_TRAIL 활성화
        invoke("KRW-H", 102.0);
        assertTrue(cache.containsKey("KRW-H"), "TP 활성화만, 매도 아님");
        assertEquals(1.0, cache.get("KRW-H")[2], 0.01, "trail activated");

        // Step 4: 105원까지 상승 → 피크 추적
        invoke("KRW-H", 105.0);
        assertEquals(105.0, cache.get("KRW-H")[1], 0.01, "peak = 105");

        // Step 5: 피크에서 -1.1% drop → 103.85 → TP_TRAIL 매도
        when(positionRepo.findById("KRW-H")).thenReturn(Optional.of(buildPosition("KRW-H", 100.0)));
        invoke("KRW-H", 103.8);
        Thread.sleep(500);

        assertFalse(cache.containsKey("KRW-H"), "TP_TRAIL drop 매도");
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 9: trough(최저가) 추적 정확성
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("trough(최저가) 추적: 하락 후 회복 시 최저가 기록 유지")
    public void scenario9_troughTracking() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        cache.put("KRW-I", new double[]{100.0, 100.0, 0, 100.0, nowMs - 5_000, 0, 0});

        // 97원까지 하락
        invoke("KRW-I", 97.0);
        assertEquals(97.0, cache.get("KRW-I")[3], 0.01, "trough = 97");

        // 99원으로 회복
        invoke("KRW-I", 99.0);
        assertEquals(97.0, cache.get("KRW-I")[3], 0.01, "trough 유지 = 97 (최저가 갱신 안 됨)");

        // 96원으로 더 하락
        invoke("KRW-I", 96.0);
        assertEquals(96.0, cache.get("KRW-I")[3], 0.01, "trough 갱신 = 96");
    }

    // ═══════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════

    private void invoke(String market, double price) throws Exception {
        Method m = AllDayScannerService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(scanner, market, price);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getTpCache() throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField("tpPositionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(scanner);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(scanner, value);
    }

    private PositionEntity buildPosition(String market, double avgPrice) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setAvgPrice(BigDecimal.valueOf(avgPrice));
        pe.setQty(BigDecimal.valueOf(1000));
        pe.setEntryStrategy("HIGH_CONFIDENCE_BREAKOUT");
        return pe;
    }
}
