package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.dto.OfficeFavoriteFolderDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeFavoriteFolder;
import com.rsdp.officeproduct.importer.office.entity.OfficeFavoriteItem;
import com.rsdp.officeproduct.importer.office.mapper.OfficeFavoriteFolderMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeFavoriteItemMapper;
import com.rsdp.officeproduct.importer.service.ProductQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OfficeFavoriteServiceTest {

    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final ProductQueryService productQueryService = mock(ProductQueryService.class);
    private final OfficeFavoriteFolderMapper folderMapper = mock(OfficeFavoriteFolderMapper.class);
    private final OfficeFavoriteItemMapper itemMapper = mock(OfficeFavoriteItemMapper.class);
    private final OfficeFavoriteService service = new OfficeFavoriteService(
        currentUserService, productQueryService, folderMapper, itemMapper);

    @Test
    void firstFolderRequestCreatesOnlyDefaultFolder() {
        authenticate("U1");
        OfficeFavoriteFolder defaultFolder = folder("F1", "U1", "默认收藏夹", true);
        when(folderMapper.selectOne(any(QueryWrapper.class))).thenReturn(null, defaultFolder);
        when(folderMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(defaultFolder));
        when(itemMapper.findByUserId("U1")).thenReturn(List.of());

        List<OfficeFavoriteFolderDto> result = service.listFolders(null);

        verify(folderMapper).insertDefaultFolder(anyString(), eq("U1"));
        assertEquals(1, result.size());
        assertEquals("默认收藏夹", result.get(0).getName());
        assertTrue(result.get(0).isDefaultFolder());
        assertEquals(0, result.get(0).getItemCount());
        assertFalse(result.get(0).isSelected());
    }

    @Test
    void folderListReportsCountsAndCurrentProductSelection() {
        authenticate("U1");
        OfficeFavoriteFolder defaultFolder = folder("F1", "U1", "默认收藏夹", true);
        OfficeFavoriteFolder customFolder = folder("F2", "U1", "我的收藏", false);
        when(folderMapper.selectOne(any(QueryWrapper.class))).thenReturn(defaultFolder);
        when(folderMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(defaultFolder, customFolder));
        when(itemMapper.findByUserId("U1")).thenReturn(List.of(
            item("F1", "P1"), item("F2", "P1"), item("F2", "P2")));

        List<OfficeFavoriteFolderDto> result = service.listFolders("P1");

        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getItemCount());
        assertTrue(result.get(0).isSelected());
        assertEquals(2, result.get(1).getItemCount());
        assertTrue(result.get(1).isSelected());
    }

    @Test
    void createFolderTrimsNameAndReturnsEmptyUnselectedFolder() {
        authenticate("U1");
        when(folderMapper.selectOne(any(QueryWrapper.class))).thenReturn(folder("F1", "U1", "默认收藏夹", true));

        OfficeFavoriteFolderDto result = service.createFolder("  我的收藏  ");

        ArgumentCaptor<OfficeFavoriteFolder> captor = ArgumentCaptor.forClass(OfficeFavoriteFolder.class);
        verify(folderMapper).insert(captor.capture());
        assertEquals("我的收藏", captor.getValue().getName());
        assertEquals("U1", captor.getValue().getUserId());
        assertFalse(captor.getValue().getDefaultFolder());
        assertEquals("我的收藏", result.getName());
        assertEquals(0, result.getItemCount());
        assertFalse(result.isSelected());
    }

    @Test
    void createFolderRejectsBlankName() {
        authenticate("U1");
        assertThrows(BusinessException.class, () -> service.createFolder("   "));
        verify(folderMapper, never()).insert(any(OfficeFavoriteFolder.class));
    }

    @Test
    void duplicateFolderNameBecomesBusinessError() {
        authenticate("U1");
        when(folderMapper.selectOne(any(QueryWrapper.class))).thenReturn(folder("F1", "U1", "默认收藏夹", true));
        when(folderMapper.insert(any(OfficeFavoriteFolder.class))).thenThrow(new DuplicateKeyException("duplicate"));

        assertThrows(BusinessException.class, () -> service.createFolder("我的收藏"));
    }

    @Test
    void saveAddsNewFoldersRemovesUncheckedAndKeepsUnchangedMembership() {
        authenticate("U1");
        when(productQueryService.getProduct("P1")).thenReturn(new ProductDetailDto());
        when(folderMapper.selectBatchIds(List.of("F2", "F3"))).thenReturn(List.of(
            folder("F2", "U1", "分组二", false), folder("F3", "U1", "分组三", false)));
        when(itemMapper.findByUserId("U1")).thenReturn(List.of(item("F1", "P1"), item("F3", "P1")));

        service.saveProductFolders("P1", List.of("F2", "F3"));

        org.mockito.InOrder order = inOrder(itemMapper);
        order.verify(itemMapper).lockProduct("U1", "P1");
        order.verify(itemMapper).findByUserId("U1");
        verify(itemMapper).delete("F1", "P1");
        verify(itemMapper).insertIgnore("F2", "P1");
        verify(itemMapper, never()).insertIgnore("F3", "P1");
        verify(itemMapper, never()).delete("F3", "P1");
    }

    @Test
    void emptySelectionRemovesProductFromAllCurrentUserFoldersOnly() {
        authenticate("U1");
        when(productQueryService.getProduct("P1")).thenReturn(new ProductDetailDto());
        when(itemMapper.findByUserId("U1")).thenReturn(List.of(item("F1", "P1"), item("F2", "P1"), item("F2", "P2")));

        service.saveProductFolders("P1", List.of());

        verify(itemMapper).delete("F1", "P1");
        verify(itemMapper).delete("F2", "P1");
        verify(itemMapper, never()).delete("F2", "P2");
        verify(folderMapper, never()).selectBatchIds(anyCollection());
        verify(itemMapper, never()).insertIgnore(anyString(), anyString());
    }

    @Test
    void saveRejectsFolderOwnedByAnotherUser() {
        authenticate("U1");
        when(productQueryService.getProduct("P1")).thenReturn(new ProductDetailDto());
        when(folderMapper.selectBatchIds(List.of("F-OTHER"))).thenReturn(List.of(folder("F-OTHER", "U2", "他人分组", false)));

        assertThrows(AccessDeniedException.class, () -> service.saveProductFolders("P1", List.of("F-OTHER")));
        verify(itemMapper, never()).insertIgnore(anyString(), anyString());
        verify(itemMapper, never()).delete(anyString(), anyString());
    }

    @Test
    void favoriteProductIdsAreDistinctAndSorted() {
        authenticate("U1");
        when(itemMapper.findByUserId("U1")).thenReturn(List.of(item("F2", "P2"), item("F1", "P1"), item("F2", "P2")));

        assertEquals(List.of("P1", "P2"), service.listFavoriteProductIds());
    }

    @Test
    void favoriteItemsListAllFoldersDistinctAndSortedByLatestFavoriteTime() {
        authenticate("U1");
        when(itemMapper.findByUserId("U1")).thenReturn(List.of(
            itemAt("F1", "P1", "2026-11-20T10:00:00"),
            itemAt("F2", "P1", "2026-11-22T10:00:00"),
            itemAt("F1", "P2", "2026-11-21T10:00:00")));
        when(productQueryService.listProducts(false)).thenReturn(List.of(
            product("P1", "职员桌"), product("P2", "办公椅"), product("P3", "未收藏商品")));

        List<ProductListItemDto> result = service.listFavoriteItems(null);

        assertEquals(List.of("P1", "P2"), result.stream().map(ProductListItemDto::getRspuId).toList());
        assertEquals("职员桌", result.get(0).getProductName());
        assertEquals("办公椅", result.get(1).getProductName());
    }

    @Test
    void favoriteItemsCanBeFilteredByFolder() {
        authenticate("U1");
        when(folderMapper.selectById("F1")).thenReturn(folder("F1", "U1", "默认收藏夹", true));
        when(itemMapper.findByUserId("U1")).thenReturn(List.of(
            itemAt("F1", "P1", "2026-11-20T10:00:00"),
            itemAt("F2", "P1", "2026-11-22T10:00:00"),
            itemAt("F1", "P2", "2026-11-21T10:00:00")));
        when(productQueryService.listProducts(false)).thenReturn(List.of(
            product("P1", "职员桌"), product("P2", "办公椅")));

        List<ProductListItemDto> result = service.listFavoriteItems("F1");

        assertEquals(List.of("P2", "P1"), result.stream().map(ProductListItemDto::getRspuId).toList());
    }

    @Test
    void favoriteItemsRejectFolderOwnedByAnotherUser() {
        authenticate("U1");
        when(folderMapper.selectById("F-OTHER")).thenReturn(folder("F-OTHER", "U2", "他人分组", false));

        assertThrows(AccessDeniedException.class, () -> service.listFavoriteItems("F-OTHER"));
        verify(itemMapper, never()).findByUserId(anyString());
    }

    @Test
    void favoriteItemsRejectUnknownFolder() {
        authenticate("U1");
        when(folderMapper.selectById("F-X")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.listFavoriteItems("F-X"));
        verify(itemMapper, never()).findByUserId(anyString());
    }

    @Test
    void favoriteItemsSkipProductsNotVisibleInCatalog() {
        authenticate("U1");
        when(itemMapper.findByUserId("U1")).thenReturn(List.of(
            itemAt("F1", "P1", "2026-11-20T10:00:00"),
            itemAt("F1", "P2", "2026-11-21T10:00:00")));
        when(productQueryService.listProducts(false)).thenReturn(List.of(product("P1", "职员桌")));

        List<ProductListItemDto> result = service.listFavoriteItems(null);

        assertEquals(List.of("P1"), result.stream().map(ProductListItemDto::getRspuId).toList());
    }

    @Test
    void deleteFolderRemovesItemsAndFolder() {
        authenticate("U1");
        when(folderMapper.selectById("F2")).thenReturn(folder("F2", "U1", "会议室方案", false));

        service.deleteFolder("F2");

        org.mockito.InOrder order = inOrder(itemMapper, folderMapper);
        order.verify(itemMapper).deleteByFolderId("F2");
        order.verify(folderMapper).deleteById("F2");
    }

    @Test
    void deleteFolderRejectsDefaultFolder() {
        authenticate("U1");
        when(folderMapper.selectById("F1")).thenReturn(folder("F1", "U1", "默认收藏夹", true));

        assertThrows(BusinessException.class, () -> service.deleteFolder("F1"));
        verify(itemMapper, never()).deleteByFolderId(anyString());
        verify(folderMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteFolderRejectsOtherUsersFolder() {
        authenticate("U1");
        when(folderMapper.selectById("F-OTHER")).thenReturn(folder("F-OTHER", "U2", "他人分组", false));

        assertThrows(AccessDeniedException.class, () -> service.deleteFolder("F-OTHER"));
        verify(itemMapper, never()).deleteByFolderId(anyString());
        verify(folderMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteFolderRejectsUnknownFolder() {
        authenticate("U1");
        when(folderMapper.selectById("F-X")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.deleteFolder("F-X"));
        verify(itemMapper, never()).deleteByFolderId(anyString());
    }

    @Test
    void anonymousUserCannotDeleteFolder() {
        when(currentUserService.authEnabled()).thenReturn(true);
        when(currentUserService.currentUser()).thenReturn(null);

        assertThrows(AccessDeniedException.class, () -> service.deleteFolder("F2"));
        verifyNoInteractions(folderMapper, itemMapper);
    }

    @Test
    void localDevModeUsesStableLocalUserForPersonalFavorites() {
        when(currentUserService.authEnabled()).thenReturn(false);
        when(currentUserService.ensureLocalFavoriteUser()).thenReturn("LOCAL-DEV");
        when(itemMapper.findByUserId("LOCAL-DEV")).thenReturn(List.of(item("F1", "P1")));

        assertEquals(List.of("P1"), service.listFavoriteProductIds());
        verify(currentUserService, never()).currentUser();
        verify(currentUserService).ensureLocalFavoriteUser();
    }

    @Test
    void anonymousUserCannotReadFavoriteItems() {
        when(currentUserService.authEnabled()).thenReturn(true);
        when(currentUserService.currentUser()).thenReturn(null);

        assertThrows(AccessDeniedException.class, () -> service.listFavoriteItems(null));
        verifyNoInteractions(folderMapper, itemMapper);
    }

    @Test
    void anonymousUserCannotReadFolders() {
        when(currentUserService.authEnabled()).thenReturn(true);
        when(currentUserService.currentUser()).thenReturn(null);
        assertThrows(AccessDeniedException.class, () -> service.listFolders(null));
        verifyNoInteractions(folderMapper, itemMapper);
    }

    private void authenticate(String userId) {
        OfficeUser user = new OfficeUser();
        user.setUserId(userId);
        user.setStatus("active");
        when(currentUserService.authEnabled()).thenReturn(true);
        when(currentUserService.currentUser()).thenReturn(user);
    }

    private OfficeFavoriteFolder folder(String folderId, String userId, String name, boolean defaultFolder) {
        OfficeFavoriteFolder folder = new OfficeFavoriteFolder();
        folder.setFolderId(folderId);
        folder.setUserId(userId);
        folder.setName(name);
        folder.setDefaultFolder(defaultFolder);
        return folder;
    }

    private OfficeFavoriteItem item(String folderId, String rspuId) {
        OfficeFavoriteItem item = new OfficeFavoriteItem();
        item.setFolderId(folderId);
        item.setRspuId(rspuId);
        return item;
    }

    private OfficeFavoriteItem itemAt(String folderId, String rspuId, String createdAt) {
        OfficeFavoriteItem item = item(folderId, rspuId);
        item.setCreatedAt(LocalDateTime.parse(createdAt));
        return item;
    }

    private ProductListItemDto product(String rspuId, String productName) {
        ProductListItemDto product = new ProductListItemDto();
        product.setRspuId(rspuId);
        product.setProductName(productName);
        return product;
    }
}
