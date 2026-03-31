import java.io.*;
import java.net.*;
import java.util.*;
import java.time.*;
import java.time.format.*;

/**
 * 캔들 데이터 다운로드 + JSON 파일 저장 + 오프닝/올데이 백테스트 분석
 *
 * 실행: javac ExportAndBacktest.java && java ExportAndBacktest
 */
public class ExportAndBacktest {

    static final String[] MARKETS = {
        "KRW-ETH","KRW-SOL","KRW-ADA","KRW-LINK","KRW-AVAX",
        "KRW-NEAR","KRW-ETC","KRW-USDT","KRW-JST","KRW-JTO",
        "KRW-VIRTUAL","KRW-IP","KRW-AKT","KRW-TRUMP","KRW-WLD",
        "KRW-ETHFI","KRW-TRX","KRW-PENDLE","KRW-APT","KRW-SEI"
    };
    static final int CANDLE_UNIT = 5; // 5분봉
    static final String DIR = "data/candles/5m";
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static void main(String[] args) throws Exception {
        new File(DIR).mkdirs();

        // Step 1: 캔들 데이터 다운로드 (파일이 없는 코인만)
        for (String market : MARKETS) {
            String file = DIR + "/" + market.replace("KRW-","") + ".csv";
            if (new File(file).exists()) {
                System.out.println("SKIP " + market + " (already exists)");
                continue;
            }
            System.out.print("Downloading " + market + "...");
            List<double[]> candles = downloadCandles(market, CANDLE_UNIT, 365);
            saveCSV(file, candles);
            System.out.println(" " + candles.size() + " candles saved");
        }

        // Step 2: 백테스트 분석
        System.out.println("\n========================================");
        System.out.println("  Backtest Analysis (5-min candles)");
        System.out.println("========================================\n");

        // 기간 정의
        String[][] periods = {
            {"2025-04-01","2025-07-31","BULL (2025-04~07, BTC +33%)"},
            {"2025-11-01","2026-02-28","BEAR (2025-11~2026-02, BTC -40%)"},
            {"2026-02-25","2026-03-26","SIDEWAYS (last 30d)"}
        };

        for (String[] period : periods) {
            String from = period[0], to = period[1], label = period[2];
            System.out.println("=== " + label + " ===");

            int totalOpenTrades = 0, totalOpenWins = 0;
            double totalOpenPnl = 0;
            int totalAdTrades = 0, totalAdWins = 0;
            double totalAdPnl = 0;

            for (String market : MARKETS) {
                String file = DIR + "/" + market.replace("KRW-","") + ".csv";
                List<double[]> candles = loadCSV(file);
                if (candles.isEmpty()) continue;

                // 날짜 필터링
                List<double[]> filtered = filterByDate(candles, from, to);
                if (filtered.size() < 50) continue;

                // 오프닝 스캐너 백테스트
                int[] openResult = backtestOpening(filtered, 500000);
                totalOpenTrades += openResult[0];
                totalOpenWins += openResult[1];
                totalOpenPnl += openResult[2] / 100.0;

                // 올데이 백테스트
                int[] adResult = backtestAllday(filtered, 500000);
                totalAdTrades += adResult[0];
                totalAdWins += adResult[1];
                totalAdPnl += adResult[2] / 100.0;
            }

            double openWinRate = totalOpenTrades > 0 ? (totalOpenWins * 100.0 / totalOpenTrades) : 0;
            double adWinRate = totalAdTrades > 0 ? (totalAdWins * 100.0 / totalAdTrades) : 0;

            // Relaxed Range SL version
            int totalOpenRelaxTrades = 0, totalOpenRelaxWins = 0;
            double totalOpenRelaxPnl = 0;
            for (String market : MARKETS) {
                String file = DIR + "/" + market.replace("KRW-","") + ".csv";
                List<double[]> candles = loadCSV(file);
                if (candles.isEmpty()) continue;
                List<double[]> filtered = filterByDate(candles, from, to);
                if (filtered.size() < 50) continue;
                int[] r = backtestOpeningRelaxed(filtered, 500000);
                totalOpenRelaxTrades += r[0];
                totalOpenRelaxWins += r[1];
                totalOpenRelaxPnl += r[2] / 100.0;
            }
            double openRelaxWinRate = totalOpenRelaxTrades > 0 ? (totalOpenRelaxWins * 100.0 / totalOpenRelaxTrades) : 0;

            // Relaxed + Extended session (14:00)
            int totalOpenExtTrades = 0, totalOpenExtWins = 0;
            double totalOpenExtPnl = 0;
            for (String market : MARKETS) {
                String file = DIR + "/" + market.replace("KRW-","") + ".csv";
                List<double[]> cd = loadCSV(file);
                if (cd.isEmpty()) continue;
                List<double[]> fd = filterByDate(cd, from, to);
                if (fd.size() < 50) continue;
                int[] r = backtestOpeningCustom(fd, 500000, false, 1200); // current + 12:00
                // already counted above
                int[] r2 = backtestOpeningCustom(fd, 500000, true, 1400); // relaxed + 14:00
                totalOpenExtTrades += r2[0];
                totalOpenExtWins += r2[1];
                totalOpenExtPnl += r2[2] / 100.0;
            }
            double openExtWinRate = totalOpenExtTrades > 0 ? (totalOpenExtWins * 100.0 / totalOpenExtTrades) : 0;

            System.out.printf("  Opening (cur,12h):  %3d | %+,8.0f | ROI %+.2f%% | win %.0f%%\n",
                totalOpenTrades, totalOpenPnl, totalOpenPnl / 5000, openWinRate);
            System.out.printf("  Opening (rlx,12h):  %3d | %+,8.0f | ROI %+.2f%% | win %.0f%%\n",
                totalOpenRelaxTrades, totalOpenRelaxPnl, totalOpenRelaxPnl / 5000, openRelaxWinRate);
            System.out.printf("  Opening (rlx,14h):  %3d | %+,8.0f | ROI %+.2f%% | win %.0f%%\n",
                totalOpenExtTrades, totalOpenExtPnl, totalOpenExtPnl / 5000, openExtWinRate);
            System.out.printf("  AllDay:             %3d | %+,8.0f | ROI %+.2f%% | win %.0f%%\n",
                totalAdTrades, totalAdPnl, totalAdPnl / 5000, adWinRate);
            System.out.println();
        }
    }

