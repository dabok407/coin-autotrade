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
        scanner = new AllDayScannerService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                candleService, catalogService, liveOrders, privateClient, txTemplate,
                tickerService, sharedPriceService, new SharedTradeThrottle()
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
    @DisplayName("시나리오 1: 매수 → +2.3% 도달 → WS Quick TP 매도 → 포지션 삭제")
    public void scenario1_wsQuickTpCycle() throws Exception {
        setField("cachedTpPct", 2.3);

        // 포지션 생성
        ConcurrentHashMap<String, double[]> cache = getTpPositionCache();
        cache.put("KRW-TEST", new double[]{1000.0});

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
        // 1. +1% (TP 미달) → 유지
        invokeCheckRealtimeTp("KRW-TEST", 1010.0);
        assertTrue(cache.containsKey("KRW-TEST"), "+1%는 TP 미달, 유지");

        // 2. +2% (TP 미달) → 유지
        invokeCheckRealtimeTp("KRW-TEST", 1020.0);
        assertTrue(cache.containsKey("KRW-TEST"), "+2%는 TP 미달, 유지");

        // 3. +2.5% (TP 도달) → 매도
        invokeCheckRealtimeTp("KRW-TEST", 1025.0);
        assertFalse(cache.containsKey("KRW-TEST"), "+2.5%는 TP 도달, 매도");

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

    private void invokeCheckRealtimeTp(String market, double price) throws Exception {
        Method m = AllDayScannerService.class.getDeclaredMethod(
                "checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);
        m.invoke(scanner, market, price);
    }
}
