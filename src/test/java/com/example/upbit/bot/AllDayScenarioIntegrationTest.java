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
 * 종일 스캐너 (AllDay) 시나리오 통합 테스트.
 *
 * 검증 시나리오:
 * 1. WS Quick TP 매도 사이클 (매수가 → +2.3% → TP 매도 → 포지션 삭제)
 * 2. SESSION_END 강제 청산 (HCB 전략 sessionEnd 처리)
 * 3. 1차 익절 후 20분 쿨다운 차단 (HourlyTradeThrottle 통합)
 *
 * (HCB 전략 자체는 HighConfidenceBreakoutStrategyTest 42개로 별도 검증됨)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AllDayScenarioIntegrationTest {

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
        ScannerLockService scannerLockService = new ScannerLockService(botConfigRepo, positionRepo, tradeLogRepo);
        scanner = new AllDayScannerService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                candleService, catalogService, liveOrders, privateClient, txTemplate,
                tickerService, sharedPriceService, new SharedTradeThrottle(), scannerLockService
        );
        setField("running", new AtomicBoolean(true));

        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        setField("scheduler", sched);

        when(txTemplate.execute(any())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object callback = invocation.getArgument(0);
                if (callback instanceof TransactionCallbackWithoutResult) {
                    ((TransactionCallbackWithoutResult) callback)
                            .doInTransaction(mock(TransactionStatus.class));
                }
                return null;
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 1: WS Quick TP 전체 사이클
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 1: 매수 → +2.5% 활성화 → 피크+3% → 피크에서 -1% → TP_TRAIL 매도")
    public void scenario1_wsQuickTpCycle() throws Exception {
        // 포지션 생성 [avgPrice=1000, peakPrice=1000, activated=0] — V129: Grace(60s) 밖으로 설정
        ConcurrentHashMap<String, double[]> cache = getTpPositionCache();
        cache.put("KRW-TEST", new double[]{1000.0, 1000.0, 0, 1000.0, System.currentTimeMillis() - 120_000L, 0, 0});

        // PAPER 모드 설정
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        cfg.setMode("PAPER");
        when(configRepo.loadOrCreate()).thenReturn(cfg);

        PositionEntity pe = new PositionEntity();
        pe.setMarket("KRW-TEST");
        pe.setQty(10.0);
        pe.setAvgPrice(1000.0);
        when(positionRepo.findById("KRW-TEST")).thenReturn(Optional.of(pe));

        // 가격 흐름 시뮬레이션
        // 1. +1% (활성화 미달) → 유지
        invokeCheckRealtimeTp("KRW-TEST", 1010.0);
        assertTrue(cache.containsKey("KRW-TEST"), "+1%는 활성화 미달, 유지");

        // 2. +2.5% → 활성화 (TP_TRAIL_ACTIVATE_PCT=2.0%)
        invokeCheckRealtimeTp("KRW-TEST", 1025.0);
        assertTrue(cache.containsKey("KRW-TEST"), "활성화됐지만 아직 매도 안 함");
        assertEquals(1.0, cache.get("KRW-TEST")[2], 0.01, "활성화 플래그=1");

        // 3. +3% → 피크 갱신
        invokeCheckRealtimeTp("KRW-TEST", 1030.0);
        assertTrue(cache.containsKey("KRW-TEST"), "피크 갱신만, 아직 유지");
        assertEquals(1030.0, cache.get("KRW-TEST")[1], 0.01, "peak=1030");

        // 4. 피크(1030)에서 -1.0% = 1019.7 이하 → 1019.0 → 매도
        invokeCheckRealtimeTp("KRW-TEST", 1019.0);
        assertFalse(cache.containsKey("KRW-TEST"), "피크에서 -1% 이상 하락, TP_TRAIL 매도");

        // scheduler 매도 실행 대기
        Thread.sleep(500);

        // 트레이드 로그 저장 + 포지션 삭제 확인
        verify(tradeLogRepo, timeout(2000)).save(any(TradeEntity.class));
        verify(positionRepo, timeout(2000)).deleteById("KRW-TEST");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 2: HourlyTradeThrottle 20분 쿨다운 (BARD 사고 방지)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 2: 종일 스캐너 1차 매수 후 5분 → 재매수 차단 (20분 쿨다운)")
    public void scenario2_hourlyThrottleCooldown() throws Exception {
        // AllDayScannerService의 SharedTradeThrottle 가져오기
        Field f = AllDayScannerService.class.getDeclaredField("hourlyThrottle");
        f.setAccessible(true);
        SharedTradeThrottle throttle = (SharedTradeThrottle) f.get(scanner);

        // 1차 매수 기록
        throttle.recordBuy("KRW-BARD");

        // 즉시 재매수 시도 → 20분 쿨다운 차단
        assertFalse(throttle.canBuy("KRW-BARD"),
                "종일 스캐너도 20분 쿨다운으로 차단되어야 함");

        // 남은 대기 시간 확인 (20분 - 1초 ~ 20분)
        long wait = throttle.remainingWaitMs("KRW-BARD");
        assertTrue(wait > 19 * 60_000L && wait <= 20 * 60_000L,
                "남은 대기 약 20분, 실제: " + (wait / 60_000) + "분");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 3: 1시간 2회 제한 (3번째 차단)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 3: 종일 스캐너 1시간 2회 제한 (3번째 차단)")
    public void scenario3_hourlyLimit() throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField("hourlyThrottle");
        f.setAccessible(true);
        SharedTradeThrottle throttle = (SharedTradeThrottle) f.get(scanner);
        HourlyTradeThrottle inner = throttle.getDelegate();

        // 가짜 1시간 2회 시뮬레이션 (history에 직접 추가, 25분/21분 전)
        Field histF = HourlyTradeThrottle.class.getDeclaredField("tradeHistory");
        histF.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, java.util.Deque<Long>> history =
                (ConcurrentHashMap<String, java.util.Deque<Long>>) histF.get(inner);

        java.util.Deque<Long> deque = new java.util.ArrayDeque<>();
        long now = System.currentTimeMillis();
        deque.addLast(now - 25 * 60_000L); // 25분 전
        deque.addLast(now - 21 * 60_000L); // 21분 전 (쿨다운 통과)
        history.put("KRW-PUMP", deque);

        // 3번째 매수 시도: 21분 전이 가장 최근 → 쿨다운(20분) 통과, 1시간 2회 제한 차단
        assertFalse(throttle.canBuy("KRW-PUMP"),
                "1시간 2회 제한으로 3번째 매수 차단");
    }

    // ═══════════════════════════════════════════════════════════
    //  V129 시나리오 4: 전체 사이클 — armed → SPLIT_1ST → 쿨다운 차단 → 쿨다운 만료 → SPLIT_2ND_TRAIL
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("V129-4: AllDay SPLIT_1ST 체결 → 60s 쿨다운 SPLIT_2ND_TRAIL 차단 → 만료 후 발동")
    public void scenarioV129_4_splitFirstThenCooldownThenSecondTrail() throws Exception {
        enableV129Split();

        // PAPER 모드 + Position mock (executor 내부에서 findById 호출됨)
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        cfg.setMode("PAPER");
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        PositionEntity pe = new PositionEntity();
        pe.setMarket("KRW-TEST");
        pe.setAvgPrice(java.math.BigDecimal.valueOf(100.0));
        pe.setQty(java.math.BigDecimal.valueOf(10.0));
        when(positionRepo.findById("KRW-TEST")).thenReturn(Optional.of(pe));

        long now = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpPositionCache();
        // openedAt 120초 전 (Grace 30s 밖)
        cache.put("KRW-TEST", new double[]{100.0, 100.0, 0, 100.0, now - 120_000L, 0, 0});

        // 1) armed: +1.5%
        invokeCheckRealtimeTp("KRW-TEST", 101.5);
        double[] pos = cache.get("KRW-TEST");
        assertEquals(1.0, pos[6], 0.01, "armed=1");

        // 2) peak +2%
        invokeCheckRealtimeTp("KRW-TEST", 102.0);
        assertEquals(102.0, pos[1], 0.01, "peak=102");

        // 3) drop 0.69% >= 0.5% → SPLIT_1ST (async 부분 매도 — 캐시는 executor 내부에서 갱신됨)
        invokeCheckRealtimeTp("KRW-TEST", 101.3);
        // splitPhase=1 로 바뀌는 것은 executor가 DB update 후 → race 회피 위해 직접 세팅
        getSplit1stExecMap().put("KRW-TEST", now);
        pos = cache.get("KRW-TEST");
        pos[5] = 1.0;   // splitPhase=1
        pos[1] = 101.3; // peak 리셋
        pos[6] = 0.0;   // armed 리셋
        getSellingMarkets().clear();  // executor async 가드 회피

        // 4) 쿨다운 중 peak 상승 + 1.5% drop → SPLIT_2ND_TRAIL 차단
        invokeCheckRealtimeTp("KRW-TEST", 102.5);  // 새 peak
        invokeCheckRealtimeTp("KRW-TEST", 101.0);  // drop (102.5-101)/102.5 = 1.46% > 1.2%
        assertTrue(cache.containsKey("KRW-TEST"),
                "쿨다운 활성 → SPLIT_2ND_TRAIL 차단");

        // 5) 쿨다운 만료 (65초 전으로 변경)
        getSplit1stExecMap().put("KRW-TEST", now - 65_000L);

        // 6) 동일 drop 재현 → SPLIT_2ND_TRAIL 발동 (전량 매도 → 캐시 제거 동기)
        invokeCheckRealtimeTp("KRW-TEST", 101.0);
        assertFalse(cache.containsKey("KRW-TEST"),
                "쿨다운 만료 → SPLIT_2ND_TRAIL 전량 매도");
    }

    // ═══════════════════════════════════════════════════════════
    //  V129 시나리오 5: 쿨다운 중 급락 → SL_WIDE 바이패스
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("V129-5: AllDay 쿨다운 중 -3.5% 급락 → SL_WIDE 발동 (쿨다운 바이패스)")
    public void scenarioV129_5_splitFirstThenCrashSlBypassesCooldown() throws Exception {
        enableV129Split();
        // WideSlPct 3.0 유지 (AllDay 기본)

        long now = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpPositionCache();
        // 이미 splitPhase=1 상태 재현 (SPLIT_1ST 체결 직후, 쿨다운 active)
        cache.put("KRW-TEST", new double[]{100.0, 101.3, 0, 100.0, now - 120_000L, 1, 0});
        getSplit1stExecMap().put("KRW-TEST", now - 10_000L);  // 10초 전 체결 (쿨다운 활성)

        // -3.5% 급락 → SL_WIDE (쿨다운은 SL 차단 안 함)
        invokeCheckRealtimeTp("KRW-TEST", 96.5);
        assertFalse(cache.containsKey("KRW-TEST"),
                "쿨다운 중에도 SL_WIDE는 발동");
    }

    // ═══════════════════════════════════════════════════════════
    //  V129 시나리오 6: 쿨다운 경계 — 29s(차단) vs 31s(허용) (쿨다운 30s일 때)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("V129-6: AllDay 쿨다운 경계 — 29초 차단, 31초 허용 (쿨다운 30s 가정)")
    public void scenarioV129_6_cooldownBoundary() throws Exception {
        enableV129Split();
        setField("cachedSplit1stCooldownMs", 30_000L);  // 30초 쿨다운

        long now = System.currentTimeMillis();
        ConcurrentHashMap<String, double[]> cache = getTpPositionCache();
        cache.put("KRW-TEST", new double[]{100.0, 102.0, 0, 100.0, now - 120_000L, 1, 0});

        // 29초 전 체결 → 쿨다운 활성
        getSplit1stExecMap().put("KRW-TEST", now - 29_000L);
        invokeCheckRealtimeTp("KRW-TEST", 100.5);  // drop 1.47% > 1.2%
        assertTrue(cache.containsKey("KRW-TEST"),
                "29초 → 쿨다운 활성 → 차단");

        // 31초 전 체결 → 쿨다운 만료
        getSplit1stExecMap().put("KRW-TEST", now - 31_000L);
        invokeCheckRealtimeTp("KRW-TEST", 100.5);
        assertFalse(cache.containsKey("KRW-TEST"),
                "31초 → 쿨다운 만료 → SPLIT_2ND_TRAIL");
    }

    /** V129 Split 기본값 활성화 (AllDay 기본 grace 30s/wide 15min/wide SL 3%/tight SL 1.5%). */
    private void enableV129Split() throws Exception {
        setField("cachedSplitExitEnabled", true);
        setField("cachedSplitTpPct", 1.5);
        setField("cachedSplitRatio", 0.60);
        setField("cachedSplit1stTrailDrop", 0.5);
        setField("cachedTrailDropAfterSplit", 1.2);
        setField("cachedSplit1stCooldownMs", 60_000L);
        setField("cachedGracePeriodMs", 30_000L);
        setField("cachedWidePeriodMs", 15 * 60_000L);
        setField("cachedWideSlPct", 3.0);
        setField("cachedTightSlPct", 1.5);
        setField("cachedTpTrailActivatePct", 2.0);
        setField("cachedTpTrailDropPct", 1.0);
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private void setField(String name, Object value) throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(scanner, value);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getTpPositionCache() throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField("tpPositionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(scanner);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Long> getSplit1stExecMap() throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField("split1stExecutedAtMap");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Long>) f.get(scanner);
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<String> getSellingMarkets() throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField("sellingMarkets");
        f.setAccessible(true);
        return (java.util.Set<String>) f.get(scanner);
    }

    private void invokeCheckRealtimeTp(String market, double price) throws Exception {
        Method m = AllDayScannerService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(scanner, market, price);
    }
}