    // ===== ATR 계산 =====
    static double calcATR(List<double[]> candles, int end, int period) {
        if (end < period) return 0;
        double sum = 0;
        for (int i = end - period + 1; i <= end; i++) {
            double h = candles.get(i)[2], l = candles.get(i)[3];
            double pc = candles.get(i-1)[4]; // prev close
            double tr = Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
            sum += tr;
        }
        return sum / period;
    }

    // ===== 오프닝 스캐너 백테스트 (ATR 기반 TP/SL + 트레일링) =====
    static int[] backtestOpening(List<double[]> candles, double capital) {
        int trades = 0, wins = 0;
        long totalPnlX100 = 0;
        double orderSize = capital * 0.33;

        Map<String, List<double[]>> byDate = new LinkedHashMap<>();
        Map<String, Integer> dateStartIdx = new LinkedHashMap<>();
        for (int i = 0; i < candles.size(); i++) {
            String date = epochToKstDate(candles.get(i)[0]);
            byDate.computeIfAbsent(date, k -> new ArrayList<>()).add(candles.get(i));
            dateStartIdx.putIfAbsent(date, i);
        }

        for (Map.Entry<String, List<double[]>> entry : byDate.entrySet()) {
            List<double[]> dayCandles = entry.getValue();
            int globalStartIdx = dateStartIdx.get(entry.getKey());

            // Range (08:00~08:59 KST)
            double rangeHigh = -1, rangeLow = Double.MAX_VALUE;
            int rangeCount = 0;
            double avgVol = 0;
            for (double[] c : dayCandles) {
                int hm = epochToKstHHMM(c[0]);
                if (hm >= 800 && hm <= 859) {
                    rangeHigh = Math.max(rangeHigh, c[2]);
                    rangeLow = Math.min(rangeLow, c[3]);
                    rangeCount++;
                    avgVol += c[5];
                }
            }
            if (rangeCount < 4 || rangeHigh <= rangeLow) continue;
            avgVol /= rangeCount;
            double rangePct = (rangeHigh - rangeLow) / rangeLow * 100;
            if (rangePct < 0.3) continue;

            // Entry (09:05~10:30 KST) with ATR-based TP/SL
            boolean inPosition = false;
            double entryPrice = 0, peakHigh = 0;
            double tpPrice = 0;
            int localIdx = 0;

            for (int di = 0; di < dayCandles.size(); di++) {
                double[] c = dayCandles.get(di);
                int hm = epochToKstHHMM(c[0]);
                double close = c[4], high = c[2], low = c[3];

                if (!inPosition && hm >= 905 && hm <= 1030) {
                    if (close > rangeHigh) {
                        double boPct = (close - rangeHigh) / rangeHigh * 100;
                        // Volume filter: 1.5x avg
                        if (boPct >= 0.2 && c[5] > avgVol * 1.5) {
                            // Body ratio filter: 45%+
                            double body = Math.abs(close - c[1]);
                            double wick = high - low;
                            if (wick > 0 && body / wick >= 0.45) {
                                entryPrice = close;
                                peakHigh = high;
                                inPosition = true;
                                // ATR-based TP: entry + ATR(10) * 1.5
                                int gi = globalStartIdx + di;
                                double atr = (gi >= 11) ? calcATR(candles, gi, 10) : rangePct * entryPrice / 100;
                                tpPrice = entryPrice + atr * 1.5;
                            }
                        }
                    }
                }

                if (inPosition) {
                    peakHigh = Math.max(peakHigh, high);
                    double pnlPct = (close - entryPrice) / entryPrice * 100;

                    // 1. Hard SL: -2%
                    if (pnlPct <= -2.0) {
                        totalPnlX100 += (long)(orderSize * pnlPct / 100 * 100);
                        trades++; inPosition = false; continue;
                    }

                    // 2. Range SL: close < rangeHigh (breakout failed)
                    if (close < rangeHigh && hm > 910) {
                        totalPnlX100 += (long)(orderSize * pnlPct / 100 * 100);
                        trades++; if (pnlPct > 0) wins++;
                        inPosition = false; continue;
                    }

                    // 3. ATR-based TP
                    if (high >= tpPrice) {
                        double tpPnl = (tpPrice - entryPrice) / entryPrice * 100;
                        totalPnlX100 += (long)(orderSize * tpPnl / 100 * 100);
                        trades++; wins++; inPosition = false; continue;
                    }

                    // 4. Trailing stop: peak - ATR*0.6
                    if (close > entryPrice) {
                        int gi = globalStartIdx + di;
                        double atr = (gi >= 11) ? calcATR(candles, gi, 10) : rangePct * entryPrice / 100;
                        double trailStop = peakHigh - atr * 0.6;
                        if (trailStop > entryPrice && close <= trailStop) {
                            totalPnlX100 += (long)(orderSize * pnlPct / 100 * 100);
                            trades++; wins++; inPosition = false; continue;
                        }
                    }

                    // 5. Session end: 12:00 force exit
                    if (hm >= 1200) {
                        totalPnlX100 += (long)(orderSize * pnlPct / 100 * 100);
                        trades++; if (pnlPct > 0) wins++;
                        inPosition = false;
                    }
                }
            }
        }
        return new int[]{trades, wins, (int)totalPnlX100};
    }

