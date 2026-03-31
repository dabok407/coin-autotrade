import java.io.*;
import java.util.*;

public class analyze2 {
    public static void main(String[] args) throws Exception {
        String tmp = System.getenv("TEMP");
        String[] markets = {"KRW-SOL", "KRW-ADA", "KRW-BTC", "KRW-XRP", "KRW-ETH"};

        // Phase 1: Show unique strategy+param combos (dedup by strategy+interval+ema+tp+sl)
        System.out.println(rep('=',180));
        System.out.println("Phase 1: Unique strategy+param combos per market (dedup by strategy+interval+ema+tp+sl)");
        System.out.println(rep('=',180));

        for (String market : markets) {
            List<Map<String, Object>> data = parseJsonArray(readFile(tmp + "\\p1_" + market + ".json"));
            Set<String> seen = new LinkedHashSet<String>();
            List<Map<String, Object>> unique = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> d : data) {
                int phase = getInt(d, "phase", 1);
                if (phase != 1) continue;
                String key = getString(d,"strategyType") + "|" + getInt(d,"intervalMin",0) + "|" + getInt(d,"emaPeriod",0) + "|" + (int)getDouble(d,"tpPct") + "|" + (int)getDouble(d,"slPct");
                if (!seen.contains(key)) {
                    seen.add(key);
                    unique.add(d);
                }
                if (unique.size() >= 10) break;
            }
            System.out.println("\n--- " + market + " (Top 10 unique) ---");
            System.out.printf("%-4s %-28s %4s %4s %4s %4s %4s %5s %5s | %8s %5s %4s | %8s %5s %4s | %8s %5s %4s%n",
                "#", "Strategy", "Int", "EMA", "TP%", "SL%", "Add", "Lock", "TStop",
                "1Y ROI", "1Y WR", "1Y T", "3M ROI", "3M WR", "3M T", "1M ROI", "1M WR", "1M T");
            for (int i = 0; i < unique.size(); i++) {
                Map<String, Object> d = unique.get(i);
                System.out.printf("%-4d %-28s %4d %4d %4.0f %4.0f %4d %5s %5d | %+7.1f%% %4.1f%% %4d | %+7.1f%% %4.1f%% %4d | %+7.1f%% %4.1f%% %4d%n",
                    i+1, getString(d,"strategyType"), getInt(d,"intervalMin",0), getInt(d,"emaPeriod",0),
                    getDouble(d,"tpPct"), getDouble(d,"slPct"), getInt(d,"maxAddBuys",0),
                    getBool(d,"strategyLock")?"Y":"N", getInt(d,"timeStopMinutes",0),
                    getDouble(d,"roi"), getDouble(d,"winRate"), getInt(d,"totalTrades",0),
                    getDoubleNull(d,"roi3m"), getDoubleNull(d,"winRate3m"), getIntNull(d,"totalTrades3m"),
                    getDoubleNull(d,"roi1m"), getDoubleNull(d,"winRate1m"), getIntNull(d,"totalTrades1m"));
            }
        }

        // Phase 2: Show unique strategy combos (dedup by strategies+tp+sl)
        System.out.println("\n\n" + rep('=',180));
        System.out.println("Phase 2: Unique multi-strategy combos per market (dedup by strategies+tp+sl)");
        System.out.println(rep('=',180));

