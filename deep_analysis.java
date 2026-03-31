import java.io.*;
import java.util.*;

public class deep_analysis {
    public static void main(String[] args) throws Exception {
        String tmp = System.getenv("TEMP");
        String[] markets = {"KRW-SOL", "KRW-ADA", "KRW-BTC", "KRW-XRP", "KRW-ETH"};

        // ================================================================
        // SECTION 1: 마켓별 Phase 1 Top 15 상세 분석 (기간별 성과 포함)
        // ================================================================
        System.out.println(rep('=',200));
        System.out.println("SECTION 1: Phase 1 — 마켓별 Top 15 단일 전략 상세 (기간별 성과 + 파라미터)");
        System.out.println(rep('=',200));

        for (String market : markets) {
            List<Map<String, Object>> data = parseJsonArray(readFile(tmp + "\\p1_" + market + ".json"));
            // Dedup by strategy+interval+ema+tp+sl
            Set<String> seen = new LinkedHashSet<String>();
            List<Map<String, Object>> unique = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> d : data) {
                int phase = getInt(d, "phase", 1);
                if (phase != 1) continue;
                String key = getString(d,"strategyType") + "|" + getInt(d,"intervalMin",0) + "|" + getInt(d,"emaPeriod",0)
                    + "|" + (int)getDouble(d,"tpPct") + "|" + (int)getDouble(d,"slPct");
                if (!seen.contains(key)) {
                    seen.add(key);
                    unique.add(d);
                }
                if (unique.size() >= 15) break;
            }
            System.out.println("\n--- " + market + " (Top 15 unique) ---");
            System.out.printf("%-3s %-30s %4s %4s %4s %4s %4s %5s %5s | %8s %5s %4s | %8s %5s %4s | %8s %5s %4s | %6s%n",
                "#", "Strategy", "Int", "EMA", "TP%", "SL%", "Add", "Lock", "TStop",
                "1Y ROI", "1Y WR", "1Y T", "3M ROI", "3M WR", "3M T", "1M ROI", "1M WR", "1M T", "Trend");
            for (int i = 0; i < unique.size(); i++) {
                Map<String, Object> d = unique.get(i);
                double roi1y = getDouble(d,"roi");
                double roi3m = getDoubleNull(d,"roi3m");
                double roi1m = getDoubleNull(d,"roi1m");
                // Trend indicator: compare annualized rates
                String trend = trendIndicator(roi1y, roi3m, roi1m);
                System.out.printf("%-3d %-30s %4d %4d %4.0f %4.0f %4d %5s %5d | %+7.1f%% %4.1f%% %4d | %+7.1f%% %4.1f%% %4d | %+7.1f%% %4.1f%% %4d | %6s%n",
                    i+1, getString(d,"strategyType"), getInt(d,"intervalMin",0), getInt(d,"emaPeriod",0),
                    getDouble(d,"tpPct"), getDouble(d,"slPct"), getInt(d,"maxAddBuys",0),
                    getBool(d,"strategyLock")?"Y":"N", getInt(d,"timeStopMinutes",0),
                    roi1y, getDouble(d,"winRate"), getInt(d,"totalTrades",0),
                    roi3m, getDoubleNull(d,"winRate3m"), getIntNull(d,"totalTrades3m"),
                    roi1m, getDoubleNull(d,"winRate1m"), getIntNull(d,"totalTrades1m"),
                    trend);
            }
        }

        // ================================================================
        // SECTION 2: Phase 2 다중 전략 조합 Top 15 상세
        // ================================================================
        System.out.println("\n\n" + rep('=',200));
        System.out.println("SECTION 2: Phase 2 — 마켓별 Top 15 다중 전략 조합 (기간별 성과)");
        System.out.println(rep('=',200));

