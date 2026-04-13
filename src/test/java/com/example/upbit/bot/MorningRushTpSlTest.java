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
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MorningRush SL 종합안 (1분 그레이스 + 5분 타이트닝 + 트레일링) 검증.
 *
 * positionCache 포맷: [avgPrice, qty, openedAtEpochMs, peakPrice, troughPrice]
 *
 * 동작 시나리오:
 *  - 0~60초:  1분 그레이스 — SL 무시 (TP만 작동)
 *  - 60s~5분: SL 5% (WIDE_SL_PCT)
 *  - 5분 이후: SL 3% (cachedSlPct) + 트레일링 스탑 (peak -1.5%)
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
                sharedPriceService, new SharedTradeThrottle()
        );
        setField("running", new AtomicBoolean(true));
    }

    /** position cache 등록 헬퍼 (SL 종합안 5필드 포맷: avgPrice, qty, openedAtMs, peakPrice, troughPrice). */
    private void putPosition(String market, double avgPrice, long openedAtMs) throws Exception {
        getPositionCache().put(market, new double[]{avgPrice, 1000.0, openedAtMs, avgPrice, avgPrice});
    }

    // ===== Test 1: TP 트레일링 — peak 추적 후 drop 시 매도 (그레이스 무관) =====
    // 2026-04-09 변경: 즉시 매도 → trail 매도
    @Test
    public void testTpFiresEvenInGracePeriod() throws Exception {
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // 매수 직후 (0초 경과)
        putPosition("KRW-TEST", 100.0, System.currentTimeMillis());

        // pnl=+5% → trail 활성화 (peak=105), 아직 drop 없음 → 매도 안 함
        invokeCheckRealtimeTpSl("KRW-TEST", 105.0);
        assertTrue(getPositionCache().containsKey("KRW-TEST"),
                "trail 활성화됨, drop 없음 → 매도 안 함");

        // peak에서 -1.1% drop → TP_TRAIL 매도 (그레이스 무관)
        invokeCheckRealtimeTpSl("KRW-TEST", 103.8);  // 105 × 0.989 = 103.845
        assertFalse(getPositionCache().containsKey("KRW-TEST"),
                "peak 105에서 -1.1% drop → TP_TRAIL 매도 (그레이스 기간에도 발동)");
    }

    // ===== Test 2: 1분 그레이스 — SL 무시 =====
    @Test
    public void testGracePeriodIgnoresSL() throws Exception {
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // 매수 직후 30초 경과 (그레이스 60초 이내)
        long openedAt = System.currentTimeMillis() - 30_000L;
        putPosition("KRW-TEST", 100.0, openedAt);

        // pnl=-10% (5% SL 훨씬 초과) → 그레이스로 무시
        invokeCheckRealtimeTpSl("KRW-TEST", 90.0);

        assertTrue(getPositionCache().containsKey("KRW-TEST"),
                "1분 그레이스 동안 SL 무시되어야 함");
    }

    // ===== Test 3: 60s~5분 구간 — SL 5% (WIDE_SL_PCT) =====
    @Test
    public void testWideSlAt2MinElapsed() throws Exception {
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // 매수 후 2분 경과 (60s~5분 구간)
        long openedAt = System.currentTimeMillis() - 120_000L;
        putPosition("KRW-TEST", 100.0, openedAt);

        // pnl=-4% (5% SL 미달) → 유지
        invokeCheckRealtimeTpSl("KRW-TEST", 96.0);
        assertTrue(getPositionCache().containsKey("KRW-TEST"),
                "60s~5분 구간에서 -4%는 SL 5% 미달이라 유지되어야 함");

        // pnl=-6% (5% SL 초과) → 매도
        invokeCheckRealtimeTpSl("KRW-TEST", 94.0);
        assertFalse(getPositionCache().containsKey("KRW-TEST"),
                "60s~5분 구간에서 -6%는 SL 5% 초과라 매도되어야 함");
    }

    // ===== Test 4: 5분 이후 — SL 3% 타이트닝 =====
    @Test
    public void testTightSlAfter5Min() throws Exception {
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // 매수 후 6분 경과 (5분 이후 구간)
        long openedAt = System.currentTimeMillis() - 360_000L;
        putPosition("KRW-TEST", 100.0, openedAt);

        // pnl=-2% (3% SL 미달) → 유지
        invokeCheckRealtimeTpSl("KRW-TEST", 98.0);
        assertTrue(getPositionCache().containsKey("KRW-TEST"),
                "5분 이후 -2%는 SL 3% 미달이라 유지되어야 함");

        // pnl=-4% (3% SL 초과) → 매도
        invokeCheckRealtimeTpSl("KRW-TEST", 96.0);
        assertFalse(getPositionCache().containsKey("KRW-TEST"),
                "5분 이후 -4%는 SL 3% 초과라 매도되어야 함");
    }

    // ===== Test 5: 5분 이후 TP trail 동작 =====
    // 2026-04-09 변경: 즉시 매도 → trail 매도
    @Test
    public void testSimpleTpAfter5Min() throws Exception {
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // 매수 후 6분 경과
        long openedAt = System.currentTimeMillis() - 360_000L;
        putPosition("KRW-TEST", 100.0, openedAt);

        // 가격 상승 (+1%) → TP 2.0% 미달, trail 미활성 → 유지
        invokeCheckRealtimeTpSl("KRW-TEST", 101.0);
        assertTrue(getPositionCache().containsKey("KRW-TEST"),
                "+1%는 TP(2.0%) 미달, trail 미활성 → 유지");

        // 가격 하락 (-1%) → trail 미활성, SL 3% 미달 → 유지
        invokeCheckRealtimeTpSl("KRW-TEST", 99.0);
        assertTrue(getPositionCache().containsKey("KRW-TEST"),
                "-1%는 SL 3% 미달, trail 미활성 → 유지");

        // 가격 상승 (+2.5%) → trail 활성화 (peak=102.5), 아직 drop 없음 → 유지
        invokeCheckRealtimeTpSl("KRW-TEST", 102.5);
        assertTrue(getPositionCache().containsKey("KRW-TEST"),
                "+2.5%는 trail 활성화, drop 없음 → 유지 (즉시 매도 안 함)");

        // peak에서 -1.1% drop → TP_TRAIL 매도
        invokeCheckRealtimeTpSl("KRW-TEST", 101.37);  // 102.5 × 0.989
        assertFalse(getPositionCache().containsKey("KRW-TEST"),
                "peak 102.5에서 -1.1% drop → TP_TRAIL 매도");
    }

    // ===== Test 6: TP/SL 미도달 → 포지션 유지 =====
    @Test
    public void testNoActionWhenWithinRange() throws Exception {
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // 매수 후 2분 경과 (그레이스 끝, 5분 전)
        long openedAt = System.currentTimeMillis() - 120_000L;
        putPosition("KRW-TEST", 100.0, openedAt);

        // pnl=+1.0% → TP(2.0%) 미달, SL(5%) 미달
        invokeCheckRealtimeTpSl("KRW-TEST", 101.0);

        assertTrue(getPositionCache().containsKey("KRW-TEST"),
                "TP/SL 미도달 시 포지션 유지되어야 함");
    }

    // ===== Test 7: 포지션 없는 마켓 → 무시 =====
    @Test
    public void testIgnoresUnknownMarket() throws Exception {
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // 캐시 비어있음
        invokeCheckRealtimeTpSl("KRW-UNKNOWN", 105.0);
        // 에러 없으면 성공
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
