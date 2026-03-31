import java.io.*;
import java.time.*;
import java.util.*;

/**
 * Morning Rush Scanner Backtest
 * - 08:00-08:59 range collection
 * - 09:00 candle: price > rangeHigh × 1.05 (5% gap) + volume 5x+ → BUY
 * - TP +2%, SL -3%, Session end 10:00
 */
public class MorningRushBacktest {

    static final String DIR = "data/candles/5m";
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final double GAP_THRESHOLD = 0.05; // 5%
    static final double VOLUME_MULT = 5.0;
    static final double TP_PCT = 2.0;
    static final double SL_PCT = 3.0;
    static final double ORDER_SIZE = 165000; // KRW per trade

    public static void main(String[] args) throws Exception {
        File dir = new File(DIR);
        String[] csvFiles = dir.list((d, n) -> n.endsWith(".csv"));
        if (csvFiles == null || csvFiles.length == 0) {
            System.out.println("No CSV files in " + DIR);
            return;
        }

        int totalTrades = 0, totalWins = 0;
        double totalPnl = 0;
        List<String> tradeDetails = new ArrayList<>();

        // Test periods
        String[][] periods = {
            {"2025-04-01", "2025-07-31", "BULL (2025-04~07)"},
            {"2025-11-01", "2026-02-28", "BEAR (2025-11~2026-02)"},
            {"2026-02-25", "2026-03-29", "RECENT (last 30d)"},
            {"2025-04-01", "2026-03-29", "ALL (1 year)"}
        };

        for (String[] period : periods) {
            String from = period[0], to = period[1], label = period[2];
            int pTrades = 0, pWins = 0;
            double pPnl = 0;
            List<String> pDetails = new ArrayList<>();

            for (String csvFile : csvFiles) {
                String symbol = csvFile.replace(".csv", "");
                List<double[]> candles = loadCSV(DIR + "/" + csvFile);
                if (candles.isEmpty()) continue;

                List<double[]> filtered = filterByDate(candles, from, to);
                if (filtered.size() < 50) continue;

                // Group by date
                Map<String, List<double[]>> byDate = new LinkedHashMap<>();
                for (double[] c : filtered) {
                    String date = epochToKstDate(c[0]);
                    byDate.computeIfAbsent(date, k -> new ArrayList<>()).add(c);
                }

                for (Map.Entry<String, List<double[]>> entry : byDate.entrySet()) {
                    String date = entry.getKey();
                    List<double[]> dayCandles = entry.getValue();

                    // Range (08:00-08:59)
                    double rangeHigh = -1;
                    int rangeCount = 0;
                    double totalVol = 0;
                    int volCount = 0;

                    for (double[] c : dayCandles) {
                        int hm = epochToKstHHMM(c[0]);
                        if (hm >= 800 && hm <= 859) {
                            rangeHigh = Math.max(rangeHigh, c[2]); // high
                            rangeCount++;
                        }
                        // 24h avg volume (use all candles as proxy)
                        totalVol += c[5];
                        volCount++;
                    }
                    if (rangeCount < 4 || rangeHigh <= 0) continue;
                    double avgVol = volCount > 0 ? totalVol / volCount : 0;

                    // Check 09:00 candle for gap-up
                    for (double[] c : dayCandles) {
                        int hm = epochToKstHHMM(c[0]);
                        if (hm != 900) continue;

                        double close = c[4];
                        double volume = c[5];
                        double gapPct = (close - rangeHigh) / rangeHigh;
                        double volRatio = avgVol > 0 ? volume / avgVol : 0;

                        // Entry conditions
                        if (gapPct < GAP_THRESHOLD) continue;
                        if (volRatio < VOLUME_MULT) continue;

                        // Body ratio check (50%+)
                        double body = Math.abs(close - c[1]);
                        double wick = c[2] - c[3];
                        if (wick <= 0) continue;
                        double bodyRatio = body / wick;
                        if (bodyRatio < 0.50) continue;

                        // ENTRY at close price
                        double entryPrice = close;
                        boolean exited = false;
                        double exitPrice = 0;
                        String exitReason = "";

                        // Monitor 09:05 ~ 10:00 for TP/SL
                        for (double[] mc : dayCandles) {
                            int mcHm = epochToKstHHMM(mc[0]);
                            if (mcHm <= 900) continue; // skip entry candle and before

                            // TP: high reaches entry × (1 + TP%)
                            double tpTarget = entryPrice * (1 + TP_PCT / 100);
                            if (mc[2] >= tpTarget) {
                                exitPrice = tpTarget;
                                exitReason = "TP";
                                exited = true;
                                break;
                            }

                            // SL: low reaches entry × (1 - SL%)
                            double slTarget = entryPrice * (1 - SL_PCT / 100);
                            if (mc[3] <= slTarget) {
                                exitPrice = slTarget;
                                exitReason = "SL";
                                exited = true;
                                break;
                            }

                            // Session end: 10:00
                            if (mcHm >= 1000) {
                                exitPrice = mc[4]; // close at session end
                                exitReason = "SESSION";
                                exited = true;
                                break;
                            }
                        }

                        if (!exited) {
                            // No exit found (data ends before 10:00)
                            exitPrice = entryPrice;
                            exitReason = "NO_EXIT";
                        }

                        double pnlPct = (exitPrice - entryPrice) / entryPrice * 100;
                        double pnlKrw = ORDER_SIZE * pnlPct / 100;

                        pTrades++;
                        pPnl += pnlKrw;
                        if (pnlKrw > 0) pWins++;

                        pDetails.add(String.format("  %s %s gap=%.1f%% vol=%.0fx body=%.0f%% entry=%.1f exit=%.1f pnl=%+.0f (%s)",
                            date, symbol, gapPct * 100, volRatio, bodyRatio * 100,
                            entryPrice, exitPrice, pnlKrw, exitReason));
                    }
                }
            }

            double winRate = pTrades > 0 ? (pWins * 100.0 / pTrades) : 0;
            System.out.printf("=== %s ===\n", label);
            System.out.printf("  Trades: %d | Wins: %d | Losses: %d | Win Rate: %.0f%%\n",
                pTrades, pWins, pTrades - pWins, winRate);
            System.out.printf("  PnL: %+,.0f KRW | Avg: %+,.0f KRW/trade\n",
                pPnl, pTrades > 0 ? pPnl / pTrades : 0);
            if (!pDetails.isEmpty()) {
                System.out.println("  Details:");
                for (String d : pDetails) System.out.println(d);
            }
            System.out.println();

            totalTrades += pTrades;
            totalWins += pWins;
            totalPnl += pPnl;
        }
    }