        for (String market : markets) {
            List<Map<String, Object>> data = parseJsonArray(readFile(tmp + "\\p2_" + market + ".json"));
            if (data.isEmpty()) {
                System.out.println("\n--- " + market + " --- (No Phase 2 results)");
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
                if (unique.size() >= 15) break;
            }
            System.out.println("\n--- " + market + " (Top 15 unique combos) ---");
            System.out.printf("%-3s %-58s %4s %4s %4s %5s %5s | %8s %5s %4s | %8s %5s %4s | %8s %5s %4s | %6s%n",
                "#", "Strategies", "TP%", "SL%", "Add", "Lock", "TStop",
                "1Y ROI", "1Y WR", "1Y T", "3M ROI", "3M WR", "3M T", "1M ROI", "1M WR", "1M T", "Trend");
            for (int i = 0; i < unique.size(); i++) {
                Map<String, Object> d = unique.get(i);
                String strategies = getString(d,"strategiesCsv");
                if (strategies.length() > 56) strategies = strategies.substring(0, 53) + "...";
                double roi1y = getDouble(d,"roi");
                double roi3m = getDoubleNull(d,"roi3m");
                double roi1m = getDoubleNull(d,"roi1m");
                String trend = trendIndicator(roi1y, roi3m, roi1m);
                System.out.printf("%-3d %-58s %4.0f %4.0f %4d %5s %5d | %+7.1f%% %4.1f%% %4d | %+7.1f%% %4.1f%% %4d | %+7.1f%% %4.1f%% %4d | %6s%n",
                    i+1, strategies,
                    getDouble(d,"tpPct"), getDouble(d,"slPct"), getInt(d,"maxAddBuys",0),
                    getBool(d,"strategyLock")?"Y":"N", getInt(d,"timeStopMinutes",0),
                    roi1y, getDouble(d,"winRate"), getInt(d,"totalTrades",0),
                    roi3m, getDoubleNull(d,"winRate3m"), getIntNull(d,"totalTrades3m"),
                    roi1m, getDoubleNull(d,"winRate1m"), getIntNull(d,"totalTrades1m"),
                    trend);
            }
        }

        // ================================================================
        // SECTION 3: 전략 빈도 분석 — 어떤 전략이 Top에 자주 등장하는가?
        // ================================================================
        System.out.println("\n\n" + rep('=',160));
        System.out.println("SECTION 3: 전략 빈도 분석 — Phase 1 Top 50 내 전략 출현 빈도");
        System.out.println(rep('=',160));

        Map<String, Map<String, int[]>> stratFreqByMarket = new LinkedHashMap<String, Map<String, int[]>>();
        for (String market : markets) {
            List<Map<String, Object>> data = parseJsonArray(readFile(tmp + "\\p1_" + market + ".json"));
            Map<String, int[]> freq = new LinkedHashMap<String, int[]>();
            int count = 0;
            for (Map<String, Object> d : data) {
                if (getInt(d,"phase",1) != 1) continue;
                String strat = getString(d,"strategyType");
                if (!freq.containsKey(strat)) freq.put(strat, new int[]{0, 0});
                freq.get(strat)[0]++;
                if (getDouble(d,"roi") > 0) freq.get(strat)[1]++;
                count++;
                if (count >= 50) break;
            }
            stratFreqByMarket.put(market, freq);
        }

        // Collect all strategy names
        Set<String> allStrats = new LinkedHashSet<String>();
        for (Map<String, int[]> m : stratFreqByMarket.values()) allStrats.addAll(m.keySet());

        System.out.printf("\n%-30s", "Strategy");
        for (String mkt : markets) System.out.printf(" | %10s", mkt.replace("KRW-",""));
        System.out.printf(" | %8s%n", "Total");
        System.out.println(rep('-', 30 + markets.length * 13 + 11));

        for (String strat : allStrats) {
            System.out.printf("%-30s", strat);
            int total = 0;
            for (String mkt : markets) {
                int[] f = stratFreqByMarket.get(mkt).get(strat);
                if (f == null) {
                    System.out.printf(" | %10s", "-");
                } else {
                    System.out.printf(" | %4d(%3d+)", f[0], f[1]);
                    total += f[0];
                }
            }
            System.out.printf(" | %8d%n", total);
        }

