package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.SharedPriceService;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.upbit.UpbitPrivateClient;

import org.junit.jupiter.api.BeforeEach;
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
 * AllDay WebSocket TP 통합 검증.
 * - 포지션 캐시 동기화 → WebSocket 가격 수신 시뮬레이션 → TP 매도 실행
 * - cachedTpPct 값 검증 (DB에서 읽는지 확인)
 * - TP 미도달 → 매도 안 됨
 * - TP 도달 → 매도 실행 + 포지션 삭제
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AllDayWsTpTest {

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

        // scheduler 초기화 (매도 실행에 필요)
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        setField("scheduler", sched);

        // txTemplate mock
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

    // ===== Test: cachedTpPct가 DB에서 정확히 읽히는지 =====
    @Test
    public void testCachedTpPctFromDb() throws Exception {
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        cfg.setQuickTpPct(BigDecimal.valueOf(2.3));
        when(configRepo.loadOrCreate()).thenReturn(cfg);

        // syncTpWebSocket 호출
        List<PositionEntity> positions = new ArrayList<PositionEntity>();
        invokeSyncTpWebSocket(positions);

        double cachedTpPct = (double) getField("cachedTpPct");
        assertEquals(2.3, cachedTpPct, 0.01,
                "cachedTpPct가 DB의 quick_tp_pct(2.3)에서 읽혀야 함");
    }

    // ===== Test: TP 도달 → 포지션 캐시 제거 + 매도 스케줄 =====
    @Test
    public void testTpTriggered() throws Exception {
        setField("cachedTpPct", 2.3);

        // 포지션 캐시에 등록
        ConcurrentHashMap<String, double[]> cache = getTpPositionCache();
        cache.put("KRW-TEST", new double[]{100.0});

        // PAPER 매도용 mock
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        cfg.setMode("PAPER");
        when(configRepo.loadOrCreate()).thenReturn(cfg);

        PositionEntity pe = new PositionEntity();
        pe.setMarket("KRW-TEST");
        pe.setQty(1000.0);
        pe.setAvgPrice(100.0);
        when(positionRepo.findById("KRW-TEST")).thenReturn(Optional.of(pe));

        // +2.5% 도달 → TP(2.3%) 초과
        invokeCheckRealtimeTp("KRW-TEST", 102.5);

        // 캐시에서 즉시 제거
        assertFalse(cache.containsKey("KRW-TEST"),
                "TP 도달 시 포지션 캐시에서 즉시 제거되어야 함");

        // scheduler에서 매도 실행 대기
        Thread.sleep(500);

        // trade_log 저장 + position 삭제 확인
        verify(tradeLogRepo, timeout(2000)).save(any(TradeEntity.class));
        verify(positionRepo, timeout(2000)).deleteById("KRW-TEST");
    }

    // ===== Test: TP 미도달 → 포지션 유지 =====
    @Test
    public void testTpNotReached() throws Exception {
        setField("cachedTpPct", 2.3);

        ConcurrentHashMap<String, double[]> cache = getTpPositionCache();
        cache.put("KRW-TEST", new double[]{100.0});

        // +1.5% → TP(2.3%) 미도달
        invokeCheckRealtimeTp("KRW-TEST", 101.5);

        assertTrue(cache.containsKey("KRW-TEST"),
                "TP 미도달 시 포지션 캐시에 유지되어야 함");
    }

    // ===== Test: 포지션 없는 마켓 → 무시 =====
    @Test
    public void testIgnoresUnknownMarket() throws Exception {
        setField("cachedTpPct", 2.3);
        // 캐시 비어있음
        invokeCheckRealtimeTp("KRW-UNKNOWN", 105.0);
        // 에러 없으면 성공
    }

    // ===== Test: 포지션 캐시 동기화 (새 포지션 추가, 삭제된 포지션 제거) =====
    @Test
    public void testPositionCacheSync() throws Exception {
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        cfg.setQuickTpPct(BigDecimal.valueOf(2.3));
        when(configRepo.loadOrCreate()).thenReturn(cfg);

        ConcurrentHashMap<String, double[]> cache = getTpPositionCache();

        // 기존 캐시에 OLD 포지션
        cache.put("KRW-OLD", new double[]{50.0});

        // 현재 포지션: NEW만 있음 (OLD는 매도됨)
        List<PositionEntity> positions = new ArrayList<PositionEntity>();
        PositionEntity newPe = new PositionEntity();
        newPe.setMarket("KRW-NEW");
        newPe.setQty(100.0);
        newPe.setAvgPrice(200.0);
        positions.add(newPe);

        invokeSyncTpWebSocket(positions);

        // KRW-OLD 제거, KRW-NEW 추가
        assertFalse(cache.containsKey("KRW-OLD"),
                "매도된 포지션(OLD)은 캐시에서 제거되어야 함");
        assertTrue(cache.containsKey("KRW-NEW"),
                "새 포지션(NEW)은 캐시에 추가되어야 함");
        assertEquals(200.0, cache.get("KRW-NEW")[0], 0.01,
                "avgPrice가 정확히 캐시되어야 함");
    }

    // ===== Helpers =====

    private void setField(String name, Object value) throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(scanner, value);
    }

    private Object getField(String name) throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(scanner);
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

    private void invokeSyncTpWebSocket(List<PositionEntity> positions) throws Exception {
        Method m = AllDayScannerService.class.getDeclaredMethod(
                "syncTpWebSocket", List.class);
        m.setAccessible(true);
        m.invoke(scanner, positions);
    }
}
