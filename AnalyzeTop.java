import java.io.*;
import java.util.*;

public class AnalyzeTop {
    public static void main(String[] args) throws Exception {
        String csvPath = "data/optimize_results.csv";
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(csvPath), "UTF-8"));
        String header = br.readLine();
        // strategies,coin,interval,tp_pct,sl_pct,min_confidence,trades,wins,win_rate,total_pnl,roi_pct,final_capital,tp_sells,sl_sells,pattern_sells

        List<String[]> rows = new ArrayList<String[]>();
        String line;
        while ((line = br.readLine()) != null) {
            String[] cols = line.split(",");
            if (cols.length >= 12) {
                double fc = Double.parseDouble(cols[11]);
                double roi = (fc - 1000000.0) / 10000.0;
                if (roi > 0) {
                    rows.add(new String[]{
                        String.format("%.2f", roi), cols[0], cols[1], cols[2], cols[3], cols[4], cols[5], cols[6], cols[8], cols[11]
                    });
                }
            }
        }
        br.close();

        // Sort by ROI descending
        Collections.sort(rows, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                return Double.compare(Double.parseDouble(b[0]), Double.parseDouble(a[0]));
            }
        });

        // Deduplicate
        Set<String> seen = new LinkedHashSet<String>();
        System.out.println("=== TOP 20 PROFITABLE COMBOS (deduplicated) ===");
        int count = 0;
        for (String[] r : rows) {
            String key = r[1] + "|" + r[2] + "|" + r[3] + "|" + r[4] + "|" + r[5];
            if (seen.contains(key)) continue;
            seen.add(key);
            count++;
            System.out.printf("ROI +%s%% | %s | %s %sm | TP%s SL%s | %s trades WR%s%%%n",
                r[0], r[1], r[2], r[3], r[4], r[5], r[7], r[8]);
            if (count >= 20) break;
        }

        System.out.println();
        System.out.println("=== BEST PER COIN ===");
        for (String coin : new String[]{"KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-ADA"}) {
            for (String[] r : rows) {
                if (r[2].equals(coin)) {
                    System.out.printf("%s: ROI +%s%% | %s | %sm | TP%s SL%s | %s trades WR%s%%%n",
                        coin, r[0], r[1], r[3], r[4], r[5], r[7], r[8]);
                    break;
                }
            }
        }

        System.out.println();
        System.out.println("=== BEST PER STRATEGY ===");
        Map<String, String[]> stratBest = new LinkedHashMap<String, String[]>();
        for (String[] r : rows) {
            if (!stratBest.containsKey(r[1])) {
                stratBest.put(r[1], r);
            }
        }
        List<Map.Entry<String, String[]>> entries = new ArrayList<Map.Entry<String, String[]>>(stratBest.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, String[]>>() {
            public int compare(Map.Entry<String, String[]> a, Map.Entry<String, String[]> b) {
                return Double.compare(Double.parseDouble(b.getValue()[0]), Double.parseDouble(a.getValue()[0]));
            }
        });
        for (Map.Entry<String, String[]> e : entries) {
            String[] r = e.getValue();
            System.out.printf("ROI +%s%% | %s | %s %sm | TP%s SL%s | %s trades WR%s%%%n",
                r[0], r[1], r[2], r[3], r[4], r[5], r[7], r[8]);
        }
    }
}