        // ================================================================
        // SECTION 4: 인터벌별 성과 분석 — 어떤 캔들 주기가 최적인가?
        // ================================================================
        System.out.println("\n\n" + rep('=',160));
        System.out.println("SECTION 4: 인터벌별 평균 ROI — Phase 1 Top 100 내 인터벌별 성과");
        System.out.println(rep('=',160));

        for (String market : markets) {
            List<Map<String, Object>> data = parseJsonArray(readFile(tmp + "\\p1_" + market + ".json"));
            Map<Integer, double[]> intervalStats = new TreeMap<Integer, double[]>(); // interval -> [sumRoi, count, sumWR, maxRoi]
            int count = 0;
            for (Map<String, Object> d : data) {
                if (getInt(d,"phase",1) != 1) continue;
                int interval = getInt(d,"intervalMin",0);
                double roi = getDouble(d,"roi");
                double wr = getDouble(d,"winRate");
                if (!intervalStats.containsKey(interval)) intervalStats.put(interval, new double[]{0,0,0,-999});
                double[] s = intervalStats.get(interval);
                s[0] += roi; s[1]++; s[2] += wr; if (roi > s[3]) s[3] = roi;
                count++;
                if (count >= 100) break;
            }
            System.out.printf("\n--- %s ---\n", market);
            System.out.printf("%-8s %8s %8s %8s %6s%n", "Interval", "AvgROI", "MaxROI", "AvgWR", "Count");
            for (Map.Entry<Integer, double[]> e : intervalStats.entrySet()) {
                double[] s = e.getValue();
                System.out.printf("%-8d %+7.1f%% %+7.1f%% %6.1f%% %6.0f%n",
                    e.getKey(), s[0]/s[1], s[3], s[2]/s[1], s[1]);
            }
        }

        // ================================================================
        // SECTION 5: TP/SL 분포 분석 — 최적 리스크 파라미터 대역
        // ================================================================
        System.out.println("\n\n" + rep('=',160));
        System.out.println("SECTION 5: TP/SL 분포 분석 — Phase 1 Top 50 내 TP·SL 분포");
        System.out.println(rep('=',160));

        for (String market : markets) {
            List<Map<String, Object>> data = parseJsonArray(readFile(tmp + "\\p1_" + market + ".json"));
            Map<Integer, int[]> tpDist = new TreeMap<Integer, int[]>();
            Map<Integer, int[]> slDist = new TreeMap<Integer, int[]>();
            int count = 0;
            for (Map<String, Object> d : data) {
                if (getInt(d,"phase",1) != 1) continue;
                int tp = (int) getDouble(d,"tpPct");
                int sl = (int) getDouble(d,"slPct");
                if (!tpDist.containsKey(tp)) tpDist.put(tp, new int[]{0});
                tpDist.get(tp)[0]++;
                if (!slDist.containsKey(sl)) slDist.put(sl, new int[]{0});
                slDist.get(sl)[0]++;
                count++;
                if (count >= 50) break;
            }
            System.out.printf("\n--- %s ---\n", market);
            System.out.print("  TP% distribution: ");
            for (Map.Entry<Integer, int[]> e : tpDist.entrySet()) {
                System.out.printf("TP%d=%d ", e.getKey(), e.getValue()[0]);
            }
            System.out.print("\n  SL% distribution: ");
            for (Map.Entry<Integer, int[]> e : slDist.entrySet()) {
                System.out.printf("SL%d=%d ", e.getKey(), e.getValue()[0]);
            }
            System.out.println();
        }

        // ================================================================
        // SECTION 6: 기간별 성과 일관성 분석 — 최근 하락 vs 상승 추세
        // ================================================================
        System.out.println("\n\n" + rep('=',200));
        System.out.println("SECTION 6: 기간별 성과 일관성 — Phase 1 Top 5 + Phase 2 Top 5 (1Y vs 3M vs 1M ROI 비교)");
        System.out.println(rep('=',200));

