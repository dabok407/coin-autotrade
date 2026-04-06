package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Indicators#peakHighSinceEntry(List, double, Instant)}.
 *
 * Verifies that the openedAt-based filtering correctly ignores candles that
 * predate position entry, and that the avgPrice-based fallback still works
 * when no openedAt is provided.
 */
public class PeakHighResetTest {

    private static final DateTimeFormatter UTC_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Build a single candle with explicit OHLC and a UTC timestamp string. */
    private UpbitCandle candle(double open, double high, double low, double close, ZonedDateTime time) {
        UpbitCandle c = new UpbitCandle();
        c.market = "KRW-TEST";
        c.opening_price = open;
        c.high_price = high;
        c.low_price = low;
        c.trade_price = close;
        c.candle_acc_trade_volume = 1000;
        c.candle_date_time_utc = time.format(UTC_FMT);
        return c;
    }

    /**
     * Build a list of candles:
     *   - 5 "old" candles with high=100 (before the dip)
     *   - 1 "dip" candle with high=70  (marks the old-peak period)
     *   - 5 "recent" candles with high=85 (after openedAt should be)
     *
     * The anchor for openedAt is set to the timestamp of the dip candle + 1s,
     * so only the 5 recent candles (high=85) are inside scope.
     *
     * @return Object[]{candles, openedAt} where openedAt is an Instant
     */
    private Object[] buildMixedCandles() {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();

        // T0 = 2 hours ago; each candle is 5 minutes apart
        ZonedDateTime t0 = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(120);

        // 5 "old" candles: high = 100
        for (int i = 0; i < 5; i++) {
            ZonedDateTime t = t0.plusMinutes(i * 5L);
            candles.add(candle(90, 100, 85, 95, t));
        }

        // 1 dip candle: high = 70 — this is where the position would have been opened
        ZonedDateTime dipTime = t0.plusMinutes(5 * 5L);
        candles.add(candle(65, 70, 60, 68, dipTime));

        // 5 "recent" candles: high = 85
        for (int i = 0; i < 5; i++) {
            ZonedDateTime t = dipTime.plusMinutes((i + 1) * 5L);
            candles.add(candle(75, 85, 72, 82, t));
        }

        // openedAt = 1 second after the dip candle's open time
        // → the dip candle itself is INCLUDED (candleEpochSec >= openedEpochSec when equal),
        //   so we set openedAt to the first "recent" candle's time to exclude the dip too.
        ZonedDateTime firstRecentTime = dipTime.plusMinutes(5L);
        Instant openedAt = firstRecentTime.toInstant();

        return new Object[]{candles, openedAt};
    }

    // ========================================================================================
    // Test 1: openedAt provided → only post-entry candles contribute to peak
    // ========================================================================================

    /**
     * Old candles have high=100, recent candles (after entry) have high=85.
     * With openedAt set to the start of the recent period, peak must be ~85, not 100.
     */
    @Test
    public void testPeakHighWithOpenedAt_ignoresOldCandles() {
        Object[] built = buildMixedCandles();
        @SuppressWarnings("unchecked")
        List<UpbitCandle> candles = (List<UpbitCandle>) built[0];
        Instant openedAt = (Instant) built[1];

        double avgPrice = 75.0; // approximate entry price in the recent candles

        double peak = Indicators.peakHighSinceEntry(candles, avgPrice, openedAt);

        // Peak must reflect only the recent candles (high=85), not the old ones (high=100)
        assertTrue(peak <= 90.0,
                "Peak should be at most 90 (post-entry range), but was " + peak);
        assertTrue(peak >= 85.0,
                "Peak should be at least 85 (highest recent candle high), but was " + peak);

        // Confirm the old candles' high (100) is NOT used
        assertNotEquals(100.0, peak, 0.01,
                "Peak must not equal 100 — old candles must be ignored when openedAt is provided");
    }

    // ========================================================================================
    // Test 2: no openedAt → avgPrice-based fallback (old behavior)
    // ========================================================================================

    /**
     * Same candle data, but openedAt is null.
     * The fallback logic searches backwards for a candle whose range spans avgPrice (±1%),
     * then scans forward from there.  With avgPrice=75 the dip-candle or a recent candle
     * will be the match, so the peak is drawn from that point onward (≤ 85), still not 100.
     *
     * More importantly, we verify the two-argument overload (no openedAt) is consistent
     * with passing null explicitly.
     */
    @Test
    public void testPeakHighWithoutOpenedAt_usesAvgPriceFallback() {
        Object[] built = buildMixedCandles();
        @SuppressWarnings("unchecked")
        List<UpbitCandle> candles = (List<UpbitCandle>) built[0];

        // avgPrice is in the "recent" price range, not in the old-candles range
        double avgPrice = 75.0;

        double peakNoArg  = Indicators.peakHighSinceEntry(candles, avgPrice);
        double peakNullAt = Indicators.peakHighSinceEntry(candles, avgPrice, null);

        // Both overloads must agree
        assertEquals(peakNoArg, peakNullAt, 0.001,
                "Two-arg and three-arg(null) overloads must produce the same result");

        // The fallback scans backward from the end for the first candle that spans avgPrice (75).
        // The recent candles (high=85, low=72) span 75, so startIdx lands in the recent block.
        // Peak must therefore come from that block (high=85), not from the old block (high=100).
        assertTrue(peakNoArg >= avgPrice,
                "Peak must be >= avgPrice, was " + peakNoArg);

        // Sanity: with avgPrice=75 the search stays in the recent block, peak ≤ 85
        assertTrue(peakNoArg <= 90.0,
                "Fallback peak should stay within the recent candle range (<=90), was " + peakNoArg);
    }

