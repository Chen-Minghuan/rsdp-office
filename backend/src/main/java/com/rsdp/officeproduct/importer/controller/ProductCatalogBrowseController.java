package com.rsdp.officeproduct.importer.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.service.ProductCatalogBrowseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
                                                        @RequestParam(defaultValue = "") String color) {
        return Result.ok(products.page(page, size, search, status, category, color));
    }

    @GetMapping("/facets")
    public Result<ProductCatalogBrowseService.Facets> facets() {
        return Result.ok(products.facets());
    }
}
