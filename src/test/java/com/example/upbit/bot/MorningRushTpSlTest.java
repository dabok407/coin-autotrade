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
        ScannerLockService scannerLockService = new ScannerLockService(botConfigRepo, positionRepo, tradeLogRepo);
        scanner = new MorningRushScannerService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, privateClient, txTemplate, catalogService, tickerService,
                sharedPriceService, new SharedTradeThrottle(), scannerLockService
        );
        setField("running", new AtomicBoolean(true));
        // V110: 기존 테스트는 drop 1.0% 기준으로 작성됨
        setField("cachedTpTrailDropPct", 1.0);
    }

    /** position cache 등록 헬퍼 (SL 종합안 5필드 포맷: avgPrice, qty, openedAtMs, peakPrice, troughPrice). */
    private void putPosition(String market, double avgPrice, long openedAtMs) throws Exception {
        getPositionCache().put(market, new double[]{avgPrice, 1000.0, openedAtMs, avgPrice, avgPrice, 0, 0});
    }

    // ===== Test 1 (V129): Grace 구간 확장 — TP도 차단 =====
    // V129 변경: Grace 가드가 checkRealtimeTpSl 상단에서 모든 매도 차단 (SL/TP/SPLIT 공통)
    // 이유: 매수 직후 꼬리(-2~3%) 후 대폭 반등 사례(AXL/FLOCK) 방어 — Grace 내에선 매매 유보
    @Test
    public void testTpFiresEvenInGracePeriod() throws Exception {
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // 매수 직후 (0초 경과 — Grace 내)
        putPosition("KRW-TEST", 100.0, System.currentTimeMillis());

        // pnl=+5% → peak 갱신, Grace로 매도 안 함
        invokeCheckRealtimeTpSl("KRW-TEST", 105.0);
        assertTrue(getPositionCache().containsKey("KRW-TEST"),
                "peak 갱신, Grace 내 매도 차단");

        // peak에서 -1.1% drop → V129: Grace 내이므로 TP_TRAIL도 차단
        invokeCheckRealtimeTpSl("KRW-TEST", 103.8);
        assertTrue(getPositionCache().containsKey("KRW-TEST"),
                "V129: Grace 구간 내 TP_TRAIL 차단 (꼬리 흔들기 흡수)");
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

    // ===== A안 P0 root fix (2026-04-19) — placeholder 제거 + pendingBuyMarkets + DB 재조회 =====
    // 구 버그: realtime BUY 시 positionCache에 qty=0 placeholder → checkRealtimeTpSl의
    //         qty<=0 조기 return 가드가 WS realtime TP/SL을 영구 무효화.
    // 수정:  (1) pendingBuyMarkets set으로 중복 진입 CAS 차단
    //        (2) placeholder 제거, executeBuy 완료 후 DB 재조회로 실제 qty/avg 저장
    //        (3) checkRealtimeTpSl의 qty<=0 가드 제거 (stale placeholder 없으니 불필요)

    @Test
    public void testPendingBuyMarketsFieldExists() throws Exception {
        // A안 코어: pendingBuyMarkets 필드가 반드시 Set<String>으로 존재해야 함
        Field f = MorningRushScannerService.class.getDeclaredField("pendingBuyMarkets");
        f.setAccessible(true);
        Object val = f.get(scanner);
        assertNotNull(val, "pendingBuyMarkets Set 필드 존재 필수 (A안)");
        assertTrue(val instanceof java.util.Set, "pendingBuyMarkets는 Set이어야 함 (CAS add/remove)");
    }

    @Test
    public void testCheckRealtimeTpSlNoOpWhenPositionMissing() throws Exception {
        // placeholder 제거 후: positionCache miss 시 어떤 side effect도 없어야 함
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);

        // positionCache에 entry 없음 → 호출 시 조용히 return
        assertFalse(getPositionCache().containsKey("KRW-MISSING"));
        invokeCheckRealtimeTpSl("KRW-MISSING", 150.0);
        // 자동 생성되지 않아야 함 (placeholder 제거 원칙)
        assertFalse(getPositionCache().containsKey("KRW-MISSING"),
                "positionCache 미존재 마켓에 대해 어떤 entry도 자동 생성 금지");
    }

    @Test
    public void testRealtimeTpWorksWhenDbRereadFallsBackToQtyZero() throws Exception {
        // A안의 핵심 회귀 방어 테스트 — 바로 오늘의 P0 버그 재현/해결 시나리오.
        // executeBuy 후 DB 재조회 실패로 qty=0 fallback이 들어가더라도,
        // avgPrice=fPrice(>0) 이면 WS realtime TP/SL이 정상 동작해야 한다.
        // (구 버그: qty<=0 가드 때문에 영구 무효화 → 오늘 MR 사고 원인)
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 3.0);
        setField("cachedTpTrailDropPct", 1.5);

        // DB 재조회 실패 fallback path 상태 직접 재현:
        //   [avgPrice=fPrice, qty=0, openedAt=now-6분, peak=avg, trough=avg, phase=0, armed=0]
        long openedAt = System.currentTimeMillis() - 360_000L;
        getPositionCache().put("KRW-FALLBACK",
                new double[]{100.0, 0.0, openedAt, 100.0, 100.0, 0, 0});

        // +5% 상승 → peak 105 갱신 (이전 버그에선 qty<=0 가드로 이 갱신이 안 됐음)
        invokeCheckRealtimeTpSl("KRW-FALLBACK", 105.0);
        double[] after = getPositionCache().get("KRW-FALLBACK");
        assertNotNull(after, "엔트리 유지");
        assertEquals(105.0, after[3], 0.001,
                "qty=0 fallback에서도 peak 갱신 되어야 함 (A안 qty 가드 제거)");

        // peak 105 대비 -1.6% drop → TP_TRAIL 정상 매도 (이전 버그에선 무효화)
        invokeCheckRealtimeTpSl("KRW-FALLBACK", 103.3);
        assertFalse(getPositionCache().containsKey("KRW-FALLBACK"),
                "qty=0 fallback에서도 TP_TRAIL 정상 발동 (오늘 P0 사고 회귀 방어)");
    }

    @Test
    public void testRealtimeSlWorksAfterDbRereadFallback() throws Exception {
        // Split-Exit 경로도 동일하게 qty=0 fallback에서 동작해야 함
        setField("cachedTpPct", 2.0);
        setField("cachedSlPct", 2.5);
        setField("cachedSplitExitEnabled", true);
        setField("cachedSplitTpPct", 1.5);
        setField("cachedSplit1stTrailDrop", 0.65);
        setField("cachedSplitRatio", 0.5);
        // V130: Trail Ladder 비활성 (기존 단일값 테스트 유지)
        setField("cachedTrailLadderEnabled", false);

        // qty=0 fallback entry (DB 재조회 실패 상태)
        long openedAt = System.currentTimeMillis() - 360_000L;
        getPositionCache().put("KRW-SPLITFB",
                new double[]{100.0, 0.0, openedAt, 100.0, 100.0, 0, 0});

        // +2% → armed (splitTpPct=1.5 초과)
        invokeCheckRealtimeTpSl("KRW-SPLITFB", 102.0);
        double[] armed = getPositionCache().get("KRW-SPLITFB");
        assertNotNull(armed, "armed 시점에도 엔트리 유지");
        assertEquals(1.0, armed[6], 0.001, "SPLIT_1ST armed 되어야 함 (qty=0 fallback에서도)");

        // peak 102 대비 -0.70% drop (0.65% 이상) → SPLIT_1ST 발동
        invokeCheckRealtimeTpSl("KRW-SPLITFB", 101.29);
        // splitPhase 전이 or 매도 트리거 중 하나 발생해야 함
        double[] afterSplit = getPositionCache().get("KRW-SPLITFB");
        boolean splitFired = afterSplit == null || (afterSplit.length >= 6 && afterSplit[5] >= 1.0);
        assertTrue(splitFired, "qty=0 fallback에서도 SPLIT_1ST 정상 발동 (오늘 P0 회귀 방어)");
    }

    @Test
    public void testPendingBuyMarketsAtomicCasOnlyOnce() throws Exception {
        // pendingBuyMarkets.add() CAS — 같은 마켓 2번째 add는 false 반환해야 (동시 WS 틱 방어)
        Field f = MorningRushScannerService.class.getDeclaredField("pendingBuyMarkets");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> pending = (java.util.Set<String>) f.get(scanner);

        assertTrue(pending.add("KRW-RACE"), "첫 add는 true (claim 성공)");
        assertFalse(pending.add("KRW-RACE"), "두번째 add는 false (동시 진입 차단)");

        // 정리 후 다시 add 가능해야 함
        pending.remove("KRW-RACE");
        assertTrue(pending.add("KRW-RACE"), "정리 후에는 다시 claim 가능");
        pending.remove("KRW-RACE");
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
