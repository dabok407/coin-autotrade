import java.io.*;

public class parse_trades {
    public static void main(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new FileReader("bt_result.json"));
        String line; while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        String json = sb.toString();
        
        System.out.println("=== Response Summary ===");
        System.out.println("candleUnitMin: " + extract(json, "candleUnitMin"));
        System.out.println("usedTpPct: " + extract(json, "usedTpPct"));
        System.out.println("usedSlPct: " + extract(json, "usedSlPct"));
        System.out.println("periodDays: " + extract(json, "periodDays"));
        System.out.println("candleCount: " + extract(json, "candleCount"));
        System.out.println("totalTrades: " + extract(json, "totalTrades"));
        System.out.println("roi: " + extract(json, "roi"));
        
        int idx = json.indexOf("\"trades\":[");
        if (idx < 0) { System.out.println("No trades"); return; }
        String rest = json.substring(idx + 9);
        
        System.out.println("\n=== All Trades ===");
        System.out.printf("%-3s %-6s %-28s %6s %12s %12s %10s %8s%n",
            "#", "Type", "Strategy", "Conf", "Entry", "Exit", "PnL", "ROI%");
        int depth = 0, start = -1, count = 0;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String t = rest.substring(start, i + 1);
                    count++;
                    System.out.printf("%-3d %-6s %-28s %6s %12s %12s %10s %7s%%%n",
                        count, extract(t,"type"), extract(t,"strategy"),
                        extract(t,"confidence"), extract(t,"entryPrice"),
                        extract(t,"exitPrice"), extract(t,"pnl"), extract(t,"roiPct"));
                    start = -1;
                }
            }
        }
    }
    
    static String extract(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return "N/A";
        int valStart = json.indexOf(":", idx) + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;
        if (json.charAt(valStart) == '"') {
            int valEnd = json.indexOf('"', valStart + 1);
            return json.substring(valStart + 1, valEnd);
        } else if (json.charAt(valStart) == 'n') return "null";
        else {
            int valEnd = valStart;
            while (valEnd < json.length() && ",}]".indexOf(json.charAt(valEnd)) < 0) valEnd++;
            return json.substring(valStart, valEnd).trim();
        }
    }
}
