import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import java.io.*;
import java.util.*;

public class CreateExcel {

    static final String CSV_PATH = "C:/workspace/upbit-autotrade-java8/upbit-autotrade/data/backtest_results.csv";
    static final String OUT_PATH = "C:/workspace/upbit-autotrade-java8/upbit-autotrade/data/backtest_analysis.xlsx";

    public static void main(String[] args) throws Exception {
        List<String[]> rows = readCsv();
        String[] hdr = rows.get(0);
        List<String[]> data = rows.subList(1, rows.size());

        XSSFWorkbook wb = new XSSFWorkbook();

        // Styles
        XSSFCellStyle hdrStyle = wb.createCellStyle();
        XSSFFont hdrFont = wb.createFont();
        hdrFont.setFontName("Arial");
        hdrFont.setBold(true);
        hdrFont.setFontHeightInPoints((short) 10);
        hdrFont.setColor(IndexedColors.WHITE.getIndex());
        hdrStyle.setFont(hdrFont);
        hdrStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 47, (byte) 84, (byte) 150}, null));
        hdrStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        hdrStyle.setAlignment(HorizontalAlignment.CENTER);

        XSSFCellStyle numStyle = wb.createCellStyle();
        numStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
        XSSFFont normalFont = wb.createFont();
        normalFont.setFontName("Arial");
        normalFont.setFontHeightInPoints((short) 10);
        numStyle.setFont(normalFont);

        XSSFCellStyle pctStyle = wb.createCellStyle();
        pctStyle.setDataFormat(wb.createDataFormat().getFormat("0.00"));
        pctStyle.setFont(normalFont);

        XSSFCellStyle greenStyle = wb.createCellStyle();
        greenStyle.cloneStyleFrom(pctStyle);
        greenStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 198, (byte) 239, (byte) 206}, null));
        greenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        XSSFFont greenFont = wb.createFont();
        greenFont.setFontName("Arial");
        greenFont.setFontHeightInPoints((short) 10);
        greenFont.setColor(new XSSFColor(new byte[]{0, (byte) 97, 0}, null));
        greenStyle.setFont(greenFont);

        XSSFCellStyle redStyle = wb.createCellStyle();
        redStyle.cloneStyleFrom(pctStyle);
        redStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 255, (byte) 199, (byte) 206}, null));
        redStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        XSSFFont redFont = wb.createFont();
        redFont.setFontName("Arial");
        redFont.setFontHeightInPoints((short) 10);
        redFont.setColor(new XSSFColor(new byte[]{(byte) 156, 0, 6}, null));
        redStyle.setFont(redFont);

        XSSFCellStyle normalStyle = wb.createCellStyle();
        normalStyle.setFont(normalFont);

        XSSFCellStyle titleStyle = wb.createCellStyle();
        XSSFFont titleFont = wb.createFont();
        titleFont.setFontName("Arial");
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleFont.setColor(new XSSFColor(new byte[]{(byte) 47, (byte) 84, (byte) 150}, null));
        titleStyle.setFont(titleFont);

        XSSFCellStyle sectionStyle = wb.createCellStyle();
        XSSFFont sectionFont = wb.createFont();
        sectionFont.setFontName("Arial");
        sectionFont.setBold(true);
        sectionFont.setFontHeightInPoints((short) 11);
        sectionFont.setColor(new XSSFColor(new byte[]{(byte) 47, (byte) 84, (byte) 150}, null));
        sectionStyle.setFont(sectionFont);

        XSSFCellStyle labelStyle = wb.createCellStyle();
        XSSFFont labelFont = wb.createFont();
        labelFont.setFontName("Arial");
        labelFont.setBold(true);
        labelFont.setFontHeightInPoints((short) 10);
        labelStyle.setFont(labelFont);

        XSSFCellStyle highlightStyle = wb.createCellStyle();
        highlightStyle.setFont(normalFont);
        highlightStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 255, (byte) 242, (byte) 204}, null));
        highlightStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // ===== Sheet 1: Raw Data =====
        XSSFSheet ws1 = wb.createSheet("Raw Data");
        writeHeaderRow(ws1, hdr, hdrStyle);
        int[] colWidths = {55, 12, 12, 8, 8, 10, 15, 10, 15, 9, 9, 13, 12, 11};
        for (int i = 0; i < colWidths.length; i++) ws1.setColumnWidth(i, colWidths[i] * 256);

        for (int r = 0; r < data.size(); r++) {
            Row row = ws1.createRow(r + 1);
            String[] d = data.get(r);
            for (int c = 0; c < d.length; c++) {
                Cell cell = row.createCell(c);
                String colName = hdr[c];
                if (colName.equals("combo") || colName.equals("coin")) {
                    cell.setCellValue(d[c]);
                    cell.setCellStyle(normalStyle);
                } else {
                    double val = parseDouble(d[c]);
                    cell.setCellValue(val);
                    if (colName.equals("roi_pct")) {
                        cell.setCellStyle(val > 0 ? greenStyle : (val < -50 ? redStyle : pctStyle));
                    } else if (colName.equals("total_pnl_krw") || colName.equals("final_capital")) {
                        cell.setCellStyle(numStyle);
                    } else if (colName.equals("win_rate")) {
                        cell.setCellStyle(pctStyle);
                    } else {
                        cell.setCellStyle(normalStyle);
                    }
                }
            }
        }
        ws1.createFreezePane(0, 1);
        ws1.setAutoFilter(new CellRangeAddress(0, data.size(), 0, hdr.length - 1));

        // ===== Sheet 2: Top Performers =====
        XSSFSheet ws2 = wb.createSheet("Top Performers");
        List<String[]> profitable = new ArrayList<String[]>();
        for (String[] d : data) {
            double roi = parseDouble(d[7]); // roi_pct
            if (roi > 0) profitable.add(d);
        }
        Collections.sort(profitable, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                return Double.compare(parseDouble(b[7]), parseDouble(a[7]));
            }
        });

        String[] topHdr = new String[hdr.length + 1];
        topHdr[0] = "Rank";
        System.arraycopy(hdr, 0, topHdr, 1, hdr.length);
        writeHeaderRow(ws2, topHdr, hdrStyle);
        ws2.setColumnWidth(0, 6 * 256);
        for (int i = 0; i < colWidths.length; i++) ws2.setColumnWidth(i + 1, colWidths[i] * 256);

        for (int r = 0; r < profitable.size(); r++) {
            Row row = ws2.createRow(r + 1);
            row.createCell(0).setCellValue(r + 1);
            row.getCell(0).setCellStyle(labelStyle);
            String[] d = profitable.get(r);
            for (int c = 0; c < d.length; c++) {
                Cell cell = row.createCell(c + 1);
                String colName = hdr[c];
                if (colName.equals("combo") || colName.equals("coin")) {
                    cell.setCellValue(d[c]);
                    cell.setCellStyle(normalStyle);
                } else {
                    double val = parseDouble(d[c]);
                    cell.setCellValue(val);
                    if (colName.equals("roi_pct")) {
                        cell.setCellStyle(greenStyle);
                    } else if (colName.equals("total_pnl_krw") || colName.equals("final_capital")) {
                        cell.setCellStyle(numStyle);
                    } else {
                        cell.setCellStyle(normalStyle);
                    }
                }
            }
        }
        ws2.createFreezePane(0, 1);

        // ===== Sheet 3: By Strategy =====
        XSSFSheet ws3 = wb.createSheet("By Strategy");
        String[] stratHdr = {"Strategy Combo", "Avg ROI %", "Best ROI %", "Worst ROI %", "Avg Win Rate %", "Profitable", "Total", "Profit Rate %"};
        writeHeaderRow(ws3, stratHdr, hdrStyle);
        ws3.setColumnWidth(0, 55 * 256);
        for (int i = 1; i < 8; i++) ws3.setColumnWidth(i, 14 * 256);

        Map<String, List<String[]>> byCombo = groupBy(data, 0);
        List<Map.Entry<String, List<String[]>>> comboEntries = new ArrayList<Map.Entry<String, List<String[]>>>(byCombo.entrySet());
        Collections.sort(comboEntries, new Comparator<Map.Entry<String, List<String[]>>>() {
            public int compare(Map.Entry<String, List<String[]>> a, Map.Entry<String, List<String[]>> b) {
                return Double.compare(avgCol(b.getValue(), 7), avgCol(a.getValue(), 7));
            }
        });
        int r3 = 1;
        for (Map.Entry<String, List<String[]>> e : comboEntries) {
            Row row = ws3.createRow(r3++);
            List<String[]> grp = e.getValue();
            int profCount = countPositive(grp, 7);
            row.createCell(0).setCellValue(e.getKey()); row.getCell(0).setCellStyle(normalStyle);
            setCellNum(row, 1, avgCol(grp, 7), avgCol(grp, 7) > 0 ? greenStyle : redStyle);
            setCellNum(row, 2, maxCol(grp, 7), pctStyle);
            setCellNum(row, 3, minCol(grp, 7), pctStyle);
            setCellNum(row, 4, avgCol(grp, 5), pctStyle);
            setCellNum(row, 5, profCount, normalStyle);
            setCellNum(row, 6, grp.size(), normalStyle);
            setCellNum(row, 7, profCount * 100.0 / grp.size(), pctStyle);
        }

        // ===== Sheet 4: By Coin =====
        XSSFSheet ws4 = wb.createSheet("By Coin");
        String[] coinHdr = {"Coin", "Avg ROI %", "Best ROI %", "Best Combo", "Best Interval", "Profitable", "Total"};
        writeHeaderRow(ws4, coinHdr, hdrStyle);
        int[] coinWidths = {12, 12, 12, 55, 14, 12, 10};
        for (int i = 0; i < coinWidths.length; i++) ws4.setColumnWidth(i, coinWidths[i] * 256);

        Map<String, List<String[]>> byCoin = groupBy(data, 1);
        List<Map.Entry<String, List<String[]>>> coinEntries = new ArrayList<Map.Entry<String, List<String[]>>>(byCoin.entrySet());
        Collections.sort(coinEntries, new Comparator<Map.Entry<String, List<String[]>>>() {
            public int compare(Map.Entry<String, List<String[]>> a, Map.Entry<String, List<String[]>> b) {
                return Double.compare(avgCol(b.getValue(), 7), avgCol(a.getValue(), 7));
            }
        });
        int r4 = 1;
        for (Map.Entry<String, List<String[]>> e : coinEntries) {
            Row row = ws4.createRow(r4++);
            List<String[]> grp = e.getValue();
            String[] bestRow = findMax(grp, 7);
            row.createCell(0).setCellValue(e.getKey()); row.getCell(0).setCellStyle(normalStyle);
            setCellNum(row, 1, avgCol(grp, 7), pctStyle);
            setCellNum(row, 2, maxCol(grp, 7), pctStyle);
            row.createCell(3).setCellValue(bestRow[0]); row.getCell(3).setCellStyle(normalStyle);
            row.createCell(4).setCellValue(bestRow[2] + "min"); row.getCell(4).setCellStyle(normalStyle);
            setCellNum(row, 5, countPositive(grp, 7), normalStyle);
            setCellNum(row, 6, grp.size(), normalStyle);
        }

        // ===== Sheet 5: By Interval =====
        XSSFSheet ws5 = wb.createSheet("By Interval");
        String[] intHdr = {"Interval (min)", "Avg ROI %", "Avg Win Rate %", "Profitable", "Total", "Profit Rate %", "Avg Trades"};
        writeHeaderRow(ws5, intHdr, hdrStyle);
        for (int i = 0; i < 7; i++) ws5.setColumnWidth(i, 16 * 256);

        Map<String, List<String[]>> byInterval = groupBy(data, 2);
        int[] intervals = {15, 30, 60, 240};
        int r5 = 1;
        for (int intv : intervals) {
            List<String[]> grp = byInterval.get(String.valueOf(intv));
            if (grp == null) continue;
            Row row = ws5.createRow(r5++);
            int profCount = countPositive(grp, 7);
            setCellNum(row, 0, intv, normalStyle);
            setCellNum(row, 1, avgCol(grp, 7), avgCol(grp, 7) > 0 ? greenStyle : redStyle);
            setCellNum(row, 2, avgCol(grp, 5), pctStyle);
            setCellNum(row, 3, profCount, normalStyle);
            setCellNum(row, 4, grp.size(), normalStyle);
            setCellNum(row, 5, profCount * 100.0 / grp.size(), pctStyle);
            setCellNum(row, 6, avgCol(grp, 3), pctStyle);
        }

        // ===== Sheet 6: Summary =====
        XSSFSheet ws6 = wb.createSheet("Summary");
        ws6.setColumnWidth(0, 22 * 256);
        ws6.setColumnWidth(1, 55 * 256);
        for (int i = 2; i < 8; i++) ws6.setColumnWidth(i, 14 * 256);

        ws6.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));
        Row r0 = ws6.createRow(0);
        r0.createCell(0).setCellValue("Backtest Analysis Summary");
        r0.getCell(0).setCellStyle(titleStyle);

        Row r1 = ws6.createRow(1);
        r1.createCell(0).setCellValue("Period: 90 Days | Capital: 1,000,000 KRW | TP: 5% | SL: 3% | StrategyLock: ON");
        r1.getCell(0).setCellStyle(highlightStyle);

        // Best overall
        String[] best = findMax(data, 7);
        Row r3_ = ws6.createRow(3);
        r3_.createCell(0).setCellValue("BEST OVERALL RESULT");
        r3_.getCell(0).setCellStyle(sectionStyle);

        String[][] bestInfo = {
            {"Strategy", best[0]},
            {"Coin", best[1]},
            {"Interval", best[2] + "min"},
            {"ROI %", String.format("%.2f%%", parseDouble(best[7]))},
            {"Final Capital", String.format("%,.0f KRW", parseDouble(best[8]))},
            {"Win Rate %", String.format("%.1f%%", parseDouble(best[5]))},
            {"Trades", best[3]},
            {"TP Sells", best[9]},
            {"SL Sells", best[10]}
        };
        for (int i = 0; i < bestInfo.length; i++) {
            Row row = ws6.createRow(4 + i);
            row.createCell(0).setCellValue(bestInfo[i][0]);
            row.getCell(0).setCellStyle(labelStyle);
            row.createCell(1).setCellValue(bestInfo[i][1]);
            row.getCell(1).setCellStyle(normalStyle);
        }

        // Profitable results table
        int r6 = 15;
        Row rSec = ws6.createRow(r6);
        rSec.createCell(0).setCellValue("ALL PROFITABLE RESULTS (ROI > 0%)");
        rSec.getCell(0).setCellStyle(sectionStyle);
        r6++;

        String[] profTableHdr = {"Rank", "Strategy", "Coin", "Interval", "ROI %", "Win Rate %", "Trades", "Final Capital"};
        Row hdrRow = ws6.createRow(r6++);
        for (int c = 0; c < profTableHdr.length; c++) {
            Cell cell = hdrRow.createCell(c);
            cell.setCellValue(profTableHdr[c]);
            cell.setCellStyle(hdrStyle);
        }

        for (int i = 0; i < profitable.size(); i++) {
            Row row = ws6.createRow(r6++);
            String[] d = profitable.get(i);
            row.createCell(0).setCellValue(i + 1); row.getCell(0).setCellStyle(normalStyle);
            row.createCell(1).setCellValue(d[0]); row.getCell(1).setCellStyle(normalStyle);
            row.createCell(2).setCellValue(d[1]); row.getCell(2).setCellStyle(normalStyle);
            row.createCell(3).setCellValue(d[2] + "min"); row.getCell(3).setCellStyle(normalStyle);
            setCellNum(row, 4, parseDouble(d[7]), greenStyle);
            setCellNum(row, 5, parseDouble(d[5]), normalStyle);
            setCellNum(row, 6, parseDouble(d[3]), normalStyle);
            setCellNum(row, 7, parseDouble(d[8]), numStyle);
        }

        // Key Insights
        r6 += 2;
        Row insightHeader = ws6.createRow(r6++);
        insightHeader.createCell(0).setCellValue("KEY INSIGHTS");
        insightHeader.getCell(0).setCellStyle(sectionStyle);

        String xrpRoi = "N/A";
        for (String[] d : data) {
            if (d[0].equals("THREE_MARKET_PATTERN") && d[1].equals("KRW-XRP") && d[2].equals("240")) {
                xrpRoi = String.format("%.2f", parseDouble(d[7]));
            }
        }

        String[] insights = {
            "1. 240-minute interval is the ONLY consistently profitable interval across all strategies.",
            "2. Shorter intervals (15, 30, 60 min) are consistently unprofitable - too much noise and fees.",
            "3. Best single strategy: THREE_MARKET_PATTERN on KRW-XRP 240min (+" + xrpRoi + "% ROI)",
            "4. KRW-XRP is the most profitable coin. KRW-SOL also shows positive results at 240min.",
            "5. Pattern-based exits dominate. TP/SL rarely trigger with current settings (TP5%/SL3%).",
            "6. Multi-strategy combos do not significantly outperform single strategies - simpler is better.",
            "7. Total: " + profitable.size() + " profitable out of " + data.size() + " tests (" + String.format("%.1f", profitable.size() * 100.0 / data.size()) + "%)"
        };
        for (String insight : insights) {
            Row row = ws6.createRow(r6++);
            row.createCell(0).setCellValue(insight);
            row.getCell(0).setCellStyle(normalStyle);
        }

        // Save
        try (FileOutputStream fos = new FileOutputStream(OUT_PATH)) {
            wb.write(fos);
        }
        wb.close();

        System.out.println("Saved: " + OUT_PATH);
        System.out.println("Profitable: " + profitable.size() + " / " + data.size());
        System.out.println("Best: " + best[0] + " | " + best[1] + " | " + best[2] + "min | ROI: " + best[7] + "%");
    }

    static List<String[]> readCsv() throws Exception {
        List<String[]> rows = new ArrayList<String[]>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(CSV_PATH), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                rows.add(line.split(","));
            }
        }
        return rows;
    }

    static void writeHeaderRow(Sheet sheet, String[] headers, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int c = 0; c < headers.length; c++) {
            Cell cell = row.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(style);
        }
    }

    static double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    static Map<String, List<String[]>> groupBy(List<String[]> data, int colIdx) {
        Map<String, List<String[]>> map = new LinkedHashMap<String, List<String[]>>();
        for (String[] d : data) {
            String key = d[colIdx];
            if (!map.containsKey(key)) map.put(key, new ArrayList<String[]>());
            map.get(key).add(d);
        }
        return map;
    }

    static double avgCol(List<String[]> data, int col) {
        double sum = 0;
        for (String[] d : data) sum += parseDouble(d[col]);
        return sum / data.size();
    }

    static double maxCol(List<String[]> data, int col) {
        double max = Double.MIN_VALUE;
        for (String[] d : data) { double v = parseDouble(d[col]); if (v > max) max = v; }
        return max;
    }

    static double minCol(List<String[]> data, int col) {
        double min = Double.MAX_VALUE;
        for (String[] d : data) { double v = parseDouble(d[col]); if (v < min) min = v; }
        return min;
    }

    static String[] findMax(List<String[]> data, int col) {
        String[] best = data.get(0);
        double max = parseDouble(best[col]);
        for (String[] d : data) { double v = parseDouble(d[col]); if (v > max) { max = v; best = d; } }
        return best;
    }

    static int countPositive(List<String[]> data, int col) {
        int count = 0;
        for (String[] d : data) if (parseDouble(d[col]) > 0) count++;
        return count;
    }

    static void setCellNum(Row row, int col, double val, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(val);
        cell.setCellStyle(style);
    }
}