    // ===== 오프닝 스캐너 (파라미터화: relaxed SL + session end) =====
    static int[] backtestOpeningCustom(List<double[]> candles, double capital, boolean relaxed, int sessionEndHHMM) {
        int trades = 0, wins = 0;
        long totalPnlX100 = 0;
        double orderSize = capital * 0.33;

        Map<String, List<double[]>> byDate = new LinkedHashMap<>();
        Map<String, Integer> dateStartIdx = new LinkedHashMap<>();
        for (int i = 0; i < candles.size(); i++) {
            String date = epochToKstDate(candles.get(i)[0]);
            byDate.computeIfAbsent(date, k -> new ArrayList<>()).add(candles.get(i));
            dateStartIdx.putIfAbsent(date, i);
        }

        for (Map.Entry<String, List<double[]>> entry : byDate.entrySet()) {
            List<double[]> dayCandles = entry.getValue();
            int globalStartIdx = dateStartIdx.get(entry.getKey());

            double rangeHigh = -1, rangeLow = Double.MAX_VALUE;
            int rangeCount = 0; double avgVol = 0;
            for (double[] c : dayCandles) {
                int hm = epochToKstHHMM(c[0]);
                if (hm >= 800 && hm <= 859) {
                    rangeHigh = Math.max(rangeHigh, c[2]);
                    rangeLow = Math.min(rangeLow, c[3]);
                    rangeCount++; avgVol += c[5];
                }
            }
            if (rangeCount < 4 || rangeHigh <= rangeLow) continue;
            avgVol /= rangeCount;
            double rangePct = (rangeHigh - rangeLow) / rangeLow * 100;
            if (rangePct < 0.3) continue;

            boolean inPosition = false;
            double entryPrice = 0, peakHigh = 0, tpPrice = 0, slLine = 0;

            for (int di = 0; di < dayCandles.size(); di++) {
                double[] c = dayCandles.get(di);
                int hm = epochToKstHHMM(c[0]);
                double close = c[4], high = c[2];

                if (!inPosition && hm >= 905 && hm <= 1030) {
                    if (close > rangeHigh) {
                        double boPct = (close - rangeHigh) / rangeHigh * 100;
                        if (boPct >= 0.2 && c[5] > avgVol * 1.5) {
                            double body = Math.abs(close - c[1]), wick = high - c[3];
                            if (wick > 0 && body / wick >= 0.45) {
                                entryPrice = close; peakHigh = high; inPosition = true;
                                int gi = globalStartIdx + di;
                                double atr = (gi >= 11) ? calcATR(candles, gi, 10) : rangePct * entryPrice / 100;
                                tpPrice = entryPrice + atr * 1.5;
                                slLine = relaxed ? rangeHigh - atr * 0.3 : rangeHigh;
                            }
                        }
                    }
                }
                if (inPosition) {
                    peakHigh = Math.max(peakHigh, high);
                    double pnlPct = (close - entryPrice) / entryPrice * 100;
                    if (pnlPct <= -2.0) { totalPnlX100 += (long)(orderSize*pnlPct/100*100); trades++; inPosition=false; continue; }
                    if (close < slLine && hm > 910) { totalPnlX100 += (long)(orderSize*pnlPct/100*100); trades++; if(pnlPct>0)wins++; inPosition=false; continue; }
                    if (high >= tpPrice) { double tp=(tpPrice-entryPrice)/entryPrice*100; totalPnlX100+=(long)(orderSize*tp/100*100); trades++; wins++; inPosition=false; continue; }
                    if (close > entryPrice) {
                        int gi = globalStartIdx + di;
                        double atr = (gi >= 11) ? calcATR(candles, gi, 10) : rangePct * entryPrice / 100;
                        double trail = peakHigh - atr * 0.6;
                        if (trail > entryPrice && close <= trail) { totalPnlX100+=(long)(orderSize*pnlPct/100*100); trades++; wins++; inPosition=false; continue; }
                    }
                    if (hm >= sessionEndHHMM) { totalPnlX100+=(long)(orderSize*pnlPct/100*100); trades++; if(pnlPct>0)wins++; inPosition=false; }
                }
            }
        }
        return new int[]{trades, wins, (int)totalPnlX100};
    }

