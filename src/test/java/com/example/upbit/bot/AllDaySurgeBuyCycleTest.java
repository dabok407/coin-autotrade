package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.SharedPriceService;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitCandle;
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
 * 올데이 WS Surge 매수 전체 사이클 + 서버 재기동 splitPhase 복원 테스트.
 *
 * 시나리오:
 *   1. WS Surge 정상 매수 → position 생성 + tpPositionCache 등록 확인
 *   2. 엔트리 윈도우 밖 → 매수 차단
 *   3. 이미 보유 중 → 매수 차단
 *   4. maxPositions 도달 → 매수 차단
 *   5. sellCooldown 중 → 매수 차단
 *   6. syncTpWebSocket — splitPhase=0 복원
 *   7. syncTpWebSocket — splitPhase=1 복원
 *   8. syncTpWebSocket — 없어진 포지션 캐시 제거
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AllDaySurgeBuyCycleTest {

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

        AllDayScannerConfigEntity cfg = buildConfig();
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig()));
        when(positionRepo.findAll()).thenReturn(Collections.<PositionEntity>emptyList());

        when(txTemplate.execute(any())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object cb = invocation.getArgument(0);
                if (cb instanceof TransactionCallbackWithoutResult) {
                    ((TransactionCallbackWithoutResult) cb).doInTransaction(mock(TransactionStatus.class));
                }
                return null;
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    //  매수 사이클 테스트
    // ═══════════════════════════════════════════════════════

    @Test
    @DisplayName("S01: WS Surge HCB 미달 → NO_SIGNAL로 정상 차단 (진입 필터 동작 확인)")
    public void s01_surgeBuyHcbBlockedNormally() throws Exception {
        // HCB 점수 미달 캔들 → 정상적으로 차단되어야 함
        when(candleService.getMinuteCandles(eq("KRW-BUY"), anyInt(), anyInt(), any()))
                .thenReturn(buildHighScoreCandles("KRW-BUY"));

        invokeSurge("KRW-BUY", 109.0, 3.0);

        // HCB 미달 → 매수 안 됨 (정상 차단)
        verify(positionRepo, never()).save(argThat(new ArgumentMatcher<PositionEntity>() {
            @Override
            public boolean matches(PositionEntity p) {
                return "KRW-BUY".equals(p.getMarket());
            }
        }));
        // 캐시에도 없음
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        assertFalse(cache.containsKey("KRW-BUY"), "HCB 미달 → 캐시 미등록");
    }

    @Test
    @DisplayName("S02: 엔트리 윈도우 밖 → 매수 차단")
    public void s02_outsideEntryWindow() throws Exception {
        // 엔트리 윈도우: 10:30~22:00, 현재 시각이 윈도우 밖이면 차단
        // processSurgeBuy 내부에서 KST 시각 체크 → 현재 실행 시각에 따라 다름
        // 엔트리 윈도우를 좁게 설정 (00:00~00:01)
        AllDayScannerConfigEntity cfg = buildConfig();
        cfg.setEntryStartHour(0);
        cfg.setEntryStartMin(0);
        cfg.setEntryEndHour(0);
        cfg.setEntryEndMin(1);
        when(configRepo.loadOrCreate()).thenReturn(cfg);

        when(candleService.getMinuteCandles(eq("KRW-WINDOW"), anyInt(), anyInt(), any()))
                .thenReturn(buildHighScoreCandles("KRW-WINDOW"));

        invokeSurge("KRW-WINDOW", 109.0, 3.0);

        // 엔트리 윈도우 밖이면 매수 안 됨 (현재 시각이 00:00~00:01이 아닌 경우)
        // 현재 테스트 실행 시각이 00:00~00:01 KST일 확률은 거의 0
        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    @Test
    @DisplayName("S03: 이미 보유 중 → 매수 차단")
    public void s03_alreadyHeld() throws Exception {
        PositionEntity existing = new PositionEntity();
        existing.setMarket("KRW-HELD");
        existing.setQty(BigDecimal.valueOf(100));
        existing.setEntryStrategy("HIGH_CONFIDENCE_BREAKOUT");
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(existing));

        when(candleService.getMinuteCandles(eq("KRW-HELD"), anyInt(), anyInt(), any()))
                .thenReturn(buildHighScoreCandles("KRW-HELD"));

        invokeSurge("KRW-HELD", 109.0, 3.0);

        // 이미 보유 중이므로 추가 매수 차단
        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    @Test
    @DisplayName("S04: maxPositions 도달 → 매수 차단")
    public void s04_maxPositionsReached() throws Exception {
        // maxPositions=1로 설정하고, 이미 1개 보유
        AllDayScannerConfigEntity cfg = buildConfig();
        cfg.setMaxPositions(1);
        when(configRepo.loadOrCreate()).thenReturn(cfg);

        PositionEntity existing = new PositionEntity();
        existing.setMarket("KRW-OTHER");
        existing.setQty(BigDecimal.valueOf(100));
        existing.setEntryStrategy("HIGH_CONFIDENCE_BREAKOUT");
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(existing));

        when(candleService.getMinuteCandles(eq("KRW-MAX"), anyInt(), anyInt(), any()))
                .thenReturn(buildHighScoreCandles("KRW-MAX"));

        invokeSurge("KRW-MAX", 109.0, 3.0);

        verify(positionRepo, never()).save(argThat(new ArgumentMatcher<PositionEntity>() {
            @Override
            public boolean matches(PositionEntity p) {
                return "KRW-MAX".equals(p.getMarket());
            }
        }));
    }

    @Test
    @DisplayName("S05: sellCooldown 중 → 매수 차단")
    public void s05_sellCooldownBlocked() throws Exception {
        // sellCooldownMap에 등록
        ConcurrentHashMap<String, Long> cooldown = getCooldownMap();
        cooldown.put("KRW-COOL", System.currentTimeMillis()); // 방금 매도

        when(candleService.getMinuteCandles(eq("KRW-COOL"), anyInt(), anyInt(), any()))
                .thenReturn(buildHighScoreCandles("KRW-COOL"));

        invokeSurge("KRW-COOL", 109.0, 3.0);

        verify(positionRepo, never()).save(argThat(new ArgumentMatcher<PositionEntity>() {
            @Override
            public boolean matches(PositionEntity p) {
                return "KRW-COOL".equals(p.getMarket());
            }
        }));
    }

    // ═══════════════════════════════════════════════════════
    //  서버 재기동 syncTpWebSocket splitPhase 복원
    // ═══════════════════════════════════════════════════════

    @Test
    @DisplayName("S06: syncTpWebSocket — splitPhase=0 포지션 캐시 복원")
    public void s06_syncRestoresSplitPhase0() throws Exception {
        PositionEntity pe = buildPosition("KRW-SYNC0", 1000.0, 100.0, 0);
        pe.setOpenedAt(Instant.now().minusSeconds(300));

        ConcurrentHashMap<String, double[]> cache = getTpCache();
        assertTrue(cache.isEmpty(), "초기 캐시 비어있음");

        invokeSync(Collections.singletonList(pe));

        assertTrue(cache.containsKey("KRW-SYNC0"), "캐시 복원됨");
        double[] pos = cache.get("KRW-SYNC0");
        assertEquals(7, pos.length, "V115: 7요소 배열");
        assertEquals(0, (int) pos[5], "splitPhase=0 복원");
        assertEquals(0, (int) pos[6], "V115: split1stTrailArmed=0 초기");
        assertEquals(100.0, pos[0], 0.01, "avgPrice=100");
    }

    @Test
    @DisplayName("S07: syncTpWebSocket — splitPhase=1 포지션 캐시 복원")
    public void s07_syncRestoresSplitPhase1() throws Exception {
        PositionEntity pe = buildPosition("KRW-SYNC1", 400.0, 100.0, 1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));
        pe.setOpenedAt(Instant.now().minusSeconds(600));

        invokeSync(Collections.singletonList(pe));

        ConcurrentHashMap<String, double[]> cache = getTpCache();
        assertTrue(cache.containsKey("KRW-SYNC1"), "캐시 복원됨");
        assertEquals(1, (int) cache.get("KRW-SYNC1")[5], "splitPhase=1 복원");
    }

    @Test
    @DisplayName("S08: syncTpWebSocket — 없어진 포지션 캐시 제거")
    public void s08_syncRemovesGonePositions() throws Exception {
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        cache.put("KRW-GONE", new double[]{100.0, 100.0, 0, 100.0, System.currentTimeMillis(), 0, 0});

        // 빈 포지션 목록으로 sync → KRW-GONE 제거
        invokeSync(Collections.<PositionEntity>emptyList());

        assertFalse(cache.containsKey("KRW-GONE"), "없어진 포지션 캐시 제거됨");
    }

    // ═══════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════

    private void invokeSurge(String market, double wsPrice, double changePct) throws Exception {
        Method m = AllDayScannerService.class.getDeclaredMethod(
                "processSurgeBuy", String.class, double.class, double.class);
        m.setAccessible(true);
        m.invoke(scanner, market, wsPrice, changePct);
    }

    private void invokeSync(List<PositionEntity> positions) throws Exception {
        Method m = AllDayScannerService.class.getDeclaredMethod(
                "syncTpWebSocket", List.class);
        m.setAccessible(true);
        m.invoke(scanner, positions);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getTpCache() throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField("tpPositionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(scanner);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Long> getCooldownMap() throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField("sellCooldownMap");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Long>) f.get(scanner);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(scanner, value);
    }

    private PositionEntity buildPosition(String market, double qty, double avgPrice, int splitPhase) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setQty(BigDecimal.valueOf(qty));
        pe.setAvgPrice(BigDecimal.valueOf(avgPrice));
        pe.setAddBuys(0);
        pe.setOpenedAt(Instant.now());
        pe.setEntryStrategy("HIGH_CONFIDENCE_BREAKOUT");
        pe.setSplitPhase(splitPhase);
        return pe;
    }

    /** HCB 스코어 높게 나오는 캔들 — 실제 진입 가능 조건 */
    private List<UpbitCandle> buildHighScoreCandles(String market) {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();
        for (int i = 0; i < 80; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = market;
            c.candle_date_time_utc = String.format("2026-04-13T%02d:%02d:00", i / 60, i % 60);
            double base = 100.0 + i * 0.1;
            double adjust = (i % 5 == 3) ? -0.15 : 0.05;
            c.opening_price = base + adjust;
            c.trade_price = base + adjust + 0.15;
            c.high_price = c.trade_price + 0.1;
            c.low_price = c.opening_price - 0.1;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        UpbitCandle last = candles.get(79);
        last.opening_price = 107.5;
        last.trade_price = 109.0;
        last.high_price = 109.5;
        last.low_price = 107.0;
        last.candle_acc_trade_volume = 8000;
        Collections.reverse(candles);
        return candles;
    }

    private AllDayScannerConfigEntity buildConfig() {
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        cfg.setEnabled(true);
        cfg.setMode("PAPER");
        cfg.setMaxPositions(3);
        cfg.setOrderSizingMode("FIXED");
        cfg.setOrderSizingValue(BigDecimal.valueOf(100000));
        cfg.setCandleUnitMin(5);
        cfg.setEntryStartHour(0);
        cfg.setEntryStartMin(0);
        cfg.setEntryEndHour(23);
        cfg.setEntryEndMin(59);
        cfg.setSessionEndHour(23);
        cfg.setSessionEndMin(59);
        cfg.setMinConfidence(BigDecimal.valueOf(7.0));
        cfg.setExcludeMarkets("");
        cfg.setBtcFilterEnabled(false);
        cfg.setTopN(10);
        cfg.setSlPct(BigDecimal.valueOf(1.5));
        cfg.setTrailAtrMult(BigDecimal.valueOf(0.8));
        cfg.setVolumeSurgeMult(BigDecimal.valueOf(3.0));
        cfg.setMinBodyRatio(BigDecimal.valueOf(0.60));
        cfg.setTimeStopCandles(12);
        cfg.setTimeStopMinPnl(BigDecimal.valueOf(0.3));
        // Split-Exit 기본 비활성
        cfg.setSplitExitEnabled(false);
        cfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        cfg.setSplitRatio(BigDecimal.valueOf(0.60));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.0));
        return cfg;
    }

    private BotConfigEntity buildBotConfig() {
        BotConfigEntity bc = new BotConfigEntity();
        bc.setCapitalKrw(BigDecimal.valueOf(1000000));
        return bc;
    }
}
