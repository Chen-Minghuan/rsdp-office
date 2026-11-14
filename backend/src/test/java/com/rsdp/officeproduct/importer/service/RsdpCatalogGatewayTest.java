package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 主项目商品网关映射测试：产品列表/变体/尺寸解析。
 */
class RsdpCatalogGatewayTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void officeProductsFlattenActiveVariantsWithExactDimensions() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        RsdpCatalogGateway gateway = new RsdpCatalogGateway(client, json);

        JsonNode list = json.readTree("""
            {"total":1,"page":1,"size":500,"rows":[{
              "rspuId":"RSPU-1","rspuCode":"OF-MC-0001","productName":"办公桌",
              "retailPrice":10,"status":"active","primaryImageUrl":"/api/v1/images/IMG-1",
              "createdAt":"2026-11-05T09:54:58.953677","updatedAt":"2026-11-05T09:54:58.953677"}]}
            """);
        JsonNode variants = json.readTree("""
            [{"variantId":"VARIANT","status":"active","displayName":"标准款",
              "dimensions":"{\\"widthMm\\":1200.01,\\"depthMm\\":600,\\"heightMm\\":750,\\"unit\\":\\"mm\\"}"},
             {"variantId":"VARIANT-DELETED","status":"deleted"}]
            """);
        when(client.get(anyString())).thenAnswer(call -> {
            String path = call.getArgument(0);
            return path.startsWith("/api/v1/products?page=") ? list : variants;
        });
        when(client.baseUrl()).thenReturn("http://rsdp.test");

        List<OfficeProductDto> result = gateway.listOfficeProducts("full");

        assertEquals(1, result.size());
        OfficeProductDto dto = result.get(0);
        assertEquals("RSPU-1", dto.getRspuId());
        assertEquals("VARIANT", dto.getVariantId());
        assertEquals(new BigDecimal("1200.01"), dto.getWidthMm());
        assertEquals(new BigDecimal("750"), dto.getHeightMm());
        assertEquals(121, dto.getWidthCm());
        assertEquals("http://rsdp.test/api/v1/images/IMG-1", dto.getImageUrl());
        assertEquals(new BigDecimal("10"), dto.getPrice());
    }

    @Test
    void officeProductsNormalizeMainShortDimensionKeys() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        RsdpCatalogGateway gateway = new RsdpCatalogGateway(client, json);

        JsonNode list = json.readTree("""
            {"total":1,"page":1,"size":500,"rows":[{
              "rspuId":"RSPU-1","productName":"职员台","retailPrice":10,"status":"active"}]}
            """);
        JsonNode variants = json.readTree("""
            [{"variantId":"MM","status":"active","dimensions":"{\\"w\\":1400.01,\\"d\\":1200,\\"h\\":750,\\"unit\\":\\"mm\\"}"},
             {"variantId":"LEGACY","status":"active","dimensions":"{\\"w\\":600,\\"d\\":500,\\"h\\":null,\\"unit\\":null}"},
             {"variantId":"CM","status":"active","dimensions":"{\\"w\\":140,\\"d\\":120,\\"h\\":75,\\"unit\\":\\"cm\\"}"},
             {"variantId":"INVALID","status":"active","dimensions":"{\\"w\\":999,\\"d\\":999,\\"unit\\":\\"m\\"}"}]
            """);
        when(client.get(anyString())).thenAnswer(call -> {
            String path = call.getArgument(0);
            return path.startsWith("/api/v1/products?page=") ? list : variants;
        });

        List<OfficeProductDto> result = gateway.listOfficeProducts("full");

        assertEquals(4, result.size());
        assertEquals(new BigDecimal("1400.01"), result.get(0).getWidthMm());
        assertEquals(new BigDecimal("1200"), result.get(0).getDepthMm());
        assertEquals(new BigDecimal("750"), result.get(0).getHeightMm());
        assertEquals(141, result.get(0).getWidthCm());
        assertEquals(new BigDecimal("600"), result.get(1).getWidthMm());
        assertEquals(new BigDecimal("500"), result.get(1).getDepthMm());
        assertNull(result.get(1).getHeightMm());
        assertEquals(new BigDecimal("1400"), result.get(2).getWidthMm());
        assertEquals(new BigDecimal("1200"), result.get(2).getDepthMm());
        assertEquals(140, result.get(2).getWidthCm());
        assertNull(result.get(3).getWidthMm());
        assertNull(result.get(3).getDepthMm());
    }

    @Test
    void officeProductsLimitConcurrentVariantRequests() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        RsdpCatalogGateway gateway = new RsdpCatalogGateway(client, json);
        JsonNode list = json.readTree("{\"total\":24,\"page\":1,\"size\":500,\"rows\":[]}");
        for (int i = 0; i < 24; i++) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) list.get("rows")).addObject().put("rspuId", "RSPU-" + i).put("productName", "商品" + i);
        }
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        when(client.get(anyString())).thenAnswer(call -> {
            String path = call.getArgument(0);
            if (path.startsWith("/api/v1/products?page=")) return list;
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            calls.incrementAndGet();
            try {
                Thread.sleep(50);
                return json.readTree("[]");
            } finally {
                active.decrementAndGet();
            }
        });

        gateway.listOfficeProducts("full");

        assertEquals(24, calls.get());
        assertTrue(maximum.get() > 1);
        assertTrue(maximum.get() <= 8, "最多同时执行 8 个变体请求，实际为 " + maximum.get());
    }

    @Test
    void officeProductsCacheAvoidsRefetchWithinTtl() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        RsdpCatalogGateway gateway = new RsdpCatalogGateway(client, json);
        JsonNode emptyList = json.readTree("{\"total\":0,\"page\":1,\"size\":500,\"rows\":[]}");
        when(client.get(anyString())).thenReturn(emptyList);

        gateway.listOfficeProducts("full");
        gateway.listOfficeProducts("full");

        verify(client, times(1)).get(anyString());
    }
}