        for (String market : markets) {
            List<Map<String, Object>> data = parseJsonArray(readFile(tmp + "\\p2_" + market + ".json"));
            if (data.isEmpty()) {
                System.out.println("\n--- " + market + " --- (No results)");
                continue;
            }
            Set<String> seen = new LinkedHashSet<String>();
            List<Map<String, Object>> unique = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> d : data) {
                String strats = getString(d,"strategiesCsv");
                String key = strats + "|" + (int)getDouble(d,"tpPct") + "|" + (int)getDouble(d,"slPct") + "|" + getInt(d,"timeStopMinutes",0);
                if (!seen.contains(key)) {
                    seen.add(key);
                    unique.add(d);
                }
                if (unique.size() >= 10) break;
            }
            System.out.println("\n--- " + market + " (Top 10 unique) ---");
            System.out.printf("%-4s %-55s %4s %4s %4s %5s %5s | %8s %5s %4s | %8s %5s %4s | %8s %5s %4s%n",
                "#", "Strategies", "TP%", "SL%", "Add", "Lock", "TStop",
                "1Y ROI", "1Y WR", "1Y T", "3M ROI", "3M WR", "3M T", "1M ROI", "1M WR", "1M T");
            for (int i = 0; i < unique.size(); i++) {
                Map<String, Object> d = unique.get(i);
                String strategies = getString(d,"strategiesCsv");
                if (strategies.length() > 53) strategies = strategies.substring(0, 50) + "...";
                System.out.printf("%-4d %-55s %4.0f %4.0f %4d %5s %5d | %+7.1f%% %4.1f%% %4d | %+7.1f%% %4.1f%% %4d | %+7.1f%% %4.1f%% %4d%n",
                    i+1, strategies,
                    getDouble(d,"tpPct"), getDouble(d,"slPct"), getInt(d,"maxAddBuys",0),
                    getBool(d,"strategyLock")?"Y":"N", getInt(d,"timeStopMinutes",0),
                    getDouble(d,"roi"), getDouble(d,"winRate"), getInt(d,"totalTrades",0),
                    getDoubleNull(d,"roi3m"), getDoubleNull(d,"winRate3m"), getIntNull(d,"totalTrades3m"),
                    getDoubleNull(d,"roi1m"), getDoubleNull(d,"winRate1m"), getIntNull(d,"totalTrades1m"));
            }
        }
    }

    static String rep(char c, int n) { char[] a = new char[n]; java.util.Arrays.fill(a,c); return new String(a); }
    static String readFile(String p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(p))) { String l; while((l=br.readLine())!=null) sb.append(l); }
        return sb.toString();
    }
    static List<Map<String, Object>> parseJsonArray(String json) {
        List<Map<String, Object>> r = new ArrayList<Map<String, Object>>();
        json = json.trim();
        if (!json.startsWith("[") || json.equals("[]")) return r;
        int depth=0, os=-1;
        for (int i=0; i<json.length(); i++) {
            char c=json.charAt(i);
            if (c=='{') { if(depth==0) os=i; depth++; }
            else if (c=='}') { depth--; if(depth==0&&os>=0) { r.add(parseJsonObject(json.substring(os,i+1))); os=-1; } }
        }
        return r;
    }
    static Map<String, Object> parseJsonObject(String json) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length()-1);
        int i=0;
        while (i<json.length()) {
            while (i<json.length() && " \n\r\t,".indexOf(json.charAt(i))>=0) i++;
            if (i>=json.length()) break;
            if (json.charAt(i)!='"') { i++; continue; }
            int ks=i+1, ke=json.indexOf('"',ks); if(ke<0) break;
            String key=json.substring(ks,ke); i=ke+1;
            while(i<json.length()&&json.charAt(i)!=':') i++; i++;
            while(i<json.length()&&json.charAt(i)==' ') i++;
            if(i>=json.length()) break;
            if (json.charAt(i)=='"') {
                int vs=i+1,ve=json.indexOf('"',vs); if(ve<0) break;
                m.put(key,json.substring(vs,ve)); i=ve+1;
            } else if (json.charAt(i)=='n'&&json.substring(i).startsWith("null")) { m.put(key,null); i+=4; }
            else if (json.charAt(i)=='t'&&json.substring(i).startsWith("true")) { m.put(key,Boolean.TRUE); i+=4; }
            else if (json.charAt(i)=='f'&&json.substring(i).startsWith("false")) { m.put(key,Boolean.FALSE); i+=5; }
            else { int ns=i; while(i<json.length()&&",} ".indexOf(json.charAt(i))<0) i++;
                String s=json.substring(ns,i).trim();
                try { if(s.contains(".")) m.put(key,Double.parseDouble(s)); else m.put(key,Long.parseLong(s)); }
                catch(NumberFormatException e) { m.put(key,s); }
            }
        }
        return m;
    }
    static String getString(Map<String,Object> m, String k) { Object v=m.get(k); return v==null?"N/A":v.toString(); }
    static double getDouble(Map<String,Object> m, String k) { Object v=m.get(k); if(v==null) return 0; if(v instanceof Number) return ((Number)v).doubleValue(); return Double.parseDouble(v.toString()); }
    static double getDoubleNull(Map<String,Object> m, String k) { Object v=m.get(k); if(v==null) return 0; if(v instanceof Number) return ((Number)v).doubleValue(); return Double.parseDouble(v.toString()); }
    static int getInt(Map<String,Object> m, String k, int d) { Object v=m.get(k); if(v==null) return d; if(v instanceof Number) return ((Number)v).intValue(); return Integer.parseInt(v.toString()); }
    static int getIntNull(Map<String,Object> m, String k) { Object v=m.get(k); if(v==null) return 0; if(v instanceof Number) return ((Number)v).intValue(); return Integer.parseInt(v.toString()); }
    static boolean getBool(Map<String,Object> m, String k) { Object v=m.get(k); if(v==null) return false; if(v instanceof Boolean) return (Boolean)v; return Boolean.parseBoolean(v.toString()); }
}
