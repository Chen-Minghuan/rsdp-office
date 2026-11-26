package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.dto.request.CatalogProductPageRequest;
import com.rsdp.officeproduct.importer.dto.response.CatalogProductPageDto;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.office.service.OfficeCatalogAccess;
import com.rsdp.officeproduct.importer.office.service.SharedCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 商品目录按会话工作区的共享范围和已核验的工厂归属读取。 */
@Service
@RequiredArgsConstructor
public class ProductQueryService {

    private final RsdpCatalogGateway rsdpCatalogGateway;
    private final CatalogProductFilterService catalogProductFilterService;
    private final SharedCatalogService sharedCatalogService;
    private final OfficeCatalogAccess access;

    public List<ProductListItemDto> listProducts(boolean managed) {
        String view = resolveView(managed);
        return "none".equals(view) ? List.of() : rsdpCatalogGateway.listProducts(view);
    }

    public CatalogProductPageDto pageProducts(CatalogProductPageRequest request) {
        request.validate();
        String view = resolveView(request.isManaged());
        return "none".equals(view) ? CatalogProductPageDto.empty(request.getPage(), request.getSize())
            : catalogProductFilterService.pageProducts(view, request, request.isRefresh());
    }

    private String resolveView(boolean managed) {
        if (managed || !"full".equals(sharedCatalogService.resolveViewMode())) {
            return access.capabilities().managed() ? "managed" : "none";
        }
        return "full";
    }

    public ProductDetailDto getProduct(String rspuId) {
        var capabilities = access.capabilities();
        boolean managed = capabilities.managed() && rsdpCatalogGateway.inView(rspuId, "managed");
        if (!managed && (!"full".equals(sharedCatalogService.resolveViewMode())
            || !rsdpCatalogGateway.inView(rspuId, "full"))) {
            throw new AccessDeniedException("当前工作区无权查看该商品");
        }
        return rsdpCatalogGateway.getProductDetail(rspuId);
    }
}
