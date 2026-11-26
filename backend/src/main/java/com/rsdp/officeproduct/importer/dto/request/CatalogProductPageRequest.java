package com.rsdp.officeproduct.importer.dto.request;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.service.ProductFilter;
import com.rsdp.officeproduct.importer.service.ProductFilterSupport;
import lombok.Data;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** /api/v1/products/page 的分页与高级筛选参数。 */
@Data
public class CatalogProductPageRequest {

    private static final Set<String> LEVELS = Set.of("S", "A", "B", "C");
    private static final Set<String> SPACE_TYPES = Set.of("STAFF", "MANAGER", "SENIOR_MANAGER", "PRESIDENT",
        "MEETING", "NEGOTIATION", "RECEPTION", "PANTRY", "UNSPECIFIED");
    private static final Set<String> PRODUCT_TYPES = Set.of("DESK", "CABINET", "CHAIR", "SOFA", "UNCLASSIFIED");
    private static final Set<String> LEG_TYPES = Set.of("STEEL", "SUPPORT_CABINET", "UNSPECIFIED");
    private static final Set<String> STORAGE_TYPES = Set.of("MOBILE_SMALL_CABINET", "LONG_SIDE_CABINET",
        "HIGH_CABINET", "BACK_CABINET", "SUPPORT_CABINET", "SIDE_CABINET", "DRAWER", "UNSPECIFIED");
    private static final Set<String> COLORS = Set.of("雅士白", "蜜芽胡桃", "液态银", "纳柯", "暮山黑橡", "月影胡桃",
        "海鸥灰", "爱马仕橙", "天使白", "灰绿色(扪布)", "高级灰", "棕色", "灰色");

    private static final Set<String> WIDTH_RANGES = Stream.concat(
        ProductFilterSupport.WIDTH_RANGES.stream().map(ProductFilterSupport.WidthRange::code),
        Stream.of("UNSPECIFIED")).collect(Collectors.toUnmodifiableSet());

    private boolean managed = false;
    private int page = 1;
    private int size = 20;
    private String search = "";
    private String status = "";
    private boolean refresh = false;
    private List<String> levels = List.of();
    private List<String> spaceTypes = List.of();
    private List<String> productTypes = List.of();
    private List<String> materials = List.of();
    private List<String> colors = List.of();
    private List<String> widthRanges = List.of();
    private List<String> legTypes = List.of();
    private List<String> storageTypes = List.of();

    public void validate() {
        if (page < 1 || size < 1 || size > 100 || search == null || search.length() > 120
                || status == null || !List.of("", "active", "other").contains(status)) {
            throw new BusinessException("商品目录分页或筛选参数无效");
        }
        requireCodes(levels, LEVELS);
        requireCodes(spaceTypes, SPACE_TYPES);
        requireCodes(productTypes, PRODUCT_TYPES);
        requireCodes(legTypes, LEG_TYPES);
        requireCodes(storageTypes, STORAGE_TYPES);
        requireCodes(colors, COLORS);
        requireCodes(widthRanges, WIDTH_RANGES);
        if (materials.size() > 50 || materials.stream()
                .anyMatch(value -> value == null || value.isBlank() || value.length() > 128)) {
            throw new BusinessException("商品筛选材质参数无效");
        }
    }

    public ProductFilter toFilter() {
        return new ProductFilter(levels, spaceTypes, productTypes, materials, colors, widthRanges, legTypes, storageTypes);
    }

    public boolean hasAdvancedFilters() {
        return Stream.of(levels, spaceTypes, productTypes, materials, colors, widthRanges, legTypes, storageTypes)
            .anyMatch(list -> !list.isEmpty());
    }

    public String normalizedSearch() {
        return search == null ? "" : search.strip();
    }

    private static void requireCodes(List<String> values, Set<String> allowed) {
        if (values.size() > 50 || values.stream()
                .anyMatch(value -> value == null || !allowed.contains(value))) {
            throw new BusinessException("商品筛选分类参数无效");
        }
    }
}
