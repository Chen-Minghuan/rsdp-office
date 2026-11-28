package com.rsdp.officeproduct.importer.dto.response;

import java.util.List;
import java.util.Map;

/** 商品目录筛选面板统计，count 基于基础集合且不随高级筛选收缩。 */
public record CatalogFacetsDto(Map<String, List<Option>> groups) {

    public record Option(String code, String label, long count, boolean disabled) {
        public static Option of(String code, String label, long count) {
            return new Option(code, label, count, count == 0);
        }
    }

    public static CatalogFacetsDto empty() {
        return new CatalogFacetsDto(Map.of());
    }
}
