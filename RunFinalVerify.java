import java.io.*;
import java.net.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;

/**
 * Final Verification: Run optimal settings for each coin
 */
public class RunFinalVerify {

    static String BASE = "http://localhost:8080";
    static String sessionCookie = null;
    static String xsrfToken = null;

    public static void main(String[] args) throws Exception {
        System.out.println("=== FINAL VERIFICATION: Optimal Settings ===\n");

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
        httpPost(BASE + "/api/auth/login",
            "{\"username\":\"admin\",\"encryptedPassword\":\"" + encPwd + "\"}", "application/json");
        System.out.println("Login OK\n");

        System.out.println("=== RECOMMENDED SETTINGS (1-Year Backtest) ===\n");

        // SOL: IBB+BEC+BE+ESS, 240min, TP7/SL2
        runBacktest("SOL [IBB+BEC+BE+ESS] TP7/SL2 240min",
            mkBody("KRW-SOL", new String[]{"INSIDE_BAR_BREAKOUT","BULLISH_ENGULFING_CONFIRM","BEARISH_ENGULFING","EVENING_STAR_SELL"},
                240, 7.0, 2.0, 4.0, 0, false, 0));

        // ETH: IBB+BE+ESS, 240min, TP7/SL1.5
        runBacktest("ETH [IBB+BE+ESS] TP7/SL1.5 240min",
            mkBody("KRW-ETH", new String[]{"INSIDE_BAR_BREAKOUT","BEARISH_ENGULFING","EVENING_STAR_SELL"},
                240, 7.0, 1.5, 4.0, 0, false, 0));

        // XRP: BEC+BE+ESS, 240min, TP10/SL1.5
        runBacktest("XRP [BEC+BE+ESS] TP10/SL1.5 240min",
            mkBody("KRW-XRP", new String[]{"BULLISH_ENGULFING_CONFIRM","BEARISH_ENGULFING","EVENING_STAR_SELL"},
                240, 10.0, 1.5, 4.0, 0, false, 0));

        // BTC: IBB+BEC+BE+ESS, 240min, TP3/SL2
        runBacktest("BTC [IBB+BEC+BE+ESS] TP3/SL2 240min",
            mkBody("KRW-BTC", new String[]{"INSIDE_BAR_BREAKOUT","BULLISH_ENGULFING_CONFIRM","BEARISH_ENGULFING","EVENING_STAR_SELL"},
                240, 3.0, 2.0, 4.0, 0, false, 0));

        // ADA: BEC+BE+ESS, 240min, TP10/SL1
        runBacktest("ADA [BEC+BE+ESS] TP10/SL1 240min",
            mkBody("KRW-ADA", new String[]{"BULLISH_ENGULFING_CONFIRM","BEARISH_ENGULFING","EVENING_STAR_SELL"},
                240, 10.0, 1.0, 4.0, 0, false, 0));

        // === Verify usedTpPct fix ===
        System.out.println("\n=== TP/SL Reporting Fix Verification ===\n");

        // SOL should show usedTpPct=7.0, usedSlPct=2.0
        String solResp = httpPostAuth(BASE + "/api/backtest/run",
            mkBody("KRW-SOL", new String[]{"INSIDE_BAR_BREAKOUT","BEARISH_ENGULFING","EVENING_STAR_SELL"},
                240, 7.0, 2.0, 4.0, 0, false, 0));
        System.out.println("SOL TP7/SL2 => usedTpPct=" + extractJson(solResp, "usedTpPct") +
            ", usedSlPct=" + extractJson(solResp, "usedSlPct") + " (expected: 7.0, 2.0)");

        // BTC with TP3/SL2
        String btcResp = httpPostAuth(BASE + "/api/backtest/run",
            mkBody("KRW-BTC", new String[]{"INSIDE_BAR_BREAKOUT","BEARISH_ENGULFING","EVENING_STAR_SELL"},
                240, 3.0, 2.0, 4.0, 0, false, 0));
        System.out.println("BTC TP3/SL2 => usedTpPct=" + extractJson(btcResp, "usedTpPct") +
            ", usedSlPct=" + extractJson(btcResp, "usedSlPct") + " (expected: 3.0, 2.0)");

        // === Multi-coin portfolio simulation ===
        System.out.println("\n=== 5-COIN PORTFOLIO (200K each, total 1M) ===\n");

        // Run each coin with 200K capital
        runBacktest("SOL 200K [IBB+BEC+BE+ESS] TP7/SL2",
            mkBodyCap("KRW-SOL", new String[]{"INSIDE_BAR_BREAKOUT","BULLISH_ENGULFING_CONFIRM","BEARISH_ENGULFING","EVENING_STAR_SELL"},
                240, 7.0, 2.0, 4.0, 0, false, 0, 200000));
        runBacktest("ETH 200K [IBB+BE+ESS] TP7/SL1.5",
            mkBodyCap("KRW-ETH", new String[]{"INSIDE_BAR_BREAKOUT","BEARISH_ENGULFING","EVENING_STAR_SELL"},
                240, 7.0, 1.5, 4.0, 0, false, 0, 200000));
        runBacktest("XRP 200K [BEC+BE+ESS] TP10/SL1.5",
            mkBodyCap("KRW-XRP", new String[]{"BULLISH_ENGULFING_CONFIRM","BEARISH_ENGULFING","EVENING_STAR_SELL"},
                240, 10.0, 1.5, 4.0, 0, false, 0, 200000));
        runBacktest("BTC 200K [IBB+BEC+BE+ESS] TP3/SL2",
            mkBodyCap("KRW-BTC", new String[]{"INSIDE_BAR_BREAKOUT","BULLISH_ENGULFING_CONFIRM","BEARISH_ENGULFING","EVENING_STAR_SELL"},
                240, 3.0, 2.0, 4.0, 0, false, 0, 200000));
        runBacktest("ADA 200K [BEC+BE+ESS] TP10/SL1",
            mkBodyCap("KRW-ADA", new String[]{"BULLISH_ENGULFING_CONFIRM","BEARISH_ENGULFING","EVENING_STAR_SELL"},
                240, 10.0, 1.0, 4.0, 0, false, 0, 200000));

        System.out.println("\n=== VERIFICATION COMPLETE ===");
    }

