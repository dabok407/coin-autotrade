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
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MorningRush TP/SL 실시간 체크 검증.
 * - entryPhaseComplete=false → checkRealtimeTpSl 스킵
 * - entryPhaseComplete=true → checkRealtimeTpSl 동작
 * - TP 도달 시 매도 스케줄
 * - SL 도달 시 매도 스케줄
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class MorningRushTpSlTest {

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
                sharedPriceService
        );
        // Set running=true
        setField("running", new AtomicBoolean(true));
    }

    // ===== Test: entryPhaseComplete=false여도 TP/SL 즉시 동작 =====
    @Test
    public void testTpWorksEvenDuringEntryPhase() throws Exception {
        setField("entryPhaseComplete", false);

        // 포지션 캐시에 매수 포지션 등록
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-TEST", new double[]{100.0, 1000.0});

        // cachedTpPct=2.0, cachedSlPct=3.0
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // TP 조건 (pnl=+5%) → entryPhaseComplete=false여도 즉시 매도
        invokeCheckRealtimeTpSl("KRW-TEST", 105.0);

        // 포지션이 캐시에서 제거되어야 함 (매도 트리거)
        assertFalse(cache.containsKey("KRW-TEST"),
                "Entry Phase여도 포지션 있으면 TP/SL 즉시 체크해야 함");
    }

    // ===== Test: entryPhaseComplete=true + TP 도달 → 매도 트리거 =====
    @Test
    public void testTpTriggeredWhenEntryPhaseComplete() throws Exception {
        setField("entryPhaseComplete", true);

        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-TEST", new double[]{100.0, 1000.0});

        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // TP 조건: +3% >= 2.0% TP → 매도 트리거
        invokeCheckRealtimeTpSl("KRW-TEST", 103.0);

        // 포지션이 캐시에서 제거되어야 함 (매도 스케줄됨)
        assertFalse(cache.containsKey("KRW-TEST"),
                "TP 도달 시 포지션 캐시에서 제거되어야 함");
    }

    // ===== Test: entryPhaseComplete=true + SL 도달 → 매도 트리거 =====
    @Test
    public void testSlTriggeredWhenEntryPhaseComplete() throws Exception {
        setField("entryPhaseComplete", true);

        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-TEST", new double[]{100.0, 1000.0});

        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // SL 조건: -4% <= -3.0% SL → 매도 트리거
        invokeCheckRealtimeTpSl("KRW-TEST", 96.0);

        assertFalse(cache.containsKey("KRW-TEST"),
                "SL 도달 시 포지션 캐시에서 제거되어야 함");
    }

    // ===== Test: TP/SL 미도달 → 포지션 유지 =====
    @Test
    public void testNoActionWhenWithinRange() throws Exception {
        setField("entryPhaseComplete", true);

        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("KRW-TEST", new double[]{100.0, 1000.0});

        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // pnl=+1.0% → TP(2.0%) 미도달, SL(-3.0%) 미도달
        invokeCheckRealtimeTpSl("KRW-TEST", 101.0);

        assertTrue(cache.containsKey("KRW-TEST"),
                "TP/SL 미도달 시 포지션 유지되어야 함");
    }

    // ===== Test: 포지션 없는 마켓 → 무시 =====
    @Test
    public void testIgnoresUnknownMarket() throws Exception {
        setField("entryPhaseComplete", true);

        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        // KRW-TEST 없음

        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // 아무 에러 없이 스킵
        invokeCheckRealtimeTpSl("KRW-UNKNOWN", 105.0);
        // 아무 일도 안 일어남 - 에러 없으면 성공
    }

    // ===== Helpers =====

    private void setField(String name, Object value) throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(scanner, value);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getPositionCache() throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(scanner);
    }

    private void invokeCheckRealtimeTpSl(String market, double price) throws Exception {
        Method m = MorningRushScannerService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(scanner, market, price);
    }
}
