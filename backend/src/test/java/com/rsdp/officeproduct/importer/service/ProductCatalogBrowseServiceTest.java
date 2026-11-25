package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.CatalogProductPageDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.product.LocalCatalogReader;
import com.rsdp.officeproduct.importer.office.product.LocalProductModels.CatalogItem;
import com.rsdp.officeproduct.importer.office.service.CurrentUserService;
import com.rsdp.officeproduct.importer.office.service.SharedCatalogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductCatalogBrowseServiceTest {
    private final CurrentUserService users = mock(CurrentUserService.class);
    private final LocalCatalogReader local = mock(LocalCatalogReader.class);
    private final SharedCatalogService visibility = mock(SharedCatalogService.class);
    private final RsdpCatalogGateway rsdp = mock(RsdpCatalogGateway.class);
    private final ProductCatalogBrowseService service = new ProductCatalogBrowseService(users, visibility, local, rsdp);

    @ParameterizedTest
    @CsvSource({"23, 57, 10", "57, 23, 10", "1, 24, 5", "24, 1, 5", "0, 22, 10", "22, 0, 10", "3, 3, 1"})
    void readsConsecutiveMixedPagesWithoutMissingOrRepeatingRows(int localCount, int sharedCount, int size) {
        seedLocal(localCount);
        when(visibility.canViewSharedCatalog(any())).thenReturn(true);
        when(rsdp.pageProducts(eq("full"), anyInt(), eq(size), eq(""), eq(""), isNull())).thenAnswer(call -> {
            int page = call.getArgument(1);
            int from = Math.min(sharedCount, (page - 1) * size);
            List<ProductListItemDto> rows = IntStream.range(from, Math.min(sharedCount, from + size))
                .mapToObj(this::sharedRow).toList();
            return new CatalogProductPageDto(sharedCount, sharedCount, sharedCount, page, size, rows);
        });

        List<String> expected = new ArrayList<>();
        for (int index = 0; index < Math.max(localCount, sharedCount); index++) {
            if (index < localCount) expected.add("local-" + index);
            if (index < sharedCount) expected.add("shared-" + index);
        }
        for (int page = 1; (page - 1) * size < expected.size(); page++) {
            var result = service.page(page, size, "", "", "", "");
            int offset = (page - 1) * size;
            assertThat(result.total()).isEqualTo(expected.size());
            assertThat(result.rows()).extracting(row -> row.local() == null ? row.shared().getRspuId() : row.local().id())
                .containsExactlyElementsOf(expected.subList(offset, Math.min(expected.size(), offset + size)));
        }
        assertThat(service.page(expected.size() / size + 2, size, "", "", "", "").rows()).isEmpty();
    }

    @Test
    void closingPublicCatalogLeavesLocalRowsAndFacetsWithoutCallingRsdp() {
        seedLocal(2);
        when(local.categories("WORKSPACE")).thenReturn(List.of("TB"));
        when(local.colors("WORKSPACE")).thenReturn(List.of("胡桃色"));

        var page = service.page(1, 20, "", "", "", "");
        assertThat(page.rows()).extracting(row -> row.local().id()).containsExactly("local-0", "local-1");
        assertThat(page.activeTotal()).isZero();
        assertThat(service.facets().localCategories()).containsExactly("TB");
        assertThat(service.facets().sharedCategories()).isEmpty();
        verifyNoInteractions(rsdp);
    }

    @Test
    void anUnavailablePublicCatalogStillReturnsPrivateRowsWithAStatusFlag() {
        seedLocal(2);
        when(visibility.canViewSharedCatalog(any())).thenReturn(true);
        when(rsdp.pageProducts("full", 1, 20, "", "", null)).thenThrow(new BusinessException("目录暂不可用"));

        var page = service.page(1, 20, "", "", "", "");
        assertThat(page.sharedUnavailable()).isTrue();
        assertThat(page.rows()).extracting(row -> row.local().id()).containsExactly("local-0", "local-1");
    }

    private void seedLocal(int count) {
        OfficeUser user = new OfficeUser();
        user.setWorkspaceId("WORKSPACE");
        user.setRole("USER");
        when(users.currentUser()).thenReturn(user);
        when(local.counts("WORKSPACE", "", "", "")).thenReturn(new LocalCatalogReader.Counts(count, count));
        when(local.rows(eq("WORKSPACE"), eq(""), eq(""), eq(""), anyLong(), anyInt())).thenAnswer(call -> {
            long offset = call.getArgument(4);
            int size = call.getArgument(5);
            return IntStream.range((int) offset, (int) offset + size).mapToObj(index ->
                new CatalogItem("local-" + index, 1, "本地商品", "TB", "", "MODEL-" + index, null)).toList();
        });
    }

    private ProductListItemDto sharedRow(int index) {
        ProductListItemDto row = new ProductListItemDto();
        row.setRspuId("shared-" + index);
        return row;
    }
}
