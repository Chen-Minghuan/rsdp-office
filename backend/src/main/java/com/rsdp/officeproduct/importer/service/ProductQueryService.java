package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.office.service.SharedCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 商品列表查询。数据源为主项目（RSDP）商品库，viewMode 按当前用户两级公共库开关解析：
 * full=全库只读（别家价格由主项目掩码），own=仅本厂已报价产品。
 */
@Service
@RequiredArgsConstructor
public class ProductQueryService {

    private final RsdpCatalogGateway rsdpCatalogGateway;
    private final SharedCatalogService sharedCatalogService;

    public List<ProductListItemDto> listProducts() {
        return rsdpCatalogGateway.listProducts(sharedCatalogService.resolveViewMode());
    }
}
