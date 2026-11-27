package com.rsdp.officeproduct.importer.service;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ProductFilterSupport {

    private static final List<Rule> SPACE_RULES = List.of(
        new Rule("职员台", "STAFF"),
        new Rule("主管台", "MANAGER"),
        new Rule("高管台", "SENIOR_MANAGER"),
        new Rule("总裁台", "PRESIDENT"),
        new Rule("会议台", "MEETING"),
        new Rule("洽谈台", "NEGOTIATION"),
        new Rule("前台", "RECEPTION"),
        new Rule("茶台", "PANTRY"),
        new Rule("茶水柜", "PANTRY")
    );

    private static final List<Rule> STORAGE_RULES = List.of(
        new Rule("活动小柜", "MOBILE_SMALL_CABINET"),
        new Rule("长副柜", "LONG_SIDE_CABINET"),
        new Rule("高柜", "HIGH_CABINET"),
        new Rule("背柜", "BACK_CABINET"),
        new Rule("支撑柜", "SUPPORT_CABINET"),
        new Rule("副柜", "SIDE_CABINET"),
        new Rule("抽屉", "DRAWER")
    );

    private static final Set<String> ALLOWED_COLORS = Set.of(
        "雅士白", "蜜芽胡桃", "液态银", "纳柯", "暮山黑橡", "月影胡桃", "海鸥灰",
        "爱马仕橙", "天使白", "灰绿色(扪布)", "高级灰", "棕色", "灰色"
    );

    private ProductFilterSupport() {}

    public record Source(String productLevel, String productName, String colorPrimaryName,
                         String description, List<Variant> variants) {
        public Source {
            variants = variants == null ? List.of() : List.copyOf(variants);
        }

        public record Variant(String materialText, BigDecimal widthMm) {}
    }

    /** 尺寸区间按 2026-11-25 主库 185 个商品的 23 种真实宽度分布划定；边界为大于下限、含上限。 */
    public record WidthRange(String code, String label, Integer minExclusive, Integer maxInclusive) {
        public boolean contains(BigDecimal width) {
            if (width == null) return false;
            return (minExclusive == null || width.compareTo(BigDecimal.valueOf(minExclusive)) > 0)
                && (maxInclusive == null || width.compareTo(BigDecimal.valueOf(maxInclusive)) <= 0);
        }
    }

    public static final List<WidthRange> WIDTH_RANGES = List.of(
        new WidthRange("LE_1000", "1m及以下", null, 1000),
        new WidthRange("R1000_1200", "1–1.2m", 1000, 1200),
        new WidthRange("R1200_1600", "1.2–1.6m", 1200, 1600),
        new WidthRange("R1600_2000", "1.6–2m", 1600, 2000),
        new WidthRange("R2000_2400", "2–2.4m", 2000, 2400),
        new WidthRange("R2400_2800", "2.4–2.8m", 2400, 2800),
        new WidthRange("GT_2800", "2.8m以上", 2800, null)
    );

    public static String widthRangeCode(BigDecimal width) {
        if (width == null) return "UNSPECIFIED";
        for (WidthRange range : WIDTH_RANGES) {
            if (range.contains(width)) return range.code();
        }
        return "UNSPECIFIED";
    }

    public static boolean widthInRanges(BigDecimal width, List<String> codes) {
        if (codes.isEmpty()) return true;
        if (width == null) return codes.contains("UNSPECIFIED");
        return WIDTH_RANGES.stream().anyMatch(range -> codes.contains(range.code()) && range.contains(width));
    }

    public record Attributes(String productLevel, String spaceType, String productType,
                             Set<String> colorNames, Set<String> legTypes, String storageType,
                             Set<String> widthRanges, List<Source.Variant> variants) {
        public Attributes {
            colorNames = Set.copyOf(colorNames);
            legTypes = Set.copyOf(legTypes);
            widthRanges = Set.copyOf(widthRanges);
            variants = List.copyOf(variants);
        }

        public boolean matches(ProductFilter filter) {
            return filter.matches(this);
        }
    }

    private record Rule(String keyword, String code) {}

    public static Attributes attributes(Source source) {
        Set<String> widthRanges = new LinkedHashSet<>();
        for (Source.Variant variant : source.variants()) {
            widthRanges.add(widthRangeCode(variant.widthMm()));
        }
        if (widthRanges.isEmpty()) widthRanges.add("UNSPECIFIED");
        return new Attributes(source.productLevel(), spaceType(source.productName()),
            productType(source.productName()), colorNames(source.colorPrimaryName()),
            legTypes(source.productName(), source.description()),
            storageType(source.productName(), source.description()), widthRanges, source.variants());
    }

    public static String spaceType(String name) {
        return firstMatch(name, SPACE_RULES, "UNSPECIFIED");
    }

    public static String productType(String name) {
        String text = text(name);
        if (text.contains("沙发")) return "SOFA";
        if (text.contains("椅")) return "CHAIR";
        if (text.contains("台") || text.contains("桌") || text.contains("茶几")) return "DESK";
        if (text.contains("柜")) return "CABINET";
        return "UNCLASSIFIED";
    }

    public static Set<String> colorNames(String raw) {
        Set<String> colors = new LinkedHashSet<>();
        for (String part : text(raw).split("\\+")) {
            String value = part.trim();
            if (ALLOWED_COLORS.contains(value)) colors.add(value);
        }
        return colors;
    }

    public static Set<String> legTypes(String name, String description) {
        String text = text(name) + " " + text(description);
        Set<String> legs = new LinkedHashSet<>();
        if (text.contains("钢脚")) legs.add("STEEL");
        if (text.contains("支撑柜")) legs.add("SUPPORT_CABINET");
        if (legs.isEmpty()) legs.add("UNSPECIFIED");
        return legs;
    }

    public static String storageType(String name, String description) {
        String fromName = firstMatch(name, STORAGE_RULES, null);
        if (fromName != null) return fromName;
        return firstMatch(description, STORAGE_RULES, "UNSPECIFIED");
    }

    private static String firstMatch(String text, List<Rule> rules, String fallback) {
        String value = text(text);
        for (Rule rule : rules) {
            if (value.contains(rule.keyword())) return rule.code();
        }
        return fallback;
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
