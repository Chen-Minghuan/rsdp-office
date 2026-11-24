package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.dto.OfficeComboDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeCombo;
import com.rsdp.officeproduct.importer.office.mapper.OfficeComboMapper;
import com.rsdp.officeproduct.importer.service.ProductQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OfficeComboServiceTest {

    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final ProductQueryService productQueryService = mock(ProductQueryService.class);
    private final OfficeComboMapper comboMapper = mock(OfficeComboMapper.class);
    private final OfficeComboService service = new OfficeComboService(
        currentUserService, productQueryService, comboMapper);

    @Test
    void listCombosReturnsItemsWithProductInfoAndAvailability() {
        authenticate("U1");
        when(comboMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(combo("C1", "U1", "会议组合", "P1", "P2")));
        when(productQueryService.listProducts(false)).thenReturn(List.of(product("P1", "active"), product("P2", "archived")));

        List<OfficeComboDto> result = service.listCombos();

        assertEquals(1, result.size());
        assertEquals("会议组合", result.get(0).getName());
        assertEquals(2, result.get(0).getItems().size());
        assertEquals("P1", result.get(0).getItems().get(0).getRspuId());
        assertTrue(result.get(0).getItems().get(0).isAvailable());
        assertFalse(result.get(0).getItems().get(1).isAvailable());
    }

    @Test
    void listCombosMarksMissingProductAsUnavailable() {
        authenticate("U1");
        when(comboMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(combo("C1", "U1", "会议组合", "P-GONE")));
        when(productQueryService.listProducts(false)).thenReturn(List.of());

        List<OfficeComboDto> result = service.listCombos();

        assertFalse(result.get(0).getItems().get(0).isAvailable());
    }

    @Test
    void createComboTrimsNameAndReturnsEmptyCombo() {
        authenticate("U1");
        when(comboMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        OfficeComboDto result = service.createCombo("  会议组合  ");

        org.mockito.InOrder order = inOrder(comboMapper);
        order.verify(comboMapper).lockUser("U1");
        order.verify(comboMapper).selectCount(any(QueryWrapper.class));
        ArgumentCaptor<OfficeCombo> captor = ArgumentCaptor.forClass(OfficeCombo.class);
        verify(comboMapper).insert(captor.capture());
        assertEquals("会议组合", captor.getValue().getName());
        assertEquals("U1", captor.getValue().getUserId());
        assertTrue(captor.getValue().getRspuIds().isEmpty());
        assertTrue(result.getItems().isEmpty());
    }

    @Test
    void createComboRejectsBlankName() {
        authenticate("U1");
        assertThrows(BusinessException.class, () -> service.createCombo("   "));
        verify(comboMapper, never()).insert(any(OfficeCombo.class));
    }

    @Test
    void createComboRejectsTooLongName() {
        authenticate("U1");
        assertThrows(BusinessException.class, () -> service.createCombo("很".repeat(51)));
        verify(comboMapper, never()).insert(any(OfficeCombo.class));
    }

    @Test
    void createComboRejectsEleventhCombo() {
        authenticate("U1");
        when(comboMapper.selectCount(any(QueryWrapper.class))).thenReturn(10L);

        assertThrows(BusinessException.class, () -> service.createCombo("新组合"));
        verify(comboMapper, never()).insert(any(OfficeCombo.class));
    }

    @Test
    void duplicateComboNameBecomesBusinessError() {
        authenticate("U1");
        when(comboMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(comboMapper.insert(any(OfficeCombo.class))).thenThrow(new DuplicateKeyException("duplicate"));

        assertThrows(BusinessException.class, () -> service.createCombo("会议组合"));
    }

    @Test
    void renameComboUpdatesOwnedCombo() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U1", "旧名称"));

        service.renameCombo("C1", "新名称");

        ArgumentCaptor<OfficeCombo> captor = ArgumentCaptor.forClass(OfficeCombo.class);
        verify(comboMapper).updateById(captor.capture());
        assertEquals("新名称", captor.getValue().getName());
    }

    @Test
    void renameRejectsComboOwnedByAnotherUser() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U2", "他人组合"));

        assertThrows(AccessDeniedException.class, () -> service.renameCombo("C1", "新名称"));
        verify(comboMapper, never()).updateById(any(OfficeCombo.class));
    }

    @Test
    void renameRejectsMissingCombo() {
        authenticate("U1");
        when(comboMapper.selectById("C-X")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.renameCombo("C-X", "新名称"));
    }

    @Test
    void addItemsLocksThenAppendsNewIdsInOrder() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U1", "会议组合", "P1"));
        when(productQueryService.getProduct(anyString())).thenReturn(new ProductDetailDto());

        service.addComboItems("C1", List.of("P2", "P1", "P3"));

        org.mockito.InOrder order = inOrder(comboMapper);
        order.verify(comboMapper).lockCombo("U1", "C1");
        order.verify(comboMapper).selectById("C1");
        order.verify(comboMapper).updateById(any(OfficeCombo.class));
        ArgumentCaptor<OfficeCombo> captor = ArgumentCaptor.forClass(OfficeCombo.class);
        verify(comboMapper).updateById(captor.capture());
        assertEquals(List.of("P1", "P2", "P3"), captor.getValue().getRspuIds());
        // 已在组内的 P1 不属于本次新增，不重复校验也不重复保存
        verify(productQueryService).getProduct("P2");
        verify(productQueryService).getProduct("P3");
        verify(productQueryService, never()).getProduct("P1");
    }

    @Test
    void addItemsKeepsInvalidExistingItemsAndValidatesOnlyNewOnes() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U1", "会议组合", "P-GONE"));
        when(productQueryService.getProduct("P2")).thenReturn(new ProductDetailDto());

        service.addComboItems("C1", List.of("P2"));

        ArgumentCaptor<OfficeCombo> captor = ArgumentCaptor.forClass(OfficeCombo.class);
        verify(comboMapper).updateById(captor.capture());
        assertEquals(List.of("P-GONE", "P2"), captor.getValue().getRspuIds());
        verify(productQueryService, never()).getProduct("P-GONE");
    }

    @Test
    void consecutiveAddsBothKeepItems() {
        authenticate("U1");
        OfficeCombo stored = combo("C1", "U1", "会议组合", "P1");
        when(comboMapper.selectById("C1")).thenReturn(stored);
        when(productQueryService.getProduct(anyString())).thenReturn(new ProductDetailDto());

        service.addComboItems("C1", List.of("P2"));
        service.addComboItems("C1", List.of("P3"));

        assertEquals(List.of("P1", "P2", "P3"), stored.getRspuIds());
        verify(comboMapper, times(2)).updateById(stored);
    }

    @Test
    void addItemsRejectsMoreThanFiveProducts() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U1", "会议组合", "P1", "P2", "P3", "P4"));

        assertThrows(BusinessException.class, () -> service.addComboItems("C1", List.of("P5", "P6")));
        verify(comboMapper, never()).updateById(any(OfficeCombo.class));
    }

    @Test
    void addItemsRejectsBlankProductId() {
        authenticate("U1");
        assertThrows(BusinessException.class, () -> service.addComboItems("C1", List.of(" ")));
        verify(comboMapper, never()).updateById(any(OfficeCombo.class));
    }

    @Test
    void addItemsRejectsNullProductList() {
        authenticate("U1");
        assertThrows(BusinessException.class, () -> service.addComboItems("C1", null));
        verify(comboMapper, never()).updateById(any(OfficeCombo.class));
    }

    @Test
    void addItemsRejectsComboOwnedByAnotherUser() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U2", "他人组合"));

        assertThrows(AccessDeniedException.class, () -> service.addComboItems("C1", List.of("P1")));
        verify(comboMapper, never()).updateById(any(OfficeCombo.class));
    }

    @Test
    void addItemsSkipsWriteWhenNothingNew() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U1", "会议组合", "P1"));

        service.addComboItems("C1", List.of("P1"));

        verify(comboMapper, never()).updateById(any(OfficeCombo.class));
        verify(productQueryService, never()).getProduct(anyString());
    }

    @Test
    void removeItemFiltersWithoutProductValidation() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U1", "会议组合", "P1", "P-GONE", "P2"));

        service.removeComboItem("C1", "P2");

        ArgumentCaptor<OfficeCombo> captor = ArgumentCaptor.forClass(OfficeCombo.class);
        verify(comboMapper).updateById(captor.capture());
        assertEquals(List.of("P1", "P-GONE"), captor.getValue().getRspuIds());
        verify(productQueryService, never()).getProduct(anyString());
    }

    @Test
    void removeItemRejectsComboOwnedByAnotherUser() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U2", "他人组合"));

        assertThrows(AccessDeniedException.class, () -> service.removeComboItem("C1", "P1"));
        verify(comboMapper, never()).updateById(any(OfficeCombo.class));
    }

    @Test
    void deleteComboRemovesRow() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U1", "会议组合"));

        service.deleteCombo("C1");

        verify(comboMapper).deleteById("C1");
    }

    @Test
    void deleteRejectsComboOwnedByAnotherUser() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U2", "他人组合"));

        assertThrows(AccessDeniedException.class, () -> service.deleteCombo("C1"));
        verify(comboMapper, never()).deleteById(anyString());
    }

    private void authenticate(String userId) {
        when(currentUserService.authEnabled()).thenReturn(true);
        OfficeUser user = new OfficeUser();
        user.setUserId(userId);
        when(currentUserService.currentUser()).thenReturn(user);
    }

    private OfficeCombo combo(String comboId, String userId, String name, String... rspuIds) {
        OfficeCombo combo = new OfficeCombo();
        combo.setComboId(comboId);
        combo.setUserId(userId);
        combo.setName(name);
        combo.setRspuIds(new ArrayList<>(List.of(rspuIds)));
        return combo;
    }

    private ProductListItemDto product(String rspuId, String status) {
        ProductListItemDto product = new ProductListItemDto();
        product.setRspuId(rspuId);
        product.setProductName("商品" + rspuId);
        product.setRetailPrice(new BigDecimal("100.00"));
        product.setStatus(status);
        return product;
    }
}
