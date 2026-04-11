package com.example.upbit.bot;

import com.example.upbit.market.SharedPriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpeningBreakoutDetector.
 *
 * Tests cover:
 * a) Breakout detection: 4 consecutive prices above threshold fires callback
 * b) Confirm reset: count resets when price drops below threshold
 * c) Already confirmed market: no duplicate callbacks
 * d) Multiple markets: independent tracking per market
 * e) Below threshold: no count, no callback
 * f) Integration-like: full flow with callback content verification
 * g) RSI threshold check: ScalpOpeningBreakStrategy.RSI_OVERBOUGHT == 83
 */
@ExtendWith(MockitoExtension.class)
public class OpeningBreakoutDetectorTest {

    private OpeningBreakoutDetector detector;

    // Track callbacks
    private final List<String> callbackMarkets = new ArrayList<>();
    private final List<Double> callbackPrices = new ArrayList<>();
    private final List<Double> callbackRangeHighs = new ArrayList<>();
    private final List<Double> callbackPcts = new ArrayList<>();

    private OpeningBreakoutDetector.BreakoutListener recordingListener;

    @BeforeEach
    void setUp() {
        detector = new OpeningBreakoutDetector(mock(SharedPriceService.class));
        detector.setConfirmMinIntervalMs(0); // disable 500ms interval for unit tests
        callbackMarkets.clear();
        callbackPrices.clear();
        callbackRangeHighs.clear();
        callbackPcts.clear();

        recordingListener = new OpeningBreakoutDetector.BreakoutListener() {
            @Override
            public void onBreakoutConfirmed(String market, double price, double rangeHigh, double breakoutPctActual) {
                callbackMarkets.add(market);
                callbackPrices.add(price);
                callbackRangeHighs.add(rangeHigh);
                callbackPcts.add(breakoutPctActual);
            }
        };
        detector.setListener(recordingListener);
    }

    // ─── helper: invoke private checkBreakout(String, double) ───────────────────
    private void checkBreakout(String market, double price) throws Exception {
        Method m = OpeningBreakoutDetector.class.getDeclaredMethod("checkBreakout", String.class, double.class);
        m.setAccessible(true);
        m.invoke(detector, market, price);
    }

    private void setupRange(String market, double rangeHigh) {
        Map<String, Double> map = new HashMap<>();
        map.put(market, rangeHigh);
        detector.setRangeHighMap(map);
    }

    // ─── (a) Basic breakout detection ───────────────────────────────────────────

