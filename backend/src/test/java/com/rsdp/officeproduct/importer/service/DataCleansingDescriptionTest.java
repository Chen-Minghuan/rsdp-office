package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.dto.excel.ProductImportRow;
import com.rsdp.officeproduct.importer.dto.response.DataCleansingReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataCleansingDescriptionTest {
    @Test
    void descriptionKeepsSupplierParagraphsWhileCleaningExtraSpaces() {
        ProductImportRow row = new ProductImportRow();
        row.setDescription("第一条   内容\n第二条    内容");
        new DataCleansingService().cleanse(row, 4, new DataCleansingReport());
        assertEquals("第一条 内容\n第二条 内容", row.getDescription());
    }
}
