import java.io.*;
import java.net.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;

/**
 * Multi-period backtest: 1Y, 3M, 1M for optimal settings
 */
public class RunPeriodTest {

    static String BASE = "http://localhost:8080";
    static String sessionCookie = null;
    static String xsrfToken = null;

    public static void main(String[] args) throws Exception {
        System.out.println("=== MULTI-PERIOD BACKTEST ===\n");

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

        String[] periods = {"365d", "90d", "30d"};
        String[] periodLabels = {"1Y (365d)", "3M (90d) ", "1M (30d) "};

        // Coin configs: {name, market, strategies[], interval, tp, sl, conf}
        String[][] coins = {
            {"SOL", "KRW-SOL", "INSIDE_BAR_BREAKOUT,BULLISH_ENGULFING_CONFIRM,BEARISH_ENGULFING,EVENING_STAR_SELL", "240", "7.0", "2.0"},
            {"ETH", "KRW-ETH", "INSIDE_BAR_BREAKOUT,BEARISH_ENGULFING,EVENING_STAR_SELL", "240", "7.0", "1.5"},
            {"XRP", "KRW-XRP", "BULLISH_ENGULFING_CONFIRM,BEARISH_ENGULFING,EVENING_STAR_SELL", "240", "10.0", "1.5"},
            {"BTC", "KRW-BTC", "INSIDE_BAR_BREAKOUT,BULLISH_ENGULFING_CONFIRM,BEARISH_ENGULFING,EVENING_STAR_SELL", "240", "3.0", "2.0"},
            {"ADA", "KRW-ADA", "BULLISH_ENGULFING_CONFIRM,BEARISH_ENGULFING,EVENING_STAR_SELL", "240", "10.0", "1.0"},
        };

        // Header
        System.out.println(rep('=',130));
        System.out.printf("%-6s %-30s %-10s  %8s  %6s  %5s  %12s  %4s %4s %4s%n",
            "COIN", "STRATEGY", "PERIOD", "ROI", "TRADES", "WR", "FINAL", "TP", "SL", "PAT");
        System.out.println(rep('=',130));

        for (String[] coin : coins) {
            String name = coin[0];
            String market = coin[1];
            String[] strats = coin[2].split(",");
            int interval = Integer.parseInt(coin[3]);
            double tp = Double.parseDouble(coin[4]);
            double sl = Double.parseDouble(coin[5]);

            String stratLabel = "";
            for (String s : strats) {
                String abbr = abbreviate(s);
                stratLabel += (stratLabel.isEmpty() ? "" : "+") + abbr;
            }
            stratLabel += " TP" + coin[4] + "/SL" + coin[5];

            for (int p = 0; p < periods.length; p++) {
                String body = mkBody(market, strats, interval, tp, sl, periods[p]);
                runRow(name, stratLabel, periodLabels[p], body);
            }
            System.out.println(rep('-',130));
        }

        // Portfolio summary
        System.out.println("\n" + rep('=',80));
        System.out.println("=== PORTFOLIO SUMMARY (200K per coin = 1M total) ===");
        System.out.println(rep('=',80) + "\n");

        System.out.printf("%-10s  %12s  %12s  %12s%n", "PERIOD", "TOTAL FINAL", "TOTAL ROI", "AVG WR");
        System.out.println(rep('-',60));

        for (int p = 0; p < periods.length; p++) {
            double totalFinal = 0;
            double totalWR = 0;
            int coinCount = 0;

            for (String[] coin : coins) {
                String market = coin[1];
                String[] strats = coin[2].split(",");
                int interval = Integer.parseInt(coin[3]);
                double tp = Double.parseDouble(coin[4]);
                double sl = Double.parseDouble(coin[5]);

                String body = mkBodyCap(market, strats, interval, tp, sl, periods[p], 200000);
                String resp = httpPostAuth(BASE + "/api/backtest/run", body);
                String finalCap = extractJson(resp, "finalCapital");
                String winRate = extractJson(resp, "winRate");

                if (finalCap != null) totalFinal += Double.parseDouble(finalCap);
                if (winRate != null) totalWR += Double.parseDouble(winRate);
                coinCount++;
            }

            double portRoi = ((totalFinal - 1000000.0) / 1000000.0) * 100.0;
            System.out.printf("%-10s  %,12.0f  %+11.1f%%  %11.1f%%%n",
                periodLabels[p], totalFinal, portRoi, totalWR / coinCount);
        }

        System.out.println("\n=== COMPLETE ===");
    }

