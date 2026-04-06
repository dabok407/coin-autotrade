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
 * a) Breakout detection: 3 consecutive prices above threshold fires callback
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
    @DisplayName("a) 3 consecutive breakout prices should fire callback")
    void testBasicBreakoutFiresAfterThreeConsecutive() throws Exception {
        // rangeHigh=100, breakoutPct=1.0 → threshold = 101.0
        setupRange("KRW-BTC", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(3);

        // price 101.5 > 101.0 → count should increment each time
        checkBreakout("KRW-BTC", 101.5); // count=1
        assertEquals(0, callbackMarkets.size(), "No callback yet at count=1");

        checkBreakout("KRW-BTC", 101.5); // count=2
        assertEquals(0, callbackMarkets.size(), "No callback yet at count=2");

        checkBreakout("KRW-BTC", 101.5); // count=3 → fires
        assertEquals(1, callbackMarkets.size(), "Callback must fire at count=3");
        assertEquals("KRW-BTC", callbackMarkets.get(0));
    }

    @Test
    @DisplayName("a2) Price exactly at threshold (101.0) should count as breakout")
    void testExactThresholdCounts() throws Exception {
        setupRange("KRW-ETH", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(3);

        double threshold = 100.0 * 1.01; // 101.0

        checkBreakout("KRW-ETH", threshold); // count=1
        checkBreakout("KRW-ETH", threshold); // count=2
        checkBreakout("KRW-ETH", threshold); // count=3

        assertEquals(1, callbackMarkets.size(), "Callback must fire when price == threshold");
    }

    // ─── (b) Confirm reset ───────────────────────────────────────────────────────

    @Test
    @DisplayName("b) Count resets to 0 when price drops below threshold; subsequent 3 hits still fire")
    void testConfirmResetWhenPriceDips() throws Exception {
        setupRange("KRW-SOL", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(3);

        checkBreakout("KRW-SOL", 101.5); // count=1
        assertEquals(0, callbackMarkets.size());

        // Price drops below threshold → count should reset
        checkBreakout("KRW-SOL", 100.5); // below 101.0 → reset to 0
        assertEquals(0, callbackMarkets.size(), "No callback after price drop");

        // Verify count is truly reset: need 3 more hits now
        checkBreakout("KRW-SOL", 101.5); // count=1
        checkBreakout("KRW-SOL", 101.5); // count=2
        assertEquals(0, callbackMarkets.size(), "Still no callback at count=2");
        checkBreakout("KRW-SOL", 101.5); // count=3 → fires
        assertEquals(1, callbackMarkets.size(), "Callback fires after reset + 3 new hits");
    }

    @Test
    @DisplayName("b2) Count does NOT reset if market never had any count entry")
    void testNoResetIfNeverCountedBefore() throws Exception {
        setupRange("KRW-ADA", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(3);

        // Only below-threshold prices
        checkBreakout("KRW-ADA", 100.5);
        checkBreakout("KRW-ADA", 100.5);
        // Then above threshold 3 times
        checkBreakout("KRW-ADA", 101.5);
        checkBreakout("KRW-ADA", 101.5);
        checkBreakout("KRW-ADA", 101.5);

        assertEquals(1, callbackMarkets.size(), "Callback must fire after 3 hits");
    }

    // ─── (c) Already confirmed market ───────────────────────────────────────────

    @Test
    @DisplayName("c) After confirmation, same market must NOT fire callback again")
    void testNoDoubleCallbackAfterConfirmation() throws Exception {
        setupRange("KRW-XRP", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(3);

        checkBreakout("KRW-XRP", 101.5);
        checkBreakout("KRW-XRP", 101.5);
        checkBreakout("KRW-XRP", 101.5); // fires once

        assertEquals(1, callbackMarkets.size(), "Exactly one callback after 3 hits");

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
        detector.setRequiredConfirm(3);

        // Confirm KRW-NEAR
        checkBreakout("KRW-NEAR", 202.5); // 200 * 1.01 = 202.0 → above
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
        detector.setRequiredConfirm(3);

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
        detector.setRequiredConfirm(3);

        checkBreakout("KRW-ATOM", 100.99); // threshold=101.0, so this is below

        // Then hit threshold exactly 3 times
        checkBreakout("KRW-ATOM", 101.0);
        checkBreakout("KRW-ATOM", 101.0);
        checkBreakout("KRW-ATOM", 101.0);

        assertEquals(1, callbackMarkets.size(), "Callback fires for 3 hits, the below-threshold one did not count");
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
        detector.setRequiredConfirm(3);

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
    @DisplayName("f2) Integration: sequential prices, callback fires at correct 3rd tick")
    void testCallbackFiresAtExactlyThirdConsecutiveTick() throws Exception {
        setupRange("KRW-MATIC", 1000.0);
        detector.setBreakoutPct(0.5);   // threshold = 1005.0
        detector.setRequiredConfirm(3);

        checkBreakout("KRW-MATIC", 1004.0); // below → no count
        checkBreakout("KRW-MATIC", 1006.0); // count=1
        assertEquals(0, callbackMarkets.size(), "No callback after 1st confirm");
        checkBreakout("KRW-MATIC", 1004.0); // below → reset count
        checkBreakout("KRW-MATIC", 1006.0); // count=1 again
        checkBreakout("KRW-MATIC", 1006.0); // count=2
        assertEquals(0, callbackMarkets.size(), "No callback after 2nd confirm");
        checkBreakout("KRW-MATIC", 1006.0); // count=3 → fires
        assertEquals(1, callbackMarkets.size(), "Callback fires at exactly 3rd consecutive tick");
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
        detector.setRequiredConfirm(3);

        // Feed prices for a market not in the map
        checkBreakout("KRW-UNKNOWN", 999.0);
        checkBreakout("KRW-UNKNOWN", 999.0);
        checkBreakout("KRW-UNKNOWN", 999.0);

        assertEquals(0, callbackMarkets.size(), "Unknown market must not trigger callback");
    }

    @Test
    @DisplayName("edge) reset() clears all state including confirmed markets")
    void testResetClearsAllState() throws Exception {
        setupRange("KRW-BTC", 100.0);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(3);

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

    @Test
    @DisplayName("edge) setRangeHighMap clears previous state from prior session")
    void testSetRangeHighMapClearsPreviousSession() throws Exception {
        // First session: confirm KRW-BTC
        Map<String, Double> session1 = new HashMap<>();
        session1.put("KRW-BTC", 100.0);
        detector.setRangeHighMap(session1);
        detector.setBreakoutPct(1.0);
        detector.setRequiredConfirm(3);

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
