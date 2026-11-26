package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.dto.request.CatalogProductPageRequest;
import com.rsdp.officeproduct.importer.dto.response.CatalogFacetsDto;
import com.rsdp.officeproduct.importer.dto.response.CatalogProductPageDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 在 Office 侧对授权商品快照做派生筛选、facet 统计和分页。 */
@Service
@RequiredArgsConstructor
public class CatalogProductFilterService {

    private static final List<String> LEVEL_ORDER = List.of("S", "A", "B", "C");
    private static final Map<String, String> LEVEL_LABELS = Map.of(
        "S", "S级", "A", "A级", "B", "B级", "C", "C级");
    private static final List<String> SPACE_ORDER = List.of("STAFF", "MANAGER", "SENIOR_MANAGER", "PRESIDENT",
        "MEETING", "NEGOTIATION", "RECEPTION", "PANTRY");
    private static final Map<String, String> SPACE_LABELS = Map.ofEntries(
        Map.entry("STAFF", "职员空间"), Map.entry("MANAGER", "主管空间"),
        Map.entry("SENIOR_MANAGER", "高管空间"), Map.entry("PRESIDENT", "总裁空间"),
        Map.entry("MEETING", "会议空间"), Map.entry("NEGOTIATION", "洽谈空间"),
        Map.entry("RECEPTION", "接待空间"), Map.entry("PANTRY", "茶水空间"),
        Map.entry("UNSPECIFIED", "未标注"));
    private static final List<String> PRODUCT_TYPE_ORDER = List.of("DESK", "CABINET", "CHAIR", "SOFA");
    private static final Map<String, String> PRODUCT_TYPE_LABELS = Map.of(
        "DESK", "桌类", "CABINET", "柜类", "CHAIR", "椅类", "SOFA", "沙发类", "UNCLASSIFIED", "未归类");
    private static final List<String> LEG_ORDER = List.of("STEEL", "SUPPORT_CABINET");
    private static final Map<String, String> LEG_LABELS = Map.of(
        "STEEL", "钢脚", "SUPPORT_CABINET", "支撑柜", "UNSPECIFIED", "未标注");
    private static final List<String> STORAGE_ORDER = List.of("MOBILE_SMALL_CABINET", "LONG_SIDE_CABINET",
        "HIGH_CABINET", "BACK_CABINET", "SUPPORT_CABINET", "SIDE_CABINET", "DRAWER");
    private static final Map<String, String> STORAGE_LABELS = Map.ofEntries(
        Map.entry("MOBILE_SMALL_CABINET", "活动小柜"), Map.entry("LONG_SIDE_CABINET", "长副柜"),
        Map.entry("HIGH_CABINET", "高柜"), Map.entry("BACK_CABINET", "背柜"),
        Map.entry("SUPPORT_CABINET", "支撑柜"), Map.entry("SIDE_CABINET", "副柜"),
        Map.entry("DRAWER", "抽屉"), Map.entry("UNSPECIFIED", "未标注"));
    private static final List<String> COLOR_ORDER = List.of("雅士白", "蜜芽胡桃", "液态银", "纳柯", "暮山黑橡",
        "月影胡桃", "海鸥灰", "爱马仕橙", "天使白", "灰绿色(扪布)", "高级灰", "棕色", "灰色");
    private static final List<String> WIDTH_RANGE_ORDER = ProductFilterSupport.WIDTH_RANGES.stream()
        .map(ProductFilterSupport.WidthRange::code).toList();
    private static final Map<String, String> WIDTH_RANGE_LABELS = Stream.concat(
        ProductFilterSupport.WIDTH_RANGES.stream(),
        Stream.of(new ProductFilterSupport.WidthRange("UNSPECIFIED", "未标注", null, null)))
        .collect(Collectors.toMap(ProductFilterSupport.WidthRange::code,
            ProductFilterSupport.WidthRange::label, (a, b) -> a, LinkedHashMap::new));

    private final RsdpCatalogGateway rsdpCatalogGateway;

    public CatalogProductPageDto pageProducts(String view, CatalogProductPageRequest request, boolean refresh) {
        List<CatalogFilterProduct> snapshot = rsdpCatalogGateway.listFilterProducts(view, refresh);
        String term = request.normalizedSearch().toLowerCase(Locale.ROOT);
        List<Row> base = snapshot.stream()
            .filter(row -> matchesSearch(row.summary(), term))
            .filter(row -> matchesStatus(row.summary(), request.getStatus()))
            .map(row -> new Row(row, ProductFilterSupport.attributes(toSource(row))))
            .toList();
        CatalogFacetsDto facets = buildFacets(base);
        ProductFilter filter = request.toFilter();
        List<Row> matching = base.stream().filter(row -> row.attributes().matches(filter)).toList();
        int start = (int) Math.min((request.getPage() - 1L) * request.getSize(), matching.size());
        List<ProductListItemDto> rows = matching.subList(start, Math.min(start + request.getSize(), matching.size()))
            .stream().map(row -> row.product().summary()).toList();
        long active = snapshot.stream().filter(row -> "active".equals(row.summary().getStatus())).count();
        return new CatalogProductPageDto(matching.size(), snapshot.size(), active,
            request.getPage(), request.getSize(), rows, facets);
    }

