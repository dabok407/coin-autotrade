import java.io.*;
import java.net.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;

/**
 * Standalone Java script to:
 * 1. Login via RSA-encrypted auth
 * 2. Run expert-recommended backtests for 5 coins
 *
 * Usage: java RunBacktest.java
 */
public class RunBacktest {

    static String BASE = "http://localhost:8080";
    static String sessionCookie = null;
    static String xsrfToken = null;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Expert Backtest Runner ===\n");

        // Step 1: Get RSA public key
        System.out.println("[1] Getting RSA public key...");
        String pubKeyJson = httpGet(BASE + "/api/auth/pubkey");
        String pubKeyB64 = extractJson(pubKeyJson, "publicKey");
        System.out.println("  Public key received (" + pubKeyB64.length() + " chars)");

        // Step 2: Encrypt password
        byte[] keyBytes = Base64.getDecoder().decode(pubKeyB64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey publicKey = kf.generatePublic(spec);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        String encryptedPassword = Base64.getEncoder().encodeToString(
            cipher.doFinal("admin123".getBytes("UTF-8"))
        );
        System.out.println("  Password encrypted");

        // Step 3: Login
        System.out.println("\n[2] Logging in...");
        String loginBody = "{\"username\":\"admin\",\"encryptedPassword\":\"" + encryptedPassword + "\"}";
        String loginResp = httpPost(BASE + "/api/auth/login", loginBody, "application/json");
        System.out.println("  Login response: " + loginResp);

        if (sessionCookie == null) {
            System.out.println("ERROR: No session cookie received");
            System.exit(1);
        }
        System.out.println("  Session: " + sessionCookie.substring(0, Math.min(20, sessionCookie.length())) + "...");
        System.out.println("  XSRF: " + (xsrfToken != null ? xsrfToken.substring(0, Math.min(20, xsrfToken.length())) + "..." : "null"));

        // Step 4: Verify auth
        System.out.println("\n[3] Verifying authentication...");
        String strategies = httpGetAuth(BASE + "/api/strategies");
        System.out.println("  Strategies endpoint: " + (strategies.contains("ADAPTIVE_TREND_MOMENTUM") ? "OK" : "FAILED"));

        // Step 5: Run backtests
        System.out.println("\n[4] Running backtests...\n");

        // Test 1: BTC (ATM + ERT + BE + ESS) TP2/SL1.5
        runBacktest("KRW-BTC (ATM+ERT, TP2/SL1.5)",
            "{\"period\":\"365d\",\"capitalKrw\":1000000,\"groups\":[{\"groupName\":\"BTC\",\"markets\":[\"KRW-BTC\"],"
            + "\"strategies\":[\"ADAPTIVE_TREND_MOMENTUM\",\"EMA_RSI_TREND\",\"BEARISH_ENGULFING\",\"EVENING_STAR_SELL\"],"
            + "\"candleUnitMin\":60,\"takeProfitPct\":2.0,\"stopLossPct\":1.5,\"maxAddBuys\":0,"
            + "\"minConfidence\":6.0,\"strategyLock\":false,\"timeStopMinutes\":360,"
            + "\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}");

        // Test 2: SOL (RFP + ATM + BBSqueeze + BE + ESS) TP5/SL3
        runBacktest("KRW-SOL (RFP+ATM+BBSq, TP5/SL3)",
            "{\"period\":\"365d\",\"capitalKrw\":1000000,\"groups\":[{\"groupName\":\"SOL\",\"markets\":[\"KRW-SOL\"],"
            + "\"strategies\":[\"REGIME_PULLBACK\",\"ADAPTIVE_TREND_MOMENTUM\",\"BOLLINGER_SQUEEZE_BREAKOUT\","
            + "\"BEARISH_ENGULFING\",\"EVENING_STAR_SELL\"],"
            + "\"candleUnitMin\":60,\"takeProfitPct\":5.0,\"stopLossPct\":3.0,\"maxAddBuys\":0,"
            + "\"minConfidence\":6.0,\"strategyLock\":false,\"timeStopMinutes\":720,"
            + "\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}");

        // Test 3: XRP (BEC + BE + ESS + 3BC) TP4/SL2
        runBacktest("KRW-XRP (BEC+sell, TP4/SL2)",
            "{\"period\":\"365d\",\"capitalKrw\":1000000,\"groups\":[{\"groupName\":\"XRP\",\"markets\":[\"KRW-XRP\"],"
            + "\"strategies\":[\"BULLISH_ENGULFING_CONFIRM\",\"BEARISH_ENGULFING\",\"EVENING_STAR_SELL\","
            + "\"THREE_BLACK_CROWS_SELL\"],"
            + "\"candleUnitMin\":240,\"takeProfitPct\":4.0,\"stopLossPct\":2.0,\"maxAddBuys\":0,"
            + "\"minConfidence\":4.0,\"strategyLock\":false,\"timeStopMinutes\":0,"
            + "\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}");

        // Test 4: ETH (ATM + ERT + MFP + BE + ESS) TP3/SL2
        runBacktest("KRW-ETH (ATM+ERT+MFP, TP3/SL2)",
            "{\"period\":\"365d\",\"capitalKrw\":1000000,\"groups\":[{\"groupName\":\"ETH\",\"markets\":[\"KRW-ETH\"],"
            + "\"strategies\":[\"ADAPTIVE_TREND_MOMENTUM\",\"EMA_RSI_TREND\",\"MOMENTUM_FVG_PULLBACK\","
            + "\"BEARISH_ENGULFING\",\"EVENING_STAR_SELL\"],"
            + "\"candleUnitMin\":60,\"takeProfitPct\":3.0,\"stopLossPct\":2.0,\"maxAddBuys\":0,"
            + "\"minConfidence\":6.0,\"strategyLock\":false,\"timeStopMinutes\":360,"
            + "\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}");

        // Test 5: ADA (ERT + BBSqueeze + 3MKT + BE + ESS + 3BC) TP3/SL2
        runBacktest("KRW-ADA (ERT+BBSq+3MKT, TP3/SL2)",
            "{\"period\":\"365d\",\"capitalKrw\":1000000,\"groups\":[{\"groupName\":\"ADA\",\"markets\":[\"KRW-ADA\"],"
            + "\"strategies\":[\"EMA_RSI_TREND\",\"BOLLINGER_SQUEEZE_BREAKOUT\",\"THREE_MARKET_PATTERN\","
            + "\"BEARISH_ENGULFING\",\"EVENING_STAR_SELL\",\"THREE_BLACK_CROWS_SELL\"],"
            + "\"candleUnitMin\":60,\"takeProfitPct\":3.0,\"stopLossPct\":2.0,\"maxAddBuys\":0,"
            + "\"minConfidence\":6.0,\"strategyLock\":false,\"timeStopMinutes\":360,"
            + "\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}");

        // === INDIVIDUAL STRATEGY BASELINES ===
        System.out.println("\n========================================");
        System.out.println("=== INDIVIDUAL STRATEGY BASELINES ===");
        System.out.println("========================================\n");

        String[] buyStrategies = {
            "ADAPTIVE_TREND_MOMENTUM", "EMA_RSI_TREND", "REGIME_PULLBACK",
            "BOLLINGER_SQUEEZE_BREAKOUT", "THREE_MARKET_PATTERN", "TRIANGLE_CONVERGENCE",
            "BULLISH_ENGULFING_CONFIRM", "MOMENTUM_FVG_PULLBACK",
            "BULLISH_PINBAR_ORDERBLOCK", "INSIDE_BAR_BREAKOUT", "MORNING_STAR"
        };

        String[] markets = {"KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-ADA"};

        // Self-contained strategies (have own sell logic): test alone
        String[] selfContained = {
            "ADAPTIVE_TREND_MOMENTUM", "REGIME_PULLBACK",
            "BOLLINGER_SQUEEZE_BREAKOUT", "THREE_MARKET_PATTERN", "TRIANGLE_CONVERGENCE"
        };

        // Buy-only strategies: need sell strategies (BE+ESS)
        String[] buyOnly = {
            "EMA_RSI_TREND", "BULLISH_ENGULFING_CONFIRM", "MOMENTUM_FVG_PULLBACK",
            "BULLISH_PINBAR_ORDERBLOCK", "INSIDE_BAR_BREAKOUT", "MORNING_STAR"
        };

        for (String market : markets) {
            String coin = market.split("-")[1];

            // Self-contained: alone
            for (String strat : selfContained) {
                boolean isCandlePattern = strat.equals("BULLISH_ENGULFING_CONFIRM");
                int interval = isCandlePattern ? 240 : 60;
                String body = "{\"period\":\"365d\",\"capitalKrw\":1000000,\"groups\":[{\"groupName\":\"" + coin + "\","
                    + "\"markets\":[\"" + market + "\"],"
                    + "\"strategies\":[\"" + strat + "\"],"
                    + "\"candleUnitMin\":" + interval + ",\"takeProfitPct\":3.0,\"stopLossPct\":2.0,\"maxAddBuys\":0,"
                    + "\"minConfidence\":4.0,\"strategyLock\":false,\"timeStopMinutes\":360,"
                    + "\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}";
                runBacktest(market + " [" + strat + "] alone", body);
            }

            // Buy-only: with BE+ESS
            for (String strat : buyOnly) {
                boolean isCandlePattern = strat.equals("BULLISH_ENGULFING_CONFIRM")
                    || strat.equals("MOMENTUM_FVG_PULLBACK")
                    || strat.equals("BULLISH_PINBAR_ORDERBLOCK")
                    || strat.equals("INSIDE_BAR_BREAKOUT")
                    || strat.equals("MORNING_STAR");
                int interval = isCandlePattern ? 240 : 60;
                String body = "{\"period\":\"365d\",\"capitalKrw\":1000000,\"groups\":[{\"groupName\":\"" + coin + "\","
                    + "\"markets\":[\"" + market + "\"],"
                    + "\"strategies\":[\"" + strat + "\",\"BEARISH_ENGULFING\",\"EVENING_STAR_SELL\"],"
                    + "\"candleUnitMin\":" + interval + ",\"takeProfitPct\":3.0,\"stopLossPct\":2.0,\"maxAddBuys\":0,"
                    + "\"minConfidence\":4.0,\"strategyLock\":false,\"timeStopMinutes\":360,"
                    + "\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}";
                runBacktest(market + " [" + strat + "+BE+ESS]", body);
            }
        }

        System.out.println("\n=== ALL TESTS COMPLETE ===");
    }

