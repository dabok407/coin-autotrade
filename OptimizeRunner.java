import java.io.*;
import java.net.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;

/**
 * TP/SL/Confidence optimization backtest runner.
 * Tests various parameter combos on the winning strategy/coin/interval setups.
 */
public class OptimizeRunner {

    private static final String BASE = "http://localhost:8080";
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "Test1234!";

    private String sessionCookie;
    private String csrfToken;

    // Winning targets from initial 140-test scan (240min only, all profitable or near-profitable)
    private static final String[][] TARGETS = {
        {"THREE_MARKET_PATTERN", "KRW-XRP"},
        {"THREE_MARKET_PATTERN", "KRW-SOL"},
        {"THREE_MARKET_PATTERN", "KRW-BTC"},
        {"THREE_MARKET_PATTERN", "KRW-ADA"},
        {"TRIANGLE_CONVERGENCE", "KRW-XRP"},
        {"TRIANGLE_CONVERGENCE", "KRW-SOL"},
        {"BOLLINGER_SQUEEZE_BREAKOUT", "KRW-XRP"},
        {"THREE_MARKET_PATTERN\",\"TRIANGLE_CONVERGENCE", "KRW-XRP"},
        {"THREE_MARKET_PATTERN\",\"TRIANGLE_CONVERGENCE", "KRW-SOL"},
        {"THREE_MARKET_PATTERN\",\"BOLLINGER_SQUEEZE_BREAKOUT", "KRW-XRP"},
    };

    private static final double[] TP_VALUES = {3.0, 5.0, 7.0, 10.0, 15.0};
    private static final double[] SL_VALUES = {2.0, 3.0, 5.0, 7.0};
    private static final double[] CONF_VALUES = {1.0, 3.0, 5.0};

    public static void main(String[] args) throws Exception {
        OptimizeRunner runner = new OptimizeRunner();
        runner.login();
        runner.runOptimization();
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

    private void runOptimization() throws Exception {
        int totalTests = TARGETS.length * TP_VALUES.length * SL_VALUES.length * CONF_VALUES.length;
        System.out.println("=== Optimization: " + totalTests + " tests ===");

        String outFile = "data/optimize_results.csv";
        new File("data").mkdirs();
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outFile), "UTF-8"));
        pw.println("strategies,coin,interval,tp_pct,sl_pct,min_confidence,trades,wins,win_rate,total_pnl,roi_pct,final_capital,tp_sells,sl_sells,pattern_sells");

        int done = 0;
        for (String[] target : TARGETS) {
            String stratJson = target[0]; // already formatted for JSON array
            String coin = target[1];
            String label = stratJson.replace("\",\"", "+").replace("\"", "");

            for (double tp : TP_VALUES) {
                for (double sl : SL_VALUES) {
                    for (double conf : CONF_VALUES) {
                        done++;
                        try {
                            String body = "{"
                                + "\"strategies\":[\"" + stratJson + "\"],"
                                + "\"market\":\"" + coin + "\","
                                + "\"period\":\"90d\","
                                + "\"candleUnitMin\":240,"
                                + "\"capitalKrw\":1000000,"
                                + "\"orderSizingMode\":\"PCT\","
                                + "\"orderSizingValue\":90,"
                                + "\"takeProfitPct\":" + tp + ","
                                + "\"stopLossPct\":" + sl + ","
                                + "\"maxAddBuysGlobal\":2,"
                                + "\"minConfidence\":" + conf + ","
                                + "\"strategyLock\":true"
                                + "}";

                            String resp = httpPost(BASE + "/api/backtest/run", body);

                            int trades = extractInt(resp, "\"totalTrades\":", 0);
                            int wins = extractInt(resp, "\"winCount\":", 0);
                            double wr = extractDouble(resp, "\"winRate\":", 0);
                            double pnl = extractDouble(resp, "\"totalPnlKrw\":", 0);
                            double roi = extractDouble(resp, "\"roiPct\":", 0);
                            double fc = extractDouble(resp, "\"finalCapital\":", 0);
                            int tpSells = extractInt(resp, "\"tpSellCount\":", 0);
                            int slSells = extractInt(resp, "\"slSellCount\":", 0);
                            int patSells = extractInt(resp, "\"patternSellCount\":", 0);

                            pw.printf(Locale.ROOT, "%s,%s,240,%.1f,%.1f,%.1f,%d,%d,%.2f,%.2f,%.4f,%.2f,%d,%d,%d%n",
                                    label, coin, tp, sl, conf,
                                    trades, wins, wr, pnl, roi, fc, tpSells, slSells, patSells);
                            pw.flush();

                            if (done % 30 == 0) {
                                System.out.printf("[%d/%d] %.0f%%%n", done, totalTests, (done * 100.0 / totalTests));
                            }
                            Thread.sleep(200);
                        } catch (Exception e) {
                            System.err.println("FAIL [" + done + "]: " + label + " " + coin + " tp=" + tp + " sl=" + sl + " -> " + e.getMessage());
                            Thread.sleep(500);
                        }
                    }
                }
            }
        }

        pw.close();
        System.out.println("\nDone! " + done + " tests -> " + new File(outFile).getAbsolutePath());
    }

    // ===== HTTP helpers (same as BacktestRunner) =====
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
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
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
                    if (part.startsWith("JSESSIONID=")) {
                        if (sessionCookie == null) {
                            sessionCookie = part;
                        } else {
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
        InputStream is = (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300)
                ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();
        return sb.toString();
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
}
