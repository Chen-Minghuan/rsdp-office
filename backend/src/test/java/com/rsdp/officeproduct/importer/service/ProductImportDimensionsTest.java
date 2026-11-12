package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.dto.excel.ProductImportRow;
import com.rsdp.officeproduct.importer.dto.request.ManualProductEntryRequest;
import com.rsdp.officeproduct.importer.dto.response.ProductImportPreviewRow;
import com.rsdp.officeproduct.importer.util.EmbeddedImageStore;
import com.rsdp.officeproduct.importer.util.WorkbookImportSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 导入服务保留行为的测试（转发写库版）：
 * 内嵌图片选择/主图归位、手工录入行映射均不依赖本地商品表。
 */
class ProductImportDimensionsTest {

    /** 新构造签名：字典/别名/导入配置/清洗/ObjectMapper/内嵌图存储/主项目转发网关。 */
    private ProductImportService newService(EmbeddedImageStore store) {
        return new ProductImportService(null, null, null, null, new ObjectMapper(), store, null);
    }

    @Test
    void selectedEmbeddedImagesBecomePrimaryAndDetailsInRequestedOrder() {
        EmbeddedImageStore store = mock(EmbeddedImageStore.class);
        ProductImportService service = newService(store);
        var first = new WorkbookImportSource.Image(new byte[] { 1 }, "png", "first");
        var second = new WorkbookImportSource.Image(new byte[] { 2 }, "png", "second");
        when(store.save(first)).thenReturn(new EmbeddedImageStore.Saved("https://images.test/first", null));
        when(store.save(second)).thenReturn(new EmbeddedImageStore.Saved("https://images.test/second", null));
        ProductImportPreviewRow selection = new ProductImportPreviewRow();
        selection.setPrimaryEmbeddedImageIndex(1);
        ProductImportRow row = new ProductImportRow();
        var written = new ArrayList<EmbeddedImageStore.Saved>();
        ReflectionTestUtils.invokeMethod(service, "appendEmbeddedImages", selection, row, List.of(first, second), written);
        assertEquals("https://images.test/second", row.getPrimaryImageUrl());
        assertEquals("https://images.test/first", row.getDetailImageUrls());
        assertEquals(2, written.size());

        selection.setSelectedEmbeddedImageIndexes(List.of(1));
        row = new ProductImportRow();
        written.clear();
        ReflectionTestUtils.invokeMethod(service, "appendEmbeddedImages", selection, row, List.of(first, second), written);
        assertEquals("https://images.test/second", row.getPrimaryImageUrl());
        assertNull(row.getDetailImageUrls());
        assertEquals(1, written.size());
    }

    @Test
    void manualEntryKeepsMillimetreDimensionsWithoutLegacySpecs() {
        ProductImportService service = newService(null);
        ManualProductEntryRequest request = new ManualProductEntryRequest();
        request.setWidthMm(1200);
        request.setDepthMm(600);
        request.setHeightMm(750);
        ProductImportRow row = (ProductImportRow) ReflectionTestUtils.invokeMethod(service, "toImportRow", request);
        assertNotNull(row);
        assertEquals(1200, row.getWidthMm());
        assertEquals(600, row.getDepthMm());
        assertEquals(750, row.getHeightMm());
    }
}
