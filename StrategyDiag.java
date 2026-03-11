import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Diagnostic: Fetch candle data from Upbit public API and test strategy conditions.
 * No auth needed — uses public endpoints only.
 */
public class StrategyDiag {

    public static void main(String[] args) throws Exception {
        String market = "KRW-ADA";
        int unit = 60; // 60-min candles
        int count = 200; // max per request

        System.out.println("Fetching " + count + " candles for " + market + " (" + unit + "min)...");
        String url = "https://api.upbit.com/v1/candles/minutes/" + unit
                    + "?market=" + market + "&count=" + count;
        String json = httpGet(url);

        // Parse candles (simple JSON parsing)
        List<double[]> candles = new ArrayList<double[]>(); // [open, high, low, close, volume]
        // Format: [{"market":"KRW-ADA","candle_date_time_utc":"...","opening_price":1234,...}]

        String[] entries = json.split("\\{");
        for (String entry : entries) {
            if (!entry.contains("opening_price")) continue;
            double open = extractNum(entry, "opening_price");
            double high = extractNum(entry, "high_price");
            double low = extractNum(entry, "low_price");
            double close = extractNum(entry, "trade_price");
            double vol = extractNum(entry, "candle_acc_trade_volume");
            candles.add(new double[]{open, high, low, close, vol});
        }

        // Upbit returns newest first, reverse to oldest first
        Collections.reverse(candles);

        System.out.println("Parsed " + candles.size() + " candles");
        System.out.println("Price range: " + candles.get(0)[3] + " -> " + candles.get(candles.size()-1)[3]);
        System.out.println();

        // Test Bollinger Squeeze conditions step by step
        System.out.println("=== BOLLINGER SQUEEZE DIAGNOSTIC ===");
        testBollingerSqueeze(candles);

        System.out.println();
        System.out.println("=== THREE MARKET PATTERN DIAGNOSTIC ===");
        testThreeMarketPattern(candles);

        System.out.println();
        System.out.println("=== TRIANGLE CONVERGENCE DIAGNOSTIC ===");
        testTriangleConvergence(candles);
    }

    static void testBollingerSqueeze(List<double[]> candles) {
        int BB_PERIOD = 20;
        double BB_STD_MULT = 2.0;
        int SQUEEZE_LOOKBACK = 15;
        double SQUEEZE_THRESHOLD = 0.75;

        int n = candles.size();
        int signalCount = 0;
        int step1Fail = 0, step2aFail = 0, step2bFail = 0, step2cFail = 0;
        int step3aFail = 0, step3bFail = 0, step3cFail = 0, step4Fail = 0;

        for (int cur = 40; cur < n; cur++) {
            double[] last = candles.get(cur);
            double close = last[3];

            // Step 1: BB calculation
            double[] bb = calcBB(candles, cur, BB_PERIOD, BB_STD_MULT);
            if (bb[1] <= 0) { step1Fail++; continue; }
            double bbWidth = (bb[2] - bb[0]) / bb[1];

            // Step 2: Squeeze detection
            double widthSum = 0;
            int widthCount = 0;
            double minWidth = Double.MAX_VALUE;

            for (int i = Math.max(BB_PERIOD, cur - SQUEEZE_LOOKBACK); i < cur; i++) {
                double sum = 0, sumSq = 0;
                for (int j = i - BB_PERIOD + 1; j <= i; j++) {
                    double p = candles.get(j)[3];
                    sum += p;
                    sumSq += p * p;
                }
                double mean = sum / BB_PERIOD;
                if (mean <= 0) continue;
                double variance = sumSq / BB_PERIOD - mean * mean;
                double std = Math.sqrt(Math.max(0, variance));
                double w = (BB_STD_MULT * 2 * std) / mean;
                widthSum += w;
                widthCount++;
                if (w < minWidth) minWidth = w;
            }
            if (widthCount < 3) { step2aFail++; continue; }
            double avgWidth = widthSum / widthCount;

            boolean hadSqueeze = minWidth < avgWidth * SQUEEZE_THRESHOLD;
            if (!hadSqueeze) { step2bFail++; continue; }

            boolean expanding = bbWidth > minWidth * 1.2;
            if (!expanding) { step2cFail++; continue; }

            // Step 3: Bullish candle above upper band
            boolean isBullish = last[3] > last[0]; // close > open
            if (!isBullish) { step3aFail++; continue; }
            if (close <= bb[2]) { step3bFail++; continue; }

            double body = Math.abs(last[3] - last[0]);
            double range = last[1] - last[2];
            double bodyRatio = range > 0 ? body / range : 0;
            if (bodyRatio < 0.40) { step3cFail++; continue; }

            // Step 4: Volume (skip, just count)
            signalCount++;
            System.out.printf("  SIGNAL at candle %d: close=%.2f bbUpper=%.2f squeeze=yes vol=%.1f%n",
                    cur, close, bb[2], last[4]);
        }

        System.out.println("  Total candles evaluated: " + (n - 40));
        System.out.println("  Step1 (bb invalid):    " + step1Fail);
        System.out.println("  Step2a (width count):  " + step2aFail);
        System.out.println("  Step2b (no squeeze):   " + step2bFail);
        System.out.println("  Step2c (not expanding):" + step2cFail);
        System.out.println("  Step3a (not bullish):  " + step3aFail);
        System.out.println("  Step3b (below upper):  " + step3bFail);
        System.out.println("  Step3c (body ratio):   " + step3cFail);
        System.out.println("  SIGNALS:               " + signalCount);
    }