    static void runBacktest(String name, String jsonBody) {
        try {
            long start = System.currentTimeMillis();
            String resp = httpPostAuth(BASE + "/api/backtest/run", jsonBody);
            long elapsed = (System.currentTimeMillis() - start) / 1000;

            // Simple JSON parsing (BacktestResponse fields)
            String roi = extractJson(resp, "roi");
            String trades = extractJson(resp, "tradesCount");
            String winRate = extractJson(resp, "winRate");
            String finalCap = extractJson(resp, "finalCapital");
            String wins = extractJson(resp, "wins");
            String tpSells = extractJson(resp, "tpSellCount");
            String slSells = extractJson(resp, "slSellCount");
            String patternSells = extractJson(resp, "patternSellCount");
            String usedTp = extractJson(resp, "usedTpPct");
            String usedSl = extractJson(resp, "usedSlPct");
            String note = extractJson(resp, "note");

            System.out.printf("=== %s === (%ds)%n", name, elapsed);
            System.out.printf("  ROI: %s%%  Trades: %s  WinRate: %s%%  Final: %s%n",
                roi, trades, winRate, finalCap);
            System.out.printf("  Wins: %s  TP: %s  SL: %s  Pattern: %s  (TP%%=%s, SL%%=%s)%n",
                wins, tpSells, slSells, patternSells, usedTp, usedSl);
            if (note != null) System.out.println("  Note: " + note);
        } catch (Exception e) {
            System.out.printf("=== %s === ERROR: %s%n", name, e.getMessage());
        }
    }

