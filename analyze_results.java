import java.io.*;
import java.util.*;

public class analyze_results {
    public static void main(String[] args) throws Exception {
        String tempDir = System.getenv("TEMP") != null ? System.getenv("TEMP") : "/tmp";
        String[] markets = {"KRW-SOL", "KRW-ADA", "KRW-BTC", "KRW-XRP", "KRW-ETH"};

        System.out.println(rep('=',160));
        System.out.println("Phase 1: 단일 전략 최적화 결과 (기간별 메트릭 포함)");
        System.out.println(rep('=',160));

        for (String market : markets) {
            String path = System.getenv("TEMP") + "\\p1_" + market + ".json";
            List<Map<String, Object>> data = parseJsonArray(readFile(path));
            // Filter phase 1
            List<Map<String, Object>> p1 = new ArrayList<>();
            for (Map<String, Object> d : data) {
                int phase = getInt(d, "phase", 1);
                if (phase == 1) p1.add(d);
                if (p1.size() >= 5) break;
            }
            System.out.println("\n--- " + market + " (Top 5) ---");
            System.out.printf("%-4s %-28s %4s %4s %4s %4s %4s %5s %5s | %8s %5s %4s | %8s %5s %4s | %8s %5s %4s%n",
                "#", "Strategy", "Int", "EMA", "TP%", "SL%", "Add", "Lock", "TStop",
                "1Y ROI", "1Y WR", "1Y T",
                "3M ROI", "3M WR", "3M T",
                "1M ROI", "1M WR", "1M T");
            for (int i = 0; i < p1.size(); i++) {
                Map<String, Object> d = p1.get(i);
                System.out.printf("%-4d %-28s %4d %4d %4.0f %4.0f %4d %5s %5d | %+7.1f%% %4.1f%% %4d | %+7.1f%% %4.1f%% %4d | %+7.1f%% %4.1f%% %4d%n",
                    i+1,
                    getString(d, "strategyType"),
                    getInt(d, "intervalMin", 0),
                    getInt(d, "emaPeriod", 0),
                    getDouble(d, "tpPct"),
                    getDouble(d, "slPct"),
                    getInt(d, "maxAddBuys", 0),
                    getBool(d, "strategyLock") ? "Y" : "N",
                    getInt(d, "timeStopMinutes", 0),
                    getDouble(d, "roi"),
                    getDouble(d, "winRate"),
                    getInt(d, "totalTrades", 0),
                    getDoubleNull(d, "roi3m"),
                    getDoubleNull(d, "winRate3m"),
                    getIntNull(d, "totalTrades3m"),
                    getDoubleNull(d, "roi1m"),
                    getDoubleNull(d, "winRate1m"),
                    getIntNull(d, "totalTrades1m"));
            }
        }

        System.out.println("\n\n" + rep('=',160));
        System.out.println("Phase 2: 다중 전략 조합 최적화 결과");
        System.out.println(rep('=',160));

        for (String market : markets) {
            String path = System.getenv("TEMP") + "\\p2_" + market + ".json";
            List<Map<String, Object>> data = parseJsonArray(readFile(path));
            if (data.isEmpty()) {
                System.out.println("\n--- " + market + " --- (결과 없음)");
                continue;
            }
            List<Map<String, Object>> top5 = data.subList(0, Math.min(5, data.size()));
            System.out.println("\n--- " + market + " (Top 5) ---");
            System.out.printf("%-4s %-50s %4s %4s %4s %5s %5s | %8s %5s %4s | %8s %5s %4s | %8s %5s %4s%n",
                "#", "Strategies", "TP%", "SL%", "Add", "Lock", "TStop",
                "1Y ROI", "1Y WR", "1Y T",
                "3M ROI", "3M WR", "3M T",
                "1M ROI", "1M WR", "1M T");
            for (int i = 0; i < top5.size(); i++) {
                Map<String, Object> d = top5.get(i);
                String strategies = getString(d, "strategiesCsv");
                if (strategies == null || strategies.equals("null")) strategies = getString(d, "strategyType");
                // Truncate long strategy strings
                if (strategies.length() > 48) strategies = strategies.substring(0, 45) + "...";
                System.out.printf("%-4d %-50s %4.0f %4.0f %4d %5s %5d | %+7.1f%% %4.1f%% %4d | %+7.1f%% %4.1f%% %4d | %+7.1f%% %4.1f%% %4d%n",
                    i+1,
                    strategies,
                    getDouble(d, "tpPct"),
                    getDouble(d, "slPct"),
                    getInt(d, "maxAddBuys", 0),
                    getBool(d, "strategyLock") ? "Y" : "N",
                    getInt(d, "timeStopMinutes", 0),
                    getDouble(d, "roi"),
                    getDouble(d, "winRate"),
                    getInt(d, "totalTrades", 0),
                    getDoubleNull(d, "roi3m"),
                    getDoubleNull(d, "winRate3m"),
                    getIntNull(d, "totalTrades3m"),
                    getDoubleNull(d, "roi1m"),
                    getDoubleNull(d, "winRate1m"),
                    getIntNull(d, "totalTrades1m"));
            }
        }

        // Phase 1 vs Phase 2 best comparison
        System.out.println("\n\n" + rep('=',120));
        System.out.println("Phase 1 vs Phase 2: 마켓별 최고 ROI 비교");
        System.out.println(rep('=',120));
        System.out.printf("%-10s | %-30s %8s | %-50s %8s%n",
            "Market", "Phase 1 Best Strategy", "ROI", "Phase 2 Best Strategies", "ROI");
        System.out.println(rep('-',120));

        for (String market : markets) {
            List<Map<String, Object>> p1 = parseJsonArray(readFile(System.getenv("TEMP") + "\\p1_" + market + ".json"));
            List<Map<String, Object>> p2 = parseJsonArray(readFile(System.getenv("TEMP") + "\\p2_" + market + ".json"));

            String p1Strategy = p1.isEmpty() ? "N/A" : getString(p1.get(0), "strategyType");
            double p1Roi = p1.isEmpty() ? 0 : getDouble(p1.get(0), "roi");

            String p2Strategy = "N/A";
            double p2Roi = 0;
            if (!p2.isEmpty()) {
                p2Strategy = getString(p2.get(0), "strategiesCsv");
                if (p2Strategy == null || p2Strategy.equals("null")) p2Strategy = getString(p2.get(0), "strategyType");
                if (p2Strategy.length() > 48) p2Strategy = p2Strategy.substring(0, 45) + "...";
                p2Roi = getDouble(p2.get(0), "roi");
            }

            System.out.printf("%-10s | %-30s %+7.1f%% | %-50s %+7.1f%%%n",
                market, p1Strategy, p1Roi, p2Strategy, p2Roi);
        }
    }

