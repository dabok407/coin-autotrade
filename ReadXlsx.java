import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
public class ReadXlsx {
    public static void main(String[] args) throws Exception {
        String path = "C:/Users/MinKook/OneDrive/\uBC14\uD0D5 \uD654\uBA74/\uBC31\uD14C\uC2A4\uD2B8\uACB0\uACFC_\uC0D8\uD50C\uC591\uC2DD.xlsx";
        try (FileInputStream fis = new FileInputStream(path); Workbook wb = new XSSFWorkbook(fis)) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                System.out.println("=== Sheet: " + sheet.getSheetName() + " ===");
                for (Row row : sheet) {
                    StringBuilder sb = new StringBuilder();
                    for (int c = 0; c <= row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        if (c > 0) sb.append("|");
                        if (cell == null) { sb.append(""); continue; }
                        switch (cell.getCellType()) {
                            case STRING: sb.append(cell.getStringCellValue()); break;
                            case NUMERIC: sb.append(cell.getNumericCellValue()); break;
                            case BOOLEAN: sb.append(cell.getBooleanCellValue()); break;
                            case FORMULA: sb.append("=" + cell.getCellFormula()); break;
                            default: sb.append(""); break;
                        }
                    }
                    System.out.println(sb.toString());
                }
            }
        }
    }
}