    // ===== 오프닝 스캐너 (Relaxed Range SL: rangeHigh - ATR*0.3) =====
    static int[] backtestOpeningRelaxed(List<double[]> candles, double capital) {
        int trades = 0, wins = 0;
        long totalPnlX100 = 0;
        double orderSize = capital * 0.33;

        Map<String, List<double[]>> byDate = new LinkedHashMap<>();
        Map<String, Integer> dateStartIdx = new LinkedHashMap<>();
        for (int i = 0; i < candles.size(); i++) {
            String date = epochToKstDate(candles.get(i)[0]);
            byDate.computeIfAbsent(date, k -> new ArrayList<>()).add(candles.get(i));
            dateStartIdx.putIfAbsent(date, i);
        }

        for (Map.Entry<String, List<double[]>> entry : byDate.entrySet()) {
            List<double[]> dayCandles = entry.getValue();
            int globalStartIdx = dateStartIdx.get(entry.getKey());

            double rangeHigh = -1, rangeLow = Double.MAX_VALUE;
            int rangeCount = 0;
            double avgVol = 0;
            for (double[] c : dayCandles) {
                int hm = epochToKstHHMM(c[0]);
                if (hm >= 800 && hm <= 859) {
                    rangeHigh = Math.max(rangeHigh, c[2]);
                    rangeLow = Math.min(rangeLow, c[3]);
                    rangeCount++;
                    avgVol += c[5];
                }
            }
            if (rangeCount < 4 || rangeHigh <= rangeLow) continue;
            avgVol /= rangeCount;
            double rangePct = (rangeHigh - rangeLow) / rangeLow * 100;
            if (rangePct < 0.3) continue;

            boolean inPosition = false;
            double entryPrice = 0, peakHigh = 0, tpPrice = 0, relaxedSL = 0;

            for (int di = 0; di < dayCandles.size(); di++) {
                double[] c = dayCandles.get(di);
                int hm = epochToKstHHMM(c[0]);
                double close = c[4], high = c[2];

                if (!inPosition && hm >= 905 && hm <= 1030) {
                    if (close > rangeHigh) {
                        double boPct = (close - rangeHigh) / rangeHigh * 100;
                        if (boPct >= 0.2 && c[5] > avgVol * 1.5) {
                            double body = Math.abs(close - c[1]), wick = high - c[3];
                            if (wick > 0 && body / wick >= 0.45) {
                                entryPrice = close;
                                peakHigh = high;
                                inPosition = true;
                                int gi = globalStartIdx + di;
                                double atr = (gi >= 11) ? calcATR(candles, gi, 10) : rangePct * entryPrice / 100;
                                tpPrice = entryPrice + atr * 1.5;
                                // KEY DIFFERENCE: relaxed range SL
                                relaxedSL = rangeHigh - atr * 0.3;
                            }
                        }
                    }
                }

                if (inPosition) {
                    peakHigh = Math.max(peakHigh, high);
                    double pnlPct = (close - entryPrice) / entryPrice * 100;

                    if (pnlPct <= -2.0) {
                        totalPnlX100 += (long)(orderSize * pnlPct / 100 * 100);
                        trades++; inPosition = false; continue;
                    }
                    // RELAXED: close < rangeHigh - ATR*0.3 (instead of rangeHigh)
                    if (close < relaxedSL && hm > 910) {
                        totalPnlX100 += (long)(orderSize * pnlPct / 100 * 100);
                        trades++; if (pnlPct > 0) wins++;
                        inPosition = false; continue;
                    }
                    if (high >= tpPrice) {
                        double tpPnl = (tpPrice - entryPrice) / entryPrice * 100;
                        totalPnlX100 += (long)(orderSize * tpPnl / 100 * 100);
                        trades++; wins++; inPosition = false; continue;
                    }
                    if (close > entryPrice) {
                        int gi = globalStartIdx + di;
                        double atr = (gi >= 11) ? calcATR(candles, gi, 10) : rangePct * entryPrice / 100;
                        double trailStop = peakHigh - atr * 0.6;
                        if (trailStop > entryPrice && close <= trailStop) {
                            totalPnlX100 += (long)(orderSize * pnlPct / 100 * 100);
                            trades++; wins++; inPosition = false; continue;
                        }
                    }
                    if (hm >= 1200) {
                        totalPnlX100 += (long)(orderSize * pnlPct / 100 * 100);
                        trades++; if (pnlPct > 0) wins++;
                        inPosition = false;
                    }
                }
            }
        }
        return new int[]{trades, wins, (int)totalPnlX100};
    }