    private CatalogFacetsDto buildFacets(List<Row> base) {
        Map<String, Long> levelCounts = countBy(base, row -> nullSafe(row.attributes().productLevel()));
        Map<String, Long> spaceCounts = countBy(base, row -> row.attributes().spaceType());
        Map<String, Long> productTypeCounts = countBy(base, row -> row.attributes().productType());
        Map<String, Long> legCounts = new LinkedHashMap<>();
        Map<String, Long> storageCounts = countBy(base, row -> row.attributes().storageType());
        Map<String, Long> colorCounts = new LinkedHashMap<>();
        for (Row row : base) {
            row.attributes().legTypes().forEach(leg -> legCounts.merge(leg, 1L, Long::sum));
            row.attributes().colorNames().forEach(color -> colorCounts.merge(color, 1L, Long::sum));
        }
        Set<String> materials = new LinkedHashSet<>();
        Map<String, Long> materialCounts = new LinkedHashMap<>();
        Map<String, Long> widthRangeCounts = new LinkedHashMap<>();
        for (Row row : base) {
            row.attributes().widthRanges().forEach(code -> widthRangeCounts.merge(code, 1L, Long::sum));
            for (ProductFilterSupport.Source.Variant variant : row.attributes().variants()) {
                if (variant.materialText() != null && !variant.materialText().isBlank()) {
                    materials.add(variant.materialText());
                    materialCounts.merge(variant.materialText(), 1L, Long::sum);
                }
            }
        }

        Map<String, List<CatalogFacetsDto.Option>> groups = new LinkedHashMap<>();
        groups.put("levels", fixedOptions(LEVEL_ORDER, LEVEL_LABELS, levelCounts));
        groups.put("spaceTypes", fixedOptions(SPACE_ORDER, SPACE_LABELS, spaceCounts));
        groups.put("productTypes", fixedOptions(PRODUCT_TYPE_ORDER, PRODUCT_TYPE_LABELS, productTypeCounts));
        groups.put("materials", materials.stream().sorted()
            .map(material -> CatalogFacetsDto.Option.of(material, material, materialCounts.get(material)))
            .toList());
        groups.put("colors", colorCounts.keySet().stream()
            .sorted(Comparator.comparingInt(color -> {
                int index = COLOR_ORDER.indexOf(color);
                return index < 0 ? COLOR_ORDER.size() : index;
            }))
            .map(color -> CatalogFacetsDto.Option.of(color, color, colorCounts.get(color)))
            .toList());
        groups.put("widthRanges", fixedOptions(WIDTH_RANGE_ORDER, WIDTH_RANGE_LABELS, widthRangeCounts));
        groups.put("legTypes", fixedOptions(LEG_ORDER, LEG_LABELS, legCounts));
        groups.put("storageTypes", fixedOptions(STORAGE_ORDER, STORAGE_LABELS, storageCounts));
        return new CatalogFacetsDto(groups);
    }

    private static List<CatalogFacetsDto.Option> fixedOptions(List<String> order, Map<String, String> labels,
                                                              Map<String, Long> counts) {
        return order.stream()
            .map(code -> CatalogFacetsDto.Option.of(code, labels.getOrDefault(code, code),
                counts.getOrDefault(code, 0L)))
            .toList();
    }

    private static Map<String, Long> countBy(List<Row> base, java.util.function.Function<Row, String> key) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Row row : base) {
            String value = key.apply(row);
            if (!value.isEmpty()) counts.merge(value, 1L, Long::sum);
        }
        return counts;
    }

    private static boolean matchesSearch(ProductListItemDto summary, String term) {
        if (term.isEmpty()) return true;
        return Stream.of(summary.getProductName(), summary.getRspuCode(), summary.getExternalCode(), summary.getRspuId())
            .anyMatch(value -> value != null && value.toLowerCase(Locale.ROOT).contains(term));
    }

    private static boolean matchesStatus(ProductListItemDto summary, String status) {
        if (status == null || status.isEmpty()) return true;
        return "active".equals(status) == "active".equals(summary.getStatus());
    }

    private static ProductFilterSupport.Source toSource(CatalogFilterProduct row) {
        var summary = row.summary();
        return new ProductFilterSupport.Source(summary.getProductLevel(), summary.getProductName(),
            summary.getColorPrimaryName(), row.description(),
            row.variants().stream()
                .map(variant -> new ProductFilterSupport.Source.Variant(variant.getMaterialText(), variant.getWidthMm()))
                .toList());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record Row(CatalogFilterProduct product, ProductFilterSupport.Attributes attributes) {}
}