        for (String market : markets) {
            System.out.println("\n" + rep('-',180));
            System.out.println("  " + market);
            System.out.println(rep('-',180));

            // Phase 1 Top 5
            List<Map<String, Object>> p1 = parseJsonArray(readFile(tmp + "\\p1_" + market + ".json"));
            System.out.println("  [Phase 1 Top 5]");
            System.out.printf("  %-3s %-28s %4s %4s | %8s → %8s → %8s | %s%n",
                "#", "Strategy", "TP", "SL", "1Y ROI", "3M ROI", "1M ROI", "Assessment");
            int cnt = 0;
            for (Map<String, Object> d : p1) {
                if (getInt(d,"phase",1) != 1) continue;
                double r1y = getDouble(d,"roi");
                double r3m = getDoubleNull(d,"roi3m");
                double r1m = getDoubleNull(d,"roi1m");
                int trades3m = getIntNull(d,"totalTrades3m");
                int trades1m = getIntNull(d,"totalTrades1m");
                String assess = assessConsistency(r1y, r3m, r1m, trades3m, trades1m);
                System.out.printf("  %-3d %-28s %4.0f %4.0f | %+7.1f%% → %+7.1f%% → %+7.1f%% | %s%n",
                    cnt+1, getString(d,"strategyType"),
                    getDouble(d,"tpPct"), getDouble(d,"slPct"),
                    r1y, r3m, r1m, assess);
                cnt++;
                if (cnt >= 5) break;
            }

            // Phase 2 Top 5
            List<Map<String, Object>> p2 = parseJsonArray(readFile(tmp + "\\p2_" + market + ".json"));
            if (!p2.isEmpty()) {
                System.out.println("  [Phase 2 Top 5]");
                System.out.printf("  %-3s %-55s %4s %4s | %8s → %8s → %8s | %s%n",
                    "#", "Strategies", "TP", "SL", "1Y ROI", "3M ROI", "1M ROI", "Assessment");
                cnt = 0;
                Set<String> seen = new LinkedHashSet<String>();
                for (Map<String, Object> d : p2) {
                    String strats = getString(d,"strategiesCsv");
                    String key = strats + "|" + (int)getDouble(d,"tpPct") + "|" + (int)getDouble(d,"slPct");
                    if (seen.contains(key)) continue;
                    seen.add(key);
                    double r1y = getDouble(d,"roi");
                    double r3m = getDoubleNull(d,"roi3m");
                    double r1m = getDoubleNull(d,"roi1m");
                    int trades3m = getIntNull(d,"totalTrades3m");
                    int trades1m = getIntNull(d,"totalTrades1m");
                    String assess = assessConsistency(r1y, r3m, r1m, trades3m, trades1m);
                    String sDisplay = strats.length() > 53 ? strats.substring(0,50) + "..." : strats;
                    System.out.printf("  %-3d %-55s %4.0f %4.0f | %+7.1f%% → %+7.1f%% → %+7.1f%% | %s%n",
                        cnt+1, sDisplay,
                        getDouble(d,"tpPct"), getDouble(d,"slPct"),
                        r1y, r3m, r1m, assess);
                    cnt++;
                    if (cnt >= 5) break;
                }
            }
        }

        // ================================================================
        // SECTION 7: 매도전용 전략 효과 분석 (Phase 2)
        // ================================================================
        System.out.println("\n\n" + rep('=',160));
        System.out.println("SECTION 7: 매도전용 전략 효과 — Phase 2에서 매도전용 전략 포함 여부별 성과");
        System.out.println(rep('=',160));

