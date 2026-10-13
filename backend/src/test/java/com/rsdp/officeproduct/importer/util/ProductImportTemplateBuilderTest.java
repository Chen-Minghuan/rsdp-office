package com.rsdp.officeproduct.importer.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductImportTemplateBuilderTest {

    @Test
    void headerNamesContainsSixDimTags() {
        List<String> headers = ProductImportTemplateBuilder.headerNames();
        assertTrue(headers.contains("六维标签"), "模板应包含六维标签列");
        assertTrue(headers.contains("品类码"), "模板应包含品类码列");
    }

    @Test
    void writeTemplateGeneratesExcel() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProductImportTemplateBuilder.write(out);
        byte[] bytes = out.toByteArray();
        assertTrue(bytes.length > 0, "模板文件不应为空");
        // xlsx 文件以 ZIP 魔数开头
        assertTrue(bytes[0] == 0x50 && bytes[1] == 0x4B, "应为 xlsx 格式");
    }
}
