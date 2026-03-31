import java.io.*;
import java.net.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;

public class TestOneBacktest {
    static String sessionCookie;
    static String csrfToken;

    public static void main(String[] args) throws Exception {
        // Login same as BacktestRunner
        String pubKeyResp = httpGet("http://localhost:8080/api/auth/pubkey");
        System.out.println("PubKey resp (first 100): " + pubKeyResp.substring(0, Math.min(100, pubKeyResp.length())));
        String pubKeyB64 = pubKeyResp.split("\"publicKey\":\"")[1].split("\"")[0];

        byte[] keyBytes = Base64.getDecoder().decode(pubKeyB64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey pubKey = kf.generatePublic(spec);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pubKey);
        String encPwd = Base64.getEncoder().encodeToString(cipher.doFinal("Test1234!".getBytes("UTF-8")));

        HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:8080/login").openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.connect();
        StringBuilder cookies = new StringBuilder();
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            if ("Set-Cookie".equalsIgnoreCase(e.getKey())) {
                for (String v : e.getValue()) {
                    String cp = v.split(";")[0];
                    if (cookies.length() > 0) cookies.append("; ");
                    cookies.append(cp);
                    if (cp.startsWith("XSRF-TOKEN=")) csrfToken = cp.substring("XSRF-TOKEN=".length());
                }
            }
        }
        conn.disconnect();
        sessionCookie = cookies.toString();

        String loginBody = "{\"username\":\"admin\",\"encryptedPassword\":\"" + encPwd + "\"}";
        String loginResp = httpPost("http://localhost:8080/api/auth/login", loginBody);
        System.out.println("Login: " + loginResp);

        // Run one backtest
        String body = "{\"strategies\":[\"THREE_MARKET_PATTERN\"],\"market\":\"KRW-XRP\",\"period\":\"90d\",\"candleUnitMin\":240,\"capitalKrw\":1000000,\"orderSizingMode\":\"PCT\",\"orderSizingValue\":90,\"takeProfitPct\":5.0,\"stopLossPct\":3.0,\"maxAddBuysGlobal\":2,\"minConfidence\":1.0,\"strategyLock\":true}";
        String resp = httpPost("http://localhost:8080/api/backtest/run", body);
        System.out.println("Backtest response (first 500): " + resp.substring(0, Math.min(500, resp.length())));
    }

    static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        if (sessionCookie != null) c.setRequestProperty("Cookie", sessionCookie);
        c.connect();
        updateCookies(c);
        return readBody(c);
    }

    static String httpPost(String url, String jsonBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (sessionCookie != null) c.setRequestProperty("Cookie", sessionCookie);
        if (csrfToken != null) c.setRequestProperty("X-XSRF-TOKEN", csrfToken);
        c.setDoOutput(true);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(30000);
        c.setReadTimeout(120000);
        c.getOutputStream().write(jsonBody.getBytes("UTF-8"));
        updateCookies(c);
        return readBody(c);
    }

    static void updateCookies(HttpURLConnection conn) {
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            if ("Set-Cookie".equalsIgnoreCase(e.getKey())) {
                for (String v : e.getValue()) {
                    String part = v.split(";")[0];
                    if (part.startsWith("XSRF-TOKEN=")) csrfToken = part.substring("XSRF-TOKEN=".length());
                    if (part.startsWith("JSESSIONID=") && sessionCookie != null && sessionCookie.contains("JSESSIONID=")) {
                        sessionCookie = sessionCookie.replaceAll("JSESSIONID=[^;]*", part);
                    }
                }
            }
        }
    }

    static String readBody(HttpURLConnection c) throws Exception {
        InputStream is = (c.getResponseCode() >= 200 && c.getResponseCode() < 300) ? c.getInputStream() : c.getErrorStream();
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();
        return sb.toString();
    }
}