        String[] sellOnlyStrats = {"BEARISH_ENGULFING","EVENING_STAR_SELL","THREE_BLACK_CROWS_SELL","THREE_METHODS_BEARISH"};
        for (String market : markets) {
            List<Map<String, Object>> data = parseJsonArray(readFile(tmp + "\\p2_" + market + ".json"));
            if (data.isEmpty()) continue;
            // Split by has-sell-only vs buy-only combos
            double sumRoiWithSell = 0, sumRoiNoSell = 0;
            int cntWithSell = 0, cntNoSell = 0;
            double maxRoiWithSell = -999, maxRoiNoSell = -999;
            for (Map<String, Object> d : data) {
                String strats = getString(d,"strategiesCsv");
                double roi = getDouble(d,"roi");
                boolean hasSellOnly = false;
                for (String so : sellOnlyStrats) {
                    if (strats.contains(so)) { hasSellOnly = true; break; }
                }
                if (hasSellOnly) {
                    sumRoiWithSell += roi; cntWithSell++;
                    if (roi > maxRoiWithSell) maxRoiWithSell = roi;
                } else {
                    sumRoiNoSell += roi; cntNoSell++;
                    if (roi > maxRoiNoSell) maxRoiNoSell = roi;
                }
            }
            System.out.printf("\n--- %s ---\n", market);
            System.out.printf("  매도전용 포함: %4d results, avgROI=%+.1f%%, maxROI=%+.1f%%%n",
                cntWithSell, cntWithSell>0?sumRoiWithSell/cntWithSell:0, maxRoiWithSell);
            System.out.printf("  매도전용 미포함: %4d results, avgROI=%+.1f%%, maxROI=%+.1f%%%n",
                cntNoSell, cntNoSell>0?sumRoiNoSell/cntNoSell:0, maxRoiNoSell);
        }

        // ================================================================
        // SECTION 8: Strategy Lock 효과 분석 (Phase 2)
        // ================================================================
        System.out.println("\n\n" + rep('=',160));
        System.out.println("SECTION 8: Strategy Lock 효과 — Phase 2에서 Lock ON vs OFF 비교");
        System.out.println(rep('=',160));

        for (String market : markets) {
            List<Map<String, Object>> data = parseJsonArray(readFile(tmp + "\\p2_" + market + ".json"));
            if (data.isEmpty()) continue;
            double sumLockOn = 0, sumLockOff = 0;
            int cntLockOn = 0, cntLockOff = 0;
            double maxLockOn = -999, maxLockOff = -999;
            for (Map<String, Object> d : data) {
                double roi = getDouble(d,"roi");
                boolean lock = getBool(d,"strategyLock");
                if (lock) {
                    sumLockOn += roi; cntLockOn++;
                    if (roi > maxLockOn) maxLockOn = roi;
                } else {
                    sumLockOff += roi; cntLockOff++;
                    if (roi > maxLockOff) maxLockOff = roi;
                }
            }
            System.out.printf("\n--- %s ---\n", market);
            System.out.printf("  Lock ON:  %4d results, avgROI=%+.1f%%, maxROI=%+.1f%%%n",
                cntLockOn, cntLockOn>0?sumLockOn/cntLockOn:0, maxLockOn);
            System.out.printf("  Lock OFF: %4d results, avgROI=%+.1f%%, maxROI=%+.1f%%%n",
                cntLockOff, cntLockOff>0?sumLockOff/cntLockOff:0, maxLockOff);
        }

        // ================================================================
        // SECTION 9: TimeStop 영향 분석
        // ================================================================
        System.out.println("\n\n" + rep('=',160));
        System.out.println("SECTION 9: TimeStop 영향 — Phase 1 Top 100 내 TimeStop별 평균 ROI");
        System.out.println(rep('=',160));

