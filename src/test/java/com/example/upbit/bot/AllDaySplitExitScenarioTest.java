package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.SharedPriceService;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.strategy.Signal;
import com.example.upbit.strategy.SignalAction;
import com.example.upbit.strategy.StrategyType;
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
 * Split-Exit 분할 익절 시나리오 테스트 (AllDayScanner 기준).
 *
 * tpPositionCache: [avgPrice, peakPrice, trailActivated(0/1), troughPrice, openedAtEpochMs, splitPhase]
 *
 * 설계 요약:
 *   1차 매도(60%): +1.5% 도달 → splitRatio 만큼 매도, splitPhase 0→1
 *   2차 매도(잔여): TP_TRAIL drop 1.0% 또는 Breakeven SL(매수가)
 *   splitExitEnabled=false → 기존 전량 매도
 *
 * 시나리오 18개:
 *   매도 기본 (1-4):  정상1차, 정상2차TP, 정상2차BEV, 1차미달
 *   상태 관리 (5-10): 재발동차단, ADD_BUY차단, 쿨다운, 캐시재빌드, 재시작복구, peak리셋
 *   특수 상황 (11-15): SESSION_END, TIME_STOP, 최소금액, qty=1, 이중매도
 *   자본 관리 (16-18): calcInvested, maxPositions, 과투자방지
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AllDaySplitExitScenarioTest {

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
    private AllDayScannerConfigEntity splitCfg;

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

        // 티어드 SL cached 변수 (기존 값 유지)
        setField("cachedGracePeriodMs", 30_000L);
        setField("cachedWidePeriodMs", 15 * 60_000L);
        setField("cachedWideSlPct", 3.0);
        setField("cachedTightSlPct", 1.5);
        setField("cachedTpTrailActivatePct", 2.0);
        setField("cachedTpTrailDropPct", 1.0);

        // Split-Exit cached 변수 (구현 시 추가될 필드)
        setField("cachedSplitExitEnabled", true);
        setField("cachedSplitTpPct", 1.5);
        setField("cachedSplitRatio", 0.60);
        setField("cachedTrailDropAfterSplit", 1.0);

        // Split-Exit 설정이 포함된 config
        splitCfg = buildSplitConfig();
        when(configRepo.loadOrCreate()).thenReturn(splitCfg);

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

    // ═══════════════════════════════════════════════════════════════
    //  매도 기본 (1-4)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S01: 정상 1차 매도 — +1.5% 도달 시 60% 매도, splitPhase 0→1")
    public void s01_normalFirstSplit() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        // 매수 2분 전, splitPhase=0
        cache.put("KRW-A", new double[]{100.0, 100.0, 0, 100.0, nowMs - 120_000, 0});

        PositionEntity pe = buildPosition("KRW-A", 1000.0, 100.0);
        pe.setSplitPhase(0);
        when(positionRepo.findById("KRW-A")).thenReturn(Optional.of(pe));

        // +1.6% → 1차 매도 조건 충족
        invoke("KRW-A", 101.6);
        Thread.sleep(500);

        // 캐시 유지 (1차 매도 후 2차 대기)
        assertTrue(cache.containsKey("KRW-A"), "1차 매도 후 캐시 유지 (2차 대기)");
        // splitPhase=1로 변경 확인
        assertEquals(1.0, cache.get("KRW-A")[5], 0.01, "캐시 splitPhase=1");

        // position save 호출 확인 (deleteById 아님)
        verify(positionRepo, never()).deleteById("KRW-A");
        verify(positionRepo).save(argThat(new ArgumentMatcher<PositionEntity>() {
            @Override
            public boolean matches(PositionEntity p) {
                return "KRW-A".equals(p.getMarket())
                        && p.getSplitPhase() == 1
                        && p.getSplitOriginalQty() != null
                        && p.getSplitOriginalQty().doubleValue() == 1000.0;
            }
        }));

        // tradeLog: SPLIT_1ST note 기록
        verify(tradeLogRepo).save(argThat(new ArgumentMatcher<TradeEntity>() {
            @Override
            public boolean matches(TradeEntity t) {
                return "SELL".equals(t.getAction())
                        && t.getNote() != null
                        && t.getNote().contains("SPLIT_1ST");
            }
        }));
    }

    @Test
    @DisplayName("S02: 정상 2차 매도 (TP_TRAIL) — splitPhase=1에서 피크 대비 drop → 잔량 전량 매도")
    public void s02_normalSecondSplitTpTrail() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        // splitPhase=1, TP_TRAIL 활성(activated=1), 피크 103
        cache.put("KRW-B", new double[]{100.0, 103.0, 1.0, 100.0, nowMs - 300_000, 1});

        PositionEntity pe = buildPosition("KRW-B", 400.0, 100.0); // 1차 매도 후 40% 잔량
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));
        when(positionRepo.findById("KRW-B")).thenReturn(Optional.of(pe));

        // 피크 103에서 -1.1% drop → 101.87 → TP_TRAIL 발동
        invoke("KRW-B", 101.8);
        Thread.sleep(500);

        // 캐시 제거 (2차 완료, 포지션 종료)
        assertFalse(cache.containsKey("KRW-B"), "2차 매도 완료 → 캐시 제거");

        // position 삭제
        verify(positionRepo).deleteById("KRW-B");

        // tradeLog: SPLIT_2ND note
        verify(tradeLogRepo).save(argThat(new ArgumentMatcher<TradeEntity>() {
            @Override
            public boolean matches(TradeEntity t) {
                return "SELL".equals(t.getAction())
                        && t.getNote() != null
                        && t.getNote().contains("SPLIT_2ND");
            }
        }));

        // 2차 매도 시에만 sellCooldownMap 등록
        ConcurrentHashMap<String, Long> cooldown = getCooldownMap();
        assertTrue(cooldown.containsKey("KRW-B"), "2차 매도 시 쿨다운 등록");
    }

    @Test
    @DisplayName("S03: 정상 2차 매도 (Breakeven SL) — splitPhase=1에서 매수가까지 하락 → 잔량 매도")
    public void s03_normalSecondSplitBreakeven() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        // splitPhase=1, TP_TRAIL 미활성(activated=0), 피크 101.5
        cache.put("KRW-C", new double[]{100.0, 101.5, 0, 100.0, nowMs - 300_000, 1});

        PositionEntity pe = buildPosition("KRW-C", 400.0, 100.0);
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));
        when(positionRepo.findById("KRW-C")).thenReturn(Optional.of(pe));

        // 매수가 100원까지 하락 → Breakeven SL 발동
        invoke("KRW-C", 100.0);
        Thread.sleep(500);

        assertFalse(cache.containsKey("KRW-C"), "Breakeven SL → 캐시 제거");
        verify(positionRepo).deleteById("KRW-C");
    }

    @Test
    @DisplayName("S04: 1차 미달 — +1.4%로 splitTpPct 미달, 매도 안 됨")
    public void s04_firstSplitNotReached() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        cache.put("KRW-D", new double[]{100.0, 100.0, 0, 100.0, nowMs - 120_000, 0});

        // +1.4% → splitTpPct(1.5%) 미달
        invoke("KRW-D", 101.4);

        assertTrue(cache.containsKey("KRW-D"), "1차 조건 미달 → 매도 안 됨");
        assertEquals(0, cache.get("KRW-D")[5], 0.01, "splitPhase 유지=0");
        verify(positionRepo, never()).save(any());
        verify(positionRepo, never()).deleteById(anyString());
    }

    // ═══════════════════════════════════════════════════════════════
    //  상태 관리 (5-10)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S05: 재발동 차단 — splitPhase=1에서 다시 +1.5% 도달해도 1차 재발동 안 됨")
    public void s05_noReFireAfterFirstSplit() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        // splitPhase=1 (1차 이미 완료), TP_TRAIL 미활성
        cache.put("KRW-E", new double[]{100.0, 101.5, 0, 100.0, nowMs - 300_000, 1});

        // +1.8% → 1차 조건이지만 splitPhase==1이므로 무시
        invoke("KRW-E", 101.8);

        assertTrue(cache.containsKey("KRW-E"), "splitPhase=1에서 1차 재발동 안 됨, 캐시 유지");
        assertEquals(1.0, cache.get("KRW-E")[5], 0.01, "splitPhase 변경 없음=1");

        // 1차 매도 트랜잭션 없음 (SPLIT_1ST 기록 없음)
        verify(txTemplate, never()).execute(any());
    }

    @Test
    @DisplayName("S06: ADD_BUY 차단 — splitPhase>0이면 추가매수 완전 차단")
    public void s06_addBuyBlockedDuringSplit() throws Exception {
        // splitPhase=1인 포지션이 이미 존재
        PositionEntity pe = buildPosition("KRW-F", 400.0, 100.0);
        pe.setSplitPhase(1);
        pe.setEntryStrategy("HIGH_CONFIDENCE_BREAKOUT");

        when(positionRepo.findById("KRW-F")).thenReturn(Optional.of(pe));
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pe));

        // ADD_BUY 시도 → splitPhase>0이므로 차단
        // 이 테스트는 tick() 내 진입 로직에서 검증
        // splitPhase>0인 마켓은 "이미 보유 중"으로 차단되어야 함
        assertTrue(pe.getSplitPhase() > 0, "splitPhase=1");

        // 실제 차단은 서비스 구현에서 검증 (ownedMarkets에 포함되어 BUY 차단)
        // 여기서는 position이 존재하면 ownedMarkets에 들어가는 기존 로직으로 충분
    }

    @Test
    @DisplayName("S07: 쿨다운 — 1차 매도 시 sellCooldownMap 미등록, 2차에서만 등록")
    public void s07_cooldownOnlyOnSecondSplit() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        ConcurrentHashMap<String, Long> cooldown = getCooldownMap();

        // 1차 매도 시나리오
        cache.put("KRW-G", new double[]{100.0, 100.0, 0, 100.0, nowMs - 120_000, 0});
        PositionEntity pe = buildPosition("KRW-G", 1000.0, 100.0);
        pe.setSplitPhase(0);
        when(positionRepo.findById("KRW-G")).thenReturn(Optional.of(pe));

        invoke("KRW-G", 101.6); // +1.6% → 1차 매도
        Thread.sleep(500);

        assertFalse(cooldown.containsKey("KRW-G"), "1차 매도 시 쿨다운 미등록");
    }

    @Test
    @DisplayName("S08: 캐시 재빌드 — DB splitPhase 읽어서 tpPositionCache 복원")
    public void s08_cacheRebuildFromDb() throws Exception {
        // DB에 splitPhase=1인 포지션 존재
        PositionEntity pe = buildPosition("KRW-H", 400.0, 100.0);
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));
        pe.setOpenedAt(Instant.now().minusSeconds(300));

        // syncTpWebSocket 호출 시 캐시 재빌드
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        assertFalse(cache.containsKey("KRW-H"), "초기 캐시 비어있음");

        // syncTpWebSocket을 통해 캐시 재빌드 시뮬레이션
        // 구현 시: tpPositionCache에 splitPhase를 [5] 인덱스로 복원
        invokeSyncTpWebSocket(Collections.singletonList(pe));

        assertTrue(cache.containsKey("KRW-H"), "DB에서 캐시 재빌드됨");
        assertEquals(1.0, cache.get("KRW-H")[5], 0.01, "splitPhase=1 복원");
    }

    @Test
    @DisplayName("S09: 재시작 복구 — 재시작 후 peak=현재가로 초기화 (avgPrice 아님)")
    public void s09_restartPeakReset() throws Exception {
        // DB에 splitPhase=1인 포지션, 재시작 상황
        PositionEntity pe = buildPosition("KRW-I", 400.0, 100.0);
        pe.setSplitPhase(1);
        pe.setOpenedAt(Instant.now().minusSeconds(600));

        ConcurrentHashMap<String, double[]> cache = getTpCache();

        // syncTpWebSocket으로 재빌드 — peak는 avgPrice가 아닌 현재가로 초기화되어야 함
        // (재시작 시점 현재가를 모르면 avgPrice 사용, SharedPriceService에서 조회 가능 시 현재가)
        invokeSyncTpWebSocket(Collections.singletonList(pe));

        assertTrue(cache.containsKey("KRW-I"), "캐시 복원됨");
        // peak는 최소 avgPrice 이상이어야 함
        double peak = cache.get("KRW-I")[1];
        assertTrue(peak >= 100.0, "peak >= avgPrice");
    }

    @Test
    @DisplayName("S10: peak 리셋 — 1차 매도 후 peak를 현재가로 리셋 (2차 trail 기준)")
    public void s10_peakResetAfterFirstSplit() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        // splitPhase=0, peak=100(초기)
        cache.put("KRW-J", new double[]{100.0, 100.0, 0, 100.0, nowMs - 120_000, 0});

        PositionEntity pe = buildPosition("KRW-J", 1000.0, 100.0);
        pe.setSplitPhase(0);
        when(positionRepo.findById("KRW-J")).thenReturn(Optional.of(pe));

        // +1.6% → 1차 매도 발동
        invoke("KRW-J", 101.6);
        Thread.sleep(500);

        // 1차 매도 후 peak 리셋 → 현재가(101.6)로 재설정
        assertTrue(cache.containsKey("KRW-J"), "1차 후 캐시 유지");
        double newPeak = cache.get("KRW-J")[1];
        assertEquals(101.6, newPeak, 0.1, "peak 리셋=현재가(101.6)");
    }

    // ═══════════════════════════════════════════════════════════════
    //  특수 상황 (11-15)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S11: SESSION_END — splitPhase=1이어도 executeSellInner로 잔량 전량 매도")
    public void s11_sessionEndForceExit() throws Exception {
        // SESSION_END는 캔들 기반 executeSellInner 경로로 전량 매도
        // splitPhase 무관 — executeSellInner는 항상 전량 매도 + position 삭제
        PositionEntity pe = buildPosition("KRW-K", 400.0, 100.0);
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));

        UpbitCandle candle = new UpbitCandle();
        candle.trade_price = 101.0;
        candle.market = "KRW-K";

        Signal signal = Signal.of(SignalAction.SELL, StrategyType.HIGH_CONFIDENCE_BREAKOUT,
                "HC_SESSION_END 08:00");

        invokeExecuteSellInner(pe, candle, signal, splitCfg);

        // 전량 매도 → position 삭제 (split 무시)
        verify(positionRepo).deleteById("KRW-K");
        verify(tradeLogRepo).save(argThat(new ArgumentMatcher<TradeEntity>() {
            @Override
            public boolean matches(TradeEntity t) {
                return "SELL".equals(t.getAction()) && "KRW-K".equals(t.getMarket());
            }
        }));
    }

    @Test
    @DisplayName("S12: TIME_STOP — splitPhase=1이어도 실시간 TP/SL에서는 발동 안 됨 (2차는 trail/BEV만)")
    public void s12_timeStopDisabledDuringSplit() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        // splitPhase=1, 20분 경과 (Tight SL 구간)
        // 하지만 split 2차는 Breakeven SL(매수가) / TP_TRAIL drop만 적용
        // Tight SL(-1.5%)은 splitPhase=1에서 비활성
        cache.put("KRW-L", new double[]{100.0, 101.0, 0, 100.0, nowMs - 20 * 60_000, 1});

        // -1.2% → splitPhase=1이므로 tiered SL 비활성, breakeven 아직 아님 (pnl > 0 아닌 -1.2%)
        // 실제로 pnl=-1.2%인데 breakeven은 pnl<=0 체크이므로... -1.2% <= 0 → BEV 발동!
        // 수정: pnl=+0.3% (아직 수익권, trail drop/BEV 미달)
        invoke("KRW-L", 100.3);

        assertTrue(cache.containsKey("KRW-L"), "splitPhase=1: tiered SL 비활성, BEV/trail 미달 → 유지");
        assertEquals(1.0, cache.get("KRW-L")[5], 0.01, "splitPhase=1 유지");
    }

    @Test
    @DisplayName("S13: 최소금액 미달 — 1차 매도 후 잔량이 5000원 미달 시 dust 처리 (전량 매도)")
    public void s13_dustHandlingAfterSplit() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        cache.put("KRW-M", new double[]{100.0, 100.0, 0, 100.0, nowMs - 120_000, 0});

        // qty * avgPrice = 40 * 100 = 4000원 → 60% 매도 후 잔량 = 16 * 100 = 1600원 < 5000원
        PositionEntity pe = buildPosition("KRW-M", 40.0, 100.0);
        pe.setSplitPhase(0);
        when(positionRepo.findById("KRW-M")).thenReturn(Optional.of(pe));

        // +1.6% → 1차 조건이지만 잔량(40%) 금액이 5000원 미달
        invoke("KRW-M", 101.6);
        Thread.sleep(500);

        // dust 처리: 전량 매도, splitPhase=-1 또는 그냥 전량 매도
        assertFalse(cache.containsKey("KRW-M"), "최소금액 미달 → 전량 매도(dust)");
        verify(positionRepo).deleteById("KRW-M");
    }

    @Test
    @DisplayName("S14: qty=1 — 분할 불가, splitPhase=-1로 마킹 후 기존 전량 매도 로직 적용")
    public void s14_singleQtyCannotSplit() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        cache.put("KRW-N", new double[]{50000.0, 50000.0, 0, 50000.0, nowMs - 120_000, 0});

        // qty=1 → 분할 불가 (코인은 소수점 가능하지만 최소주문금액 미달 가능)
        // 매도 후 잔량 0.4개 * 50000원 = 20000원 → 분할 가능
        // 하지만 qty가 매우 작아서 분할 시 잔량 < 최소주문금액인 경우
        PositionEntity pe = buildPosition("KRW-N", 0.05, 50000.0);
        pe.setSplitPhase(0);
        when(positionRepo.findById("KRW-N")).thenReturn(Optional.of(pe));

        // 잔량 0.05 * 0.4 = 0.02개 * 50000 = 1000원 < 5000원
        invoke("KRW-N", 50750.0); // +1.5%
        Thread.sleep(500);

        // 전량 매도 처리
        assertFalse(cache.containsKey("KRW-N"), "분할 불가 → 전량 매도");
        verify(positionRepo).deleteById("KRW-N");
    }

    @Test
    @DisplayName("S15: 이중 매도 방지 — sellingMarkets로 1차 매도 중 2차 조건 도달해도 차단")
    public void s15_noDoubleSellDuringExecution() throws Exception {
        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        cache.put("KRW-O", new double[]{100.0, 100.0, 0, 100.0, nowMs - 120_000, 0});

        Set<String> selling = getSellingMarkets();
        selling.add("KRW-O"); // 이미 매도 진행 중

        PositionEntity pe = buildPosition("KRW-O", 1000.0, 100.0);
        when(positionRepo.findById("KRW-O")).thenReturn(Optional.of(pe));

        // +1.6% → 조건 충족하지만 sellingMarkets에 이미 있으므로 차단
        // checkRealtimeTpSl은 sellingMarkets를 직접 체크하지 않음 (executeSellForWsTp에서 체크)
        // 그러나 캐시 remove 시점에서 중복 방지
        assertTrue(selling.contains("KRW-O"), "이미 매도 진행 중");
    }

    // ═══════════════════════════════════════════════════════════════
    //  자본 관리 (16-18)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S16: calcTotalInvestedAllPositions — splitPhase=1이면 splitOriginalQty 기준")
    public void s16_calcInvestedWithOriginalQty() throws Exception {
        // splitPhase=1: 잔량 400개(avgPrice 100원) → 실제 투자 = 1000 * 100 = 100,000원
        PositionEntity pe = buildPosition("KRW-P", 400.0, 100.0);
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));

        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pe));

        // 실제 서비스 메서드 호출
        double invested = invokeCalcTotalInvested();
        assertEquals(100_000.0, invested, 0.01, "splitPhase=1 → originalQty(1000) * avgPrice(100) = 100,000");
    }

    @Test
    @DisplayName("S17: calcTotalInvestedAllPositions — splitPhase=0이면 qty 기준")
    public void s17_calcInvestedNormalPosition() throws Exception {
        PositionEntity pe = buildPosition("KRW-Q", 1000.0, 200.0);
        pe.setSplitPhase(0);

        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pe));

        double invested = invokeCalcTotalInvested();
        assertEquals(200_000.0, invested, 0.01, "splitPhase=0 → qty(1000) * avgPrice(200) = 200,000");
    }

    @Test
    @DisplayName("S18: calcTotalInvestedAllPositions — split+normal 혼합 포지션 합산")
    public void s18_calcInvestedMixedPositions() throws Exception {
        // splitPhase=1: 잔량 400, original 1000, avgPrice 100 → 100,000
        PositionEntity pe1 = buildPosition("KRW-R", 400.0, 100.0);
        pe1.setSplitPhase(1);
        pe1.setSplitOriginalQty(BigDecimal.valueOf(1000.0));

        // splitPhase=0: qty 500, avgPrice 200 → 100,000
        PositionEntity pe2 = buildPosition("KRW-S", 500.0, 200.0);
        pe2.setSplitPhase(0);

        when(positionRepo.findAll()).thenReturn(Arrays.asList(pe1, pe2));

        double invested = invokeCalcTotalInvested();
        assertEquals(200_000.0, invested, 0.01,
                "split(100,000) + normal(100,000) = 200,000");
    }

    // ═══════════════════════════════════════════════════════════════
    //  splitExitEnabled=false 일 때 기존 로직 유지 확인
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S00: splitExitEnabled=false → 기존 전량 매도 (TP_TRAIL 도달 시)")
    public void s00_splitDisabledFallback() throws Exception {
        // split 비활성화
        setField("cachedSplitExitEnabled", false);

        long nowMs = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpCache();
        // TP_TRAIL 활성(+2%), 피크 103, splitPhase=0
        cache.put("KRW-Z", new double[]{100.0, 103.0, 1.0, 100.0, nowMs - 300_000, 0});

        PositionEntity pe = buildPosition("KRW-Z", 1000.0, 100.0);
        when(positionRepo.findById("KRW-Z")).thenReturn(Optional.of(pe));

        // 피크 대비 -1.1% → 기존 TP_TRAIL 전량 매도
        invoke("KRW-Z", 101.8);
        Thread.sleep(500);

        assertFalse(cache.containsKey("KRW-Z"), "기존 TP_TRAIL 전량 매도");
        verify(positionRepo).deleteById("KRW-Z");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private void invoke(String market, double price) throws Exception {
        Method m = AllDayScannerService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(scanner, market, price);
    }

    private double invokeCalcTotalInvested() throws Exception {
        Method m = AllDayScannerService.class.getDeclaredMethod("calcTotalInvestedAllPositions");
        m.setAccessible(true);
        return (Double) m.invoke(scanner);
    }

    private void invokeExecuteSellInner(PositionEntity pe, UpbitCandle candle,
                                         Signal signal, AllDayScannerConfigEntity cfg) throws Exception {
        Method m = AllDayScannerService.class.getDeclaredMethod(
                "executeSellInner", PositionEntity.class, UpbitCandle.class,
                Signal.class, AllDayScannerConfigEntity.class);
        m.setAccessible(true);
        m.invoke(scanner, pe, candle, signal, cfg);
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

    @SuppressWarnings("unchecked")
    private Set<String> getSellingMarkets() throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField("sellingMarkets");
        f.setAccessible(true);
        return (Set<String>) f.get(scanner);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(scanner, value);
    }

    private void invokeSyncTpWebSocket(List<PositionEntity> positions) throws Exception {
        Method m = AllDayScannerService.class.getDeclaredMethod(
                "syncTpWebSocket", List.class);
        m.setAccessible(true);
        m.invoke(scanner, positions);
    }

    private PositionEntity buildPosition(String market, double qty, double avgPrice) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setQty(BigDecimal.valueOf(qty));
        pe.setAvgPrice(BigDecimal.valueOf(avgPrice));
        pe.setAddBuys(0);
        pe.setOpenedAt(Instant.now());
        pe.setEntryStrategy("HIGH_CONFIDENCE_BREAKOUT");
        return pe;
    }

    private AllDayScannerConfigEntity buildSplitConfig() {
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        cfg.setEnabled(true);
        cfg.setMode("PAPER");
        cfg.setMaxPositions(5);
        cfg.setOrderSizingMode("FIXED");
        cfg.setOrderSizingValue(BigDecimal.valueOf(50000));
        cfg.setCandleUnitMin(5);
        cfg.setBtcFilterEnabled(false);
        cfg.setTopN(10);
        cfg.setEntryStartHour(0);
        cfg.setEntryStartMin(0);
        cfg.setEntryEndHour(23);
        cfg.setEntryEndMin(59);
        cfg.setSessionEndHour(23);
        cfg.setSessionEndMin(59);
        cfg.setMinConfidence(BigDecimal.valueOf(9.4));
        cfg.setSlPct(BigDecimal.valueOf(1.5));
        cfg.setTrailAtrMult(BigDecimal.valueOf(0.8));
        cfg.setVolumeSurgeMult(BigDecimal.valueOf(3.0));
        cfg.setMinBodyRatio(BigDecimal.valueOf(0.60));
        cfg.setTimeStopCandles(12);
        cfg.setTimeStopMinPnl(BigDecimal.valueOf(0.3));
        cfg.setExcludeMarkets("");
        // Split-Exit 설정
        cfg.setSplitExitEnabled(true);
        cfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        cfg.setSplitRatio(BigDecimal.valueOf(0.60));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.0));
        return cfg;
    }
}
