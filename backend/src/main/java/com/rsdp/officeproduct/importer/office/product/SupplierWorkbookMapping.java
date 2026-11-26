package com.rsdp.officeproduct.importer.office.product;

import com.rsdp.officeproduct.importer.common.BusinessException;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

final class SupplierWorkbookMapping {
    static final int MAX_COLUMNS = 128;
    static final int MAX_LABEL_LENGTH = 120;

    enum Field { MODEL, NAME, PICTURE, SIZE, WIDTH_MM, DEPTH_MM, HEIGHT_MM, COLOR, MATERIAL, CATEGORY, DESCRIPTION }

    record Column(int index, String label) {}
    record Header(int row, List<Column> columns) {}
    record Sheet(int index, String name, List<Header> headers) {}
    record Selection(int sheetIndex, int headerRow, Map<Field, Integer> columns) {
        int column(Field field) { return columns.getOrDefault(field, -1); }

        void validate(XSSFWorkbook workbook) {
            if (sheetIndex < 0 || sheetIndex >= workbook.getNumberOfSheets() || headerRow < 1
                    || columns == null || !columns.containsKey(Field.MODEL) || !columns.containsKey(Field.NAME)) {
                throw new BusinessException("请选择工作表、表头行、型号列和名称列");
            }
            var header = workbook.getSheetAt(sheetIndex).getRow(headerRow - 1);
            if (header == null || columns.values().stream().anyMatch(column -> column == null
                    || column < 0 || column >= header.getLastCellNum())) {
                throw new BusinessException("所选表头或列不在工作表中");
            }
            if (new HashSet<>(columns.values()).size() != columns.size()) {
                throw new BusinessException("同一列只能对应一个商品字段");
            }
        }
    }

    private SupplierWorkbookMapping() {}

    static boolean validColumn(int index) {
        return index >= 0 && index < SpreadsheetVersion.EXCEL2007.getMaxColumns();
    }

    static List<Sheet> inspect(XSSFWorkbook workbook, DataFormatter formatter) {
        var sheets = new ArrayList<Sheet>();
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            if (workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)) continue;
            var sheet = workbook.getSheetAt(index);
            var headers = new ArrayList<Header>();
            for (var row : sheet) {
                if (headers.size() == 12) break;
                var columns = new ArrayList<Column>();
                for (int column = Math.max(0, row.getFirstCellNum()); column < row.getLastCellNum()
                        && columns.size() < MAX_COLUMNS; column++) {
                    String label = formatter.formatCellValue(row.getCell(column)).strip();
                    columns.add(new Column(column, label.substring(0, Math.min(label.length(), MAX_LABEL_LENGTH))));
                }
                if (columns.stream().filter(column -> !column.label().isBlank()).count() >= 2) {
                    headers.add(new Header(row.getRowNum() + 1, columns));
                }
            }
            if (!headers.isEmpty()) sheets.add(new Sheet(index, sheet.getSheetName(), headers));
            if (sheets.size() == 20) break;
        }
        return sheets;
    }
}
