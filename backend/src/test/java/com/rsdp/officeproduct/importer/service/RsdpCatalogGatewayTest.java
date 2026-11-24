package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    void confirmsLegacyServerBeforeFallingBackFromWrappedBatchError() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        var gateway = new RsdpCatalogGateway(client, json);
        String batch = "/api/v1/products/variants/retail-prices?viewMode=full&variantId=V-A";
        String probe = "/api/v1/products/catalog-page?page=1&size=1&viewMode=full";
        String list = "/api/v1/products?page=1&size=500&viewMode=full&status=active";
        when(client.get(batch)).thenThrow(new BusinessException(500, "legacy batch route"));
        when(client.get(probe)).thenThrow(new BusinessException(404, "missing page route"));
        when(client.get(list)).thenReturn(json.readTree("""
            {"total":2,"rows":[
              {"rspuId":"P1","productName":"旧版桌子","status":"active","retailPrice":190},
              {"rspuId":"P2","productName":"旧版椅子","status":"active"}]}
            """));
        when(client.get("/api/v1/products/P1/variants")).thenReturn(json.readTree("[{\"variantId\":\"V-A\"}]"));
        when(client.get("/api/v1/products/P2/variants")).thenReturn(json.createArrayNode());

        assertEquals(Map.of("V-A", new BigDecimal("190")), gateway.retailPrices("full", Set.of("V-A")));
        assertEquals("旧版桌子", gateway.quotationProducts(Set.of("V-A")).get("V-A").getName());
        assertEquals(2, gateway.pageProducts("full", 1, 1, "", "", null).catalogTotal());
        assertEquals("P2", gateway.pageProducts("full", 2, 1, "", "", null).rows().getFirst().getRspuId());
        verify(client).get(batch);
        verify(client).get(probe);
    }

    @Test
    void preservesPermissionErrorsAndNewServerBatchFailures() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        var gateway = new RsdpCatalogGateway(client, json);
        String batch = "/api/v1/products/variants/retail-prices?viewMode=full&variantId=V-A";
        var forbidden = new BusinessException(403, "permission denied");
        var failure = new BusinessException(500, "pricing unavailable");
        when(client.get(batch)).thenThrow(forbidden).thenThrow(failure);
        when(client.get("/api/v1/products/catalog-page?page=1&size=1&viewMode=full"))
            .thenReturn(json.createObjectNode());

        assertSame(forbidden, assertThrows(BusinessException.class,
            () -> gateway.retailPrices("full", Set.of("V-A"))));
        assertSame(failure, assertThrows(BusinessException.class,
            () -> gateway.retailPrices("full", Set.of("V-A"))));
        verify(client, times(2)).get(batch);
        verify(client).get("/api/v1/products/catalog-page?page=1&size=1&viewMode=full");
        verifyNoMoreInteractions(client);
    }

    @Test
    void legacyManagedCatalogRequiresVerifiedFactoryOwnership() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        var gateway = new RsdpCatalogGateway(client, json);
        when(client.catalogFactoryCode()).thenReturn("A001");
        when(client.get("/api/v1/products/catalog-page?page=1&size=20&viewMode=managed&factoryCode=A001"))
            .thenThrow(new BusinessException(404, "missing page route"));
        when(client.get("/api/v1/products?page=1&size=500&viewMode=managed&factoryCode=A001")).thenReturn(json.readTree("""
            {"total":3,"rows":[
              {"rspuId":"GOOD","ownerFactoryCode":"A001","ownerVerified":true,"status":"active"},
              {"rspuId":"UNKNOWN","ownerFactoryCode":"A001","status":"active"},
              {"rspuId":"FOREIGN","ownerFactoryCode":"A002","ownerVerified":true,"status":"active"}]}
            """));

        var page = gateway.pageProducts("managed", 1, 20, "", "", null);
        assertEquals(1, page.catalogTotal());
        assertEquals("GOOD", page.rows().getFirst().getRspuId());
    }

    @Test
    void sharesColdRefreshWithinOneViewAndKeepsManagedCatalogSeparate() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        RsdpCatalogGateway gateway = new RsdpCatalogGateway(client, json);
        String full = "/api/v1/products?page=1&size=500&viewMode=full&status=active";
        String managed = "/api/v1/products/catalog-page?page=1&size=100&viewMode=managed&factoryCode=A001";
        when(client.catalogFactoryCode()).thenReturn("A001");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.get(full)).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return json.readTree("""
                {"total":1,"rows":[{"rspuId":"RSPU-SHARED","status":"active"}]}
                """);
        });
        when(client.get(managed)).thenReturn(json.readTree("""
            {"total":1,"catalogTotal":1,"activeTotal":1,"rows":[{"rspuId":"RSPU-FACTORY","status":"active"}]}
            """));
        when(client.get("/api/v1/products/RSPU-SHARED/variants")).thenReturn(
            json.createArrayNode().add(json.createObjectNode().put("variantId", "PUBLIC-V")));
        when(client.get("/api/v1/products/RSPU-FACTORY/variants")).thenReturn(
            json.createArrayNode().add(json.createObjectNode().put("variantId", "FACTORY-V")));

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> gateway.listOfficeProducts("full"));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var second = workers.submit(() -> gateway.listOfficeProducts("full"));
                var factory = workers.submit(() -> gateway.listOfficeProducts("managed"));
                assertEquals("FACTORY-V", factory.get(5, TimeUnit.SECONDS).getFirst().getId());
                release.countDown();
                assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
                assertEquals("PUBLIC-V", gateway.listOfficeProducts("full").getFirst().getId());
            } finally {
                release.countDown();
            }
        }

        assertTrue(gateway.listOfficeProducts("none").isEmpty());
        verify(client, times(1)).get(full);
        verify(client, times(1)).get(managed);
        verify(client, never()).get("/api/v1/products?page=1&size=500&viewMode=none");
    }

    @Test
    void failedRefreshCanRetryOnNextRequest() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        RsdpCatalogGateway gateway = new RsdpCatalogGateway(client, json);
        String path = "/api/v1/products?page=1&size=500&viewMode=full&status=active";
        when(client.get(path)).thenThrow(new IllegalStateException("upstream unavailable"))
            .thenReturn(json.readTree("{\"total\":0,\"rows\":[]}"));

        assertThrows(IllegalStateException.class, () -> gateway.listOfficeProducts("full"));
        assertTrue(gateway.listOfficeProducts("full").isEmpty());
        verify(client, times(2)).get(path);
    }

    @Test
    void productDetailFormatsStructuredDimensionsWithoutReplacingSourceText() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        RsdpCatalogGateway gateway = new RsdpCatalogGateway(client, json);
        when(client.get("/api/v1/products/RSPU-DETAIL")).thenReturn(json.readTree("""
            {"rspu":{"rspuId":"RSPU-DETAIL"}}
            """));
        var variants = json.createArrayNode();
        variants.addObject().put("variantId", "MM").put("dimensions", """
            {"w":1200,"d":600,"h":450,"unit":"mm"}
            """);
        variants.addObject().put("variantId", "CM").put("sizeText", "供应商尺寸原文").put("dimensions", """
            {"widthCm":120.05,"depthCm":60,"unit":"cm"}
            """);
        variants.addObject().put("variantId", "PARTIAL").put("dimensions", """
            {"w":1200,"h":450,"unit":"mm"}
            """);
        variants.addObject().put("variantId", "INVALID").put("dimensions", """
            {"w":1200,"d":600,"unit":"unknown"}
            """);
        variants.addObject().put("variantId", "MISSING");
        when(client.get("/api/v1/products/RSPU-DETAIL/variants")).thenReturn(variants);

        var detail = gateway.getProductDetail("RSPU-DETAIL").getVariants();
        assertEquals("1200 × 600 × 450 mm", detail.get(0).getDimensionsText());
        assertNull(detail.get(0).getSizeText());
        assertEquals("1200.5 × 600 mm", detail.get(1).getDimensionsText());
        assertEquals("供应商尺寸原文", detail.get(1).getSizeText());
        assertEquals("宽 1200 × 高 450 mm", detail.get(2).getDimensionsText());
        assertNull(detail.get(3).getDimensionsText());
        assertNull(detail.get(4).getDimensionsText());
    }

    @Test
    void detailAndCurrentVariantFindExactIdAcrossVisibleCatalogPages() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        RsdpCatalogGateway gateway = new RsdpCatalogGateway(client, json);
        when(client.get("/api/v1/products?page=1&size=500&viewMode=full&status=active"))
            .thenReturn(json.readTree("""
                {"total":2,"rows":[{"rspuId":"RSPU-FIRST","status":"active"}]}
                """));
        when(client.get("/api/v1/products?page=2&size=500&viewMode=full&status=active"))
            .thenReturn(json.readTree("""
                {"total":2,"rows":[{"rspuId":"RSPU-TARGET","status":"active","productName":"茶几"}]}
                """));
        when(client.get("/api/v1/products/RSPU-TARGET/variants")).thenReturn(json.readTree("""
            [{"variantId":"V1","status":"active"}]
            """));
        when(client.catalogFactoryCode()).thenReturn("A001");
        when(client.get("/api/v1/products/catalog-page?page=1&size=1&viewMode=managed&factoryCode=A001&rspuId=RSPU-TARGET"))
            .thenReturn(json.readTree("""
                {"total":0,"catalogTotal":1,"activeTotal":1,"rows":[]}
                """));

        assertTrue(gateway.inView("RSPU-TARGET", "full"));
        assertFalse(gateway.inView("RSPU-TARGET", "managed"));
        assertFalse(gateway.inView("RSPU-MISSING", "full"));
        assertEquals("RSPU-TARGET", gateway.currentVariant("full", "RSPU-TARGET", "V1").getRspuId());
        assertNull(gateway.currentVariant("full", "RSPU-MISSING", "V1"));
        verify(client, never()).get("/api/v1/products/RSPU-MISSING/variants");
    }

    @Test
    void batchesRequestedRetailPricesAndKeepsVisibilityScopesSeparate() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        var gateway = new RsdpCatalogGateway(client, json);
        when(client.catalogFactoryCode()).thenReturn("A001");
        String full = "/api/v1/products/variants/retail-prices?viewMode=full&variantId=V-A&variantId=V-MISSING";
        String managed = "/api/v1/products/variants/retail-prices?viewMode=managed&factoryCode=A001&variantId=V-A&variantId=V-MISSING";
        when(client.get(full)).thenReturn(json.readTree("{\"V-A\": 100, \"UNREQUESTED\": 99}"));
        when(client.get(managed)).thenReturn(json.createObjectNode());

        assertEquals(Map.of("V-A", new BigDecimal("100")), gateway.retailPrices("full", Set.of("V-MISSING", "V-A")));
        assertEquals(1, gateway.retailPrices("full", Set.of("V-A", "V-MISSING")).size());
        assertTrue(gateway.retailPrices("managed", Set.of("V-A", "V-MISSING")).isEmpty());
        assertTrue(gateway.retailPrices("none", Set.of("V-A")).isEmpty());
        verify(client).get(full);
        verify(client).get(managed);
        verify(client, never()).get(argThat(path -> !path.contains("/retail-prices?")));
    }

    @Test
    void fetchesOnlyRequestedPageAndEncodesSearchText() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        var gateway = new RsdpCatalogGateway(client, json);
        String path = "/api/v1/products/catalog-page?page=2&size=10&viewMode=full&search=%E6%A1%8C+%26%2B%25&status=active";
        when(client.get(path)).thenReturn(json.readTree("""
            {"total":11,"catalogTotal":185,"activeTotal":185,
             "rows":[{"rspuId":"ROW-11","productName":"办公桌","externalCode":"EXT-11"}]}
            """));

        var result = gateway.pageProducts("full", 2, 10, "桌 &+%", "active", null);
        assertEquals(11, result.total());
        assertEquals(185, result.catalogTotal());
        assertEquals("EXT-11", result.rows().getFirst().getExternalCode());
        verify(client).get(path);
        verifyNoMoreInteractions(client);
    }

    @Test
    void factoryPriceLookupKeepsLowestAuthorizedPricePerVariant() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        RsdpCatalogGateway gateway = new RsdpCatalogGateway(client, json);
        when(client.get("/api/v1/products/RSPU-1/rsku")).thenReturn(json.readTree("""
            [{"variantId":"A","factoryPrice":60.25},{"variantId":"A","factoryPrice":52.75},
             {"variantId":"B","factoryPrice":null},{"factoryPrice":85}]
            """));

        var prices = gateway.getFactoryPrices("RSPU-1");

        assertEquals(new BigDecimal("52.75"), prices.get("A"));
        assertEquals(new BigDecimal("85"), prices.get(""));
        assertFalse(prices.containsKey("B"));
    }

    @Test
    void officeProductsFlattenActiveVariantsWithExactDimensions() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        RsdpCatalogGateway gateway = new RsdpCatalogGateway(client, json);

        JsonNode list = json.readTree("""
            {"total":1,"page":1,"size":500,"rows":[{
              "rspuId":"RSPU-1","rspuCode":"OF-MC-0001","productName":"办公桌","categoryPath":"[\\"家具\\",\\"办公桌\\"]",
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
        assertEquals(List.of("家具", "办公桌"), dto.getCategoryPath());
        assertEquals("标准款", dto.getVariantName());
        assertEquals(new BigDecimal("1200.01"), dto.getWidthMm());
        assertEquals(new BigDecimal("750"), dto.getHeightMm());
        assertEquals(121, dto.getWidthCm());
        assertEquals("http://rsdp.test/api/v1/images/IMG-1", dto.getImageUrl());
        assertEquals(new BigDecimal("10"), dto.getPrice());
        verify(client, never()).get("/api/v1/products/RSPU-1");
    }

    @Test
    void officeProductsReadSixDimensionalEvidenceFromAmbiguousProductDetail() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        RsdpCatalogGateway gateway = new RsdpCatalogGateway(client, json);
        var list = json.readTree("""
            {"total":2,"rows":[{"rspuId":"OPEN","productName":"WG-1","categoryPath":["办公家具"],"status":"active"},
            {"rspuId":"HIDDEN","productName":"椅子","status":"inactive"}]}
            """);
        var variants = json.readTree("""
            [{"variantId":"V","status":"active","dimensions":"{\\"w\\":450,\\"d\\":490}"}]
            """);
        var detail = json.readTree("""
            {"rspu":{"categoryPath":["办公家具"],"sixDimTags":{"A":"圆形茶几","B":["圆形台面"],"E":"实木"}}}
            """);
        when(client.get(anyString())).thenAnswer(call -> switch (call.<String>getArgument(0)) {
            case "/api/v1/products/OPEN/variants" -> variants;
            case "/api/v1/products/OPEN" -> detail;
            default -> list;
        });

        var results = gateway.listOfficeProducts("full");
        assertEquals(1, results.size());
        assertEquals(List.of("圆形茶几", "圆形台面"), results.get(0).getFurnitureTags());
        assertEquals(List.of("办公家具"), results.get(0).getCategoryPath());
        gateway.listOfficeProducts("full");
        verify(client).get("/api/v1/products/OPEN");
        verify(client, never()).get("/api/v1/products/HIDDEN/variants");
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

    @Test
    void officeProductsExposeRspuPrimaryColorForEveryVariant() throws Exception {
        RsdpClient client = mock(RsdpClient.class);
        RsdpCatalogGateway gateway = new RsdpCatalogGateway(client, json);
        JsonNode list = json.readTree("""
            {"total":2,"rows":[
              {"rspuId":"COLORED","productName":"茶几","categoryPath":["办公家具"],"status":"active","colorPrimaryName":"蜜芽胡桃+雅士白"},
              {"rspuId":"UNCATEGORIZED","productName":"边几","categoryPath":["办公家具"],"status":"active"}
            ]}
            """);
        JsonNode coloredVariants = json.readTree("""
            [{"variantId":"V1","status":"active","dimensions":"{\\"w\\":1200,\\"d\\":600}"},
             {"variantId":"V2","status":"active","dimensions":"{\\"w\\":600,\\"d\\":600}"}]
            """);
        JsonNode uncoloredVariants = json.readTree("""
            [{"variantId":"V3","status":"active","dimensions":"{\\"w\\":500,\\"d\\":500}"}]
            """);
        when(client.get(anyString())).thenAnswer(call -> switch (call.<String>getArgument(0)) {
            case "/api/v1/products/COLORED/variants" -> coloredVariants;
            case "/api/v1/products/UNCATEGORIZED/variants" -> uncoloredVariants;
            default -> list;
        });

        List<OfficeProductDto> result = gateway.listOfficeProducts("full");

        assertEquals(3, result.size());
        assertEquals("蜜芽胡桃+雅士白", result.get(0).getColorPrimaryName());
        assertEquals("蜜芽胡桃+雅士白", result.get(1).getColorPrimaryName());
        assertNull(result.get(2).getColorPrimaryName());
    }

}