    @Test
    @DisplayName("a) 4 consecutive breakout prices should fire callback")
    void testBasicBreakoutFiresAfterFourConsecutive() throws Exception {
        // rangeHigh=100, breakoutPct=1.0 → threshold = 101.0
        setupRange("KRW-BTC", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        // price 101.5 > 101.0 → count should increment each time
        checkBreakout("KRW-BTC", 101.5); // count=1
        assertEquals(0, callbackMarkets.size(), "No callback yet at count=1");

        checkBreakout("KRW-BTC", 101.5); // count=2
        assertEquals(0, callbackMarkets.size(), "No callback yet at count=2");

        checkBreakout("KRW-BTC", 101.5); // count=3
        assertEquals(0, callbackMarkets.size(), "No callback yet at count=3");

        checkBreakout("KRW-BTC", 101.5); // count=4 → fires
        assertEquals(1, callbackMarkets.size(), "Callback must fire at count=4");
        assertEquals("KRW-BTC", callbackMarkets.get(0));
    }

    @Test
    @DisplayName("a2) Price exactly at threshold (101.0) should count as breakout")
    void testExactThresholdCounts() throws Exception {
        setupRange("KRW-ETH", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        double threshold = 100.0 * 1.01; // 101.0

        checkBreakout("KRW-ETH", threshold); // count=1
        checkBreakout("KRW-ETH", threshold); // count=2
        checkBreakout("KRW-ETH", threshold); // count=3
        checkBreakout("KRW-ETH", threshold); // count=4

        assertEquals(1, callbackMarkets.size(), "Callback must fire when price == threshold");
    }

    // ─── (b) Confirm reset ───────────────────────────────────────────────────────

    @Test
    @DisplayName("b) Count resets to 0 when price drops below threshold; subsequent 4 hits still fire")
    void testConfirmResetWhenPriceDips() throws Exception {
        setupRange("KRW-SOL", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        checkBreakout("KRW-SOL", 101.5); // count=1
        assertEquals(0, callbackMarkets.size());

        // Price drops below threshold → count should reset
        checkBreakout("KRW-SOL", 100.5); // below 101.0 → reset to 0
        assertEquals(0, callbackMarkets.size(), "No callback after price drop");

        // Verify count is truly reset: need 4 more hits now
        checkBreakout("KRW-SOL", 101.5); // count=1
        checkBreakout("KRW-SOL", 101.5); // count=2
        checkBreakout("KRW-SOL", 101.5); // count=3
        assertEquals(0, callbackMarkets.size(), "Still no callback at count=3");
        checkBreakout("KRW-SOL", 101.5); // count=4 → fires
        assertEquals(1, callbackMarkets.size(), "Callback fires after reset + 4 new hits");
    }

    @Test
    @DisplayName("b2) Count does NOT reset if market never had any count entry")
    void testNoResetIfNeverCountedBefore() throws Exception {
        setupRange("KRW-ADA", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        // Only below-threshold prices
        checkBreakout("KRW-ADA", 100.5);
        checkBreakout("KRW-ADA", 100.5);
        // Then above threshold 4 times
        checkBreakout("KRW-ADA", 101.5);
        checkBreakout("KRW-ADA", 101.5);
        checkBreakout("KRW-ADA", 101.5);
        checkBreakout("KRW-ADA", 101.5);

        assertEquals(1, callbackMarkets.size(), "Callback must fire after 4 hits");
    }

    // ─── (c) Already confirmed market ───────────────────────────────────────────

    @Test
    @DisplayName("c) After confirmation, same market must NOT fire callback again")
    void testNoDoubleCallbackAfterConfirmation() throws Exception {
        setupRange("KRW-XRP", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        checkBreakout("KRW-XRP", 101.5);
        checkBreakout("KRW-XRP", 101.5);
        checkBreakout("KRW-XRP", 101.5);
        checkBreakout("KRW-XRP", 101.5); // fires once

        assertEquals(1, callbackMarkets.size(), "Exactly one callback after 4 hits");

        // More price updates above threshold
        checkBreakout("KRW-XRP", 102.0);
        checkBreakout("KRW-XRP", 102.0);
        checkBreakout("KRW-XRP", 102.0);

        assertEquals(1, callbackMarkets.size(), "Still only one callback — no duplicate");
        assertTrue(detector.isAlreadyConfirmed("KRW-XRP"), "isAlreadyConfirmed must return true");
    }

    // ─── (d) Multiple markets independently tracked ──────────────────────────────

    @Test
    @DisplayName("d) Market A confirmation does not affect Market B tracking")
    void testMultipleMarketsAreIndependent() throws Exception {
        Map<String, Double> map = new HashMap<>();
        map.put("KRW-NEAR", 200.0);
        map.put("KRW-DOGE", 0.5);
        detector.setRangeHighMap(map);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        // Confirm KRW-NEAR
        checkBreakout("KRW-NEAR", 202.5); // 200 * 1.01 = 202.0 → above
        checkBreakout("KRW-NEAR", 202.5);
        checkBreakout("KRW-NEAR", 202.5);
        checkBreakout("KRW-NEAR", 202.5); // fires

        assertEquals(1, callbackMarkets.size(), "KRW-NEAR confirmed");
        assertEquals("KRW-NEAR", callbackMarkets.get(0));

        // KRW-DOGE should still be fresh (count=0)
        assertFalse(detector.isAlreadyConfirmed("KRW-DOGE"), "KRW-DOGE must not be confirmed");

        // Confirm KRW-DOGE independently
        double dogeThreshold = 0.5 * 1.01; // 0.505
        checkBreakout("KRW-DOGE", dogeThreshold + 0.01);
        checkBreakout("KRW-DOGE", dogeThreshold + 0.01);
        checkBreakout("KRW-DOGE", dogeThreshold + 0.01);
        checkBreakout("KRW-DOGE", dogeThreshold + 0.01); // fires

        assertEquals(2, callbackMarkets.size(), "Both markets confirmed independently");
        assertTrue(callbackMarkets.contains("KRW-NEAR"));
        assertTrue(callbackMarkets.contains("KRW-DOGE"));
    }

    // ─── (e) Below threshold ────────────────────────────────────────────────────

    @Test
    @DisplayName("e) Price below threshold should never increment count or fire callback")
    void testBelowThresholdNeverFiresCallback() throws Exception {
        setupRange("KRW-LINK", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        // 100.5 < 101.0 (threshold)
        checkBreakout("KRW-LINK", 100.5);
        checkBreakout("KRW-LINK", 100.5);
        checkBreakout("KRW-LINK", 100.5);
        checkBreakout("KRW-LINK", 100.5);
        checkBreakout("KRW-LINK", 100.5);

        assertEquals(0, callbackMarkets.size(), "Below threshold: callback must never fire");
        assertFalse(detector.isAlreadyConfirmed("KRW-LINK"), "Market must not be marked as confirmed");
    }

    @Test
    @DisplayName("e2) Price just below threshold (100.99) should not count")
    void testJustBelowThresholdDoesNotCount() throws Exception {
        setupRange("KRW-ATOM", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        checkBreakout("KRW-ATOM", 100.99); // threshold=101.0, so this is below

        // Then hit threshold exactly 4 times
        checkBreakout("KRW-ATOM", 101.0);
        checkBreakout("KRW-ATOM", 101.0);
        checkBreakout("KRW-ATOM", 101.0);
        checkBreakout("KRW-ATOM", 101.0);

        assertEquals(1, callbackMarkets.size(), "Callback fires for 4 hits, the below-threshold one did not count");
    }

    // ─── (f) Integration-like: full flow + callback content verification ─────────

    @Test
    @DisplayName("f) Full integration flow: correct market, price, breakoutPct in callback")
    void testCallbackContainsCorrectData() throws Exception {
        AtomicReference<String> cbMarket = new AtomicReference<>();
        AtomicReference<Double> cbPrice = new AtomicReference<>();
        AtomicReference<Double> cbRangeHigh = new AtomicReference<>();
        AtomicReference<Double> cbPct = new AtomicReference<>();

        detector.setListener(new OpeningBreakoutDetector.BreakoutListener() {
            @Override
            public void onBreakoutConfirmed(String market, double price, double rangeHigh, double breakoutPctActual) {
                cbMarket.set(market);
                cbPrice.set(price);
                cbRangeHigh.set(rangeHigh);
                cbPct.set(breakoutPctActual);
            }
        });

        double rangeHigh = 500.0;
        double breakoutPrice = 510.0; // (510-500)/500*100 = 2.0%
        setupRange("KRW-AVAX", rangeHigh);
        detector.setBreakoutPct(1.0);   // threshold = 505.0
        detector.setRequiredConfirm(4);

        checkBreakout("KRW-AVAX", breakoutPrice);
        checkBreakout("KRW-AVAX", breakoutPrice);
        checkBreakout("KRW-AVAX", breakoutPrice);
        checkBreakout("KRW-AVAX", breakoutPrice);

        assertNotNull(cbMarket.get(), "Callback must have been called");
        assertEquals("KRW-AVAX", cbMarket.get(), "Market must match");
        assertEquals(breakoutPrice, cbPrice.get(), 0.001, "Callback price must match trade price");
        assertEquals(rangeHigh, cbRangeHigh.get(), 0.001, "Callback rangeHigh must match");

        double expectedPct = (breakoutPrice - rangeHigh) / rangeHigh * 100.0;
        assertEquals(expectedPct, cbPct.get(), 0.001, "Callback breakoutPct must match formula");
    }

    @Test
    @DisplayName("f2) Integration: sequential prices, callback fires at correct 4th tick")
    void testCallbackFiresAtExactlyFourthConsecutiveTick() throws Exception {
        setupRange("KRW-MATIC", 1000.0);
        detector.setBreakoutPct(0.5);   // threshold = 1005.0
        detector.setRequiredConfirm(4);

        checkBreakout("KRW-MATIC", 1004.0); // below → no count
        checkBreakout("KRW-MATIC", 1006.0); // count=1
        assertEquals(0, callbackMarkets.size(), "No callback after 1st confirm");
        checkBreakout("KRW-MATIC", 1004.0); // below → reset count
        checkBreakout("KRW-MATIC", 1006.0); // count=1 again
        checkBreakout("KRW-MATIC", 1006.0); // count=2
        checkBreakout("KRW-MATIC", 1006.0); // count=3
        assertEquals(0, callbackMarkets.size(), "No callback after 3rd confirm");
        checkBreakout("KRW-MATIC", 1006.0); // count=4 → fires
        assertEquals(1, callbackMarkets.size(), "Callback fires at exactly 4th consecutive tick");
    }

    // ─── (g) RSI threshold constant in ScalpOpeningBreakStrategy ────────────────

    @Test
    @DisplayName("g) ScalpOpeningBreakStrategy.RSI_OVERBOUGHT must be 83.0 (not 75)")
    void testScalpOpeningBreakStrategyRsiThresholdIs83() throws Exception {
        Class<?> clazz = Class.forName("com.example.upbit.strategy.ScalpOpeningBreakStrategy");
        Field field = clazz.getDeclaredField("RSI_OVERBOUGHT");
        field.setAccessible(true);
        double value = field.getDouble(null);
        assertEquals(83.0, value, 0.001,
                "RSI_OVERBOUGHT should be 83.0 (updated from 75 to account for RSI 70-83 good-rate analysis)");
    }

    // ─── Edge cases ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("edge) Unknown market (not in rangeHighMap) is silently ignored")
    void testUnknownMarketIsIgnored() throws Exception {
        setupRange("KRW-BTC", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        // Feed prices for a market not in the map
        checkBreakout("KRW-UNKNOWN", 999.0);
        checkBreakout("KRW-UNKNOWN", 999.0);
        checkBreakout("KRW-UNKNOWN", 999.0);
        checkBreakout("KRW-UNKNOWN", 999.0);

        assertEquals(0, callbackMarkets.size(), "Unknown market must not trigger callback");
    }

    // ─── releaseMarket: DRIFT 사고 재발 방지 ────────────────────────────────────

    @Test
    @DisplayName("releaseMarket) SELL 후 release → 동일 마켓 재 BREAKOUT 정상 감지 (DRIFT 시나리오)")
    void testReleaseMarketAllowsReBreakout() throws Exception {
        // 1차 BREAKOUT 시뮬레이션
        setupRange("KRW-DRIFT", 72.5);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        // 1차: 4틱 연속 → BREAKOUT CONFIRMED
        checkBreakout("KRW-DRIFT", 73.4);
        checkBreakout("KRW-DRIFT", 73.4);
        checkBreakout("KRW-DRIFT", 73.4);
        checkBreakout("KRW-DRIFT", 73.4);
        assertEquals(1, callbackMarkets.size(), "1차 BREAKOUT 발동");
        assertTrue(detector.isAlreadyConfirmed("KRW-DRIFT"), "confirmedMarkets에 등록됨");

        // SELL 후에는 confirmedMarkets에 그대로 남아있어 → checkBreakout 즉시 return
        // (이게 사고 원인)
        checkBreakout("KRW-DRIFT", 75.0);
        checkBreakout("KRW-DRIFT", 75.0);
        checkBreakout("KRW-DRIFT", 75.0);
        checkBreakout("KRW-DRIFT", 75.0);
        assertEquals(1, callbackMarkets.size(), "release 전에는 재BREAKOUT 무시 (사고 재현)");

        // ★ releaseMarket 호출 (SELL 후 핫픽스로 추가됨)
        detector.releaseMarket("KRW-DRIFT");
        assertFalse(detector.isAlreadyConfirmed("KRW-DRIFT"), "release 후 confirmedMarkets에서 제거됨");

        // 2차: 다시 4틱 연속 → 재 BREAKOUT 정상 감지
        checkBreakout("KRW-DRIFT", 75.0);
        checkBreakout("KRW-DRIFT", 75.0);
        checkBreakout("KRW-DRIFT", 75.0);
        checkBreakout("KRW-DRIFT", 75.0);
        assertEquals(2, callbackMarkets.size(), "release 후 재 BREAKOUT 정상 감지");
        assertEquals("KRW-DRIFT", callbackMarkets.get(1));
    }

    @Test
    @DisplayName("releaseMarket) confirmCounts도 함께 초기화")
    void testReleaseMarketResetsConfirmCount() throws Exception {
        setupRange("KRW-RED", 300.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        // 2틱만 누적 (아직 BREAKOUT 안 됨)
        checkBreakout("KRW-RED", 305.0);
        checkBreakout("KRW-RED", 305.0);
        assertEquals(0, callbackMarkets.size(), "2틱은 아직 미발동");

        // release 호출 → confirmCount도 리셋
        detector.releaseMarket("KRW-RED");

        // release 후 틱 받으면 count 새로 시작 (이전 2틱 reset 확인)
        checkBreakout("KRW-RED", 305.0);
        checkBreakout("KRW-RED", 305.0);
        checkBreakout("KRW-RED", 305.0);
        assertEquals(0, callbackMarkets.size(), "release 후 3틱이라 미발동 (이전 카운트 리셋 증명)");

        // 1틱 더 (총 release 후 4틱) → 발동
        checkBreakout("KRW-RED", 305.0);
        assertEquals(1, callbackMarkets.size(), "release 후 4틱 채워 발동");
    }

    @Test
    @DisplayName("releaseMarket) 등록 안 된 마켓 release 호출 — 안전")
    void testReleaseMarketIdempotent() {
        // 등록 안 된 마켓
        detector.releaseMarket("KRW-NONEXISTENT");
        detector.releaseMarket("KRW-NONEXISTENT"); // 두 번 호출도 안전
        detector.releaseMarket(null); // null 안전
        // 예외 없이 통과
    }

    @Test
    @DisplayName("releaseMarket) 다른 마켓에 영향 없음")
    void testReleaseMarketIsolation() throws Exception {
        setupRange("KRW-A", 100.0);
        Map<String, Double> map = new HashMap<>();
        map.put("KRW-A", 100.0);
        map.put("KRW-B", 200.0);
        detector.setRangeHighMap(map);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        // A, B 모두 confirmed
        checkBreakout("KRW-A", 102.0);
        checkBreakout("KRW-A", 102.0);
        checkBreakout("KRW-A", 102.0);
        checkBreakout("KRW-A", 102.0);
        checkBreakout("KRW-B", 204.0);
        checkBreakout("KRW-B", 204.0);
        checkBreakout("KRW-B", 204.0);
        checkBreakout("KRW-B", 204.0);
        assertEquals(2, callbackMarkets.size());
        assertTrue(detector.isAlreadyConfirmed("KRW-A"));
        assertTrue(detector.isAlreadyConfirmed("KRW-B"));

        // A만 release
        detector.releaseMarket("KRW-A");
        assertFalse(detector.isAlreadyConfirmed("KRW-A"));
        assertTrue(detector.isAlreadyConfirmed("KRW-B"), "B는 그대로 유지");

        // A 재 BREAKOUT 가능
        checkBreakout("KRW-A", 102.0);
        checkBreakout("KRW-A", 102.0);
        checkBreakout("KRW-A", 102.0);
        checkBreakout("KRW-A", 102.0);
        assertEquals(3, callbackMarkets.size(), "A 재 BREAKOUT 정상");

        // B는 여전히 차단
        checkBreakout("KRW-B", 204.0);
        checkBreakout("KRW-B", 204.0);
        checkBreakout("KRW-B", 204.0);
        checkBreakout("KRW-B", 204.0);
        assertEquals(3, callbackMarkets.size(), "B는 release 안 했으므로 재 BREAKOUT 미발동");
    }

    @Test
    @DisplayName("edge) reset() clears all state including confirmed markets")
    void testResetClearsAllState() throws Exception {
        setupRange("KRW-BTC", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        checkBreakout("KRW-BTC", 101.5);
        checkBreakout("KRW-BTC", 101.5);
        checkBreakout("KRW-BTC", 101.5);
        checkBreakout("KRW-BTC", 101.5); // confirmed

        assertTrue(detector.isAlreadyConfirmed("KRW-BTC"), "Must be confirmed before reset");

        detector.reset();

        assertFalse(detector.isAlreadyConfirmed("KRW-BTC"), "Must NOT be confirmed after reset");
        // reset 후 내부 latestPrices는 비어있음 (SharedPriceService에서 가격이 올 수 있으므로 null 아닐 수 있음)
        // 핵심은 confirmedMarkets가 초기화된 것
    }

    @Test
    @DisplayName("edge) getLatestPrice returns SharedPriceService price or null")
    void testGetLatestPriceReturnsNullWhenNotSet() {
        setupRange("KRW-BTC", 100.0);
        // SharedPriceService mock이 null 반환 + 내부 latestPrices 비어있음
        Double price = detector.getLatestPrice("KRW-BTC");
        // SharedPriceService mock 기본값에 따라 null 또는 0.0
        assertTrue(price == null || price == 0.0,
                "getLatestPrice must return null or mock default before any price update");
    }

    // ─── SL 종합안 (1분 그레이스 + 5분 타이트닝 + 트레일링) ─────────────────────

    private void invokeCheckRealtimeTp(String market, double price) throws Exception {
        Method m = OpeningBreakoutDetector.class.getDeclaredMethod("checkRealtimeTp", String.class, double.class);
        m.setAccessible(true);
        m.invoke(detector, market, price);
    }

    private final List<String> sellTypes = new ArrayList<>();
    private final List<String> sellReasons = new ArrayList<>();

    private OpeningBreakoutDetector.BreakoutListener sellRecordingListener = new OpeningBreakoutDetector.BreakoutListener() {
        @Override
        public void onBreakoutConfirmed(String market, double price, double rangeHigh, double pct) {}
        @Override
        public void onTpSlTriggered(String market, double price, String sellType, String reason) {
            sellTypes.add(sellType);
            sellReasons.add(reason);
        }
    };

    @Test
    @DisplayName("SL종합안: 1분 그레이스 동안 SL 무시")
    void testGracePeriodIgnoresSL() throws Exception {
        sellTypes.clear();
        sellReasons.clear();
        detector.setListener(sellRecordingListener);
        detector.updateSlConfig(60, 5, 5.0, 5.0, 5.0, 5.0, 3.0);

        // openedAt = 30초 전 (그레이스 60초 이내)
        long openedAt = System.currentTimeMillis() - 30_000L;
        detector.addPosition("KRW-TEST", 100.0, openedAt, 999);

        // -10% 급락 (5% SL 훨씬 초과)
        invokeCheckRealtimeTp("KRW-TEST", 90.0);

        assertEquals(0, sellTypes.size(), "그레이스 동안 SL 무시되어야 함");
    }

    @Test
    @DisplayName("SL종합안: 60s~5분 SL 5% (SL_WIDE)")
    void testWideSlAfterGrace() throws Exception {
        sellTypes.clear();
        sellReasons.clear();
        detector.setListener(sellRecordingListener);
        // wide_period 5분, 단일 SL 5% (모든 rank 동일)
        detector.updateSlConfig(60, 5, 5.0, 5.0, 5.0, 5.0, 3.0);

        // 매수 후 2분 경과 (rank 999 → other = 5.0%)
        long openedAt = System.currentTimeMillis() - 120_000L;
        detector.addPosition("KRW-TEST", 100.0, openedAt, 999);

        // -4% (5% SL 미달) → 유지
        invokeCheckRealtimeTp("KRW-TEST", 96.0);
        assertEquals(0, sellTypes.size(), "-4%는 5% SL 미달");

        // -6% (5% SL 초과) → 매도
        invokeCheckRealtimeTp("KRW-TEST", 94.0);
        assertEquals(1, sellTypes.size(), "-6%는 5% SL 발동");
        assertEquals("SL_WIDE", sellTypes.get(0));
    }

    @Test
    @DisplayName("SL종합안: 5분 이후 SL 3% 타이트닝 (SL_TIGHT)")
    void testTightSlAfter5Min() throws Exception {
        sellTypes.clear();
        sellReasons.clear();
        detector.setListener(sellRecordingListener);
        // wide_period 5분, SL_TIGHT 3%
        detector.updateSlConfig(60, 5, 5.0, 5.0, 5.0, 5.0, 3.0);

        // 매수 후 6분 경과
        long openedAt = System.currentTimeMillis() - 360_000L;
        detector.addPosition("KRW-TEST", 100.0, openedAt, 999);

        // -2% (3% SL 미달) → 유지
        invokeCheckRealtimeTp("KRW-TEST", 98.0);
        assertEquals(0, sellTypes.size(), "-2%는 3% SL 미달");

        // -4% (3% SL 초과) → 매도
        invokeCheckRealtimeTp("KRW-TEST", 96.0);
        assertEquals(1, sellTypes.size(), "-4%는 3% SL 발동");
        assertEquals("SL_TIGHT", sellTypes.get(0));
    }

    @Test
    @DisplayName("SL종합안: TP 트레일링 (TP_TRAIL)")
    void testTpTrailingStillWorks() throws Exception {
        sellTypes.clear();
        sellReasons.clear();
        detector.setListener(sellRecordingListener);
        detector.setTpActivatePct(2.0);   // +2% 활성화
        detector.setTrailFromPeakPct(1.0); // peak -1% 청산
        detector.updateSlConfig(60, 5, 5.0, 5.0, 5.0, 5.0, 3.0); // SL은 발동 안 되도록

        // 매수 후 그레이스 끝난 시점
        long openedAt = System.currentTimeMillis() - 90_000L;
        detector.addPosition("KRW-TEST", 100.0, openedAt, 999);

        // +3% → 트레일링 활성화
        invokeCheckRealtimeTp("KRW-TEST", 103.0);
        assertEquals(0, sellTypes.size(), "+3%는 활성화만, 매도 없음");

        // peak 103, 가격 102 (-0.97%) → 트레일링 미달
        invokeCheckRealtimeTp("KRW-TEST", 102.0);
        assertEquals(0, sellTypes.size(), "peak -0.97%는 트레일링 미달");

        // peak 103, 가격 101.96 (-1.01%) → 트레일링 발동
        invokeCheckRealtimeTp("KRW-TEST", 101.96);
        assertEquals(1, sellTypes.size(), "peak -1.01%는 트레일링 발동");
        assertEquals("TP_TRAIL", sellTypes.get(0));
    }

    @Test
    @DisplayName("SL종합안: 그레이스 + SL_WIDE + SL_TIGHT 경계값 종합")
    void testSlBoundaryComprehensive() throws Exception {
        sellTypes.clear();
        sellReasons.clear();
        detector.setListener(sellRecordingListener);
        // wide_period 5분, 단일 SL 5% / TIGHT 3%
        detector.updateSlConfig(60, 5, 5.0, 5.0, 5.0, 5.0, 3.0);

        // 30초 시점: 그레이스 → SL 무시
        long t30s = System.currentTimeMillis() - 30_000L;
        detector.addPosition("KRW-A", 100.0, t30s, 999);
        invokeCheckRealtimeTp("KRW-A", 80.0); // -20%
        assertEquals(0, sellTypes.size(), "30초 + -20% → 그레이스 보호");

        // 90초 시점: SL_WIDE 경계 (-5%)
        long t90s = System.currentTimeMillis() - 90_000L;
        detector.addPosition("KRW-B", 100.0, t90s, 999);
        invokeCheckRealtimeTp("KRW-B", 95.0); // exactly -5%
        assertEquals(1, sellTypes.size(), "-5% 경계는 SL_WIDE 발동");
        assertEquals("SL_WIDE", sellTypes.get(0));

        // 6분 시점: SL_TIGHT 경계 (-3%)
        long t6m = System.currentTimeMillis() - 360_000L;
        detector.addPosition("KRW-C", 100.0, t6m, 999);
        invokeCheckRealtimeTp("KRW-C", 97.0); // exactly -3%
        assertEquals(2, sellTypes.size(), "-3% 경계는 SL_TIGHT 발동");
        assertEquals("SL_TIGHT", sellTypes.get(1));
    }

    // ─── TOP-N 차등 SL_WIDE 테스트 ─────────────────────────────

    @Test
    @DisplayName("TOP-N 차등 SL_WIDE: TOP 1~10 → -6.2% 적용")
    void testWideSlTop10() throws Exception {
        sellTypes.clear();
        sellReasons.clear();
        detector.setListener(sellRecordingListener);
        // 15분 wide period, 차등값
        detector.updateSlConfig(60, 15, 6.2, 5.0, 3.5, 3.0, 3.0);

        // 매수 후 5분 경과 (1~15분 SL_WIDE 구간), TOP 5위
        long t5m = System.currentTimeMillis() - 5 * 60_000L;
        detector.addPosition("KRW-BTC", 100.0, t5m, 5);

        // -6% (-6.2 미달) → 유지
        invokeCheckRealtimeTp("KRW-BTC", 94.0);
        assertEquals(0, sellTypes.size(), "TOP 5위 -6%는 SL -6.2% 미달");

        // -6.5% → 발동
        invokeCheckRealtimeTp("KRW-BTC", 93.5);
        assertEquals(1, sellTypes.size(), "TOP 5위 -6.5%는 SL_WIDE 발동");
        assertEquals("SL_WIDE", sellTypes.get(0));
    }

    @Test
    @DisplayName("TOP-N 차등 SL_WIDE: TOP 11~20 → -5% 적용")
    void testWideSlTop20() throws Exception {
        sellTypes.clear();
        detector.setListener(sellRecordingListener);
        detector.updateSlConfig(60, 15, 6.2, 5.0, 3.5, 3.0, 3.0);

        long t5m = System.currentTimeMillis() - 5 * 60_000L;
        detector.addPosition("KRW-X", 100.0, t5m, 15);

        // -4% → 유지
        invokeCheckRealtimeTp("KRW-X", 96.0);
        assertEquals(0, sellTypes.size());

        // -5.5% → 발동
        invokeCheckRealtimeTp("KRW-X", 94.5);
        assertEquals(1, sellTypes.size());
        assertEquals("SL_WIDE", sellTypes.get(0));
    }

    @Test
    @DisplayName("TOP-N 차등 SL_WIDE: TOP 21~50 → -3.5% 적용")
    void testWideSlTop50() throws Exception {
        sellTypes.clear();
        detector.setListener(sellRecordingListener);
        detector.updateSlConfig(60, 15, 6.2, 5.0, 3.5, 3.0, 3.0);

        long t5m = System.currentTimeMillis() - 5 * 60_000L;
        detector.addPosition("KRW-Y", 100.0, t5m, 30);

        // -3% → 유지
        invokeCheckRealtimeTp("KRW-Y", 97.0);
        assertEquals(0, sellTypes.size());

        // -4% → 발동
        invokeCheckRealtimeTp("KRW-Y", 96.0);
        assertEquals(1, sellTypes.size());
    }

    @Test
    @DisplayName("TOP-N 차등 SL_WIDE: TOP 51 이상 → -3% 적용")
    void testWideSlTop51Plus() throws Exception {
        sellTypes.clear();
        detector.setListener(sellRecordingListener);
        detector.updateSlConfig(60, 15, 6.2, 5.0, 3.5, 3.0, 3.0);

        long t5m = System.currentTimeMillis() - 5 * 60_000L;
        detector.addPosition("KRW-Z", 100.0, t5m, 100);

        // -2.9% → 유지
        invokeCheckRealtimeTp("KRW-Z", 97.1);
        assertEquals(0, sellTypes.size());

        // -3.5% → 발동
        invokeCheckRealtimeTp("KRW-Z", 96.5);
        assertEquals(1, sellTypes.size());
    }

    @Test
    @DisplayName("Wide period 15분 → 그 이후 SL_TIGHT 적용")
    void testWidePeriodToTight() throws Exception {
        sellTypes.clear();
        detector.setListener(sellRecordingListener);
        detector.updateSlConfig(60, 15, 6.2, 5.0, 3.5, 3.0, 3.0);

        // 16분 경과 → SL_TIGHT 구간 (단일 -3%)
        long t16m = System.currentTimeMillis() - 16 * 60_000L;
        detector.addPosition("KRW-BTC", 100.0, t16m, 5); // TOP 5위지만 wide_period 지남

        // -3.5% → SL_TIGHT 발동 (TOP 5위 차등은 무시)
        invokeCheckRealtimeTp("KRW-BTC", 96.5);
        assertEquals(1, sellTypes.size());
        assertEquals("SL_TIGHT", sellTypes.get(0));
    }

    @Test
    @DisplayName("DB 설정 갱신: updateSlConfig로 동적 변경")
    void testUpdateSlConfigDynamic() throws Exception {
        sellTypes.clear();
        detector.setListener(sellRecordingListener);

        // 초기: SL_WIDE 5%
        detector.updateSlConfig(60, 15, 5.0, 5.0, 5.0, 5.0, 3.0);
        long t5m = System.currentTimeMillis() - 5 * 60_000L;
        detector.addPosition("KRW-A", 100.0, t5m, 30);
        invokeCheckRealtimeTp("KRW-A", 96.0); // -4%
        assertEquals(0, sellTypes.size(), "초기 -5% 설정에서 -4%는 통과");

        // 동적 변경: TOP 21~50 → -3.5%
        detector.updateSlConfig(60, 15, 6.2, 5.0, 3.5, 3.0, 3.0);
        invokeCheckRealtimeTp("KRW-A", 96.0); // 같은 -4%
        assertEquals(1, sellTypes.size(), "변경 후 -3.5% 임계로 -4% 발동");
    }

    @Test
    @DisplayName("edge) setRangeHighMap clears previous state from prior session")
    void testSetRangeHighMapClearsPreviousSession() throws Exception {
        // First session: confirm KRW-BTC
        Map<String, Double> session1 = new HashMap<>();
        session1.put("KRW-BTC", 100.0);
        detector.setRangeHighMap(session1);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(4);

        checkBreakout("KRW-BTC", 101.5);
        checkBreakout("KRW-BTC", 101.5);
        checkBreakout("KRW-BTC", 101.5);
        checkBreakout("KRW-BTC", 101.5); // confirmed

        assertTrue(detector.isAlreadyConfirmed("KRW-BTC"));

        // Second session: new rangeHighMap call resets state
        Map<String, Double> session2 = new HashMap<>();
        session2.put("KRW-ETH", 2000.0);
        detector.setRangeHighMap(session2);

        assertFalse(detector.isAlreadyConfirmed("KRW-BTC"), "KRW-BTC must not be confirmed in new session");
        assertFalse(detector.isAlreadyConfirmed("KRW-ETH"), "KRW-ETH must start fresh");
    }
}
