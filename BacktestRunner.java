import java.io.*;
import java.net.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;

/**
 * Batch Backtest Runner: 3 new strategies x 5 coins x 4 intervals x all combos
 * Outputs results to CSV for Excel analysis.
 */
public class BacktestRunner {

    private static final String BASE = "http://localhost:8080";
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "Test1234!";

    // 3 new strategies
    private static final String[] STRATEGIES = {
        "THREE_MARKET_PATTERN", "BOLLINGER_SQUEEZE_BREAKOUT", "TRIANGLE_CONVERGENCE"
    };

    // 5 coins
    private static final String[] COINS = {
        "KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-ADA"
    };

    // 4 intervals
    private static final int[] INTERVALS = {15, 30, 60, 240};

    private String sessionCookie;
    private String csrfToken;
    private int debugResponseCount = 0;

    public static void main(String[] args) throws Exception {
        BacktestRunner runner = new BacktestRunner();
        runner.login();
        runner.runAllBacktests();
    }

    private void login() throws Exception {
        // 1. Get RSA public key
        String pubKeyResp = httpGet(BASE + "/api/auth/pubkey");
        String pubKeyB64 = pubKeyResp.split("\"publicKey\":\"")[1].split("\"")[0];

        // 2. Encrypt password with RSA
        byte[] keyBytes = Base64.getDecoder().decode(pubKeyB64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey pubKey = kf.generatePublic(spec);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pubKey);
        String encPwd = Base64.getEncoder().encodeToString(cipher.doFinal(PASSWORD.getBytes("UTF-8")));

        // 3. Get CSRF token from login page
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + "/login").openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.connect();
        // Extract Set-Cookie for XSRF-TOKEN and JSESSIONID
        Map<String, List<String>> headers = conn.getHeaderFields();
        StringBuilder cookies = new StringBuilder();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if ("Set-Cookie".equalsIgnoreCase(e.getKey())) {
                for (String v : e.getValue()) {
                    String cookiePart = v.split(";")[0];
                    if (cookies.length() > 0) cookies.append("; ");
                    cookies.append(cookiePart);
                    if (cookiePart.startsWith("XSRF-TOKEN=")) {
                        csrfToken = cookiePart.substring("XSRF-TOKEN=".length());
                    }
                }
            }
        }
        conn.disconnect();
        sessionCookie = cookies.toString();

        // 4. Login
        String loginBody = "{\"username\":\"" + USERNAME + "\",\"encryptedPassword\":\"" + encPwd + "\"}";
        String loginResp = httpPost(BASE + "/api/auth/login", loginBody);
        System.out.println("Login: " + loginResp);
        if (!loginResp.contains("\"success\":true")) {
            throw new RuntimeException("Login failed: " + loginResp);
        }
    }

    private void runAllBacktests() throws Exception {
        // Generate all strategy combinations
        List<List<String>> combos = new ArrayList<List<String>>();
        // Singles (3)
        for (String s : STRATEGIES) combos.add(Arrays.asList(s));
        // Pairs (3)
        for (int i = 0; i < STRATEGIES.length; i++) {
            for (int j = i + 1; j < STRATEGIES.length; j++) {
                combos.add(Arrays.asList(STRATEGIES[i], STRATEGIES[j]));
            }
        }
        // Triple (1)
        combos.add(Arrays.asList(STRATEGIES));

        // Total: 7 combos x 5 coins x 4 intervals = 140 tests
        int total = combos.size() * COINS.length * INTERVALS.length;
        System.out.println("Total tests: " + total);

        // CSV output
        StringBuilder csv = new StringBuilder();
        csv.append("combo,coin,interval_min,trades,wins,win_rate,total_pnl_krw,roi_pct,final_capital,tp_sells,sl_sells,pattern_sells,candle_count,period_days\n");

        int done = 0;
        for (List<String> combo : combos) {
            String comboName = String.join("+", combo);
            for (String coin : COINS) {
                for (int interval : INTERVALS) {
                    done++;
                    System.out.printf("[%d/%d] %s | %s | %dmin%n", done, total, comboName, coin, interval);
                    try {
                        String result = runSingleBacktest(combo, coin, interval);
                        csv.append(result).append("\n");
                        // Rate limit - Upbit API throttle
                        Thread.sleep(1500);
                    } catch (Exception e) {
                        System.out.println("  ERROR: " + e.getMessage());
                        csv.append(comboName).append(",").append(coin).append(",").append(interval)
                           .append(",ERROR,,,,,,,,,,\n");
                        Thread.sleep(2000);
                    }
                }
            }
        }

        // Write CSV
        String csvFile = "C:/workspace/upbit-autotrade-java8/upbit-autotrade/data/backtest_results.csv";
        try (FileWriter fw = new FileWriter(csvFile)) {
            fw.write(csv.toString());
        }
        System.out.println("\nDone! Results: " + csvFile);
    }

    private String runSingleBacktest(List<String> strategies, String coin, int interval) throws Exception {
        // Build JSON request
        StringBuilder strats = new StringBuilder("[");
        for (int i = 0; i < strategies.size(); i++) {
            if (i > 0) strats.append(",");
            strats.append("\"").append(strategies.get(i)).append("\"");
        }
        strats.append("]");

        String body = "{"
            + "\"strategies\":" + strats.toString() + ","
            + "\"market\":\"" + coin + "\","
            + "\"period\":\"90d\","
            + "\"candleUnitMin\":" + interval + ","
            + "\"capitalKrw\":1000000,"
            + "\"orderSizingMode\":\"PCT\","
            + "\"orderSizingValue\":90,"
            + "\"takeProfitPct\":5.0,"
            + "\"stopLossPct\":3.0,"
            + "\"maxAddBuysGlobal\":2,"
            + "\"minConfidence\":1.0,"
            + "\"strategyLock\":true"
            + "}";

        String resp = httpPost(BASE + "/api/backtest/run", body);

        // Debug: print first response to verify field names
        if (debugResponseCount < 1) {
            debugResponseCount++;
            // Print first 500 chars (skip trades array)
            int tradesIdx = resp.indexOf("\"trades\":[");
            String preview = tradesIdx > 0 ? resp.substring(0, tradesIdx) + "...}" : resp.substring(0, Math.min(500, resp.length()));
            System.out.println("  DEBUG response: " + preview);
        }

        // Parse response
        String comboName = String.join("+", strategies);
        return parseResponse(resp, comboName, coin, interval);
    }

    private String parseResponse(String json, String combo, String coin, int interval) {
        try {
            // Field names match BacktestResponse.java exactly
            int trades = extractInt(json, "\"tradesCount\":", 0);
            if (trades == 0) trades = extractInt(json, "\"totalTrades\":", 0);
            int wins = extractInt(json, "\"wins\":", 0);
            double winRate = extractDouble(json, "\"winRate\":", 0);
            double totalPnl = extractDouble(json, "\"totalPnl\":", 0);
            double roi = extractDouble(json, "\"roi\":", 0);
            double finalCap = extractDouble(json, "\"finalCapital\":", 0);
            int tpSells = extractInt(json, "\"tpSellCount\":", 0);
            int slSells = extractInt(json, "\"slSellCount\":", 0);
            int patSells = extractInt(json, "\"patternSellCount\":", 0);
            int candleCount = extractInt(json, "\"candleCount\":", 0);
            int periodDays = extractInt(json, "\"periodDays\":", 0);

            return String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%.2f,%.2f,%.4f,%.2f,%d,%d,%d,%d,%d",
                    combo, coin, interval, trades, wins, winRate,
                    totalPnl, roi, finalCap, tpSells, slSells, patSells, candleCount, periodDays);
        } catch (Exception e) {
            return combo + "," + coin + "," + interval + ",PARSE_ERROR,,,,,,,,,";
        }
    }

    private int extractInt(String json, String key, int def) {
        int idx = json.indexOf(key);
        if (idx < 0) return def;
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Integer.parseInt(json.substring(start, end)); } catch (Exception e) { return def; }
    }

    private double extractDouble(String json, String key, double def) {
        int idx = json.indexOf(key);
        if (idx < 0) return def;
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-' || json.charAt(end) == 'E')) end++;
        try { return Double.parseDouble(json.substring(start, end)); } catch (Exception e) { return def; }
    }

    // ===== HTTP Helpers =====
    private String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        if (sessionCookie != null) conn.setRequestProperty("Cookie", sessionCookie);
        conn.connect();
        updateCookies(conn);
        return readBody(conn);
    }

    private String httpPost(String url, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (sessionCookie != null) conn.setRequestProperty("Cookie", sessionCookie);
        if (csrfToken != null) conn.setRequestProperty("X-XSRF-TOKEN", csrfToken);
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes("UTF-8"));
        }
        updateCookies(conn);
        return readBody(conn);
    }

    private void updateCookies(HttpURLConnection conn) {
        Map<String, List<String>> headers = conn.getHeaderFields();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if ("Set-Cookie".equalsIgnoreCase(e.getKey())) {
                for (String v : e.getValue()) {
                    String part = v.split(";")[0];
                    if (part.startsWith("XSRF-TOKEN=")) {
                        csrfToken = part.substring("XSRF-TOKEN=".length());
                    }
                    // Update session cookie
                    if (part.startsWith("JSESSIONID=")) {
                        if (sessionCookie == null) sessionCookie = part;
                        else {
                            // Replace existing JSESSIONID
                            sessionCookie = sessionCookie.replaceAll("JSESSIONID=[^;]*", part);
                            if (!sessionCookie.contains("JSESSIONID")) {
                                sessionCookie += "; " + part;
                            }
                        }
                    }
                    if (part.startsWith("XSRF-TOKEN=") && sessionCookie != null && !sessionCookie.contains("XSRF-TOKEN")) {
                        sessionCookie += "; " + part;
                    }
                }
            }
        }
    }

    private String readBody(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }
}
