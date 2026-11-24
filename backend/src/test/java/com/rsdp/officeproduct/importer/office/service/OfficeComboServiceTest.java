package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.dto.OfficeComboDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeCombo;
import com.rsdp.officeproduct.importer.office.entity.OfficeComboItem;
import com.rsdp.officeproduct.importer.office.mapper.OfficeComboItemMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeComboMapper;
import com.rsdp.officeproduct.importer.service.ProductQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OfficeComboServiceTest {

    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final ProductQueryService productQueryService = mock(ProductQueryService.class);
    private final OfficeComboMapper comboMapper = mock(OfficeComboMapper.class);
    private final OfficeComboItemMapper itemMapper = mock(OfficeComboItemMapper.class);
    private final OfficeComboService service = new OfficeComboService(
        currentUserService, productQueryService, comboMapper, itemMapper);

    @Test
    void listCombosReturnsItemsWithProductInfoAndAvailability() {
        authenticate("U1");
        when(comboMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(combo("C1", "U1", "会议组合")));
        when(itemMapper.findByUserId("U1")).thenReturn(List.of(item("C1", "P1", 0), item("C1", "P2", 1)));
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
        when(comboMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(combo("C1", "U1", "会议组合")));
        when(itemMapper.findByUserId("U1")).thenReturn(List.of(item("C1", "P-GONE", 0)));
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
    void saveItemsReplacesItemsInRequestedOrder() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U1", "会议组合"));
        when(productQueryService.getProduct(anyString())).thenReturn(new ProductDetailDto());

        service.saveComboItems("C1", List.of("P2", "P1"));

        org.mockito.InOrder order = inOrder(itemMapper);
        order.verify(itemMapper).lockCombo("U1", "C1");
        order.verify(itemMapper).deleteByComboId("C1");
        ArgumentCaptor<OfficeComboItem> captor = ArgumentCaptor.forClass(OfficeComboItem.class);
        verify(itemMapper, times(2)).insert(captor.capture());
        assertEquals("P2", captor.getAllValues().get(0).getRspuId());
        assertEquals(0, captor.getAllValues().get(0).getSortOrder());
        assertEquals("P1", captor.getAllValues().get(1).getRspuId());
        assertEquals(1, captor.getAllValues().get(1).getSortOrder());
    }

    @Test
    void saveItemsRejectsMoreThanFiveProducts() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U1", "会议组合"));

        assertThrows(BusinessException.class,
            () -> service.saveComboItems("C1", List.of("P1", "P2", "P3", "P4", "P5", "P6")));
        verify(itemMapper, never()).insert(any(OfficeComboItem.class));
        verify(itemMapper, never()).deleteByComboId(anyString());
    }

    @Test
    void saveItemsDeduplicatesBeforeApplyingLimit() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U1", "会议组合"));
        when(productQueryService.getProduct(anyString())).thenReturn(new ProductDetailDto());

        service.saveComboItems("C1", List.of("P1", "P1", "P2", "P2", "P3"));

        ArgumentCaptor<OfficeComboItem> captor = ArgumentCaptor.forClass(OfficeComboItem.class);
        verify(itemMapper, times(3)).insert(captor.capture());
        assertEquals(List.of("P1", "P2", "P3"),
            captor.getAllValues().stream().map(OfficeComboItem::getRspuId).toList());
    }

    @Test
    void saveItemsRejectsBlankProductId() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U1", "会议组合"));

        assertThrows(BusinessException.class, () -> service.saveComboItems("C1", List.of(" ")));
        verify(itemMapper, never()).insert(any(OfficeComboItem.class));
    }

    @Test
    void saveItemsRejectsComboOwnedByAnotherUser() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U2", "他人组合"));

        assertThrows(AccessDeniedException.class, () -> service.saveComboItems("C1", List.of("P1")));
        verify(itemMapper, never()).insert(any(OfficeComboItem.class));
    }

    @Test
    void deleteComboRemovesItemsThenCombo() {
        authenticate("U1");
        when(comboMapper.selectById("C1")).thenReturn(combo("C1", "U1", "会议组合"));

        service.deleteCombo("C1");

        org.mockito.InOrder order = inOrder(itemMapper, comboMapper);
        order.verify(itemMapper).deleteByComboId("C1");
        order.verify(comboMapper).deleteById("C1");
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

    private OfficeCombo combo(String comboId, String userId, String name) {
        OfficeCombo combo = new OfficeCombo();
        combo.setComboId(comboId);
        combo.setUserId(userId);
        combo.setName(name);
        return combo;
    }

    private OfficeComboItem item(String comboId, String rspuId, int sortOrder) {
        OfficeComboItem item = new OfficeComboItem();
        item.setComboId(comboId);
        item.setRspuId(rspuId);
        item.setSortOrder(sortOrder);
        return item;
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
