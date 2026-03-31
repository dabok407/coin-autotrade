import java.io.*;
import java.net.*;
import java.util.*;
import java.time.*;
import java.time.format.*;

public class DownloadMore {
    static final String DIR = "data/candles/5m";
    static final String[] COINS = {
        "ONDO","SAHARA","BARD","STEEM","CPOOL","KITE","SUPER",
        "OPEN","KAITO","SUN","ZETA","TAO","ONG","POLYX","ONT","CFG",
        "RENDER","DKA","HBAR","SAND","AAVE","IOTA","ALGO"
    };
    
    public static void main(String[] args) throws Exception {
        new File(DIR).mkdirs();
        for (String coin : COINS) {
            String file = DIR + "/" + coin + ".csv";
            if (new File(file).exists()) { System.out.println("SKIP " + coin); continue; }
            System.out.print("Downloading " + coin + "...");
            List<double[]> candles = download("KRW-" + coin, 5, 365);
            if (candles.isEmpty()) { System.out.println(" no data"); continue; }
            saveCSV(file, candles);
            System.out.println(" " + candles.size() + " candles");
        }
    }
    
    static List<double[]> download(String market, int unit, int days) throws Exception {
        Set<Long> seen = new HashSet<>();
        List<double[]> all = new ArrayList<>();
        String to = null;
        int max = days * 24 * 60 / unit;
        for (int guard = 0; all.size() < max && guard < 600; guard++) {
            String url = "https://api.upbit.com/v1/candles/minutes/" + unit + "?market=" + market + "&count=200";
            if (to != null) url += "&to=" + URLEncoder.encode(to, "UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Accept","application/json");
            if (c.getResponseCode() == 429) { Thread.sleep(1000); continue; }
            if (c.getResponseCode() != 200) break;
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            String json = sb.toString();
            if (json.equals("[]") || json.length() < 10) break;
            
            double oldest = Double.MAX_VALUE;
            int added = 0;
            int idx = 0;
            while ((idx = json.indexOf("\"candle_date_time_utc\"", idx)) > 0) {
                try {
                    int i = json.indexOf(":", idx) + 2;
                    int e = json.indexOf("\"", i);
                    String utc = json.substring(i, e);
                    LocalDateTime ldt = LocalDateTime.parse(utc.length() > 19 ? utc.substring(0,19) : utc);
                    double ts = ldt.toEpochSecond(ZoneOffset.UTC);
                    
                    double op = extractNum(json, "opening_price", idx);
                    double hi = extractNum(json, "high_price", idx);
                    double lo = extractNum(json, "low_price", idx);
                    double cl = extractNum(json, "trade_price", idx);
                    double vol = extractNum(json, "candle_acc_trade_volume", idx);
                    
                    if (seen.add((long)ts)) { all.add(new double[]{ts,op,hi,lo,cl,vol}); added++; }
                    if (ts < oldest) oldest = ts;
                } catch (Exception ex) {}
                idx++;
            }
            if (added == 0) break;
            to = Instant.ofEpochSecond((long)oldest).atZone(ZoneOffset.UTC).toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            Thread.sleep(120);
        }
        all.sort(Comparator.comparingDouble(a -> a[0]));
        return all;
    }
    
    static double extractNum(String json, String key, int start) {
        int i = json.indexOf("\"" + key + "\"", start);
        if (i < 0) return 0;
        i = json.indexOf(":", i) + 1;
        while (i < json.length() && json.charAt(i) == ' ') i++;
        int e = i;
        while (e < json.length() && "0123456789.-eE".indexOf(json.charAt(e)) >= 0) e++;
        return Double.parseDouble(json.substring(i, e).trim());
    }
    
    static void saveCSV(String path, List<double[]> candles) throws Exception {
        PrintWriter pw = new PrintWriter(new FileWriter(path));
        pw.println("epoch_sec,open,high,low,close,volume");
        for (double[] c : candles)
            pw.printf("%.0f,%.8f,%.8f,%.8f,%.8f,%.8f%n", c[0],c[1],c[2],c[3],c[4],c[5]);
        pw.close();
    }
}
