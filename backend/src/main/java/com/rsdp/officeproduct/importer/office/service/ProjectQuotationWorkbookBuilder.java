package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationRecordDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationFeeDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationRecordLineDto;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

@Component
public class ProjectQuotationWorkbookBuilder {

    private static final String[] HEADERS = {"商品", "楼层 / 空间", "规格", "数量", "出厂价", "成交单价", "小计"};
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public byte[] build(ProjectQuotationRecordDto quotation) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("项目报价");
            int[] widths = {34, 24, 22, 10, 16, 16, 18};
            for (int column = 0; column < widths.length; column++) {
                sheet.setColumnWidth(column, widths[column] * 256);
            }

            CellStyle titleStyle = style(workbook, false, false);
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            titleStyle.setFont(titleFont);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);
            CellStyle metaStyle = style(workbook, false, false);
            CellStyle headerStyle = style(workbook, true, true);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            CellStyle textStyle = style(workbook, true, false);
            textStyle.setWrapText(true);
            CellStyle amountStyle = style(workbook, true, false);
            amountStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            CellStyle totalStyle = style(workbook, true, true);
            totalStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));

            Row title = row(sheet, 0, 26);
            Cell titleCell = title.createCell(0);
            titleCell.setCellValue(quotation.getProjectName() + " · 项目报价单");
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));

            Row project = row(sheet, 1, 20);
            cell(project, 0, "项目", metaStyle);
            cell(project, 1, quotation.getProjectName(), metaStyle);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 1, 6));
            Row pricing = row(sheet, 2, 20);
            cell(pricing, 0, "报价口径", metaStyle);
            cell(pricing, 1, pricingLabel(quotation), metaStyle);
            cell(pricing, 3, "导出时间", metaStyle);
            cell(pricing, 4, java.time.LocalDateTime.now().format(TIME_FORMAT), metaStyle);
            sheet.addMergedRegion(new CellRangeAddress(2, 2, 4, 6));

            Row header = row(sheet, 4, 22);
            for (int column = 0; column < HEADERS.length; column++) {
                cell(header, column, HEADERS[column], headerStyle);
            }

            int rowIndex = 5;
            for (QuotationRecordLineDto line : quotation.getLines()) {
                Row row = row(sheet, rowIndex++, line.getIssue() == null ? 20 : 34);
                String productText = line.getProductName() + (line.getIssue() == null ? "" : "\n" + line.getIssue());
                cell(row, 0, productText, textStyle);
                cell(row, 1, line.getFloorName() + " / " + line.getZoneName(), textStyle);
                cell(row, 2, line.getVariant() == null ? "—" : line.getVariant(), textStyle);
                cell(row, 3, line.getQty(), textStyle);
                amount(row, 4, line.getFactoryPrice(), amountStyle);
                amount(row, 5, line.getUnitPrice(), amountStyle);
                amount(row, 6, line.getSubtotal(), amountStyle);
            }
            for (QuotationFeeDto fee : quotation.getFees()) {
                Row row = row(sheet, rowIndex++, 20);
                cell(row, 0, fee.getName(), textStyle);
                amount(row, 6, fee.getAmount(), amountStyle);
            }

            rowIndex = totalRow(sheet, rowIndex, "商品小计", quotation.getGoodsTotal(), textStyle, totalStyle);
            rowIndex = totalRow(sheet, rowIndex, "费用合计", feeTotal(quotation), textStyle, totalStyle);
            totalRow(sheet, rowIndex, "商品报价合计", quotation.getTotalAmount(), textStyle, totalStyle);
            sheet.createFreezePane(0, 5);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception failure) {
            throw new BusinessException("报价 Excel 生成失败", failure);
        }
    }

    private int totalRow(org.apache.poi.xssf.usermodel.XSSFSheet sheet, int rowIndex, String label,
                         BigDecimal amount, CellStyle textStyle, CellStyle amountStyle) {
        Row row = row(sheet, rowIndex, 22);
        cell(row, 0, label, textStyle);
        amount(row, 6, amount, amountStyle);
        return rowIndex + 1;
    }

    private BigDecimal feeTotal(ProjectQuotationRecordDto quotation) {
        return quotation.getFees().stream().map(QuotationFeeDto::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String pricingLabel(ProjectQuotationRecordDto quotation) {
        if ("MARGIN".equals(quotation.getPricingMode())) {
            return "出厂价 ÷ (1 − 毛利率)，毛利率 " + quotation.getPricingValue().multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
        }
        return "出厂价 × 系数 " + quotation.getPricingValue().stripTrailingZeros().toPlainString();
    }

    private Row row(org.apache.poi.xssf.usermodel.XSSFSheet sheet, int index, int heightPoints) {
        Row row = sheet.createRow(index);
        row.setHeightInPoints(heightPoints);
        return row;
    }

    private void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private void cell(Row row, int column, Integer value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value != null) {
            cell.setCellValue(value);
        }
        cell.setCellStyle(style);
    }

    private void amount(Row row, int column, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private CellStyle style(XSSFWorkbook workbook, boolean bordered, boolean emphasized) {
        CellStyle style = workbook.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        if (bordered) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
        if (emphasized) {
            Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            style.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return style;
    }
}
