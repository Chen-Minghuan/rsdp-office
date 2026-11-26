package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.dto.request.CatalogProductPageRequest;
import com.rsdp.officeproduct.importer.dto.response.CatalogProductPageDto;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.office.product.LocalProductReference;
import com.rsdp.officeproduct.importer.office.product.LocalProductService;
import com.rsdp.officeproduct.importer.office.service.OfficeCatalogAccess;
import com.rsdp.officeproduct.importer.office.service.SharedCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 商品目录按会话工作区的共享范围和已核验的工厂归属读取。 */
@Service
@RequiredArgsConstructor
public class ProductQueryService {

    private final RsdpCatalogGateway rsdpCatalogGateway;
    private final CatalogProductFilterService catalogProductFilterService;
    private final SharedCatalogService sharedCatalogService;
    private final OfficeCatalogAccess access;
    private final LocalProductService localProducts;

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

    public void requireVisibleCollectionProduct(String reference) {
        if (LocalProductReference.isLocal(reference)) {
            localProducts.requireVisible(LocalProductReference.productId(reference));
        } else {
            getProduct(reference);
        }
    }

    public Map<String, ProductListItemDto> collectionProducts(Collection<String> references) {
        Map<String, ProductListItemDto> products = new HashMap<>();
        var localIds = references.stream().filter(LocalProductReference::isLocal)
            .map(LocalProductReference::productId).distinct().toList();
        for (var item : localProducts.catalogItems(localIds)) {
            var product = new ProductListItemDto();
            product.setRspuId(LocalProductReference.PREFIX + item.id());
            product.setRspuCode(item.factoryModel());
            product.setProductName(item.name());
            product.setStatus("active");
            if (item.primaryImageId() != null) product.setPrimaryImageUrl(LocalProductReference.imagePath(item.primaryImageId()));
            products.put(product.getRspuId(), product);
        }
        if (references.stream().anyMatch(reference -> !LocalProductReference.isLocal(reference))) {
            try {
                for (var product : listProducts(false)) {
                    if (references.contains(product.getRspuId())) products.putIfAbsent(product.getRspuId(), product);
                }
            } catch (BusinessException | RestClientException failure) {
                if (products.isEmpty()) throw failure;
            }
        }
        return products;
    }
}
