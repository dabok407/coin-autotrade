import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.*;
import java.util.*;

public class ExcelExporter {

    public static void main(String[] args) throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();

        // Styles
        CellStyle headerStyle = createHeaderStyle(wb);
        CellStyle greenStyle = createFillStyle(wb, new byte[]{(byte)198, (byte)239, (byte)206});
        CellStyle redStyle = createFillStyle(wb, new byte[]{(byte)255, (byte)199, (byte)206});
        CellStyle titleStyle = createTitleStyle(wb);
        CellStyle pctStyle = wb.createCellStyle();
        pctStyle.setDataFormat(wb.createDataFormat().getFormat("0.00%"));
        CellStyle numStyle = wb.createCellStyle();
        numStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        CellStyle boldStyle = wb.createCellStyle();
        Font boldFont = wb.createFont();
        boldFont.setBold(true);
        boldStyle.setFont(boldFont);

        // Sheet 1: All Optimization Results
        System.out.println("Creating Sheet 1: All Results...");
        XSSFSheet s1 = wb.createSheet("All Results");
        List<String[]> optData = readCsv("data/optimize_results.csv");
        writeSheet(s1, optData, headerStyle);
        // Conditional formatting
        for (int i = 1; i < optData.size(); i++) {
            Row row = s1.getRow(i);
            if (row == null) continue;
            Cell fcCell = row.getCell(11);
            if (fcCell != null && fcCell.getCellType() == CellType.NUMERIC) {
                double fc = fcCell.getNumericCellValue();
                CellStyle style = fc > 1000000 ? greenStyle : redStyle;
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    if (cell != null) {
                        CellStyle merged = wb.createCellStyle();
                        merged.cloneStyleFrom(cell.getCellStyle());
                        merged.setFillForegroundColor(fc > 1000000
                                ? new XSSFColor(new byte[]{(byte)198, (byte)239, (byte)206}, null)
                                : new XSSFColor(new byte[]{(byte)255, (byte)199, (byte)206}, null));
                        merged.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                        cell.setCellStyle(merged);
                    }
                }
            }
        }
        autoSize(s1, 15);

        // Sheet 2: Top Performers (finalCapital > 1050000)
        System.out.println("Creating Sheet 2: Top Performers...");
        XSSFSheet s2 = wb.createSheet("Top Performers");
        List<String[]> topData = new ArrayList<String[]>();
        topData.add(optData.get(0)); // header
        List<String[]> dataRows = new ArrayList<String[]>();
        for (int i = 1; i < optData.size(); i++) {
            try {
                double fc = Double.parseDouble(optData.get(i)[11]);
                if (fc > 1050000) dataRows.add(optData.get(i));
            } catch (Exception ignore) {}
        }
        Collections.sort(dataRows, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                try {
                    return Double.compare(Double.parseDouble(b[11]), Double.parseDouble(a[11]));
                } catch (Exception e) { return 0; }
            }
        });
        topData.addAll(dataRows);
        writeSheet(s2, topData, headerStyle);
        for (int i = 1; i < topData.size(); i++) {
            Row row = s2.getRow(i);
            if (row != null) {
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    if (cell != null) {
                        CellStyle merged = wb.createCellStyle();
                        merged.cloneStyleFrom(cell.getCellStyle());
                        merged.setFillForegroundColor(new XSSFColor(new byte[]{(byte)198, (byte)239, (byte)206}, null));
                        merged.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                        cell.setCellStyle(merged);
                    }
                }
            }
        }
        autoSize(s2, 15);

        // Sheet 3: Best Per Coin
        System.out.println("Creating Sheet 3: Best Per Coin...");
        XSSFSheet s3 = wb.createSheet("Best Per Coin");
        String[] coins = {"KRW-XRP", "KRW-SOL", "KRW-BTC", "KRW-ADA"};
        Row h3 = s3.createRow(0);
        String[] headers3 = {"Coin", "Best Strategy", "TP%", "SL%", "Min Confidence", "Trades", "Win Rate%", "Final Capital", "Profit"};
        for (int i = 0; i < headers3.length; i++) {
            Cell c = h3.createCell(i);
            c.setCellValue(headers3[i]);
            c.setCellStyle(headerStyle);
        }
        int row3 = 1;
        for (String coin : coins) {
            String[] best = null;
            double bestFc = 0;
            for (int i = 1; i < optData.size(); i++) {
                if (optData.get(i)[1].equals(coin)) {
                    try {
                        double fc = Double.parseDouble(optData.get(i)[11]);
                        if (fc > bestFc) { bestFc = fc; best = optData.get(i); }
                    } catch (Exception ignore) {}
                }
            }
            if (best != null) {
                Row r = s3.createRow(row3++);
                r.createCell(0).setCellValue(coin);
                r.createCell(1).setCellValue(best[0]);
                r.createCell(2).setCellValue(Double.parseDouble(best[3]));
                r.createCell(3).setCellValue(Double.parseDouble(best[4]));
                r.createCell(4).setCellValue(Double.parseDouble(best[5]));
                r.createCell(5).setCellValue(Integer.parseInt(best[6]));
                r.createCell(6).setCellValue(Double.parseDouble(best[8]));
                r.createCell(7).setCellValue(bestFc);
                r.getCell(7).setCellStyle(numStyle);
                r.createCell(8).setCellValue(bestFc - 1000000);
                r.getCell(8).setCellStyle(numStyle);
            }
        }
        autoSize(s3, 9);

        // Sheet 4: Optimal Settings
        System.out.println("Creating Sheet 4: Optimal Settings...");
        XSSFSheet s4 = wb.createSheet("Optimal Settings");
        Row titleRow = s4.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Recommended Configuration for Maximum Profitability");
        titleCell.setCellStyle(titleStyle);
        s4.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));

        Row h4 = s4.createRow(2);
        String[] h4vals = {"Setting", "Value", "Explanation"};
        for (int i = 0; i < h4vals.length; i++) {
            Cell c = h4.createCell(i);
            c.setCellValue(h4vals[i]);
            c.setCellStyle(headerStyle);
        }

        String[][] settings = {
            {"Candle Interval", "240min (4 hours)", "All profitable results are on 240min timeframe"},
            {"Primary Strategy", "THREE_MARKET_PATTERN", "Best single strategy - highest ROI on XRP (+17.4%)"},
            {"Secondary Strategy", "TRIANGLE_CONVERGENCE", "Best combo partner - improves win rate to 75%"},
            {"Best Coin", "KRW-XRP", "Highest ROI: +17.5% (1M -> 1,175,175 KRW in 90 days)"},
            {"Second Best Coin", "KRW-SOL", "Consistent profitability: +7.3% ROI"},
            {"Take Profit (TP)", "3~7%", "Lower TP captures more frequent profits on 4h chart"},
            {"Stop Loss (SL)", "2%", "Tight SL at 2% minimizes drawdown (best across all tests)"},
            {"Min Confidence", "1.0~5.0", "Strategies are already selective on 240min - low filter OK"},
            {"Max Add Buys", "2", "Default value works well for averaging down"},
            {"Strategy Lock", "true", "Prevents unintended strategy mixing on positions"},
            {"Order Sizing", "90% PCT", "Use 90% of available capital per trade"},
            {"Best Combo", "3MKT+TRIANGLE on XRP 240m", "75% win rate, +17.5% ROI, 24 trades in 90 days"},
        };

        for (int i = 0; i < settings.length; i++) {
            Row r = s4.createRow(3 + i);
            r.createCell(0).setCellValue(settings[i][0]);
            Cell valCell = r.createCell(1);
            valCell.setCellValue(settings[i][1]);
            valCell.setCellStyle(boldStyle);
            r.createCell(2).setCellValue(settings[i][2]);
        }
        s4.setColumnWidth(0, 5000);
        s4.setColumnWidth(1, 10000);
        s4.setColumnWidth(2, 18000);

        // Key findings section
        int startRow = 3 + settings.length + 2;
        Row findingsTitle = s4.createRow(startRow);
        Cell ft = findingsTitle.createCell(0);
        ft.setCellValue("Key Findings from 740 Backtests (90-day period)");
        ft.setCellStyle(titleStyle);
        s4.addMergedRegion(new CellRangeAddress(startRow, startRow, 0, 2));

        String[] findings = {
            "1. 240min (4-hour) timeframe is the ONLY profitable interval",
            "2. 15/30/60min timeframes all produce losses due to overtrading",
            "3. KRW-XRP consistently outperforms all other coins",
            "4. THREE_MARKET_PATTERN is the strongest single strategy",
            "5. Adding TRIANGLE_CONVERGENCE improves win rate (66% -> 75%)",
            "6. SL=2% outperforms SL=3,5,7% across all strategies",
            "7. TP value has minimal impact (3-15% all similar due to ATR-based exits)",
            "8. Confidence filter has minimal impact on 240min (strategies are selective)",
            "9. 327 out of 600 optimization tests were profitable (54.5%)",
            "10. Best result: +17.5% ROI in 90 days with only 24 trades",
        };
        for (int i = 0; i < findings.length; i++) {
            Row r = s4.createRow(startRow + 1 + i);
            r.createCell(0).setCellValue(findings[i]);
            s4.addMergedRegion(new CellRangeAddress(startRow + 1 + i, startRow + 1 + i, 0, 2));
        }

        // Sheet 5: Initial 140 Tests
        System.out.println("Creating Sheet 5: Initial 140 Tests...");
        XSSFSheet s5 = wb.createSheet("Initial 140 Tests");
        List<String[]> initData = readCsv("data/backtest_results.csv");
        writeSheet(s5, initData, headerStyle);
        // Color profitable rows
        for (int i = 1; i < initData.size(); i++) {
            Row row = s5.getRow(i);
            if (row == null) continue;
            try {
                double fc = Double.parseDouble(initData.get(i)[8]);
                if (fc > 1000000) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        if (cell != null) {
                            CellStyle merged = wb.createCellStyle();
                            merged.cloneStyleFrom(cell.getCellStyle());
                            merged.setFillForegroundColor(new XSSFColor(new byte[]{(byte)198, (byte)239, (byte)206}, null));
                            merged.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                            cell.setCellStyle(merged);
                        }
                    }
                }
            } catch (Exception ignore) {}
        }
        autoSize(s5, 14);

        // Save
        String outPath = "data/optimization_analysis.xlsx";
        FileOutputStream fos = new FileOutputStream(outPath);
        wb.write(fos);
        fos.close();
        wb.close();
        System.out.println("Done! -> " + new File(outPath).getAbsolutePath());
    }

    static List<String[]> readCsv(String path) throws Exception {
        List<String[]> rows = new ArrayList<String[]>();
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(path), "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            rows.add(line.split(",", -1));
        }
        br.close();
        return rows;
    }

    static void writeSheet(Sheet sheet, List<String[]> data, CellStyle headerStyle) {
        for (int i = 0; i < data.size(); i++) {
            Row row = sheet.createRow(i);
            String[] cols = data.get(i);
            for (int j = 0; j < cols.length; j++) {
                Cell cell = row.createCell(j);
                if (i == 0) {
                    cell.setCellValue(cols[j]);
                    cell.setCellStyle(headerStyle);
                } else {
                    try {
                        double d = Double.parseDouble(cols[j]);
                        cell.setCellValue(d);
                    } catch (Exception e) {
                        cell.setCellValue(cols[j]);
                    }
                }
            }
        }
    }

    static void autoSize(Sheet sheet, int cols) {
        for (int i = 0; i < cols; i++) {
            sheet.autoSizeColumn(i);
            int w = sheet.getColumnWidth(i);
            if (w < 3000) sheet.setColumnWidth(i, 3000);
            if (w > 12000) sheet.setColumnWidth(i, 12000);
        }
    }

    static CellStyle createHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(new XSSFColor(new byte[]{(byte)51, (byte)51, (byte)51}, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        return s;
    }

    static CellStyle createFillStyle(Workbook wb, byte[] rgb) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(new XSSFColor(rgb, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    static CellStyle createTitleStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 14);
        s.setFont(f);
        return s;
    }
}
