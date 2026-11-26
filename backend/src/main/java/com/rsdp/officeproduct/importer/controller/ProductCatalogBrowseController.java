package com.rsdp.officeproduct.importer.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.dto.request.CatalogProductPageRequest;
import com.rsdp.officeproduct.importer.service.ProductCatalogBrowseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products/browse")
@RequiredArgsConstructor
public class ProductCatalogBrowseController {
    private final ProductCatalogBrowseService products;

    @GetMapping
    public Result<ProductCatalogBrowseService.Page> page(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        @RequestParam(defaultValue = "") String search,
                                                        @RequestParam(defaultValue = "") String status,
                                                        @RequestParam(defaultValue = "") String category,
                                                        @RequestParam(defaultValue = "") String color,
                                                        @ModelAttribute CatalogProductPageRequest advanced) {
        return Result.ok(products.page(page, size, search, status, category, color, advanced));
    }

    @GetMapping("/facets")
    public Result<ProductCatalogBrowseService.Facets> facets(@RequestParam(defaultValue = "") String search,
                                                             @RequestParam(defaultValue = "") String status) {
        return Result.ok(products.facets(search, status));
    }
}