    static void testThreeMarketPattern(List<double[]> candles) {
        int RANGE_LOOKBACK = 30;
        double RANGE_MAX_PCT = 0.18;
        double RANGE_MIN_PCT = 0.008;
        double FALSE_BREAKOUT_MIN = 0.001;
        double FALSE_BREAKOUT_MAX = 0.06;

        int n = candles.size();
        int signalCount = 0;
        int step1Fail = 0, step2Fail = 0, step3Fail = 0, step4Fail = 0;

        for (int cur = 50; cur < n; cur++) {
            double[] last = candles.get(cur);
            double close = last[3];

            int rangeEnd = cur - 1;
            int rangeStart = Math.max(0, rangeEnd - RANGE_LOOKBACK);

            double rangeHigh = Double.MIN_VALUE, rangeLow = Double.MAX_VALUE;
            for (int i = rangeStart; i <= rangeEnd; i++) {
                if (candles.get(i)[1] > rangeHigh) rangeHigh = candles.get(i)[1];
                if (candles.get(i)[2] < rangeLow) rangeLow = candles.get(i)[2];
            }
            double rangePct = (rangeHigh - rangeLow) / rangeLow;
            if (rangePct > RANGE_MAX_PCT || rangePct < RANGE_MIN_PCT) { step1Fail++; continue; }

            // Simplified: check if any false breakout occurred
            boolean fb = false;
            for (int i = rangeStart; i <= rangeEnd; i++) {
                double hi = candles.get(i)[1];
                double lo = candles.get(i)[2];
                double cl = candles.get(i)[3];
                // up false breakout
                if (hi > rangeHigh * 0.99 && cl < rangeHigh * 0.995) fb = true;
                // down false breakout
                if (lo < rangeLow * 1.01 && cl > rangeLow * 1.005) fb = true;
            }
            if (!fb) { step2Fail++; continue; }

            // New high breakout
            if (close <= rangeHigh * 1.001) { step3Fail++; continue; }

            // Bullish
            if (last[3] <= last[0]) { step4Fail++; continue; }

            signalCount++;
            System.out.printf("  SIGNAL at candle %d: close=%.2f rangeHigh=%.2f%n", cur, close, rangeHigh);
        }

        System.out.println("  Total candles evaluated: " + (n - 50));
        System.out.println("  Step1 (range invalid):  " + step1Fail);
        System.out.println("  Step2 (no false BO):    " + step2Fail);
        System.out.println("  Step3 (no new high):    " + step3Fail);
        System.out.println("  Step4 (not bullish):    " + step4Fail);
        System.out.println("  SIGNALS:                " + signalCount);
    }

