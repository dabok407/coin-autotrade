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
        // V110: 기존 테스트는 drop 1.0% 기준으로 작성됨
        setField("cachedTpTrailDropPct", 1.0);
    }

    /** position cache 등록 헬퍼 (SL 종합안 5필드 포맷: avgPrice, qty, openedAtMs, peakPrice, troughPrice). */
    private void putPosition(String market, double avgPrice, long openedAtMs) throws Exception {
        getPositionCache().put(market, new double[]{avgPrice, 1000.0, openedAtMs, avgPrice, avgPrice, 0, 0});
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

    // ===== V110: TP_TRAIL drop 1.5% 기준 테스트 =====

    @Test
    public void testTpTrailDrop15Pct_notTriggeredAt1Pct() throws Exception {
        setField("cachedTpPct", 2.0);
        setField("cachedTpTrailDropPct", 1.5);  // V110 신규 값

        long openedAt = System.currentTimeMillis() - 120_000L;
        putPosition("KRW-V110", 100.0, openedAt);

        // +3% → trail 활성화 (peak=103)
        invokeCheckRealtimeTpSl("KRW-V110", 103.0);
        assertTrue(getPositionCache().containsKey("KRW-V110"), "trail 활성화, 매도 안 함");

        // peak에서 -1.0% drop (101.97) → 1.5% 미달 → 매도 안 함
        invokeCheckRealtimeTpSl("KRW-V110", 101.97);
        assertTrue(getPositionCache().containsKey("KRW-V110"),
                "drop 1.0% < 기준 1.5% → 매도 안 함 (기존 1.0%였으면 매도됐을 것)");
    }

    @Test
    public void testTpTrailDrop15Pct_triggeredAt15Pct() throws Exception {
        setField("cachedTpPct", 2.0);
        setField("cachedTpTrailDropPct", 1.5);  // V110 신규 값

        long openedAt = System.currentTimeMillis() - 120_000L;
        putPosition("KRW-V110B", 100.0, openedAt);

        // +3% → trail 활성화 (peak=103)
        invokeCheckRealtimeTpSl("KRW-V110B", 103.0);
        assertTrue(getPositionCache().containsKey("KRW-V110B"), "trail 활성화");

        // peak에서 -1.6% drop (101.35) → 1.5% 초과 → 매도
        invokeCheckRealtimeTpSl("KRW-V110B", 101.35);
        assertFalse(getPositionCache().containsKey("KRW-V110B"),
                "drop 1.6% > 기준 1.5% → TP_TRAIL 매도");
    }

    @Test
    public void testTpTrailDrop15Pct_biggerProfitCaptured() throws Exception {
        setField("cachedTpPct", 2.0);
        setField("cachedTpTrailDropPct", 1.5);  // V110 신규 값

        long openedAt = System.currentTimeMillis() - 120_000L;
        putPosition("KRW-V110C", 100.0, openedAt);

        // +2.5% → trail 활성화
        invokeCheckRealtimeTpSl("KRW-V110C", 102.5);
        // +5% → peak 갱신
        invokeCheckRealtimeTpSl("KRW-V110C", 105.0);
        // +4% → peak 105에서 -0.95% drop → 아직 미달
        invokeCheckRealtimeTpSl("KRW-V110C", 104.0);
        assertTrue(getPositionCache().containsKey("KRW-V110C"),
                "peak 105에서 -0.95% drop → 1.5% 미달 → 유지 (큰 수익 보존)");

        // +3.3% → peak 105에서 -1.62% drop → 매도
        invokeCheckRealtimeTpSl("KRW-V110C", 103.3);
        assertFalse(getPositionCache().containsKey("KRW-V110C"),
                "peak 105에서 -1.62% drop → 매도, roi=+3.3% (기존 1.0%면 +4%에서 매도)");
    }

    // ===== Ghost placeholder (qty=0) 방어 테스트 (2026-04-18) =====
    // BUY 신호 직후 placeholder(qty=0) 상태에서 WS 틱이 들어와도
    // checkRealtimeTpSl이 조기 return 해야 한다. (유령 SPLIT/TP 시그널 방지)

    @Test
    public void testPlaceholderQtyZeroSkipped() throws Exception {
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);
        setField("cachedTpTrailDropPct", 1.5);

        // qty=0 placeholder 삽입 (실매매 체결 전 상태 시뮬레이션)
        getPositionCache().put("KRW-GHOST",
                new double[]{100.0, 0.0, System.currentTimeMillis() - 360_000L, 100.0, 100.0, 0, 0});

        // 상승 → 하락 시나리오 돌려도 매도 발동 안 해야 함
        invokeCheckRealtimeTpSl("KRW-GHOST", 105.0);
        invokeCheckRealtimeTpSl("KRW-GHOST", 90.0);

        assertTrue(getPositionCache().containsKey("KRW-GHOST"),
                "qty=0 placeholder는 매도 트리거되지 않아야 함");
    }

    @Test
    public void testPlaceholderPeakNotUpdated() throws Exception {
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);
        setField("cachedTpTrailDropPct", 1.5);

        // qty=0 placeholder 삽입 (초기 peak=100)
        double[] placeholder = new double[]{100.0, 0.0, System.currentTimeMillis() - 120_000L, 100.0, 100.0, 0, 0};
        getPositionCache().put("KRW-GHOST2", placeholder);

        // 가격 105 업데이트 시도
        invokeCheckRealtimeTpSl("KRW-GHOST2", 105.0);

        // qty=0이면 즉시 return 하므로 peak가 갱신되지 않아야 함
        double[] after = getPositionCache().get("KRW-GHOST2");
        assertEquals(100.0, after[3], 0.001,
                "qty=0 placeholder는 peak 갱신 로직 실행 안 해야 함");
    }

    @Test
    public void testNormalPositionNotAffectedByQtyCheck() throws Exception {
        // qty 체크 추가가 정상 포지션에 영향 없는지 확인
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);
        setField("cachedTpTrailDropPct", 1.5);

        // 정상 포지션 (qty=1000)
        long openedAt = System.currentTimeMillis() - 360_000L;
        putPosition("KRW-NORMAL", 100.0, openedAt);

        // +3% → trail 활성화, peak 갱신 정상 동작 확인
        invokeCheckRealtimeTpSl("KRW-NORMAL", 103.0);
        double[] pos = getPositionCache().get("KRW-NORMAL");
        assertEquals(103.0, pos[3], 0.001, "정상 포지션은 peak 갱신되어야 함");

        // peak -1.6% → 매도 정상 발동 확인
        invokeCheckRealtimeTpSl("KRW-NORMAL", 101.35);
        assertFalse(getPositionCache().containsKey("KRW-NORMAL"),
                "정상 포지션은 TP_TRAIL 매도 정상 발동 (qty 체크 영향 없음)");
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
