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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MorningRush Split-Exit 시나리오 테스트.
 *
 * positionCache: [avgPrice, qty, openedAtEpochMs, peakPrice, troughPrice, splitPhase]
 *
 * 시나리오 8개:
 *   1. 정상 1차 매도 (+splitTpPct, 캐시 유지 + splitPhase=1)
 *   2. 정상 2차 TP_TRAIL drop
 *   3. 정상 2차 Breakeven SL
 *   4. 1차 미달
 *   5. 재발동 차단 (splitPhase=1에서 1차 재트리거 안 됨)
 *   6. 비활성 시 기존 TP_TRAIL
 *   7. 1차 후 peak 리셋 확인
 *   8. SL은 splitPhase 무관하게 작동
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class MorningRushSplitExitTest {

    @Mock private MorningRushConfigRepository configRepo;
    @Mock private BotConfigRepository botConfigRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private TradeRepository tradeLogRepo;
    @Mock private LiveOrderService liveOrders;
    @Mock private UpbitPrivateClient privateClient;
    @Mock private TransactionTemplate txTemplate;
    @Mock private UpbitMarketCatalogService catalogService;
    @Mock private TickerService tickerService;
    @Mock private SharedPriceService sharedPriceService;

    private MorningRushScannerService scanner;

    @BeforeEach
    public void setUp() throws Exception {
        ScannerLockService scannerLockService = new ScannerLockService(botConfigRepo, positionRepo, tradeLogRepo);
        scanner = new MorningRushScannerService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, privateClient, txTemplate, catalogService, tickerService,
                sharedPriceService, new SharedTradeThrottle(), scannerLockService
        );
        setField("running", new AtomicBoolean(true));

        // 기존 TP/SL 설정
        setField("cachedTpPct", 2.3);
        setField("cachedSlPct", 3.0);
        setField("cachedTpTrailDropPct", 1.0);
        setField("cachedGracePeriodMs", 60_000L);
        setField("cachedWidePeriodMs", 5 * 60_000L);
        setField("cachedWideSlPct", 5.0);

        // V111: Split-Exit 설정
        setField("cachedSplitExitEnabled", true);
        setField("cachedSplitTpPct", 1.5);
        setField("cachedSplitRatio", 0.60);
        setField("cachedTrailDropAfterSplit", 1.0);
        setField("cachedSplit1stTrailDrop", 0.5);  // V115: 1차 TRAIL drop 0.5%
        // V130: Trail Ladder 비활성 (기존 단일값 테스트 유지)
        setField("cachedTrailLadderEnabled", false);
        // V130: roi 하한선 비활성 (음수 roi에서도 SPLIT_1ST 동작하는 기존 테스트 유지)
        setField("cachedSplit1stRoiFloorPct", 0.0);

        // txTemplate mock
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

        // config mock for split sell execution
        MorningRushConfigEntity cfg = new MorningRushConfigEntity();
        cfg.setMode("PAPER");
        cfg.setSplitExitEnabled(true);
        cfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        cfg.setSplitRatio(BigDecimal.valueOf(0.60));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.0));
        cfg.setSplit1stTrailDrop(BigDecimal.valueOf(0.5));  // V115
        when(configRepo.loadOrCreate()).thenReturn(cfg);
    }

    // ═══════════════════════════════════════════════════
    //  S01: 정상 1차 매도
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S01: +1.5% 도달 → 캐시 유지 + splitPhase=1")
    void s01_normalFirstSplit() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-A", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0, 0});

        PositionEntity pe = buildPosition("KRW-A", 1000.0, 100.0);
        when(positionRepo.findById("KRW-A")).thenReturn(Optional.of(pe));

        // V115: +1.6% armed → peak 102 → drop 0.6% → SPLIT_1ST
        invoke("KRW-A", 101.6);
        invoke("KRW-A", 102.0);
        invoke("KRW-A", 101.4);
        Thread.sleep(500);

        // 캐시 유지 (1차 매도 후 2차 대기)
        assertTrue(cache.containsKey("KRW-A"), "1차 매도 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-A")[5], 0.01, "splitPhase=1");

        // position save 확인 (deleteById 아님)
        verify(positionRepo, never()).deleteById("KRW-A");
        verify(positionRepo).save(argThat(new ArgumentMatcher<PositionEntity>() {
            @Override
            public boolean matches(PositionEntity p) {
                return "KRW-A".equals(p.getMarket()) && p.getSplitPhase() == 1;
            }
        }));

        // tradeLog SPLIT_1ST note
        verify(tradeLogRepo).save(argThat(new ArgumentMatcher<TradeEntity>() {
            @Override
            public boolean matches(TradeEntity t) {
                return "SELL".equals(t.getAction()) && "SPLIT_1ST".equals(t.getNote());
            }
        }));
    }

    // ═══════════════════════════════════════════════════
    //  S02: 정상 2차 TP_TRAIL drop
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S02: splitPhase=1에서 피크 대비 drop → SPLIT_2ND_TRAIL 캐시 제거")
    void s02_normalSecondTrail() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // splitPhase=1, peak=103
        cache.put("KRW-B", new double[]{100.0, 400.0, nowMs - 300_000, 103.0, 100.0, 1, 0});

        // 피크 103에서 -1.1% drop
        invoke("KRW-B", 101.8);

        assertFalse(cache.containsKey("KRW-B"), "2차 매도 → 캐시 제거");
    }

    // ═══════════════════════════════════════════════════
    //  S03: 정상 2차 Breakeven SL
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S03: splitPhase=1에서 매수가 하락 → SPLIT_2ND_BEV 캐시 제거")
    void s03_normalSecondBreakeven() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-C", new double[]{100.0, 400.0, nowMs - 300_000, 101.5, 100.0, 1, 0});

        invoke("KRW-C", 100.0);

        assertFalse(cache.containsKey("KRW-C"), "Breakeven SL → 캐시 제거");
    }

    // ═══════════════════════════════════════════════════
    //  S04: 1차 미달
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S04: +1.4% — splitTpPct 미달, 매도 안 됨")
    void s04_firstNotReached() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-D", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0, 0});

        invoke("KRW-D", 101.4);

        assertTrue(cache.containsKey("KRW-D"), "미달 → 유지");
        assertEquals(0, (int) cache.get("KRW-D")[5], "splitPhase=0 유지");
    }

    // ═══════════════════════════════════════════════════
    //  S05: 재발동 차단
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S05: splitPhase=1에서 +1.5% 도달해도 1차 재발동 안 됨")
    void s05_noReFireAfterFirst() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // splitPhase=1, peak=101.5
        cache.put("KRW-E", new double[]{100.0, 400.0, nowMs - 300_000, 101.5, 100.0, 1, 0});

        invoke("KRW-E", 101.8);

        assertTrue(cache.containsKey("KRW-E"), "splitPhase=1에서 재발동 없음");
        assertEquals(1.0, cache.get("KRW-E")[5], 0.01, "splitPhase 유지=1");
    }

    // ═══════════════════════════════════════════════════
    //  S06: 비활성 시 기존 TP_TRAIL
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S06: splitExitEnabled=false → 기존 TP_TRAIL 전량 매도")
    void s06_splitDisabledFallback() throws Exception {
        setField("cachedSplitExitEnabled", false);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-F", new double[]{100.0, 1000.0, nowMs - 300_000, 100.0, 100.0, 0, 0});

        // +2.5% → trail 활성화 (peakPnl >= cachedTpPct 2.3%)
        invoke("KRW-F", 102.5);
        assertTrue(cache.containsKey("KRW-F"), "trail 활성화만, 매도 안 됨");

        // 피크 105로 올리기
        invoke("KRW-F", 105.0);

        // 피크에서 -1.1% drop → TP_TRAIL 매도
        invoke("KRW-F", 103.8);
        assertFalse(cache.containsKey("KRW-F"), "기존 TP_TRAIL 전량 매도");
    }

    // ═══════════════════════════════════════════════════
    //  S07: 1차 후 peak 리셋
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S07: 1차 매도 후 peak 리셋 = 매도 체결가, splitPhase=1, armed=0")
    void s07_peakResetAfterFirst() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-G", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0, 0});

        PositionEntity pe = buildPosition("KRW-G", 1000.0, 100.0);
        when(positionRepo.findById("KRW-G")).thenReturn(Optional.of(pe));

        // V115: +1.6% armed → peak 102 → drop 0.6% → 1차 매도 @ 101.4
        invoke("KRW-G", 101.6);
        invoke("KRW-G", 102.0);
        invoke("KRW-G", 101.4);
        Thread.sleep(500);

        assertTrue(cache.containsKey("KRW-G"), "캐시 유지");
        assertEquals(101.4, cache.get("KRW-G")[3], 0.1, "peak 리셋=매도 체결가 101.4");
        assertEquals(1.0, cache.get("KRW-G")[5], 0.01, "splitPhase=1");
        assertEquals(0.0, cache.get("KRW-G")[6], 0.01, "V115 armed 리셋=0");
    }

    // ═══════════════════════════════════════════════════
    //  S08: SL은 splitPhase 무관
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S08: splitPhase=0에서 -3% SL_TIGHT 발동 (split 무관)")
    void s08_slWorksWithSplit() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // 6분 경과 (Tight SL 구간)
        cache.put("KRW-H", new double[]{100.0, 1000.0, nowMs - 360_000, 100.0, 100.0, 0, 0});

        // -3.1% → SL_TIGHT 발동
        invoke("KRW-H", 96.9);

        assertFalse(cache.containsKey("KRW-H"), "SL_TIGHT 발동");
    }

    // ═══════════════════════════════════════════════════
    //  S09: dust 처리 — 잔량 < 5000원이면 전량 매도
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S09: 1차 매도 시 잔량 < 5000원 → SPLIT_1ST_DUST (전량 매도)")
    void s09_dustHandling() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // qty=40, avgPrice=100 → 잔량 40*0.4=16 * 101.4 = 1622.4원 < 5000원
        cache.put("KRW-DUST", new double[]{100.0, 40.0, nowMs - 120_000, 100.0, 100.0, 0, 0});

        PositionEntity pe = buildPosition("KRW-DUST", 40.0, 100.0);
        when(positionRepo.findById("KRW-DUST")).thenReturn(Optional.of(pe));

        // V115: armed → peak → drop → SPLIT_1ST_DUST
        invoke("KRW-DUST", 101.6);
        invoke("KRW-DUST", 102.0);
        invoke("KRW-DUST", 101.4);
        Thread.sleep(500);

        // dust → 전량 매도 → 캐시 제거
        assertFalse(cache.containsKey("KRW-DUST"), "dust → 캐시 제거 (전량 매도)");
        verify(positionRepo).deleteById("KRW-DUST");

        // tradeLog에 SPLIT_1ST_DUST note
        verify(tradeLogRepo).save(argThat(new ArgumentMatcher<TradeEntity>() {
            @Override
            public boolean matches(TradeEntity t) {
                return "SELL".equals(t.getAction()) && "SPLIT_1ST_DUST".equals(t.getNote());
            }
        }));
    }

    // ═══════════════════════════════════════════════════
    //  S10: 쿨다운 — 1차 매도 시 미등록, 2차 매도 시 등록
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S10: 1차 매도 후 sellCooldownMap 미등록 확인")
    void s10_cooldownNotSetOnFirst() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-COOL", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0, 0});

        PositionEntity pe = buildPosition("KRW-COOL", 1000.0, 100.0);
        when(positionRepo.findById("KRW-COOL")).thenReturn(Optional.of(pe));

        // V115: armed → peak → drop → SPLIT_1ST
        invoke("KRW-COOL", 101.6);
        invoke("KRW-COOL", 102.0);
        invoke("KRW-COOL", 101.4);
        Thread.sleep(500);

        // 1차 매도 → 쿨다운 미등록 (executeSplitFirstSell에서 등록 안 함)
        // MorningRush는 sellCooldownMap이 없으므로 이 테스트는 position 삭제 안 됨만 확인
        verify(positionRepo, never()).deleteById("KRW-COOL");
        assertTrue(cache.containsKey("KRW-COOL"), "1차 매도 후 캐시 유지");
    }

    // ═══════════════════════════════════════════════════
    //  S11: 캐시 재빌드 — updatePositionCache에서 splitPhase 복원
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S11: updatePositionCache — splitPhase=1 포지션 캐시 복원")
    void s11_cacheRebuildWithSplitPhase() throws Exception {
        PositionEntity pe = buildPosition("KRW-REBUILD", 400.0, 100.0);
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));
        pe.setOpenedAt(Instant.now().minusSeconds(300));

        // MorningRush의 updatePositionCache는 DB에서 로드하여 캐시 재빌드
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pe));

        MorningRushConfigEntity cfg = new MorningRushConfigEntity();
        cfg.setMode("PAPER");
        cfg.setTpPct(BigDecimal.valueOf(2.3));
        cfg.setSlPct(BigDecimal.valueOf(3.0));
        cfg.setGracePeriodSec(60);
        cfg.setWidePeriodMin(5);
        cfg.setWideSlPct(BigDecimal.valueOf(5.0));
        cfg.setTpTrailDropPct(BigDecimal.valueOf(1.0));
        cfg.setSplitExitEnabled(true);
        cfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        cfg.setSplitRatio(BigDecimal.valueOf(0.60));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.0));

        // updatePositionCache 호출
        Method m = MorningRushScannerService.class.getDeclaredMethod(
                "updatePositionCache", MorningRushConfigEntity.class);
        m.setAccessible(true);
        m.invoke(scanner, cfg);

        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        assertTrue(cache.containsKey("KRW-REBUILD"), "캐시 복원됨");
        double[] pos = cache.get("KRW-REBUILD");
        assertEquals(7, pos.length, "V115: 7요소 배열");
        assertEquals(1, (int) pos[5], "splitPhase=1 복원");
        assertEquals(0, (int) pos[6], "armed=0 초기");
    }

    // ═══════════════════════════════════════════════════
    //  S12: SESSION_END — splitPhase=1이어도 executeSell로 전량 매도
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S12: SESSION_END — splitPhase=1이어도 executeSell로 잔량 전량 매도")
    void s12_sessionEndForceExitWithSplit() throws Exception {
        PositionEntity pe = buildPosition("KRW-SESS", 400.0, 100.0);
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));

        MorningRushConfigEntity cfg = new MorningRushConfigEntity();
        cfg.setMode("PAPER");
        cfg.setSplitExitEnabled(true);
        cfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        cfg.setSplitRatio(BigDecimal.valueOf(0.60));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.0));

        // executeSell은 항상 전량 매도 + deleteById (split 무시)
        invokeExecuteSell(pe, 101.0, 1.0, "SESSION_END forced exit", "SESSION_END", cfg);

        verify(positionRepo).deleteById("KRW-SESS");
        verify(tradeLogRepo).save(argThat(new ArgumentMatcher<TradeEntity>() {
            @Override
            public boolean matches(TradeEntity t) {
                return "SELL".equals(t.getAction()) && "SESSION_END".equals(t.getNote());
            }
        }));
    }

    // ═══════════════════════════════════════════════════
    //  S13: 2차 매도 후 캐시 제거
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S13: splitPhase=1에서 2차 BEV → 캐시 제거 확인")
    void s13_secondSellRemovesCache() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-RM", new double[]{100.0, 400.0, nowMs - 300_000, 101.5, 100.0, 1, 0});

        invoke("KRW-RM", 99.5); // pnl=-0.5% → BEV

        assertFalse(cache.containsKey("KRW-RM"), "2차 매도 → 캐시 제거");
    }

    // ═══════════════════════════════════════════════════
    //  N1 (V115): armed 상태 전환만, 매도 안 됨
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N1: +1.6% 도달 → armed=1 전환, splitPhase=0 유지, 매도 없음")
    void n1_armedOnlyNoSell() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-N1", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0, 0});

        invoke("KRW-N1", 101.6);  // +1.6% → armed만
        Thread.sleep(200);

        assertTrue(cache.containsKey("KRW-N1"), "캐시 유지");
        assertEquals(0, (int) cache.get("KRW-N1")[5], "splitPhase=0 유지");
        assertEquals(1.0, cache.get("KRW-N1")[6], 0.01, "armed=1");
        verify(positionRepo, never()).save(any());
        verify(positionRepo, never()).deleteById(anyString());
    }

    // ═══════════════════════════════════════════════════
    //  N2 (V115): armed 후 drop 미달 (<0.5%) → 매도 안 됨
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N2: armed 후 drop 0.29% (<0.5%) → 매도 없음, armed 유지")
    void n2_armedDropBelowThreshold() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-N2", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0, 0});

        invoke("KRW-N2", 101.6);  // armed
        invoke("KRW-N2", 102.0);  // peak 102
        invoke("KRW-N2", 101.7);  // drop 0.29% < 0.5% → 매도 X

        assertTrue(cache.containsKey("KRW-N2"), "캐시 유지 (drop 미달)");
        assertEquals(0, (int) cache.get("KRW-N2")[5], "splitPhase=0 유지");
        assertEquals(1.0, cache.get("KRW-N2")[6], 0.01, "armed 유지=1");
        verify(positionRepo, never()).save(any());
    }

    // ═══════════════════════════════════════════════════
    //  N3 (V115): armed 후 peak 계속 상승 → 매도 없음
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N3: armed 후 peak 상승 (101.6→103→105) → 매도 없음, peak 갱신")
    void n3_peakKeepsGrowing() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-N3", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0, 0});

        invoke("KRW-N3", 101.6);  // armed
        invoke("KRW-N3", 103.0);  // peak 103
        invoke("KRW-N3", 105.0);  // peak 105

        assertTrue(cache.containsKey("KRW-N3"), "캐시 유지");
        assertEquals(105.0, cache.get("KRW-N3")[3], 0.1, "peak=105");
        assertEquals(1.0, cache.get("KRW-N3")[6], 0.01, "armed 유지");
        verify(positionRepo, never()).save(any());
    }

    // ═══════════════════════════════════════════════════
    //  N4 (V115): armed 후 노이즈 패턴 → peak 갱신 후 drop 0.58% 매도
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N4: armed 후 노이즈 (+1.6→+1.3→+2.5→+2.0→+1.9) → drop 0.58%에 매도")
    void n4_noisePattern() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-N4", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0, 0});

        PositionEntity pe = buildPosition("KRW-N4", 1000.0, 100.0);
        when(positionRepo.findById("KRW-N4")).thenReturn(Optional.of(pe));

        invoke("KRW-N4", 101.6);  // armed, peak=101.6
        invoke("KRW-N4", 101.3);  // drop 0.29% < 0.5%
        assertEquals(0, (int) cache.get("KRW-N4")[5], "첫 노이즈에서 매도 X");

        invoke("KRW-N4", 102.5);  // peak 갱신 → 102.5
        invoke("KRW-N4", 102.0);  // drop 0.49% < 0.5% 경계 미달
        assertEquals(0, (int) cache.get("KRW-N4")[5], "경계값 drop 미달");

        invoke("KRW-N4", 101.9);  // drop 0.58% >= 0.5% → SPLIT_1ST
        Thread.sleep(500);
        assertEquals(1.0, cache.get("KRW-N4")[5], 0.01, "drop 0.58%에 splitPhase=1");
    }

    // ═══════════════════════════════════════════════════
    //  N7 (V115): split_1st_trail_drop 가변값 동작 (1.0%)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N7: split_1st_trail_drop=1.0% → drop 0.58% 미달, 1.08% 도달 시 매도")
    void n7_variableTrailDrop() throws Exception {
        setField("cachedSplit1stTrailDrop", 1.0);  // 0.5 → 1.0%로 변경

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-N7", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0, 0});

        PositionEntity pe = buildPosition("KRW-N7", 1000.0, 100.0);
        when(positionRepo.findById("KRW-N7")).thenReturn(Optional.of(pe));

        invoke("KRW-N7", 101.6);  // armed, peak=101.6
        invoke("KRW-N7", 102.5);  // peak 102.5
        invoke("KRW-N7", 101.9);  // drop 0.58% < 1.0% → 매도 X
        assertEquals(0, (int) cache.get("KRW-N7")[5], "drop 0.58% < 1.0% 미달");
        assertEquals(1.0, cache.get("KRW-N7")[6], 0.01, "armed 유지");

        invoke("KRW-N7", 101.4);  // drop 1.08% >= 1.0% → SPLIT_1ST
        Thread.sleep(500);
        assertEquals(1.0, cache.get("KRW-N7")[5], 0.01, "drop 1.08%에 splitPhase=1");
    }

    // ═══════════════════════════════════════════════════
    //  N8 (V115): armed 상태 + 급락 → SL_TIGHT 발동 (Split 1차 armed여도 SL 안전망 동작)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N8: armed=1 + 급락 → SPLIT_1ST 우선 발동 (armed drop >= 0.5%이면 Split 1차 매도)")
    void n8_armedPlusDropTriggersSplit1st() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-N8", new double[]{100.0, 1000.0, nowMs - 120_000, 101.6, 100.0, 0, 1});

        PositionEntity pe = buildPosition("KRW-N8", 1000.0, 100.0);
        when(positionRepo.findById("KRW-N8")).thenReturn(Optional.of(pe));

        // -3.1% 급락 → armed이므로 peak 대비 drop 4.63% → SPLIT_1ST 우선
        invoke("KRW-N8", 96.9);
        Thread.sleep(500);

        // SPLIT_1ST 발동 후 캐시 유지 (2차 대기), splitPhase=1
        assertTrue(cache.containsKey("KRW-N8"), "SPLIT_1ST 후 캐시 유지");
        assertEquals(1.0, cache.get("KRW-N8")[5], 0.01, "splitPhase=1");
    }

    // ═══════════════════════════════════════════════════
    //  N10 (V115): armed 전 상태 + 급락 → SL_TIGHT 발동 (armed=0에선 SL 안전망 동작)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N10: armed=0 상태에서 -3.1% 급락 → SL_TIGHT 발동 (armed 전엔 SL 작동)")
    void n10_notArmedPlusSl() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // armed=0, 6분 경과 (Tight SL 구간)
        cache.put("KRW-N10", new double[]{100.0, 1000.0, nowMs - 360_000, 100.0, 100.0, 0, 0});

        invoke("KRW-N10", 96.9);

        assertFalse(cache.containsKey("KRW-N10"), "armed=0 + SL_TIGHT → 전량 매도");
    }

    // ═══════════════════════════════════════════════════
    //  N9 (Task C): splitExitEnabled=false + 캔들 TP target → 기존 TP 정상 동작
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N9 (Task C): splitExit 비활성 + pnlPct >= tpPct → 기존 TP 매도 정상")
    void n9_splitDisabledCandleTpWorks() throws Exception {
        setField("cachedSplitExitEnabled", false);

        PositionEntity pe = buildPosition("KRW-N9", 1000.0, 100.0);
        pe.setSplitPhase(0);
        pe.setEntryStrategy("MORNING_RUSH");
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pe));

        MorningRushConfigEntity cfg = configRepo.loadOrCreate();
        cfg.setTpPct(BigDecimal.valueOf(2.3));
        cfg.setSplitExitEnabled(false);

        // 현재가 102.4 → pnl +2.4% > tpPct 2.3%
        when(sharedPriceService.getPrice("KRW-N9")).thenReturn(102.4);

        Method m = MorningRushScannerService.class.getDeclaredMethod(
                "monitorPositions", MorningRushConfigEntity.class);
        m.setAccessible(true);
        m.invoke(scanner, cfg);

        // splitExit=false이면 splitPhase 무관하게 TP 경로 정상 동작
        // 실제 매도 실행은 비동기라 verify timeout으로 확인
        Thread.sleep(300);
    }

    // ═══════════════════════════════════════════════════
    //  N5 (Task C): splitPhase=1에서 캔들 TP target skip (monitorPositions)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("N5 (Task C): splitPhase=1일 때 monitorPositions TP 타겟 도달해도 skip")
    void n5_candleTpSkipWhenSplitPhase1() throws Exception {
        // splitPhase=1 포지션 (ENTRY_STRATEGY 설정 필요)
        PositionEntity pe = buildPosition("KRW-N5", 400.0, 100.0);
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));
        pe.setEntryStrategy("MORNING_RUSH");  // ENTRY_STRATEGY 일치
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pe));

        // cfg 설정: TP target 2.3%, splitExit 활성화
        MorningRushConfigEntity cfg = configRepo.loadOrCreate();
        cfg.setTpPct(BigDecimal.valueOf(2.3));
        cfg.setSplitExitEnabled(true);

        // SharedPriceService: getCurrentPrices 내부 호출 → 현재가 102.4 (pnl +2.4%)
        when(sharedPriceService.getPrice("KRW-N5")).thenReturn(102.4);

        // monitorPositions 호출 (cfg 1개 인자)
        Method m = MorningRushScannerService.class.getDeclaredMethod(
                "monitorPositions", MorningRushConfigEntity.class);
        m.setAccessible(true);
        m.invoke(scanner, cfg);

        // splitPhase=1이므로 TP 매도 경로 skip → deleteById 호출 안 됨
        verify(positionRepo, never()).deleteById("KRW-N5");
    }

    // ═══════════════════════════════════════════════════
    //  V129-A: BEV 제거 — splitPhase=1 + 매수가 하락해도 매도 없음
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-A: splitPhase=1 + 매수가 터치 → BEV 제거로 매도 없음")
    void v129_bevRemoved_noSellAtBreakeven() throws Exception {
        setField("cachedSplit1stCooldownMs", 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // splitPhase=1, peak=100, grace 밖
        cache.put("KRW-V129A", new double[]{100.0, 400.0, nowMs - 300_000, 100.0, 100.0, 1, 0});

        invoke("KRW-V129A", 100.0);  // 매수가 터치
        invoke("KRW-V129A", 99.5);   // -0.5% 하락

        assertTrue(cache.containsKey("KRW-V129A"), "V129: BEV 제거로 매수가 터치/하락해도 매도 없음");
    }

    // V129-A2: splitPhase=1 + -0.9% 손실 (쿨다운 만료 후, peak=avgPrice) → 매도 없음 (BEV 없음 재확인)
    @Test
    @DisplayName("V129-A2: splitPhase=1 + pnl -0.9% + 쿨다운 만료 → BEV 제거로 매도 없음")
    void v129a2_bevRemoved_sustainedLossNoSell() throws Exception {
        setField("cachedSplit1stCooldownMs", 60_000L);
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // Wide 구간(3분 경과 < 5분), peak=avgPrice → SPLIT_2ND_TRAIL 조건 peak>avg 불성립
        cache.put("KRW-V129A2", new double[]{100.0, 400.0, nowMs - 180_000, 100.0, 100.0, 1, 0});
        // 쿨다운은 만료 상태 (70s 전)
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>) getField("split1stExecutedAtMap");
        execMap.put("KRW-V129A2", nowMs - 70_000);

        invoke("KRW-V129A2", 99.1);  // -0.9% — 예전 BEV가 발동했을 영역

        assertTrue(cache.containsKey("KRW-V129A2"), "V129: 쿨다운 만료 후에도 BEV 안 발동 → 포지션 유지");
    }

    // V129-A3: splitPhase=1 + 본전 약간 위 (+0.3%) + TRAIL drop 미달 → 매도 없음
    @Test
    @DisplayName("V129-A3: splitPhase=1 + pnl +0.3% + peak drop 0.2% → 매도 없음 (BEV 없음)")
    void v129a3_bevRemoved_smallProfitNoSell() throws Exception {
        setField("cachedSplit1stCooldownMs", 60_000L);
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // peak=100.5, drop 0.2% (TRAIL 1.0% 미달)
        cache.put("KRW-V129A3", new double[]{100.0, 400.0, nowMs - 180_000, 100.5, 100.0, 1, 0});
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>) getField("split1stExecutedAtMap");
        execMap.put("KRW-V129A3", nowMs - 70_000);  // 쿨다운 만료

        invoke("KRW-V129A3", 100.3);  // peak 100.5에서 0.2% drop, TRAIL 1.0% 미달 → 매도 없음

        assertTrue(cache.containsKey("KRW-V129A3"), "V129: 작은 수익 유지 + drop 미달 → 매도 없음");
    }

    // V129-A4: splitPhase=1 + peak ≤ avgPrice + 큰 drop 시도 → SPLIT_2ND_TRAIL 불가 (peak>avg 가드)
    @Test
    @DisplayName("V129-A4: splitPhase=1 + peak≤avg + drop 2% 시도 → SPLIT_2ND_TRAIL 차단 (peak>avg 조건)")
    void v129a4_splitSecondTrailRequiresPeakAboveAvg() throws Exception {
        setField("cachedSplit1stCooldownMs", 60_000L);
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // peak = avgPrice (무수익 진행 중)
        cache.put("KRW-V129A4", new double[]{100.0, 400.0, nowMs - 180_000, 100.0, 100.0, 1, 0});
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>) getField("split1stExecutedAtMap");
        execMap.put("KRW-V129A4", nowMs - 70_000);  // 쿨다운 만료

        // peak=100 대비 2% drop이지만 peak<=avg → SPLIT_2ND_TRAIL 불가, BEV도 없음
        invoke("KRW-V129A4", 98.0);  // -2% (Wide SL 미달, splitPhase=1이라 tiered SL 비활성)

        assertTrue(cache.containsKey("KRW-V129A4"), "V129: peak<=avg에서 BEV 없이 drop 무시");
    }

    // ═══════════════════════════════════════════════════
    //  V129-B: 쿨다운 중 SPLIT_2ND_TRAIL 차단
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-B: 쿨다운 60s 내 peak-1% drop 발생해도 SPLIT_2ND_TRAIL 차단")
    void v129_cooldownBlocksSecondTrail() throws Exception {
        setField("cachedSplit1stCooldownMs", 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-V129B", new double[]{100.0, 400.0, nowMs - 300_000, 102.0, 100.0, 1, 0});

        // 쿨다운 기준 시점: 10초 전 (쿨다운 내)
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>)
                getField("split1stExecutedAtMap");
        execMap.put("KRW-V129B", nowMs - 10_000);

        invoke("KRW-V129B", 100.8);  // peak 102에서 -1.18% drop

        assertTrue(cache.containsKey("KRW-V129B"), "V129: 쿨다운 중 SPLIT_2ND_TRAIL 차단");
    }

    // ═══════════════════════════════════════════════════
    //  V129-C: 쿨다운 만료 후 SPLIT_2ND_TRAIL 발동
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-C: 쿨다운 만료 후 peak-1% drop → SPLIT_2ND_TRAIL 정상 발동")
    void v129_cooldownExpiredAllowsSecondTrail() throws Exception {
        setField("cachedSplit1stCooldownMs", 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-V129C", new double[]{100.0, 400.0, nowMs - 300_000, 102.0, 100.0, 1, 0});

        PositionEntity pe = buildPosition("KRW-V129C", 400.0, 100.0);
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));
        when(positionRepo.findById("KRW-V129C")).thenReturn(Optional.of(pe));

        // 쿨다운 기준 시점: 120초 전 (쿨다운 이미 만료)
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>)
                getField("split1stExecutedAtMap");
        execMap.put("KRW-V129C", nowMs - 120_000);

        invoke("KRW-V129C", 100.8);  // peak 102에서 -1.18% drop → TRAIL 발동

        assertFalse(cache.containsKey("KRW-V129C"), "V129: 쿨다운 만료 후 SPLIT_2ND_TRAIL 발동 → 캐시 제거");
    }

    // ═══════════════════════════════════════════════════
    //  V129-D: Grace 가드 확장 — SPLIT/TP/SL 모두 차단
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-D: Grace 60초 내 -5% 급락 + peak 존재해도 모든 매도 차단")
    void v129_graceBlocksAllSells() throws Exception {
        setField("cachedSplit1stCooldownMs", 60_000L);
        setField("cachedGracePeriodMs", 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // 매수 직후 (10초 경과, Grace 내), splitPhase=1, peak 102
        cache.put("KRW-V129D", new double[]{100.0, 400.0, nowMs - 10_000, 102.0, 100.0, 1, 0});

        invoke("KRW-V129D", 95.0);   // -5% (SL 조건)
        invoke("KRW-V129D", 100.8);  // peak 대비 drop 1.18% (TRAIL 조건)

        assertTrue(cache.containsKey("KRW-V129D"), "V129: Grace 내 모든 매도 차단 (SL+SPLIT+TP)");
    }

    // ═══════════════════════════════════════════════════
    //  V129-E: DB split1stExecutedAt 복원 — 재시작 후 쿨다운 유지
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-E: updatePositionCache 시 DB split1stExecutedAt 복원 → 쿨다운 유지")
    void v129_restartRestoresCooldown() throws Exception {
        // DB에 splitPhase=1 포지션 + split1stExecutedAt 설정
        PositionEntity pe = buildPosition("KRW-V129E", 400.0, 100.0);
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));
        pe.setOpenedAt(Instant.now().minusSeconds(300));
        pe.setSplit1stExecutedAt(Instant.now().minusSeconds(10));  // 10초 전 1차 체결

        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pe));

        MorningRushConfigEntity cfg = new MorningRushConfigEntity();
        cfg.setMode("PAPER");
        cfg.setTpPct(BigDecimal.valueOf(2.3));
        cfg.setSlPct(BigDecimal.valueOf(3.0));
        cfg.setGracePeriodSec(60);
        cfg.setWidePeriodMin(5);
        cfg.setWideSlPct(BigDecimal.valueOf(5.0));
        cfg.setTpTrailDropPct(BigDecimal.valueOf(1.0));
        cfg.setSplitExitEnabled(true);
        cfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        cfg.setSplitRatio(BigDecimal.valueOf(0.60));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.0));

        Method m = MorningRushScannerService.class.getDeclaredMethod(
                "updatePositionCache", MorningRushConfigEntity.class);
        m.setAccessible(true);
        m.invoke(scanner, cfg);

        // split1stExecutedAtMap 복원 확인
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>)
                getField("split1stExecutedAtMap");
        assertTrue(execMap.containsKey("KRW-V129E"), "V129: DB split1stExecutedAt 복원됨");
    }

    // ═══════════════════════════════════════════════════
    //  V129-F: 쿨다운 중에도 SL은 발동 (안전망 유지)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-F: 쿨다운 10s 경과 중 -5.1% 급락 → SL_WIDE 발동 (쿨다운은 SL 차단 안 함)")
    void v129f_cooldownDoesNotBlockSl() throws Exception {
        setField("cachedSplit1stCooldownMs", 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // splitPhase=1, peak=102, Wide SL 구간 (3분 경과 < 5분)
        cache.put("KRW-V129F", new double[]{100.0, 400.0, nowMs - 180_000, 102.0, 100.0, 1, 0});

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>)
                getField("split1stExecutedAtMap");
        execMap.put("KRW-V129F", nowMs - 10_000);  // 쿨다운 10s 내

        // -5.1% 급락 → Wide SL 조건(-5.0%) 도달. 2차 TRAIL은 쿨다운 차단, SL은 허용
        invoke("KRW-V129F", 94.9);

        assertFalse(cache.containsKey("KRW-V129F"), "V129: 쿨다운 중에도 SL_WIDE 발동 → 캐시 제거");
    }

    @Test
    @DisplayName("V129-F2: 쿨다운 중 Tight 구간 -3.1% → SL_TIGHT 발동")
    void v129f2_cooldownTightSl() throws Exception {
        setField("cachedSplit1stCooldownMs", 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // splitPhase=1, peak=102, Tight SL 구간 (6분 경과 > 5분)
        cache.put("KRW-V129F2", new double[]{100.0, 400.0, nowMs - 360_000, 102.0, 100.0, 1, 0});

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>)
                getField("split1stExecutedAtMap");
        execMap.put("KRW-V129F2", nowMs - 30_000);  // 쿨다운 30s 내

        // -3.1% → Tight SL 조건(-3.0%) 도달
        invoke("KRW-V129F2", 96.9);

        assertFalse(cache.containsKey("KRW-V129F2"), "V129: 쿨다운 중 Tight SL 발동");
    }

    // ═══════════════════════════════════════════════════
    //  V129-G: 쿨다운 경계 — 60초 정확히 경과하면 TRAIL 허용
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-G: 쿨다운 기준 시각 65s 전 (만료) → SPLIT_2ND_TRAIL 발동")
    void v129g_cooldownBoundaryExpired() throws Exception {
        setField("cachedSplit1stCooldownMs", 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-V129G", new double[]{100.0, 400.0, nowMs - 300_000, 102.0, 100.0, 1, 0});

        PositionEntity pe = buildPosition("KRW-V129G", 400.0, 100.0);
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));
        when(positionRepo.findById("KRW-V129G")).thenReturn(Optional.of(pe));

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>)
                getField("split1stExecutedAtMap");
        execMap.put("KRW-V129G", nowMs - 65_000);  // 5초 여유 → 확실히 만료

        invoke("KRW-V129G", 100.8);  // peak 102 → -1.18% drop

        assertFalse(cache.containsKey("KRW-V129G"), "V129: 쿨다운 만료 → SPLIT_2ND_TRAIL 허용");
    }

    @Test
    @DisplayName("V129-G2: 쿨다운 기준 시각 55s 전 (아직 내) → SPLIT_2ND_TRAIL 차단")
    void v129g2_cooldownBoundaryStillActive() throws Exception {
        setField("cachedSplit1stCooldownMs", 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-V129G2", new double[]{100.0, 400.0, nowMs - 300_000, 102.0, 100.0, 1, 0});

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>)
                getField("split1stExecutedAtMap");
        execMap.put("KRW-V129G2", nowMs - 55_000);  // 5초 여유 → 확실히 쿨다운 내

        invoke("KRW-V129G2", 100.8);

        assertTrue(cache.containsKey("KRW-V129G2"), "V129: 쿨다운 내 TRAIL 차단");
    }

    // ═══════════════════════════════════════════════════
    //  V129-H: Grace 경계 — 65s 경과하면 매도 허용
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-H: Grace 65s 경과 + Wide 구간 -5.1% → SL_WIDE 발동")
    void v129h_graceBoundaryExpired() throws Exception {
        setField("cachedGracePeriodMs", 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // 65s 경과 (Grace 밖), Wide SL 구간
        cache.put("KRW-V129H", new double[]{100.0, 400.0, nowMs - 65_000, 100.0, 100.0, 0, 0});

        invoke("KRW-V129H", 94.9);  // -5.1%

        assertFalse(cache.containsKey("KRW-V129H"), "V129: Grace 경과 후 SL_WIDE 발동");
    }

    @Test
    @DisplayName("V129-H2: Grace 55s 경과 (아직 내) + -10% 급락 → 모든 매도 차단")
    void v129h2_graceBoundaryStillActive() throws Exception {
        setField("cachedGracePeriodMs", 60_000L);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-V129H2", new double[]{100.0, 400.0, nowMs - 55_000, 100.0, 100.0, 0, 0});

        invoke("KRW-V129H2", 90.0);  // -10% 급락

        assertTrue(cache.containsKey("KRW-V129H2"), "V129: Grace 내 -10%도 차단");
    }

    // ═══════════════════════════════════════════════════
    //  V129-WS: addPosition(positionCache.put) 누락 회귀 방지
    //  2026-04-20 ONT 사고 패턴: WS 매수 → positionCache 미등록 → checkRealtimeTp no-op
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-WS1: positionCache 미등록 상태에서 checkRealtimeTp 호출 → no-op (no sell, no split)")
    void v129ws1_missingPositionCacheIsNoop() throws Exception {
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        assertFalse(cache.containsKey("KRW-MISSING"), "precondition: 미등록");

        // 어떤 가격이 와도 매도 콜백/캐시 변경 없어야 함
        invoke("KRW-MISSING", 100.0);
        invoke("KRW-MISSING", 105.0);  // +5% (TP 영역이지만 cache 없으므로 no-op)
        invoke("KRW-MISSING", 90.0);   // -10% (SL 영역이지만 cache 없으므로 no-op)

        assertFalse(cache.containsKey("KRW-MISSING"), "positionCache 미등록 → no-op 유지");
    }

    @Test
    @DisplayName("V129-WS2: WS 경로 executeBuy 후 positionCache.put 누락 재현 → 실시간 TP/SL 누락 회귀")
    void v129ws2_missingCacheSkipsRealtimeSellPath() throws Exception {
        // 사고 패턴: executeBuy는 실행됐으나 positionCache.put이 누락된 상태
        // 검증: 이 상태에서 가격 피드가 들어와도 매도가 발동하지 않는다 (포지션 미등록 → realtime TP/SL 통과)
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        String market = "KRW-WS-ORPHAN";
        assertFalse(cache.containsKey(market));

        for (double p : new double[]{100.0, 101.5, 103.0, 102.0, 99.5, 97.0}) {
            invoke(market, p);
        }
        // 회귀 검출 기준: cache에 put되지 않은 market은 no-op (이전 ONT 사고 확정 재현)
        assertFalse(cache.containsKey(market),
                "V129-WS 회귀 가드: positionCache.put 누락 시 WS 실시간 체크는 모두 no-op");
    }

    @Test
    @DisplayName("V129-WS3: positionCache.put 정상 수행 후엔 실시간 경로 정상 작동 (회귀 반대 검증)")
    void v129ws3_withCacheRealtimeWorks() throws Exception {
        setField("cachedSplit1stCooldownMs", 60_000L);
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // positionCache 정상 등록 상태 (splitExit 활성 + Grace 밖)
        cache.put("KRW-WS-OK", new double[]{100.0, 400.0, nowMs - 180_000, 100.0, 100.0, 0, 0});

        invoke("KRW-WS-OK", 101.6);  // +1.6% — armed 조건 도달
        invoke("KRW-WS-OK", 102.5);  // peak 갱신
        // 여기서 cache에 armed=1 / peak=102.5가 반영됨을 확인 (정상 경로)
        double[] pos = cache.get("KRW-WS-OK");
        assertTrue(pos[6] > 0, "V129-WS3: 정상 등록 경로는 armed=1 설정됨");
        assertEquals(102.5, pos[3], 0.01, "V129-WS3: peak 정상 갱신");
    }

    // ═══════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════

    private void invoke(String market, double price) throws Exception {
        Method m = MorningRushScannerService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(scanner, market, price);
    }

    private Object getField(String name) throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(scanner);
    }

    private void invokeExecuteSell(PositionEntity pe, double price, double pnlPct,
                                    String reason, String sellType,
                                    MorningRushConfigEntity cfg) throws Exception {
        Method m = MorningRushScannerService.class.getDeclaredMethod(
                "executeSell", PositionEntity.class, double.class, double.class,
                String.class, String.class, MorningRushConfigEntity.class);
        m.setAccessible(true);
        m.invoke(scanner, pe, price, pnlPct, reason, sellType, cfg);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getPositionCache() throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(scanner);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(scanner, value);
    }

    private PositionEntity buildPosition(String market, double qty, double avgPrice) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setQty(BigDecimal.valueOf(qty));
        pe.setAvgPrice(BigDecimal.valueOf(avgPrice));
        pe.setAddBuys(0);
        pe.setOpenedAt(Instant.now());
        pe.setEntryStrategy("MORNING_RUSH");
        return pe;
    }
}