        for (String market : markets) {
            List<Map<String, Object>> data = parseJsonArray(readFile(tmp + "\\p1_" + market + ".json"));
            Map<Integer, double[]> tsStats = new TreeMap<Integer, double[]>();
            int count = 0;
            for (Map<String, Object> d : data) {
                if (getInt(d,"phase",1) != 1) continue;
                int ts = getInt(d,"timeStopMinutes",0);
                double roi = getDouble(d,"roi");
                if (!tsStats.containsKey(ts)) tsStats.put(ts, new double[]{0,0});
                double[] s = tsStats.get(ts);
                s[0] += roi; s[1]++;
                count++;
                if (count >= 100) break;
            }
            System.out.printf("\n--- %s ---\n", market);
            System.out.printf("%-10s %8s %6s%n", "TimeStop", "AvgROI", "Count");
            for (Map.Entry<Integer, double[]> e : tsStats.entrySet()) {
                double[] s = e.getValue();
                System.out.printf("%-10d %+7.1f%% %6.0f%n", e.getKey(), s[0]/s[1], s[1]);
            }
        }

        // ================================================================
        // SECTION 10: 최종 마켓별 최적 설정 권장안
        // ================================================================
        System.out.println("\n\n" + rep('=',200));
        System.out.println("SECTION 10: 마켓별 종합 최적 설정 권장안 (Phase 1 + Phase 2 교차 분석)");
        System.out.println(rep('=',200));

