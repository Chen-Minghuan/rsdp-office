package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.*;
import com.rsdp.officeproduct.importer.mapper.*;
import com.rsdp.officeproduct.importer.office.dto.SaveCanvasRequest;
import com.rsdp.officeproduct.importer.office.entity.*;
import com.rsdp.officeproduct.importer.office.mapper.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OfficeProductCatalogTest {
    @Test void exposesExactSkuDimensionsAndQueriesOnlyApprovedActiveProductsAndActiveVariants() {
        var products = mock(RspuMasterMapper.class); var variants = mock(RspuVariantMapper.class);
        var service = new OfficeService(mock(OfficeWorkspaceLockMapper.class), mock(OfficeCanvasMapper.class), mock(OfficeCartMapper.class), mock(OfficeAgentMessageMapper.class), products, variants, new ObjectMapper());
        var master = new RspuMaster(); master.setRspuId("MASTER"); master.setProductName("办公桌"); master.setRetailPrice(BigDecimal.TEN);
        when(products.selectList(any())).thenAnswer(call -> {
            QueryWrapper<?> query = call.getArgument(0); assertTrue(query.getSqlSegment().contains("audit_status"));
            assertTrue(query.getParamNameValuePairs().containsValue("active")); assertTrue(query.getParamNameValuePairs().containsValue("approved"));
            return List.of(master);
        });
        var sku = new RspuVariant(); sku.setRspuId("MASTER"); sku.setVariantId("VARIANT"); sku.setDimensions("{\"widthMm\":1200.01,\"depthMm\":600,\"heightMm\":750,\"unit\":\"mm\"}");
        when(variants.selectList(any())).thenAnswer(call -> {
            QueryWrapper<?> query = call.getArgument(0); query.getSqlSegment(); assertTrue(query.getParamNameValuePairs().containsValue("active")); return List.of(sku);
        });
        var result = service.listOfficeProducts().get(0);
        assertEquals("MASTER", result.getRspuId()); assertEquals("VARIANT", result.getVariantId());
        assertEquals(new BigDecimal("1200.01"), result.getWidthMm()); assertEquals(121, result.getWidthCm());
        assertEquals(new BigDecimal("750"), result.getHeightMm());
    }
    @Test void staleDebouncedCanvasWritesCannotOverwriteNewerModuleSelections() {
        var projects = mock(OfficeWorkspaceLockMapper.class); var canvases = mock(OfficeCanvasMapper.class);
        var service = new OfficeService(projects, canvases, mock(OfficeCartMapper.class), mock(OfficeAgentMessageMapper.class), mock(RspuMasterMapper.class), mock(RspuVariantMapper.class), new ObjectMapper());
        var latest = new OfficeCanvas(); latest.setProjectId("P"); latest.setRevision(10); latest.setMode("overview"); latest.setObjectsJson("[]"); latest.setFilesJson("[]");
        when(canvases.selectList(any())).thenReturn(List.of(latest));
        var request = new SaveCanvasRequest(); request.setProjectId("P"); request.setRevision(9); request.setMode("overview"); request.setObjects(List.of());
        assertThrows(BusinessException.class, () -> service.saveCanvas(request));
        verify(projects).lockWorkspace("P"); verify(canvases, never()).insert(any(OfficeCanvas.class));
    }
}