    static List<double[]> loadCSV(String path) throws Exception {
        List<double[]> list = new ArrayList<>();
        BufferedReader br = new BufferedReader(new FileReader(path));
        br.readLine(); // skip header
        String line;
        while ((line = br.readLine()) != null) {
            String[] parts = line.split(",");
            if (parts.length < 6) continue;
            list.add(new double[]{
                Double.parseDouble(parts[0].trim()),
                Double.parseDouble(parts[1].trim()),
                Double.parseDouble(parts[2].trim()),
                Double.parseDouble(parts[3].trim()),
                Double.parseDouble(parts[4].trim()),
                Double.parseDouble(parts[5].trim())
            });
        }
        br.close();
        return list;
    }

    static String epochToKstDate(double epoch) {
        return Instant.ofEpochSecond((long) epoch).atZone(KST).toLocalDate().toString();
    }

    static int epochToKstHHMM(double epoch) {
        ZonedDateTime z = Instant.ofEpochSecond((long) epoch).atZone(KST);
        return z.getHour() * 100 + z.getMinute();
    }

    static List<double[]> filterByDate(List<double[]> candles, String from, String to) {
        double fromEpoch = LocalDate.parse(from).atStartOfDay().toEpochSecond(ZoneOffset.ofHours(9));
        double toEpoch = LocalDate.parse(to).plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.ofHours(9));
        List<double[]> filtered = new ArrayList<>();
        for (double[] c : candles) {
            if (c[0] >= fromEpoch && c[0] < toEpoch) filtered.add(c);
        }
        return filtered;
    }
}