    static String readFile(String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    static List<Map<String, Object>> parseJsonArray(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        json = json.trim();
        if (!json.startsWith("[") || json.equals("[]")) return result;
        // Simple JSON array parser
        int depth = 0;
        int objStart = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    result.add(parseJsonObject(json.substring(objStart, i + 1)));
                    objStart = -1;
                }
            }
        }
        return result;
    }

    static Map<String, Object> parseJsonObject(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

        int i = 0;
        while (i < json.length()) {
            // Skip whitespace
            while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '\n' || json.charAt(i) == '\r' || json.charAt(i) == '\t' || json.charAt(i) == ',')) i++;
            if (i >= json.length()) break;

            // Parse key
            if (json.charAt(i) != '"') { i++; continue; }
            int keyStart = i + 1;
            int keyEnd = json.indexOf('"', keyStart);
            if (keyEnd < 0) break;
            String key = json.substring(keyStart, keyEnd);
            i = keyEnd + 1;

            // Skip colon
            while (i < json.length() && json.charAt(i) != ':') i++;
            i++;
            while (i < json.length() && json.charAt(i) == ' ') i++;
            if (i >= json.length()) break;

            // Parse value
            if (json.charAt(i) == '"') {
                int valStart = i + 1;
                int valEnd = json.indexOf('"', valStart);
                if (valEnd < 0) break;
                map.put(key, json.substring(valStart, valEnd));
                i = valEnd + 1;
            } else if (json.charAt(i) == 'n' && json.substring(i).startsWith("null")) {
                map.put(key, null);
                i += 4;
            } else if (json.charAt(i) == 't' && json.substring(i).startsWith("true")) {
                map.put(key, Boolean.TRUE);
                i += 4;
            } else if (json.charAt(i) == 'f' && json.substring(i).startsWith("false")) {
                map.put(key, Boolean.FALSE);
                i += 5;
            } else {
                // Number
                int numStart = i;
                while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}' && json.charAt(i) != ' ') i++;
                String numStr = json.substring(numStart, i).trim();
                try {
                    if (numStr.contains(".")) map.put(key, Double.parseDouble(numStr));
                    else map.put(key, Long.parseLong(numStr));
                } catch (NumberFormatException e) {
                    map.put(key, numStr);
                }
            }
        }
        return map;
    }

    static String rep(char c, int n) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }

    static String getString(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "N/A" : v.toString();
    }
    static double getDouble(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        return Double.parseDouble(v.toString());
    }
    static double getDoubleNull(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        return Double.parseDouble(v.toString());
    }
    static int getInt(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(v.toString());
    }
    static int getIntNull(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(v.toString());
    }
    static boolean getBool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(v.toString());
    }
}
