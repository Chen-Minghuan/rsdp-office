package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.CatalogProductPageDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.product.LocalCatalogReader;
import com.rsdp.officeproduct.importer.office.product.LocalProductModels.CatalogItem;
import com.rsdp.officeproduct.importer.office.product.LocalProductService;
import com.rsdp.officeproduct.importer.office.service.CurrentUserService;
import com.rsdp.officeproduct.importer.office.service.SharedCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ProductCatalogBrowseService {
    private static final String LOCAL_CATEGORY = "local:";
    private static final String SHARED_CATEGORY = "shared:";
    private final CurrentUserService users;
    private final SharedCatalogService visibility;
    private final LocalCatalogReader local;
    private final RsdpCatalogGateway rsdp;
    private SharedSnapshot sharedSnapshot;

    public record Row(CatalogItem local, ProductListItemDto shared) {}
    public record Page(long total, long catalogTotal, long activeTotal, long localTotal, boolean canManageLocal, boolean sharedUnavailable,
                       List<Row> rows) {}
    public record Facets(List<String> localCategories, List<String> sharedCategories, List<String> colors,
                         boolean sharedUnavailable) {}
    private record SharedWindow(long total, long catalogTotal, long activeTotal, boolean unavailable,
                                List<ProductListItemDto> rows) {
        private static SharedWindow empty(boolean unavailable) {
            return new SharedWindow(0, 0, 0, unavailable, List.of());
        }
    }
    private record SharedSnapshot(long expiresAt, List<ProductListItemDto> products) {}

    public Page page(int page, int size, String search, String status, String category, String color) {
        if (page < 1 || size < 1 || size > 100 || search.length() > 120 || category.length() > 1000 || color.length() > 256
                || !List.of("", "active", "other").contains(status)
                || !(category.isEmpty() || category.startsWith(LOCAL_CATEGORY) || category.startsWith(SHARED_CATEGORY))) {
            throw new BusinessException("商品目录分页或筛选参数无效");
        }
        OfficeUser user = users.currentUser();
        String workspace = user == null ? null : user.getWorkspaceId();
        String keyword = search.strip();
        String localCategory = category.startsWith(LOCAL_CATEGORY) ? category.substring(LOCAL_CATEGORY.length()) : "";
        boolean matchLocal = status.isEmpty() && !category.startsWith(SHARED_CATEGORY);
        LocalCatalogReader.Counts localCounts = workspace == null ? new LocalCatalogReader.Counts(0, 0)
            : local.counts(workspace, keyword, localCategory, color);
        long localMatches = matchLocal ? localCounts.matching() : 0;
        long start = (page - 1L) * size;
        SharedWindow shared = visibility.canViewSharedCatalog(user)
            ? sharedWindow(start, size, localMatches, keyword, status, category, color) : SharedWindow.empty(false);
        long total = localMatches + shared.total();
        long end = Math.min(start + size, total);
        long localStart = localBefore(Math.min(start, total), localMatches, shared.total());
        int localLimit = (int) (localBefore(end, localMatches, shared.total()) - localStart);
        List<CatalogItem> localRows = localLimit > 0
            ? local.rows(workspace, keyword, localCategory, color, localStart, localLimit) : List.of();
        return new Page(total, localCounts.total() + shared.catalogTotal(), shared.activeTotal(), localCounts.total(),
            LocalProductService.canWrite(user), shared.unavailable(), merge(localRows, shared.rows(), start));
    }

    public Facets facets() {
        OfficeUser user = users.currentUser();
        String workspace = user == null ? null : user.getWorkspaceId();
        List<String> localCategories = workspace == null ? List.of() : local.categories(workspace);
        List<String> localColors = workspace == null ? List.of() : local.colors(workspace);
        if (!visibility.canViewSharedCatalog(user)) return new Facets(localCategories, List.of(), localColors, false);

        try {
            List<ProductListItemDto> shared = sharedProducts();
            List<String> sharedCategories = shared.stream().map(this::categoryPath)
                .filter(value -> !value.isEmpty()).distinct().toList();
            List<String> colors = Stream.concat(localColors.stream(), shared.stream()
                .map(ProductListItemDto::getColorPrimaryName).filter(Objects::nonNull).map(String::strip))
                .filter(value -> !value.isEmpty()).distinct().toList();
            return new Facets(localCategories, sharedCategories, colors, false);
        } catch (BusinessException | AccessDeniedException error) {
            return new Facets(localCategories, List.of(), localColors, true);
        }
    }

    private SharedWindow sharedWindow(long start, int size, long localMatches, String search,
                                      String status, String category, String color) {
        try {
            if (!category.isEmpty() || !color.isEmpty()) {
                return filteredSharedWindow(start, size, localMatches, search, status, category, color);
            }

            // Start with the likely downstream page. If the requested window crosses its boundary, read one more page.
            long estimatedStart = start - Math.min(localMatches, (start + 1) / 2);
            CatalogProductPageDto seed = rsdp.pageProducts("full", (int) (estimatedStart / size) + 1, size, search, status, null);
            long end = Math.min(start + size, localMatches + seed.total());
            long sharedStart = sharedBefore(Math.min(start, end), localMatches, seed.total());
            long sharedEnd = sharedBefore(end, localMatches, seed.total());
            List<ProductListItemDto> rows = new ArrayList<>();
            for (long offset = sharedStart; offset < sharedEnd;) {
                int sourcePage = (int) (offset / size) + 1;
                List<ProductListItemDto> pageRows = sourcePage == seed.page() ? seed.rows()
                    : rsdp.pageProducts("full", sourcePage, size, search, status, null).rows();
                int from = (int) (offset % size);
                int to = Math.min(pageRows.size(), from + (int) (sharedEnd - offset));
                if (to <= from) break;
                rows.addAll(pageRows.subList(from, to));
                offset += to - from;
                if (to < size) break;
            }
            return new SharedWindow(seed.total(), seed.catalogTotal(), seed.activeTotal(), false, rows);
        } catch (BusinessException | AccessDeniedException error) {
            return SharedWindow.empty(true);
        }
    }

    private SharedWindow filteredSharedWindow(long start, int size, long localMatches, String search,
                                              String status, String category, String color) {
        List<ProductListItemDto> all = sharedProducts();
        long active = all.stream().filter(row -> "active".equals(row.getStatus())).count();
        String keyword = search.toLowerCase(Locale.ROOT);
        String sharedCategory = category.startsWith(SHARED_CATEGORY) ? category.substring(SHARED_CATEGORY.length()) : "";
        List<ProductListItemDto> matches = category.startsWith(LOCAL_CATEGORY) ? List.of() : all.stream()
            .filter(row -> status.isEmpty() || "active".equals(status) == "active".equals(row.getStatus()))
            .filter(row -> sharedCategory.isEmpty() || sharedCategory.equals(categoryPath(row)))
            .filter(row -> color.isEmpty() || row.getColorPrimaryName() != null && color.equals(row.getColorPrimaryName().strip()))
            .filter(row -> keyword.isEmpty() || Stream.of(row.getProductName(), row.getRspuCode(), row.getExternalCode(), row.getRspuId())
                .anyMatch(value -> value != null && value.toLowerCase(Locale.ROOT).contains(keyword)))
            .toList();
        long end = Math.min(start + size, localMatches + matches.size());
        int from = (int) sharedBefore(Math.min(start, end), localMatches, matches.size());
        int to = (int) sharedBefore(end, localMatches, matches.size());
        return new SharedWindow(matches.size(), all.size(), active, false, matches.subList(from, to));
    }

    private synchronized List<ProductListItemDto> sharedProducts() {
        long now = System.nanoTime();
        if (sharedSnapshot == null || now - sharedSnapshot.expiresAt() >= 0) {
            List<ProductListItemDto> products = List.copyOf(rsdp.listSharedCatalogForBrowse());
            sharedSnapshot = new SharedSnapshot(System.nanoTime() + TimeUnit.SECONDS.toNanos(30), products);
        }
        return sharedSnapshot.products();
    }

    private String categoryPath(ProductListItemDto row) {
        List<String> path = row.getCategoryPath();
        return path == null ? "" : String.join(" / ", path.stream().map(String::strip).filter(value -> !value.isEmpty()).toList());
    }

    /** The two feeds alternate until one runs out; after that, the rest of the larger feed follows. */
    private long localBefore(long index, long localCount, long sharedCount) {
        long paired = Math.min(localCount, sharedCount);
        if (index <= paired * 2) return (index + 1) / 2;
        return paired + Math.min(index - paired * 2, localCount - paired);
    }

    private long sharedBefore(long index, long localCount, long sharedCount) {
        return index - localBefore(index, localCount, sharedCount);
    }

    private List<Row> merge(List<CatalogItem> localRows, List<ProductListItemDto> sharedRows, long start) {
        List<Row> rows = new ArrayList<>(localRows.size() + sharedRows.size());
        var local = localRows.iterator();
        var shared = sharedRows.iterator();
        boolean localTurn = start % 2 == 0;
        while (local.hasNext() || shared.hasNext()) {
            if (local.hasNext() && (localTurn || !shared.hasNext())) rows.add(new Row(local.next(), null));
            else rows.add(new Row(null, shared.next()));
            localTurn = !localTurn;
        }
        return rows;
    }
}
