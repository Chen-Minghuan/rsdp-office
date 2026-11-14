package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RsdpMainProperties;
import com.rsdp.officeproduct.importer.service.MainProductEntryGateway.EntryPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 报价转发：本厂同变体同供应配置已有报价时，回退为更新出厂价而不是报错。 */
class MainProductEntryGatewayTest {

    private final RsdpClient client = mock(RsdpClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MainProductEntryGateway gateway = new MainProductEntryGateway(client, new RsdpMainProperties());

    private EntryPayload payload() {
        return new EntryPayload("EXT-1", "商品", null, null, null, "默认规格", null, null,
            2400, 1200, 750, null, null, null, null, new BigDecimal("1380"), 1, 15, null);
    }

    @Test
    void addQuoteCreatesWhenNoExistingQuote() throws Exception {
        when(client.mainFactoryCode()).thenReturn("A034");
        when(client.postJson(contains("/rsku"), any())).thenReturn(mapper.createObjectNode());

        gateway.addQuote("RSPU-1", "RSPU-1-V001", payload());

        verify(client).postJson(eq("/api/v1/products/RSPU-1/rsku"), any());
        verify(client, never()).putJson(anyString(), any());
    }

    @Test
    void addQuoteUpdatesPriceWhenDuplicateQuote() throws Exception {
        when(client.mainFactoryCode()).thenReturn("A034");
        when(client.postJson(contains("/rsku"), any()))
            .thenThrow(new BusinessException("主项目接口返回错误: 该工厂对该变体的同一供应配置已有报价"));
        JsonNode rows = mapper.readTree(
            "[{\"rskuId\":\"RSKU-9\",\"variantId\":\"RSPU-1-V001\",\"factoryCode\":\"A034\"},"
            + "{\"rskuId\":\"RSKU-8\",\"variantId\":\"RSPU-1-V001\",\"factoryCode\":\"F001\"}]");
        when(client.get("/api/v1/products/RSPU-1/rsku")).thenReturn(rows);

        String rskuId = gateway.addQuote("RSPU-1", "RSPU-1-V001", payload());

        assertEquals("RSKU-9", rskuId);
        verify(client).putJson(eq("/api/v1/products/RSPU-1/rsku/RSKU-9/price"), any());
    }
}