        for (String market : markets) {
            List<Map<String, Object>> p1 = parseJsonArray(readFile(tmp + "\\p1_" + market + ".json"));
            List<Map<String, Object>> p2 = parseJsonArray(readFile(tmp + "\\p2_" + market + ".json"));

            System.out.println("\n" + rep('~',140));
            System.out.println("  " + market + " 종합 분석");
            System.out.println(rep('~',140));

            // Phase 1 best
            Map<String, Object> best1 = null;
            for (Map<String, Object> d : p1) {
                if (getInt(d,"phase",1) == 1) { best1 = d; break; }
            }
            if (best1 != null) {
                System.out.printf("  Phase 1 Best: %s (Int=%d, EMA=%d) | TP%.0f/SL%.0f | Add=%d | TStop=%d%n",
                    getString(best1,"strategyType"), getInt(best1,"intervalMin",0), getInt(best1,"emaPeriod",0),
                    getDouble(best1,"tpPct"), getDouble(best1,"slPct"), getInt(best1,"maxAddBuys",0),
                    getInt(best1,"timeStopMinutes",0));
                System.out.printf("    1Y: ROI=%+.1f%% WR=%.1f%% Trades=%d | 3M: ROI=%+.1f%% WR=%.1f%% Trades=%d | 1M: ROI=%+.1f%% WR=%.1f%% Trades=%d%n",
                    getDouble(best1,"roi"), getDouble(best1,"winRate"), getInt(best1,"totalTrades",0),
                    getDoubleNull(best1,"roi3m"), getDoubleNull(best1,"winRate3m"), getIntNull(best1,"totalTrades3m"),
                    getDoubleNull(best1,"roi1m"), getDoubleNull(best1,"winRate1m"), getIntNull(best1,"totalTrades1m"));
            }

            // Phase 2 best
            if (!p2.isEmpty()) {
                Map<String, Object> best2 = p2.get(0);
                System.out.printf("  Phase 2 Best: %s | TP%.0f/SL%.0f | Add=%d | Lock=%s | TStop=%d%n",
                    getString(best2,"strategiesCsv"),
                    getDouble(best2,"tpPct"), getDouble(best2,"slPct"), getInt(best2,"maxAddBuys",0),
                    getBool(best2,"strategyLock")?"Y":"N", getInt(best2,"timeStopMinutes",0));
                System.out.printf("    1Y: ROI=%+.1f%% WR=%.1f%% Trades=%d | 3M: ROI=%+.1f%% WR=%.1f%% Trades=%d | 1M: ROI=%+.1f%% WR=%.1f%% Trades=%d%n",
                    getDouble(best2,"roi"), getDouble(best2,"winRate"), getInt(best2,"totalTrades",0),
                    getDoubleNull(best2,"roi3m"), getDoubleNull(best2,"winRate3m"), getIntNull(best2,"totalTrades3m"),
                    getDoubleNull(best2,"roi1m"), getDoubleNull(best2,"winRate1m"), getIntNull(best2,"totalTrades1m"));
            }

            // Find best "consistent" strategy (positive in all 3 periods with reasonable trade count)
            System.out.println("  [기간 일관성 최고 전략 - 3기간 모두 양수 + 최근 추세 양호]");
            boolean found = false;
            for (Map<String, Object> d : p1) {
                if (getInt(d,"phase",1) != 1) continue;
                double r1y = getDouble(d,"roi");
                double r3m = getDoubleNull(d,"roi3m");
                double r1m = getDoubleNull(d,"roi1m");
                int t3m = getIntNull(d,"totalTrades3m");
                int t1m = getIntNull(d,"totalTrades1m");
                if (r1y > 0 && r3m > 0 && r1m > 0 && t3m >= 3 && t1m >= 1) {
                    System.out.printf("    -> %s (Int=%d, EMA=%d) TP%.0f/SL%.0f | 1Y=%+.1f%% 3M=%+.1f%% 1M=%+.1f%% | Trades: %d/%d/%d%n",
                        getString(d,"strategyType"), getInt(d,"intervalMin",0), getInt(d,"emaPeriod",0),
                        getDouble(d,"tpPct"), getDouble(d,"slPct"), r1y, r3m, r1m,
                        getInt(d,"totalTrades",0), t3m, t1m);
                    found = true;
                    break;
                }
            }
            if (!found) System.out.println("    -> (없음 - 3기간 모두 양수인 전략 없음)");

            // Same for Phase 2
            if (!p2.isEmpty()) {
                System.out.println("  [Phase 2 기간 일관성 최고 전략]");
                found = false;
                for (Map<String, Object> d : p2) {
                    double r1y = getDouble(d,"roi");
                    double r3m = getDoubleNull(d,"roi3m");
                    double r1m = getDoubleNull(d,"roi1m");
                    int t3m = getIntNull(d,"totalTrades3m");
                    int t1m = getIntNull(d,"totalTrades1m");
                    if (r1y > 0 && r3m > 0 && r1m > 0 && t3m >= 3 && t1m >= 1) {
                        String strats = getString(d,"strategiesCsv");
                        if (strats.length() > 60) strats = strats.substring(0,57) + "...";
                        System.out.printf("    -> %s TP%.0f/SL%.0f | 1Y=%+.1f%% 3M=%+.1f%% 1M=%+.1f%% | Trades: %d/%d/%d%n",
                            strats, getDouble(d,"tpPct"), getDouble(d,"slPct"), r1y, r3m, r1m,
                            getInt(d,"totalTrades",0), t3m, t1m);
                        found = true;
                        break;
                    }
                }
                if (!found) System.out.println("    -> (없음)");
            }
        }
    }

    static String trendIndicator(double r1y, double r3m, double r1m) {
        // Annualize: 3m*4, 1m*12
        double ann3m = r3m * 4;
        double ann1m = r1m * 12;
        if (ann1m > ann3m && ann3m > r1y * 0.8) return "UP";
        if (ann1m < r1y * 0.3 && ann3m < r1y * 0.5) return "DOWN";
        if (r1m < 0) return "WARN";
        return "FLAT";
    }

    static String assessConsistency(double r1y, double r3m, double r1m, int t3m, int t1m) {
        if (r1y > 0 && r3m > 0 && r1m > 0 && t3m >= 3 && t1m >= 1)
            return "*** CONSISTENT — 3기간 모두 양수, 최근 활발";
        if (r1y > 0 && r3m > 0 && r1m > 0)
            return "** GOOD — 3기간 양수 (거래수 소량)";
        if (r1y > 0 && r3m > 0 && r1m <= 0)
            return "* CAUTION — 1M 약세 (최근 실적 하락)";
        if (r1y > 0 && r3m <= 0)
            return "! WARN — 3M/1M 약세 (과거 성과에 의존)";
        if (r1y > 0 && t3m == 0)
            return "? INACTIVE — 최근 3M 거래 없음";
        return "x POOR — 부정적 추세";
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