    static String rep(char c, int n) {
        char[] arr = new char[n];
        Arrays.fill(arr, c);
        return new String(arr);
    }

    static String abbreviate(String strat) {
        if (strat.equals("INSIDE_BAR_BREAKOUT")) return "IBB";
        if (strat.equals("BULLISH_ENGULFING_CONFIRM")) return "BEC";
        if (strat.equals("BEARISH_ENGULFING")) return "BE";
        if (strat.equals("EVENING_STAR_SELL")) return "ESS";
        if (strat.equals("THREE_BLACK_CROWS_SELL")) return "3BC";
        if (strat.equals("ADAPTIVE_TREND_MOMENTUM")) return "ATM";
        if (strat.equals("REGIME_PULLBACK")) return "RFP";
        if (strat.equals("BOLLINGER_SQUEEZE_BREAKOUT")) return "BBSq";
        if (strat.equals("EMA_RSI_TREND")) return "ERT";
        return strat.substring(0, Math.min(4, strat.length()));
    }

    static String mkBody(String market, String[] strats, int interval, double tp, double sl, String period) {
        return mkBodyCap(market, strats, interval, tp, sl, period, 1000000);
    }

    static String mkBodyCap(String market, String[] strats, int interval, double tp, double sl, String period, int capital) {
        String coin = market.split("-")[1];
        StringBuilder sb = new StringBuilder();
        sb.append("{\"period\":\"").append(period).append("\",\"capitalKrw\":").append(capital);
        sb.append(",\"groups\":[{\"groupName\":\"").append(coin).append("\",\"markets\":[\"").append(market).append("\"],");
        sb.append("\"strategies\":[");
        for (int i = 0; i < strats.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(strats[i]).append("\"");
        }
        sb.append("],\"candleUnitMin\":").append(interval);
        sb.append(",\"takeProfitPct\":").append(tp);
        sb.append(",\"stopLossPct\":").append(sl);
        sb.append(",\"maxAddBuys\":0");
        sb.append(",\"minConfidence\":4.0");
        sb.append(",\"strategyLock\":false");
        sb.append(",\"timeStopMinutes\":0");
        sb.append(",\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90}]}");
        return sb.toString();
    }

    static void runRow(String coin, String stratLabel, String period, String body) {
        try {
            String resp = httpPostAuth(BASE + "/api/backtest/run", body);
            String roi = extractJson(resp, "roi");
            String trades = extractJson(resp, "tradesCount");
            String winRate = extractJson(resp, "winRate");
            String finalCap = extractJson(resp, "finalCapital");
            String tpSells = extractJson(resp, "tpSellCount");
            String slSells = extractJson(resp, "slSellCount");
            String patSells = extractJson(resp, "patternSellCount");

            double roiVal = roi != null ? Double.parseDouble(roi) : 0;
            double wrVal = winRate != null ? Double.parseDouble(winRate) : 0;
            double capVal = finalCap != null ? Double.parseDouble(finalCap) : 0;

            System.out.printf("%-6s %-30s %-10s  %+7.1f%%  %6s  %4.0f%%  %,12.0f  %4s %4s %4s%n",
                coin, stratLabel, period, roiVal, trades, wrVal, capVal, tpSells, slSells, patSells);
        } catch (Exception e) {
            System.out.printf("%-6s %-30s %-10s  ERROR: %s%n", coin, stratLabel, period, e.getMessage());
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
