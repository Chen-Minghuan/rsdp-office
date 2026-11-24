package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.dto.OfficeProjectFavoriteDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectFavoriteItem;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectFavoriteItemMapper;
import com.rsdp.officeproduct.importer.service.ProductQueryService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OfficeProjectFavoriteServiceTest {

    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final OfficeProjectAccessService accessService = mock(OfficeProjectAccessService.class);
    private final ProductQueryService productQueryService = mock(ProductQueryService.class);
    private final OfficeProjectFavoriteItemMapper itemMapper = mock(OfficeProjectFavoriteItemMapper.class);
    private final OfficeProjectFavoriteService service = new OfficeProjectFavoriteService(
        currentUserService, accessService, productQueryService, itemMapper);

    @Test
    void projectProductIdsAreScopedToCurrentProjectAndUser() {
        authenticate("U1");
        when(itemMapper.findProductIds("P-A", "U1")).thenReturn(List.of("C", "B"));

        assertEquals(List.of("C", "B"), service.listProjectFavoriteProductIds("P-A"));
        verify(accessService).requireMember("P-A");
        verify(itemMapper).findProductIds("P-A", "U1");
        verify(itemMapper, never()).findProductIds(eq("P-B"), anyString());
    }

    @Test
    void getFavoriteUsesCurrentUserRatherThanRequestOwnedIdentity() {
        authenticate("U1");
        when(productQueryService.getProduct("C")).thenReturn(new ProductDetailDto());
        OfficeProjectFavoriteItem item = new OfficeProjectFavoriteItem();
        item.setProjectId("P-A");
        item.setUserId("U1");
        item.setRspuId("C");
        when(itemMapper.findOne("P-A", "U1", "C")).thenReturn(item);

        OfficeProjectFavoriteDto result = service.getProjectFavorite("P-A", "C");

        assertTrue(result.selected());
        verify(itemMapper).findOne("P-A", "U1", "C");
    }

    @Test
    void saveSelectedProductIsIdempotentWithinCurrentProject() {
        authenticate("U1");
        when(productQueryService.getProduct("C")).thenReturn(new ProductDetailDto());

        OfficeProjectFavoriteDto result = service.saveProjectFavorite("P-A", "C", true);

        assertTrue(result.selected());
        verify(itemMapper).insertIgnore("P-A", "U1", "C");
        verify(itemMapper, never()).delete(anyString(), anyString(), anyString());
    }

    @Test
    void clearingSelectionOnlyRemovesCurrentProjectAndUserRelation() {
        authenticate("U1");
        when(productQueryService.getProduct("C")).thenReturn(new ProductDetailDto());

        OfficeProjectFavoriteDto result = service.saveProjectFavorite("P-A", "C", false);

        assertFalse(result.selected());
        verify(itemMapper).delete("P-A", "U1", "C");
        verify(itemMapper, never()).insertIgnore(anyString(), anyString(), anyString());
    }

    @Test
    void anotherMemberHasIndependentProjectFavoriteState() {
        authenticate("U2");
        when(productQueryService.getProduct("C")).thenReturn(new ProductDetailDto());
        when(itemMapper.findOne("P-A", "U2", "C")).thenReturn(null);

        assertFalse(service.getProjectFavorite("P-A", "C").selected());
        verify(itemMapper, never()).findOne("P-A", "U1", "C");
    }

    @Test
    void inaccessibleProjectCannotBeReadOrModified() {
        authenticate("U1");
        doThrow(new AccessDeniedException("无权访问该项目")).when(accessService).requireMember("P-B");

        assertThrows(AccessDeniedException.class, () -> service.listProjectFavoriteProductIds("P-B"));
        assertThrows(AccessDeniedException.class, () -> service.saveProjectFavorite("P-B", "C", true));
        verifyNoInteractions(itemMapper);
    }

    @Test
    void hiddenProductCannotBeAddedToProject() {
        authenticate("U1");
        when(productQueryService.getProduct("C")).thenThrow(new BusinessException("商品不可见"));

        assertThrows(BusinessException.class, () -> service.saveProjectFavorite("P-A", "C", true));
        verify(itemMapper, never()).insertIgnore(anyString(), anyString(), anyString());
    }

    @Test
    void blankProjectAndAnonymousUserAreRejected() {
        assertThrows(BusinessException.class, () -> service.listProjectFavoriteProductIds("  "));
        when(currentUserService.authEnabled()).thenReturn(true);
        when(currentUserService.currentUser()).thenReturn(null);
        assertThrows(AccessDeniedException.class, () -> service.listProjectFavoriteProductIds("P-A"));
        verifyNoInteractions(itemMapper);
    }

    @Test
    void projectFavoriteItemsAreScopedSortedAndMappedToProducts() {
        authenticate("U1");
        when(itemMapper.findItems("P-A", "U1")).thenReturn(List.of(
            projectItem("P-A", "U1", "C", "2026-11-20T10:00:00"),
            projectItem("P-A", "U1", "B", "2026-11-22T10:00:00")));
        when(productQueryService.listProducts(false)).thenReturn(List.of(
            product("B", "办公椅"), product("C", "会议桌"), product("D", "未收藏商品")));

        List<ProductListItemDto> result = service.listProjectFavoriteItems("P-A");

        assertEquals(List.of("B", "C"), result.stream().map(ProductListItemDto::getRspuId).toList());
        assertEquals("办公椅", result.get(0).getProductName());
        verify(accessService).requireMember("P-A");
        verify(itemMapper).findItems("P-A", "U1");
        verify(itemMapper, never()).findItems(eq("P-B"), anyString());
        verify(itemMapper, never()).findItems(anyString(), eq("U2"));
    }

    @Test
    void projectFavoriteItemsSkipProductsNotVisibleInCatalog() {
        authenticate("U1");
        when(itemMapper.findItems("P-A", "U1")).thenReturn(List.of(
            projectItem("P-A", "U1", "C", "2026-11-20T10:00:00"),
            projectItem("P-A", "U1", "B", "2026-11-22T10:00:00")));
        when(productQueryService.listProducts(false)).thenReturn(List.of(product("C", "会议桌")));

        List<ProductListItemDto> result = service.listProjectFavoriteItems("P-A");

        assertEquals(List.of("C"), result.stream().map(ProductListItemDto::getRspuId).toList());
    }

    @Test
    void inaccessibleProjectCannotListFavoriteItems() {
        authenticate("U1");
        doThrow(new AccessDeniedException("无权访问该项目")).when(accessService).requireMember("P-B");

        assertThrows(AccessDeniedException.class, () -> service.listProjectFavoriteItems("P-B"));
        verify(itemMapper, never()).findItems(anyString(), anyString());
    }

    @Test
    void anonymousUserCannotListProjectFavoriteItems() {
        when(currentUserService.authEnabled()).thenReturn(true);
        when(currentUserService.currentUser()).thenReturn(null);

        assertThrows(AccessDeniedException.class, () -> service.listProjectFavoriteItems("P-A"));
        verifyNoInteractions(itemMapper);
    }

    @Test
    void localDevModeUsesStableLocalUserForProjectFavorites() {
        when(currentUserService.authEnabled()).thenReturn(false);
        when(itemMapper.findProductIds("P-A", "LOCAL-DEV")).thenReturn(List.of("C"));

        assertEquals(List.of("C"), service.listProjectFavoriteProductIds("P-A"));
        verify(currentUserService, never()).currentUser();
        verify(accessService).requireMember("P-A");
    }

    private void authenticate(String userId) {
        OfficeUser user = new OfficeUser();
        user.setUserId(userId);
        user.setStatus("active");
        when(currentUserService.authEnabled()).thenReturn(true);
        when(currentUserService.currentUser()).thenReturn(user);
    }

    private OfficeProjectFavoriteItem projectItem(String projectId, String userId, String rspuId, String createdAt) {
        OfficeProjectFavoriteItem item = new OfficeProjectFavoriteItem();
        item.setProjectId(projectId);
        item.setUserId(userId);
        item.setRspuId(rspuId);
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
