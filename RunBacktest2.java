import java.io.*;
import java.net.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;

/**
 * Phase 2B: TP/SL Grid Search for top strategies
 * Focus on IBB, BEC, BBSq with different TP/SL/interval/confidence combos
 */
public class RunBacktest2 {

    static String BASE = "http://localhost:8080";
    static String sessionCookie = null;
    static String xsrfToken = null;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Phase 2B: TP/SL Grid Search ===\n");

        // Login
        String pubKeyJson = httpGet(BASE + "/api/auth/pubkey");
        String pubKeyB64 = extractJson(pubKeyJson, "publicKey");
        byte[] keyBytes = Base64.getDecoder().decode(pubKeyB64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey publicKey = kf.generatePublic(spec);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        String encPwd = Base64.getEncoder().encodeToString(cipher.doFinal("admin123".getBytes("UTF-8")));
        String loginResp = httpPost(BASE + "/api/auth/login",
            "{\"username\":\"admin\",\"encryptedPassword\":\"" + encPwd + "\"}", "application/json");
        System.out.println("Login: " + extractJson(loginResp, "success"));

        String[] markets = {"KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-ADA"};

        // ==== TEST 1: IBB+BE+ESS with different TP/SL combos ====
        System.out.println("\n=== IBB+BE+ESS: TP/SL Grid ===\n");
        double[] tps = {2.0, 3.0, 4.0, 5.0, 7.0, 10.0};
        double[] sls = {1.0, 1.5, 2.0, 3.0, 5.0};

        for (String market : markets) {
            String coin = market.split("-")[1];
            for (double tp : tps) {
                for (double sl : sls) {
                    if (tp <= sl) continue; // TP must > SL
                    String body = "{\"period\":\"365d\",\"capitalKrw\":1000000,\"groups\":[{\"groupName\":\"" + coin + "\","
                        + "\"markets\":[\"" + market + "\"],"
                        + "\"strategies\":[\"INSIDE_BAR_BREAKOUT\",\"BEARISH_ENGULFING\",\"EVENING_STAR_SELL\"],"
                        + "\"candleUnitMin\":240,\"takeProfitPct\":" + tp + ",\"stopLossPct\":" + sl + ",\"maxAddBuys\":0,"
                        + "\"minConfidence\":4.0,\"strategyLock\":false,\"timeStopMinutes\":0,"
                        + "\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}";
                    runBacktest(market + " IBB TP" + tp + "/SL" + sl, body);
                }
            }
        }

        // ==== TEST 2: BEC+BE+ESS with TP/SL ====
        System.out.println("\n=== BEC+BE+ESS: TP/SL Grid ===\n");
        for (String market : markets) {
            String coin = market.split("-")[1];
            for (double tp : tps) {
                for (double sl : sls) {
                    if (tp <= sl) continue;
                    String body = "{\"period\":\"365d\",\"capitalKrw\":1000000,\"groups\":[{\"groupName\":\"" + coin + "\","
                        + "\"markets\":[\"" + market + "\"],"
                        + "\"strategies\":[\"BULLISH_ENGULFING_CONFIRM\",\"BEARISH_ENGULFING\",\"EVENING_STAR_SELL\"],"
                        + "\"candleUnitMin\":240,\"takeProfitPct\":" + tp + ",\"stopLossPct\":" + sl + ",\"maxAddBuys\":0,"
                        + "\"minConfidence\":4.0,\"strategyLock\":false,\"timeStopMinutes\":0,"
                        + "\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}";
                    runBacktest(market + " BEC TP" + tp + "/SL" + sl, body);
                }
            }
        }

        // ==== TEST 3: BBSq standalone with TP/SL ====
        System.out.println("\n=== BBSq standalone: TP/SL Grid ===\n");
        for (String market : markets) {
            String coin = market.split("-")[1];
            for (double tp : tps) {
                for (double sl : sls) {
                    if (tp <= sl) continue;
                    String body = "{\"period\":\"365d\",\"capitalKrw\":1000000,\"groups\":[{\"groupName\":\"" + coin + "\","
                        + "\"markets\":[\"" + market + "\"],"
                        + "\"strategies\":[\"BOLLINGER_SQUEEZE_BREAKOUT\"],"
                        + "\"candleUnitMin\":60,\"takeProfitPct\":" + tp + ",\"stopLossPct\":" + sl + ",\"maxAddBuys\":0,"
                        + "\"minConfidence\":4.0,\"strategyLock\":false,\"timeStopMinutes\":0,"
                        + "\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}";
                    runBacktest(market + " BBSq TP" + tp + "/SL" + sl, body);
                }
            }
        }

        // ==== TEST 4: Best combos IBB+BEC (2 buy strats) ====
        System.out.println("\n=== IBB+BEC+BE+ESS combo ===\n");
        double[] bestTps = {3.0, 5.0, 7.0};
        double[] bestSls = {1.5, 2.0, 3.0};
        for (String market : markets) {
            String coin = market.split("-")[1];
            for (double tp : bestTps) {
                for (double sl : bestSls) {
                    if (tp <= sl) continue;
                    String body = "{\"period\":\"365d\",\"capitalKrw\":1000000,\"groups\":[{\"groupName\":\"" + coin + "\","
                        + "\"markets\":[\"" + market + "\"],"
                        + "\"strategies\":[\"INSIDE_BAR_BREAKOUT\",\"BULLISH_ENGULFING_CONFIRM\",\"BEARISH_ENGULFING\",\"EVENING_STAR_SELL\"],"
                        + "\"candleUnitMin\":240,\"takeProfitPct\":" + tp + ",\"stopLossPct\":" + sl + ",\"maxAddBuys\":0,"
                        + "\"minConfidence\":4.0,\"strategyLock\":false,\"timeStopMinutes\":0,"
                        + "\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}";
                    runBacktest(market + " IBB+BEC TP" + tp + "/SL" + sl, body);
                }
            }
        }

        // ==== TEST 5: Interval test (60min vs 240min) for IBB ====
        System.out.println("\n=== IBB Interval Test (60 vs 240) ===\n");
        for (String market : markets) {
            String coin = market.split("-")[1];
            String body60 = "{\"period\":\"365d\",\"capitalKrw\":1000000,\"groups\":[{\"groupName\":\"" + coin + "\","
                + "\"markets\":[\"" + market + "\"],"
                + "\"strategies\":[\"INSIDE_BAR_BREAKOUT\",\"BEARISH_ENGULFING\",\"EVENING_STAR_SELL\"],"
                + "\"candleUnitMin\":60,\"takeProfitPct\":5.0,\"stopLossPct\":2.0,\"maxAddBuys\":0,"
                + "\"minConfidence\":4.0,\"strategyLock\":false,\"timeStopMinutes\":0,"
                + "\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}";
            runBacktest(market + " IBB 60min", body60);

            String body240 = "{\"period\":\"365d\",\"capitalKrw\":1000000,\"groups\":[{\"groupName\":\"" + coin + "\","
                + "\"markets\":[\"" + market + "\"],"
                + "\"strategies\":[\"INSIDE_BAR_BREAKOUT\",\"BEARISH_ENGULFING\",\"EVENING_STAR_SELL\"],"
                + "\"candleUnitMin\":240,\"takeProfitPct\":5.0,\"stopLossPct\":2.0,\"maxAddBuys\":0,"
                + "\"minConfidence\":4.0,\"strategyLock\":false,\"timeStopMinutes\":0,"
                + "\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}";
            runBacktest(market + " IBB 240min", body240);
        }

        System.out.println("\n=== ALL GRID TESTS COMPLETE ===");
    }

