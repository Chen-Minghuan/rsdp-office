package com.rsdp.officeproduct.importer.dto.response;

import java.util.List;

public record CatalogProductPageDto(long total, long catalogTotal, long activeTotal, int page, int size,
                                    List<ProductListItemDto> rows) {
    public static CatalogProductPageDto empty(int page, int size) {
        return new CatalogProductPageDto(0, 0, 0, page, size, List.of());
    }
}
