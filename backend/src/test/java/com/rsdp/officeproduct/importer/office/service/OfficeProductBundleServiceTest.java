package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductBundleDtos.Bundle;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductBundleDtos.Item;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductBundleDtos.Save;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProductBundleMapper;
import com.rsdp.officeproduct.importer.office.product.LocalProductService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OfficeProductBundleServiceTest {

    private final OfficeProductBundleMapper bundles = mock(OfficeProductBundleMapper.class);
    private final CurrentUserService users = mock(CurrentUserService.class);
    private final OfficeService office = mock(OfficeService.class);
    private final LocalProductService localProducts = mock(LocalProductService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final OfficeProductBundleService service = new OfficeProductBundleService(
        bundles, users, office, localProducts, json);

    @Test
    void saveKeepsInvalidExistingItemsAndValidatesOnlyNewOnes() throws Exception {
        authenticate();
        previousRow("B1", 1, savedItemJson("P-OLD", "V1", "旧茶几", "旧规格"));
        when(office.currentProductVariant("P-OLD", "V1")).thenReturn(null);
        when(office.currentProductVariant("P-NEW", "V1")).thenReturn(product("P-NEW", "新商品", "新规格"));
        when(bundles.update(any())).thenReturn(1);

        Bundle result = service.save("B1", new Save("组合", 1, List.of(item("P-OLD"), item("P-NEW"))));

        assertEquals(2, result.items().size());
        assertEquals("旧茶几", result.items().get(0).name(), "失效旧条目保留原展示信息");
        assertEquals("新商品", result.items().get(1).name());
    }

    @Test
    void saveRejectsInvalidNewItem() throws Exception {
        authenticate();
        previousRow("B1", 1, savedItemJson("P-OLD", "V1", "旧茶几", "旧规格"));
        when(office.currentProductVariant("P-BAD", "V1")).thenReturn(null);

        assertThrows(BusinessException.class,
            () -> service.save("B1", new Save("组合", 1, List.of(item("P-BAD")))));
    }

    @Test
    void saveRefreshesValidExistingItem() throws Exception {
        authenticate();
        previousRow("B1", 1, savedItemJson("P-OLD", "V1", "旧茶几", "旧规格"));
        when(office.currentProductVariant("P-OLD", "V1")).thenReturn(product("P-OLD", "最新名称", "最新规格"));
        when(bundles.update(any())).thenReturn(1);

        Bundle result = service.save("B1", new Save("组合", 1, List.of(item("P-OLD"))));

        assertEquals("最新名称", result.items().get(0).name());
    }

    @Test
    void saveRejectsStaleRevision() throws Exception {
        authenticate();
        previousRow("B1", 2, savedItemJson("P-OLD", "V1", "旧茶几", "旧规格"));
        when(office.currentProductVariant("P-OLD", "V1")).thenReturn(product("P-OLD", "名称", "规格"));
        when(bundles.update(any())).thenReturn(0);

        assertThrows(BusinessException.class,
            () -> service.save("B1", new Save("组合", 1, List.of(item("P-OLD")))));
    }

    private void authenticate() {
        when(users.authEnabled()).thenReturn(true);
        OfficeUser user = new OfficeUser();
        user.setUserId("U1");
        when(users.currentUser()).thenReturn(user);
    }

    private void previousRow(String bundleId, int revision, String itemsJson) {
        when(bundles.findById(bundleId, "U1"))
            .thenReturn(new OfficeProductBundleMapper.Row(bundleId, "U1", "组合", revision, itemsJson));
    }

    private String savedItemJson(String rspuId, String variantId, String name, String variant) throws Exception {
        return json.writeValueAsString(List.of(new com.rsdp.officeproduct.importer.office.dto.OfficeProductBundleDtos.SavedItem(
            rspuId, variantId, 1, name, variant, null)));
    }

    private Item item(String rspuId) {
        return new Item(rspuId, "V1", 1);
    }

    private OfficeProductDto product(String rspuId, String name, String variant) {
        OfficeProductDto product = new OfficeProductDto();
        product.setRspuId(rspuId);
        product.setName(name);
        product.setVariant(variant);
        return product;
    }
}
