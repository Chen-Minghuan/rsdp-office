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
        assertFalse(result.items().get(0).available());
        assertTrue(result.items().get(1).available());
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

    @Test void renameAndListCannotRevealAnotherWorkspaceSnapshot() throws Exception {
        authenticate();
        String localId = "OFFICE-LOCAL-LOCAL-A";
        String secret = savedItemJson(localId, "V1", "工作区A保密商品", "保密材质");
        previousRow("B1", 1, secret);
        when(bundles.list("U1")).thenReturn(List.of(new OfficeProductBundleMapper.Row("B1", "U1", "组合", 1, secret)));
        when(localProducts.detail("LOCAL-A")).thenThrow(new BusinessException(404, "商品不存在"));
        when(bundles.update(any())).thenReturn(1);
        var listed = service.list().getFirst().items().getFirst();
        var renamed = service.save("B1", new Save("改名", 1, List.of(item(localId)))).items().getFirst();
        for (var result : List.of(listed, renamed)) {
            assertEquals(localId, result.rspuId());
            assertNull(result.name()); assertNull(result.variant()); assertNull(result.imageUrl());
            assertFalse(result.available());
        }
        var write = org.mockito.ArgumentCaptor.forClass(OfficeProductBundleMapper.Row.class);
        verify(bundles).update(write.capture());
        assertFalse(write.getValue().itemsJson().contains("保密"));
        assertThrows(BusinessException.class, () -> service.save("NEW", new Save("新组合", 0, List.of(item(localId)))));
    }

    @Test void unavailableVisibleLocalVariantKeepsAuthorizedNameAndExplicitFlag() throws Exception {
        authenticate();
        String localId = "OFFICE-LOCAL-L1";
        previousRow("B1", 1, savedItemJson(localId, "V1", "旧名称", "旧规格"));
        when(localProducts.detail("L1")).thenReturn(new com.rsdp.officeproduct.importer.office.product.LocalProductModels.Detail(
            new com.rsdp.officeproduct.importer.office.product.LocalProductModels.Summary("L1", 2, "当前可见商品", "C", "F", "M"),
            List.of(), List.of(), false));
        when(bundles.update(any())).thenReturn(1);
        var item = service.save("B1", new Save("组合", 1, List.of(new Item(localId, "V1", 2)))).items().getFirst();
        assertFalse(item.available());
        assertEquals("当前可见商品", item.name());
        assertEquals("旧规格", item.variant());
        assertEquals(2, item.quantity());
    }

    @Test void listRechecksLegacySnapshotsAndDeduplicatesReferencedVariants() throws Exception {
        authenticate();
        String legacy = "[{\"rspuId\":\"OLD\",\"variantId\":\"V1\",\"quantity\":1,\"name\":\"旧商品\",\"variant\":\"旧规格\",\"imageUrl\":null}]";
        when(bundles.list("U1")).thenReturn(List.of(
            new OfficeProductBundleMapper.Row("B1", "U1", "一组", 1, legacy),
            new OfficeProductBundleMapper.Row("B2", "U1", "二组", 1, legacy)));
        when(office.currentProductVariant("OLD", "V1")).thenReturn(null);
        var result = service.list();
        assertFalse(result.getFirst().items().getFirst().available());
        assertEquals("旧商品", result.getFirst().items().getFirst().name());
        verify(office, times(1)).currentProductVariant("OLD", "V1");
        verify(bundles, never()).update(any());
    }

    @Test void accessDeniedExistingLocalItemIsRedactedButNewOneStillRejected() throws Exception {
        authenticate();
        previousRow("B1", 1, savedItemJson("OFFICE-LOCAL-L1", "V1", "旧名称", "旧规格"));
        when(localProducts.detail("L1")).thenThrow(new com.rsdp.officeproduct.importer.common.AccessDeniedException("未分配工作区"));
        when(bundles.update(any())).thenReturn(1);
        var result = service.save("B1", new Save("改名", 1, List.of(item("OFFICE-LOCAL-L1")))).items().getFirst();
        assertNull(result.name()); assertFalse(result.available());
        assertThrows(com.rsdp.officeproduct.importer.common.AccessDeniedException.class,
            () -> service.save("NEW", new Save("新增", 0, List.of(item("OFFICE-LOCAL-L1")))));
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
