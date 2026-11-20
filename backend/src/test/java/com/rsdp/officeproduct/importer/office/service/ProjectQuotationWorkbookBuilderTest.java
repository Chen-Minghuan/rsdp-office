package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationRecordDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationFeeDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationRecordLineDto;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectQuotationWorkbookBuilderTest {

    @Test
    void writesProductIssuesFeesAndNumericTotals() throws Exception {
        ProjectQuotationRecordDto quotation = new ProjectQuotationRecordDto();
        quotation.setProjectName("测试项目");
        quotation.setPricingMode("COEFFICIENT");
        quotation.setPricingValue(new BigDecimal("1.25"));
        quotation.setGoodsTotal(new BigDecimal("500"));
        quotation.setTotalAmount(new BigDecimal("650"));
        quotation.getLines().add(line("办公桌", null, "250", "500"));
        quotation.getLines().add(line("缺价商品", "缺出厂价，无法试算", null, null));
        quotation.setFees(List.of(fee("额外服务费", "100"), fee("加急费", "50")));

        byte[] bytes = new ProjectQuotationWorkbookBuilder().build(quotation);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("项目报价");
            assertEquals("商品", sheet.getRow(4).getCell(0).getStringCellValue());
            assertEquals(2, sheet.getRow(5).getCell(3).getNumericCellValue());
            assertEquals(500, sheet.getRow(5).getCell(6).getNumericCellValue());
            assertEquals(CellType.NUMERIC, sheet.getRow(5).getCell(6).getCellType());
            assertTrue(sheet.getRow(6).getCell(0).getStringCellValue().contains("缺出厂价"));
            assertEquals("额外服务费", sheet.getRow(7).getCell(0).getStringCellValue());
            assertEquals(100, sheet.getRow(7).getCell(6).getNumericCellValue());
            assertEquals("加急费", sheet.getRow(8).getCell(0).getStringCellValue());
            assertEquals(650, sheet.getRow(11).getCell(6).getNumericCellValue());
            assertEquals(CellType.NUMERIC, sheet.getRow(11).getCell(6).getCellType());
        }
    }

    private QuotationRecordLineDto line(String name, String issue, String unitPrice, String subtotal) {
        QuotationRecordLineDto line = new QuotationRecordLineDto();
        line.setProductName(name);
        line.setFloorName("1F");
        line.setZoneName("开放办公区");
        line.setVariant("标准款");
        line.setQty(2);
        line.setIssue(issue);
        line.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        line.setSubtotal(subtotal == null ? null : new BigDecimal(subtotal));
        return line;
    }

    private QuotationFeeDto fee(String name, String amount) {
        QuotationFeeDto fee = new QuotationFeeDto();
        fee.setName(name);
        fee.setAmount(new BigDecimal(amount));
        return fee;
    }
}