    // ===== 올데이 백테스트 (8-Factor 스코어링 + Quick TP + 타임스탑) =====
    static int[] backtestAllday(List<double[]> candles, double capital) {
        int trades = 0, wins = 0;
        long totalPnlX100 = 0;
        double orderSize = capital * 0.20;

        for (int i = 50; i < candles.size(); i++) {
            double[] c = candles.get(i);
            int hm = epochToKstHHMM(c[0]);
            if (hm < 1035 || hm > 2200) continue;

            double close = c[4];

            // === 4 Required Gates ===
            // Gate 1: EMA alignment (close > EMA20)
            double ema20 = 0;
            { double m = 2.0/21; double v = 0;
              for (int j = i-19; j <= i; j++) { if (v==0) v=candles.get(j)[4]; else v=candles.get(j)[4]*m+v*(1-m); }
              ema20 = v; }
            if (close <= ema20) continue;

            // Gate 2: MACD > 0
            double ema12=0, ema26=0;
            { double m12=2.0/13, m26=2.0/27; double v12=0,v26=0;
              for (int j = Math.max(0,i-50); j <= i; j++) { double p=candles.get(j)[4]; if(v12==0){v12=p;v26=p;}else{v12=p*m12+v12*(1-m12);v26=p*m26+v26*(1-m26);} }
              ema12=v12; ema26=v26; }
            if (ema12 <= ema26) continue;

            // Gate 3: RSI 55~80
            double gainSum=0, lossSum=0;
            for (int j=i-13; j<=i; j++) { double d=candles.get(j)[4]-candles.get(j-1)[4]; if(d>0)gainSum+=d;else lossSum-=d; }
            double rsi = lossSum>0 ? 100-100/(1+gainSum/lossSum) : 100;
            if (rsi < 55 || rsi > 80) continue;

            // Gate 4: ADX > 18 (simplified)
            double atr = calcATR(candles, i, 14);
            double adxProxy = atr / close * 100 * 50; // rough proxy
            if (adxProxy < 0.5) continue;

            // === Scoring (simplified 8-factor) ===
            double score = 3.0; // 3 gates passed
            // +1: 20-bar breakout
            double high20 = 0;
            for (int j=i-20; j<i; j++) high20 = Math.max(high20, candles.get(j)[2]);
            if (close > high20) score += 1.5;
            // +1: Volume surge 3x
            double avgVol = 0;
            for (int j=i-20; j<i; j++) avgVol += candles.get(j)[5];
            avgVol /= 20;
            if (c[5] > avgVol * 3.0) score += 1.5;
            // +1: Body ratio 60%+
            double body = Math.abs(close - c[1]), wick = c[2] - c[3];
            if (wick > 0 && body/wick >= 0.60) score += 1.0;
            // +1: Breakout strength 0.5%+
            if (high20 > 0 && (close-high20)/high20*100 >= 0.5) score += 1.0;
            // +1: Bollinger upper breakout
            double sma20=0;
            for (int j=i-19; j<=i; j++) sma20 += candles.get(j)[4];
            sma20 /= 20;
            double stddev = 0;
            for (int j=i-19; j<=i; j++) stddev += Math.pow(candles.get(j)[4]-sma20, 2);
            double bbUpper = sma20 + 2 * Math.sqrt(stddev/20);
            if (close > bbUpper) score += 1.0;

            if (score < 8.0) continue;

            // === Entry ===
            double entryPrice = close;
            boolean exited = false;

            for (int j = i+1; j < Math.min(i+13, candles.size()); j++) {
                double[] ec = candles.get(j);
                double pnlPct = (ec[4] - entryPrice) / entryPrice * 100;

                // Quick TP: high reaches +0.7%
                if (ec[2] >= entryPrice * 1.007) {
                    totalPnlX100 += (long)(orderSize * 0.7 / 100 * 100);
                    trades++; wins++; exited = true; break;
                }
                // SL: -1.5%
                if (pnlPct <= -1.5) {
                    totalPnlX100 += (long)(orderSize * pnlPct / 100 * 100);
                    trades++; exited = true; break;
                }
            }
            // Timestop: 12 candles
            if (!exited) {
                int j = Math.min(i+12, candles.size()-1);
                double pnlPct = (candles.get(j)[4] - entryPrice) / entryPrice * 100;
                if (pnlPct < 0.3) { // timestop only if losing
                    totalPnlX100 += (long)(orderSize * pnlPct / 100 * 100);
                    trades++; if (pnlPct > 0) wins++;
                } else {
                    // Still profitable, let it run (simplified: exit at pnl)
                    totalPnlX100 += (long)(orderSize * pnlPct / 100 * 100);
                    trades++; wins++;
                }
            }
            i += 12;
        }
        return new int[]{trades, wins, (int)totalPnlX100};
    }

