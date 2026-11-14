package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.dto.request.ManualProductEntryRequest;
import com.rsdp.officeproduct.importer.service.MainProductEntryGateway.EntryPayload;
import com.rsdp.officeproduct.importer.service.MainProductEntryGateway.EntryResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 手工录入转发：外部编码已存在时按更新处理，不再重复建档。 */
class ProductImportManualEntryTest {

    private MainProductEntryGateway gateway = mock(MainProductEntryGateway.class);
    private DictService dictService = mock(DictService.class);

    private ProductImportService newService() {
        return new ProductImportService(dictService, null, null, null, new ObjectMapper(), null, gateway);
    }

    private ManualProductEntryRequest newRequest() {
        ManualProductEntryRequest request = new ManualProductEntryRequest();
        request.setProductName("联调验证-会议桌");
        request.setExternalCode("OFFICE-E2E-0001");
        request.setRetailPrice(new BigDecimal("2680"));
        request.setFactoryPrice(new BigDecimal("1280"));
        request.setWidthMm(2400);
        request.setDepthMm(1200);
        request.setHeightMm(750);
        return request;
    }

    @Test
    void manualEntryCreatesNewWhenExternalCodeNotFound() {
        when(gateway.findRspuIdByExternalCode("OFFICE-E2E-0001")).thenReturn(null);
        when(gateway.forwardFactoryEntry(any(EntryPayload.class)))
            .thenReturn(new EntryResult("RSPU-NEW", "RSPU-NEW-V001", "RSKU-1", "OF-TBD-0002", 0));

        Map<String, Object> result = newService().createManualEntry(newRequest());

        assertEquals(false, result.get("updated"));
        assertEquals("RSPU-NEW", result.get("rspuId"));
        verify(gateway, never()).updateBaseFields(anyString(), any(EntryPayload.class));
    }

    @Test
    void manualEntryUpdatesExistingWhenExternalCodeFound() {
        when(gateway.findRspuIdByExternalCode("OFFICE-E2E-0001")).thenReturn("RSPU-EXISTING");
        when(gateway.listVariants("RSPU-EXISTING")).thenReturn(new ObjectMapper().createArrayNode());
        when(gateway.addVariant(anyString(), any(EntryPayload.class))).thenReturn("RSPU-EXISTING-V002");
        when(gateway.addQuote(anyString(), anyString(), any(EntryPayload.class))).thenReturn("RSKU-2");

        Map<String, Object> result = newService().createManualEntry(newRequest());

        assertEquals(true, result.get("updated"));
        assertEquals("RSPU-EXISTING", result.get("rspuId"));
        assertEquals("RSPU-EXISTING-V002", result.get("variantId"));
        verify(gateway).updateBaseFields(anyString(), any(EntryPayload.class));
        verify(gateway, never()).forwardFactoryEntry(any(EntryPayload.class));
    }
}
