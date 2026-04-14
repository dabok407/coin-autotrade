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
        scanner = new MorningRushScannerService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, privateClient, txTemplate, catalogService, tickerService,
                sharedPriceService, new SharedTradeThrottle()
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
        cache.put("KRW-A", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0});

        PositionEntity pe = buildPosition("KRW-A", 1000.0, 100.0);
        when(positionRepo.findById("KRW-A")).thenReturn(Optional.of(pe));

        invoke("KRW-A", 101.6);
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
        cache.put("KRW-B", new double[]{100.0, 400.0, nowMs - 300_000, 103.0, 100.0, 1});

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
        cache.put("KRW-C", new double[]{100.0, 400.0, nowMs - 300_000, 101.5, 100.0, 1});

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
        cache.put("KRW-D", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0});

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
        cache.put("KRW-E", new double[]{100.0, 400.0, nowMs - 300_000, 101.5, 100.0, 1});

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
        cache.put("KRW-F", new double[]{100.0, 1000.0, nowMs - 300_000, 100.0, 100.0, 0});

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
    @DisplayName("S07: 1차 매도 후 peak 리셋 = 현재가(101.6)")
    void s07_peakResetAfterFirst() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-G", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0});

        PositionEntity pe = buildPosition("KRW-G", 1000.0, 100.0);
        when(positionRepo.findById("KRW-G")).thenReturn(Optional.of(pe));

        invoke("KRW-G", 101.6);
        Thread.sleep(500);

        assertTrue(cache.containsKey("KRW-G"), "캐시 유지");
        assertEquals(101.6, cache.get("KRW-G")[3], 0.1, "peak 리셋=101.6");
        assertEquals(1.0, cache.get("KRW-G")[5], 0.01, "splitPhase=1");
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
        cache.put("KRW-H", new double[]{100.0, 1000.0, nowMs - 360_000, 100.0, 100.0, 0});

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
        // qty=40, avgPrice=100 → 잔량 40*0.4=16 * 101.6 = 1625.6원 < 5000원
        cache.put("KRW-DUST", new double[]{100.0, 40.0, nowMs - 120_000, 100.0, 100.0, 0});

        PositionEntity pe = buildPosition("KRW-DUST", 40.0, 100.0);
        when(positionRepo.findById("KRW-DUST")).thenReturn(Optional.of(pe));

        invoke("KRW-DUST", 101.6);
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
        cache.put("KRW-COOL", new double[]{100.0, 1000.0, nowMs - 120_000, 100.0, 100.0, 0});

        PositionEntity pe = buildPosition("KRW-COOL", 1000.0, 100.0);
        when(positionRepo.findById("KRW-COOL")).thenReturn(Optional.of(pe));

        invoke("KRW-COOL", 101.6);
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
        assertEquals(6, pos.length, "6요소 배열");
        assertEquals(1, (int) pos[5], "splitPhase=1 복원");
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
        cache.put("KRW-RM", new double[]{100.0, 400.0, nowMs - 300_000, 101.5, 100.0, 1});

        invoke("KRW-RM", 99.5); // pnl=-0.5% → BEV

        assertFalse(cache.containsKey("KRW-RM"), "2차 매도 → 캐시 제거");
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