    static void testTriangleConvergence(List<double[]> candles) {
        int SWING_WINDOW = 2;
        int TRI_LOOKBACK = 50;

        int n = candles.size();
        int signalCount = 0;
        int step1Fail = 0, step2Fail = 0, step3Fail = 0, step4Fail = 0;

        for (int cur = 40; cur < n; cur++) {
            double[] last = candles.get(cur);
            double close = last[3];
            int scanEnd = cur - 1;
            int scanStart = Math.max(0, scanEnd - TRI_LOOKBACK);

            // Find swing highs/lows
            int shCount = 0, slCount = 0;
            int[] shIdx = new int[20];
            int[] slIdx = new int[20];

            for (int i = scanStart + SWING_WINDOW; i <= scanEnd - SWING_WINDOW && (shCount < 20 || slCount < 20); i++) {
                boolean isHigh = true, isLow = true;
                double hi = candles.get(i)[1];
                double lo = candles.get(i)[2];

                for (int j = 1; j <= SWING_WINDOW; j++) {
                    if (candles.get(i - j)[1] >= hi || candles.get(i + j)[1] >= hi) isHigh = false;
                    if (candles.get(i - j)[2] <= lo || candles.get(i + j)[2] <= lo) isLow = false;
                }

                if (isHigh && shCount < 20) shIdx[shCount++] = i;
                if (isLow && slCount < 20) slIdx[slCount++] = i;
            }

            if (shCount < 2 || slCount < 2) { step1Fail++; continue; }

            // Check convergence (at least one descending high, one ascending low)
            boolean hasDescHigh = false, hasAscLow = false;
            for (int i = 1; i < shCount; i++) {
                if (candles.get(shIdx[i])[1] < candles.get(shIdx[i-1])[1]) hasDescHigh = true;
            }
            for (int i = 1; i < slCount; i++) {
                if (candles.get(slIdx[i])[2] > candles.get(slIdx[i-1])[2]) hasAscLow = true;
            }
            if (!hasDescHigh || !hasAscLow) { step2Fail++; continue; }

            // Convergence ratio
            double firstHigh = candles.get(shIdx[0])[1];
            double firstLow = candles.get(slIdx[0])[2];
            double lastHigh = candles.get(shIdx[shCount-1])[1];
            double lastLow = candles.get(slIdx[slCount-1])[2];
            double initialWidth = firstHigh - firstLow;
            double currentWidth = lastHigh - lastLow;
            if (initialWidth <= 0) { step3Fail++; continue; }
            double ratio = currentWidth / initialWidth;
            if (ratio < 0.15 || ratio > 0.92) { step3Fail++; continue; }

            // Trendline breakout
            double trendline = firstHigh + ((double)(cur - shIdx[0]) / (shIdx[shCount-1] - shIdx[0])) * (lastHigh - firstHigh);
            if (close <= trendline) { step3Fail++; continue; }

            // Bullish
            if (last[3] <= last[0]) { step4Fail++; continue; }

            signalCount++;
            System.out.printf("  SIGNAL at candle %d: close=%.4f trendline=%.4f%n", cur, close, trendline);
        }

        System.out.println("  Total candles evaluated: " + (n - 40));
        System.out.println("  Step1 (not enough swings):" + step1Fail);
        System.out.println("  Step2 (no convergence):   " + step2Fail);
        System.out.println("  Step3 (ratio/trendline):  " + step3Fail);
        System.out.println("  Step4 (not bullish):      " + step4Fail);
        System.out.println("  SIGNALS:                  " + signalCount);
    }

    static double[] calcBB(List<double[]> candles, int endIdx, int period, double mult) {
        if (endIdx < period - 1) return new double[]{0, 0, 0};
        double sum = 0;
        for (int i = endIdx - period + 1; i <= endIdx; i++) sum += candles.get(i)[3];
        double sma = sum / period;
        double sumSq = 0;
        for (int i = endIdx - period + 1; i <= endIdx; i++) {
            double diff = candles.get(i)[3] - sma;
            sumSq += diff * diff;
        }
        double std = Math.sqrt(sumSq / period);
        return new double[]{sma - mult * std, sma, sma + mult * std};
    }

    static double extractNum(String s, String key) {
        int idx = s.indexOf("\"" + key + "\":");
        if (idx < 0) return 0;
        int start = idx + key.length() + 3;
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.' || s.charAt(end) == '-' || s.charAt(end) == 'E')) end++;
        try { return Double.parseDouble(s.substring(start, end)); } catch (Exception e) { return 0; }
    }

    static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.connect();
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }
}