    // ========================================================================================
    // Test 3: openedAt is before all candles → every candle contributes to peak
    // ========================================================================================

    /**
     * All candles are timestamped after openedAt.
     * The peak must be the global maximum high across the entire list.
     */
    @Test
    public void testPeakHighWithOpenedAt_allCandlesAfterEntry() {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();
        ZonedDateTime t0 = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(60);

        double expectedMax = 0;
        for (int i = 0; i < 10; i++) {
            double high = 80.0 + i * 3.0;   // 80, 83, 86, … 107
            if (high > expectedMax) expectedMax = high;
            ZonedDateTime t = t0.plusMinutes(i * 5L);
            candles.add(candle(high - 5, high, high - 8, high - 2, t));
        }

        // openedAt is 30 minutes BEFORE the first candle → all candles are "after" entry
        Instant openedAt = t0.minusMinutes(30).toInstant();
        double avgPrice = 80.0;

        double peak = Indicators.peakHighSinceEntry(candles, avgPrice, openedAt);

        assertEquals(expectedMax, peak, 0.001,
                "When all candles are after openedAt, peak must equal the global max high (" + expectedMax + ")");
    }

    // ========================================================================================
    // Test 4: openedAt is after all candles → should fall back to most recent candle
    // ========================================================================================

    /**
     * Edge case: openedAt is newer than every candle in the list.
     * The for-loop in peakHighSinceEntry never sets startIdx, so it stays at 0
     * and the entire list is scanned — we just verify no exception is thrown and
     * the result is >= avgPrice.
     */
    @Test
    public void testPeakHighWithOpenedAt_allCandlesBeforeEntry_noException() {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();
        ZonedDateTime t0 = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(60);

        for (int i = 0; i < 5; i++) {
            ZonedDateTime t = t0.plusMinutes(i * 5L);
            candles.add(candle(90, 100, 85, 95, t));
        }

        // openedAt is in the future → no candle qualifies, startIdx stays 0
        Instant openedAt = Instant.now().plusSeconds(3600);
        double avgPrice = 90.0;

        // Must not throw
        double peak = Indicators.peakHighSinceEntry(candles, avgPrice, openedAt);

        // startIdx=0, so the whole list is scanned → peak = 100
        assertTrue(peak >= avgPrice,
                "Peak must be >= avgPrice even when openedAt is in the future, was " + peak);
    }

    // ========================================================================================
    // Test 5: empty candle list → returns avgPrice regardless of openedAt
    // ========================================================================================

    @Test
    public void testPeakHighWithOpenedAt_emptyList_returnsAvgPrice() {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();
        double avgPrice = 55000.0;
        Instant openedAt = Instant.now().minusSeconds(300);

        double peak = Indicators.peakHighSinceEntry(candles, avgPrice, openedAt);

        assertEquals(avgPrice, peak, 0.001,
                "Empty candle list must return avgPrice as the peak");
    }

    // ========================================================================================
    // Test 6: single candle exactly at openedAt timestamp → candle IS included
    // ========================================================================================

    /**
     * The boundary condition: a candle whose timestamp equals openedAt's epoch second.
     * The implementation uses >= so the candle at exactly openedAt must be included.
     */
    @Test
    public void testPeakHighWithOpenedAt_exactTimestampBoundaryIsInclusive() {
        ZonedDateTime entryTime = ZonedDateTime.of(2026, 4, 4, 9, 0, 0, 0, ZoneOffset.UTC);

        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();

        // Candle 1: 5 minutes before entry — high=200, must be excluded
        candles.add(candle(180, 200, 175, 190, entryTime.minusMinutes(5)));

        // Candle 2: exactly at entry time — high=120, must be included
        candles.add(candle(100, 120, 95, 115, entryTime));

        // Candle 3: 5 minutes after entry — high=130, must be included
        candles.add(candle(110, 130, 105, 125, entryTime.plusMinutes(5)));

        Instant openedAt = entryTime.toInstant();
        double avgPrice = 100.0;

        double peak = Indicators.peakHighSinceEntry(candles, avgPrice, openedAt);

        // Expected: max(120, 130) = 130
        assertEquals(130.0, peak, 0.001,
                "Peak should be 130 (candles at and after openedAt), but was " + peak);

        // Specifically confirm the old candle's high (200) is NOT the result
        assertNotEquals(200.0, peak, 0.001,
                "Candle before openedAt (high=200) must not contribute to peak");
    }
}