    static void runBacktest(String name, String jsonBody) {
        try {
            long start = System.currentTimeMillis();
            String resp = httpPostAuth(BASE + "/api/backtest/run", jsonBody);
            long elapsed = (System.currentTimeMillis() - start) / 1000;

            String roi = extractJson(resp, "roi");
            String trades = extractJson(resp, "tradesCount");
            String winRate = extractJson(resp, "winRate");
            String finalCap = extractJson(resp, "finalCapital");
            String wins = extractJson(resp, "wins");
            String tpSells = extractJson(resp, "tpSellCount");
            String slSells = extractJson(resp, "slSellCount");
            String patternSells = extractJson(resp, "patternSellCount");

            // Format numbers
            double roiVal = roi != null ? Double.parseDouble(roi) : 0;
            double wrVal = winRate != null ? Double.parseDouble(winRate) : 0;
            double capVal = finalCap != null ? Double.parseDouble(finalCap) : 0;

            System.out.printf("%-45s  ROI:%+7.1f%%  Trades:%-4s  WR:%.0f%%  Final:%,.0f  (TP:%s SL:%s Pat:%s) %ds%n",
                name, roiVal, trades, wrVal, capVal, tpSells, slSells, patternSells, elapsed);
        } catch (Exception e) {
            System.out.printf("%-45s  ERROR: %s%n", name, e.getMessage());
        }
    }

    static String extractJson(String json, String key) {
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
        conn.setReadTimeout(300000);
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