    static String extractJson(String json, String key) {
        // Simple extraction: "key":value or "key":"value"
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx += search.length();
        if (json.charAt(idx) == '"') {
            int end = json.indexOf('"', idx + 1);
            return json.substring(idx + 1, end);
        } else {
            int end = idx;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(idx, end).trim();
        }
    }

    static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        captureCookies(conn);
        return readResponse(conn);
    }

    static String httpGetAuth(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        if (sessionCookie != null) conn.setRequestProperty("Cookie", buildCookieHeader());
        if (xsrfToken != null) conn.setRequestProperty("X-XSRF-TOKEN", xsrfToken);
        captureCookies(conn);
        return readResponse(conn);
    }

    static String httpPost(String urlStr, String body, String contentType) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Content-Type", contentType);
        if (sessionCookie != null) conn.setRequestProperty("Cookie", buildCookieHeader());
        if (xsrfToken != null) conn.setRequestProperty("X-XSRF-TOKEN", xsrfToken);
        conn.getOutputStream().write(body.getBytes("UTF-8"));
        captureCookies(conn);
        return readResponse(conn);
    }

    static String httpPostAuth(String urlStr, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(300000); // 5 min for backtest
        conn.setRequestProperty("Content-Type", "application/json");
        if (sessionCookie != null) conn.setRequestProperty("Cookie", buildCookieHeader());
        if (xsrfToken != null) conn.setRequestProperty("X-XSRF-TOKEN", xsrfToken);
        conn.getOutputStream().write(body.getBytes("UTF-8"));
        captureCookies(conn);
        return readResponse(conn);
    }

    static String buildCookieHeader() {
        StringBuilder sb = new StringBuilder();
        if (sessionCookie != null) sb.append("JSESSIONID=").append(sessionCookie);
        if (xsrfToken != null) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("XSRF-TOKEN=").append(xsrfToken);
        }
        return sb.toString();
    }

    static void captureCookies(HttpURLConnection conn) {
        List<String> cookies = conn.getHeaderFields().get("Set-Cookie");
        if (cookies != null) {
            for (String cookie : cookies) {
                if (cookie.startsWith("JSESSIONID=")) {
                    sessionCookie = cookie.split(";")[0].substring("JSESSIONID=".length());
                }
                if (cookie.startsWith("XSRF-TOKEN=")) {
                    xsrfToken = cookie.split(";")[0].substring("XSRF-TOKEN=".length());
                }
            }
        }
    }

    static String readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }
}
