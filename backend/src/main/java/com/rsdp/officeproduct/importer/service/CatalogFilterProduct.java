package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;

import java.util.List;

public record CatalogFilterProduct(ProductListItemDto summary, String description,
                                   List<ProductDetailDto.Variant> variants) {
    public CatalogFilterProduct {
        variants = List.copyOf(variants);
    }
}
