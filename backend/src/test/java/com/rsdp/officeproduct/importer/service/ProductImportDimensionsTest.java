package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.dto.excel.ProductImportRow;
import com.rsdp.officeproduct.importer.dto.request.ManualProductEntryRequest;
import com.rsdp.officeproduct.importer.entity.RspuVariant;
import com.rsdp.officeproduct.importer.mapper.RspuVariantMapper;
import com.rsdp.officeproduct.importer.mapper.ImageAssetsMapper;
import com.rsdp.officeproduct.importer.mapper.RspuMasterMapper;
import com.rsdp.officeproduct.importer.dto.response.ProductImportPreviewRow;
import com.rsdp.officeproduct.importer.util.EmbeddedImageStore;
import com.rsdp.officeproduct.importer.util.WorkbookImportSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProductImportDimensionsTest {
    @Test
    void imageFreeProductDoesNotIssueEmptyMasterUpdate() {
        RspuMasterMapper masterMapper = mock(RspuMasterMapper.class);
        ImageAssetsMapper imageMapper = mock(ImageAssetsMapper.class);
        ProductImportService service = new ProductImportService(masterMapper, null, imageMapper, null, null,
            null, null, null, null, null, new ObjectMapper(), null, null);
        ReflectionTestUtils.invokeMethod(service, "saveImages", "RSPU-1", new ProductImportRow());
        verifyNoInteractions(masterMapper, imageMapper);
    }

    @Test
    void selectedEmbeddedImagesBecomePrimaryAndDetailsInRequestedOrder() {
        EmbeddedImageStore store = mock(EmbeddedImageStore.class);
        ProductImportService service = new ProductImportService(null, null, null, null, null,
            null, null, null, null, null, new ObjectMapper(), null, store);
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
        ProductImportService service = new ProductImportService(null, null, null, null, null,
            null, null, null, null, null, new ObjectMapper(), null, null);
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

    @Test
    void newAndExistingVariantReceiveConfirmedMillimetreDimensions() throws Exception {
        RspuVariantMapper mapper = mock(RspuVariantMapper.class);
        ProductImportService service = new ProductImportService(null, mapper, null, null, null,
            null, null, null, null, null, new ObjectMapper(), null, null);
        ProductImportRow row = new ProductImportRow();
        row.setSizeText("1600x800x750");
        row.setSourceSizeText("1600/1800x800x750");
        row.setWidthMm(1600);
        row.setDepthMm(800);
        row.setHeightMm(750);
        row.setPackageCount(9);
        row.setPackageVolumeCbm(new BigDecimal("0.742"));
        row.setGrossWeight(new BigDecimal("209"));
        when(mapper.selectList(any())).thenReturn(List.of());
        RspuVariant created = (RspuVariant) ReflectionTestUtils.invokeMethod(service,
            "createVariantIfNotExists", "RSPU-1", row, Map.of("size", List.of(), "color", List.of(), "material", List.of()));
        assertNotNull(created);
        Map<?, ?> dimensions = new ObjectMapper().readValue(created.getDimensions(), Map.class);
        assertEquals(1600, dimensions.get("widthMm"));
        assertEquals(800, dimensions.get("depthMm"));
        assertEquals(750, dimensions.get("heightMm"));
        assertEquals("mm", dimensions.get("unit"));
        assertEquals("1600/1800x800x750", dimensions.get("sourceSizeText"));
        assertEquals(9, created.getPackageCount());
        assertEquals(new BigDecimal("0.742"), created.getPackageVolumeCbm());
        assertEquals(new BigDecimal("209"), created.getGrossWeight());
        verify(mapper).insert(created);

        when(mapper.selectList(any())).thenReturn(List.of(created));
        row.setWidthMm(1800);
        row.setSizeText("1800x800x750");
        RspuVariant updated = (RspuVariant) ReflectionTestUtils.invokeMethod(service,
            "createVariantIfNotExists", "RSPU-1", row, Map.of("size", List.of(), "color", List.of(), "material", List.of()));
        // A different size is a distinct variant; repeated imports of the same size update it.
        assertNotEquals(created.getVariantId(), updated.getVariantId());
        when(mapper.selectList(any())).thenReturn(List.of(created, updated));
        row.setHeightMm(780);
        RspuVariant repeated = (RspuVariant) ReflectionTestUtils.invokeMethod(service,
            "createVariantIfNotExists", "RSPU-1", row, Map.of("size", List.of(), "color", List.of(), "material", List.of()));
        assertEquals(updated.getVariantId(), repeated.getVariantId());
        assertEquals(780, new ObjectMapper().readTree(repeated.getDimensions()).get("heightMm").asInt());
        verify(mapper).updateById(updated);
        row.setHeightMm(null);
        row.setPackageCount(null);
        row.setPackageVolumeCbm(null);
        row.setGrossWeight(null);
        RspuVariant partial = (RspuVariant) ReflectionTestUtils.invokeMethod(service,
            "createVariantIfNotExists", "RSPU-1", row, Map.of("size", List.of(), "color", List.of(), "material", List.of()));
        assertEquals(780, new ObjectMapper().readTree(partial.getDimensions()).get("heightMm").asInt());
        assertEquals(9, partial.getPackageCount());
        assertEquals(new BigDecimal("0.742"), partial.getPackageVolumeCbm());
    }

    @Test
    void dimensionsDistinguishVariantsWhenSizeTextIsEmpty() {
        RspuVariantMapper mapper = mock(RspuVariantMapper.class);
        ProductImportService service = new ProductImportService(null, mapper, null, null, null,
            null, null, null, null, null, new ObjectMapper(), null, null);
        ProductImportRow row = new ProductImportRow();
        row.setWidthMm(1200);
        row.setDepthMm(600);
        when(mapper.selectList(any())).thenReturn(List.of());
        RspuVariant first = (RspuVariant) ReflectionTestUtils.invokeMethod(service,
            "createVariantIfNotExists", "RSPU-1", row, Map.of("size", List.of(), "color", List.of(), "material", List.of()));
        when(mapper.selectList(any())).thenReturn(List.of(first));
        row.setWidthMm(1400);
        RspuVariant second = (RspuVariant) ReflectionTestUtils.invokeMethod(service,
            "createVariantIfNotExists", "RSPU-1", row, Map.of("size", List.of(), "color", List.of(), "material", List.of()));
        assertNotEquals(first.getVariantId(), second.getVariantId());
    }
}
