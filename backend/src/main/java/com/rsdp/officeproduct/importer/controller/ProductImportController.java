package com.rsdp.officeproduct.importer.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.dto.response.CatalogProductPageDto;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.office.service.OfficeCatalogAccess;
import com.rsdp.officeproduct.importer.service.ProductQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Office 只读商品目录。 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductImportController {

    private final ProductQueryService productQueryService;
    private final OfficeCatalogAccess access;

    @GetMapping
    public Result<List<ProductListItemDto>> listProducts(@RequestParam(defaultValue = "false") boolean managed) {
        return Result.ok(productQueryService.listProducts(managed));
    }

    @GetMapping("/page")
    public Result<CatalogProductPageDto> page(@RequestParam(defaultValue = "false") boolean managed,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(defaultValue = "") String search,
                                              @RequestParam(defaultValue = "") String status) {
        return Result.ok(productQueryService.pageProducts(managed, page, size, search, status));
    }

    @GetMapping("/capabilities")
    public Result<OfficeCatalogAccess.Capabilities> capabilities() {
        return Result.ok(access.capabilities());
    }

    @GetMapping("/{rspuId}")
    public Result<ProductDetailDto> getProduct(@PathVariable String rspuId) {
        return Result.ok(productQueryService.getProduct(rspuId));
    }
}
