package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.request.CatalogProductPageRequest;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogProductFilterServiceTest {

    private final RsdpCatalogGateway gateway = mock(RsdpCatalogGateway.class);
    private final CatalogProductFilterService service = new CatalogProductFilterService(gateway);

    @Test
    void filtersBeforePaginationAndCountsStorageWithoutOverlap() {
        when(gateway.listFilterProducts("full", false)).thenReturn(List.of(managerDesk(), fileCabinet(), unknownWorkstation()));
        var request = new CatalogProductPageRequest();
        request.setSize(1);
        request.setStorageTypes(List.of("SUPPORT_CABINET"));

        var page = service.pageProducts("full", request, false);

        assertEquals(1, page.total());
        assertEquals("P1", page.rows().getFirst().getRspuId());
        assertEquals(2, page.facets().groups().get("storageTypes").stream()
            .mapToLong(option -> option.count()).sum());
        assertTrue(page.facets().groups().get("storageTypes").stream()
            .noneMatch(option -> option.code().equals("UNSPECIFIED")));
        assertTrue(page.facets().groups().get("widthRanges").stream()
            .noneMatch(option -> option.code().equals("UNSPECIFIED")));
        assertEquals(2, page.facets().groups().get("widthRanges").stream()
            .filter(option -> option.code().equals("R1200_1600"))
            .mapToLong(option -> option.count()).sum());
        assertEquals(3, page.catalogTotal());
        assertEquals(2, page.activeTotal());
    }

    @Test
    void matchesMaterialAndWidthOnTheSameVariant() {
        when(gateway.listFilterProducts("full", false)).thenReturn(List.of(managerDesk(), fileCabinet(), unknownWorkstation()));
        var request = new CatalogProductPageRequest();
        request.setMaterials(List.of("PB"));
        request.setWidthRanges(List.of("R1200_1600"));

        var page = service.pageProducts("full", request, false);

        assertEquals(1, page.total());
        assertEquals("P2", page.rows().getFirst().getRspuId());
    }

    @Test
    void searchesProductsWithMissingCodes() {
        var product = managerDesk();
        product.summary().setRspuCode(null);
        product.summary().setExternalCode(null);
        when(gateway.listFilterProducts("full", false)).thenReturn(List.of(product));
        var request = new CatalogProductPageRequest();
        request.setSearch("主管");

        var page = service.pageProducts("full", request, false);

        assertEquals(1, page.total());
        assertEquals("P1", page.rows().getFirst().getRspuId());
    }

    @Test
    void rejectsInvalidWidthRange() {
        var request = new CatalogProductPageRequest();
        request.setWidthRanges(List.of("1200"));
        assertThrows(BusinessException.class, request::validate);
        request.setWidthRanges(List.of("R1200_1600", "UNSPECIFIED"));
        assertDoesNotThrow(request::validate);
    }

    private CatalogFilterProduct managerDesk() {
        return product("P1", "1.4米主管台", "蜜芽胡桃+雅士白", "支撑柜带抽屉，钢脚", "PB", "1200");
    }

    private CatalogFilterProduct fileCabinet() {
        return product("P2", "2米文件柜", "纳柯", "高柜", "PB", "1400");
    }

    private CatalogFilterProduct unknownWorkstation() {
        var product = product("P3", "1.2米双人位", "雅士白", "", "WO", "1400");
        product.summary().setStatus("discontinued");
        return product;
    }

    private CatalogFilterProduct product(String id, String name, String color, String description,
                                         String material, String width) {
        var summary = new ProductListItemDto();
        summary.setRspuId(id);
        summary.setProductName(name);
        summary.setProductLevel("B");
        summary.setColorPrimaryName(color);
        summary.setStatus("active");
        var variant = new ProductDetailDto.Variant();
        variant.setVariantId(id + "-V1");
        variant.setMaterialText(material);
        variant.setWidthMm(new BigDecimal(width));
        variant.setProductLevel("B");
        variant.setStatus("active");
        return new CatalogFilterProduct(summary, description, List.of(variant));
    }
}