    // ===== 업비트 API 캔들 다운로드 =====
    static List<double[]> downloadCandles(String market, int unit, int days) throws Exception {
        Set<Long> seen = new HashSet<>();
        List<double[]> all = new ArrayList<>();
        String to = null;
        int maxCandles = days * 24 * 60 / unit;
        int emptyRounds = 0;

        while (all.size() < maxCandles && emptyRounds < 3) {
            String url = "https://api.upbit.com/v1/candles/minutes/" + unit
                + "?market=" + market + "&count=200";
            if (to != null) url += "&to=" + URLEncoder.encode(to, "UTF-8");

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();

            if (code == 429) {
                Thread.sleep(1000);
                continue;
            }
            if (code != 200) { emptyRounds++; Thread.sleep(500); continue; }

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            String json = sb.toString();
            if (json.equals("[]") || json.length() < 10) break;

            List<double[]> batch = parseCandles(json);
            if (batch.isEmpty()) break;

            int added = 0;
            double oldestEpoch = Double.MAX_VALUE;
            for (double[] c : batch) {
                long key = (long) c[0];
                if (seen.add(key)) {
                    all.add(c);
                    added++;
                }
                if (c[0] < oldestEpoch) oldestEpoch = c[0];
            }

            if (added == 0) { emptyRounds++; break; }

            // to = oldest candle UTC ISO (for next page, go further back)
            Instant oldest = Instant.ofEpochSecond((long) oldestEpoch);
            to = oldest.atZone(ZoneOffset.UTC).toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

            Thread.sleep(120);
            if (batch.size() < 200) break;
        }

        all.sort(Comparator.comparingDouble(a -> a[0]));
        return all;
    }