    static String mkBody(String market, String[] strats, int interval, double tp, double sl,
                          double conf, int maxAdd, boolean lock, int timeStop) {
        return mkBodyCap(market, strats, interval, tp, sl, conf, maxAdd, lock, timeStop, 1000000);
    }

    static String mkBodyCap(String market, String[] strats, int interval, double tp, double sl,
                          double conf, int maxAdd, boolean lock, int timeStop, int capital) {
        String coin = market.split("-")[1];
        StringBuilder sb = new StringBuilder();
        sb.append("{\"period\":\"365d\",\"capitalKrw\":").append(capital);
        sb.append(",\"groups\":[{\"groupName\":\"").append(coin).append("\",\"markets\":[\"").append(market).append("\"],");
        sb.append("\"strategies\":[");
        for (int i = 0; i < strats.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(strats[i]).append("\"");
        }
        sb.append("],\"candleUnitMin\":").append(interval);
        sb.append(",\"takeProfitPct\":").append(tp);
        sb.append(",\"stopLossPct\":").append(sl);
        sb.append(",\"maxAddBuys\":").append(maxAdd);
        sb.append(",\"minConfidence\":").append(conf);
        sb.append(",\"strategyLock\":").append(lock);
        sb.append(",\"timeStopMinutes\":").append(timeStop);
        sb.append(",\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}");
        return sb.toString();
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
            String patSells = extractJson(resp, "patternSellCount");
            String usedTp = extractJson(resp, "usedTpPct");
            String usedSl = extractJson(resp, "usedSlPct");

            double roiVal = roi != null ? Double.parseDouble(roi) : 0;
            double wrVal = winRate != null ? Double.parseDouble(winRate) : 0;
            double capVal = finalCap != null ? Double.parseDouble(finalCap) : 0;

            System.out.printf("%-45s  ROI:%+7.1f%%  Trades:%-3s  WR:%.0f%%  Final:%,.0f  TP:%s SL:%s Pat:%s  [TP%%=%.1f SL%%=%.1f]%n",
                name, roiVal, trades, wrVal, capVal, tpSells, slSells, patSells,
                usedTp != null ? Double.parseDouble(usedTp) : 0,
                usedSl != null ? Double.parseDouble(usedSl) : 0);
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
        URL url = new URL(urlStr); HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET"); conn.setInstanceFollowRedirects(false);
        captureCookies(conn); return readResponse(conn);
    }
    static String httpPost(String urlStr, String body, String ct) throws Exception {
        URL url = new URL(urlStr); HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST"); conn.setDoOutput(true); conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Content-Type", ct);
        if (sessionCookie != null) conn.setRequestProperty("Cookie", buildCookieHeader());
        if (xsrfToken != null) conn.setRequestProperty("X-XSRF-TOKEN", xsrfToken);
        conn.getOutputStream().write(body.getBytes("UTF-8")); captureCookies(conn); return readResponse(conn);
    }
    static String httpPostAuth(String urlStr, String body) throws Exception {
        URL url = new URL(urlStr); HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST"); conn.setDoOutput(true); conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(10000); conn.setReadTimeout(300000);
        conn.setRequestProperty("Content-Type", "application/json");
        if (sessionCookie != null) conn.setRequestProperty("Cookie", buildCookieHeader());
        if (xsrfToken != null) conn.setRequestProperty("X-XSRF-TOKEN", xsrfToken);
        conn.getOutputStream().write(body.getBytes("UTF-8")); captureCookies(conn); return readResponse(conn);
    }
    static String buildCookieHeader() {
        StringBuilder sb = new StringBuilder();
        if (sessionCookie != null) sb.append("JSESSIONID=").append(sessionCookie);
        if (xsrfToken != null) { if (sb.length() > 0) sb.append("; "); sb.append("XSRF-TOKEN=").append(xsrfToken); }
        return sb.toString();
    }
    static void captureCookies(HttpURLConnection conn) {
        List<String> cookies = conn.getHeaderFields().get("Set-Cookie");
        if (cookies != null) { for (String c : cookies) {
            if (c.startsWith("JSESSIONID=")) sessionCookie = c.split(";")[0].substring("JSESSIONID=".length());
            if (c.startsWith("XSRF-TOKEN=")) xsrfToken = c.split(";")[0].substring("XSRF-TOKEN=".length());
        }}
    }
    static String readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode(); InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return ""; BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder(); String line; while ((line = br.readLine()) != null) sb.append(line);
        br.close(); return sb.toString();
    }
}