    static List<double[]> parseCandles(String json) {
        List<double[]> list = new ArrayList<>();
        int idx = 0;
        while ((idx = json.indexOf("\"candle_date_time_utc\"", idx)) > 0) {
            try {
                String utc = extractField(json, "candle_date_time_utc", idx);
                double ts = utcToEpoch(utc);
                double open = extractDouble(json, "opening_price", idx);
                double high = extractDouble(json, "high_price", idx);
                double low = extractDouble(json, "low_price", idx);
                double close = extractDouble(json, "trade_price", idx);
                double vol = extractDouble(json, "candle_acc_trade_volume", idx);
                list.add(new double[]{ts, open, high, low, close, vol});
            } catch (Exception e) { /* skip */ }
            idx++;
        }
        return list;
    }

    static String extractField(String json, String key, int startIdx) {
        int i = json.indexOf("\"" + key + "\"", startIdx);
        if (i < 0) return "";
        i = json.indexOf(":", i) + 1;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '"')) i++;
        int end = json.indexOf("\"", i);
        if (end < 0) end = json.indexOf(",", i);
        if (end < 0) end = json.indexOf("}", i);
        return json.substring(i, end).trim();
    }

    static double extractDouble(String json, String key, int startIdx) {
        int i = json.indexOf("\"" + key + "\"", startIdx);
        if (i < 0) return 0;
        i = json.indexOf(":", i) + 1;
        int end = i;
        while (end < json.length() && "0123456789.-eE".indexOf(json.charAt(end)) >= 0) end++;
        if (end == i) { // skip whitespace
            while (i < json.length() && json.charAt(i) == ' ') i++;
            end = i;
            while (end < json.length() && "0123456789.-eE".indexOf(json.charAt(end)) >= 0) end++;
        }
        return Double.parseDouble(json.substring(i, end).trim());
    }

    // ===== CSV 저장/로드 =====
    // Format: epoch_sec,open,high,low,close,volume
    static void saveCSV(String path, List<double[]> candles) throws Exception {
        PrintWriter pw = new PrintWriter(new FileWriter(path));
        pw.println("epoch_sec,open,high,low,close,volume");
        for (double[] c : candles) {
            pw.printf("%.0f,%.8f,%.8f,%.8f,%.8f,%.8f%n", c[0], c[1], c[2], c[3], c[4], c[5]);
        }
        pw.close();
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
                Double.parseDouble(parts[0]),
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]),
                Double.parseDouble(parts[4]),
                Double.parseDouble(parts[5])
            });
        }
        br.close();
        return list;
    }

    // ===== 유틸 =====
    static double utcToEpoch(String utc) {
        LocalDateTime ldt = LocalDateTime.parse(utc.length() > 19 ? utc.substring(0,19) : utc);
        return ldt.toEpochSecond(ZoneOffset.UTC);
    }

    static String epochToKstDate(double epoch) {
        return Instant.ofEpochSecond((long)epoch).atZone(KST)
            .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    static int epochToKstHHMM(double epoch) {
        ZonedDateTime z = Instant.ofEpochSecond((long)epoch).atZone(KST);
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
