package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.service.RsdpCatalogGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 解析报价所需的商品和变体出厂价，试算与确认共用缓存。 */
@Slf4j
@Component
@RequiredArgsConstructor
class QuotationCatalog {

    private static final long FACTORY_PRICE_CACHE_TTL_MS = 120_000L;

    private final RsdpCatalogGateway catalogGateway;
    private final Map<String, FactoryPriceCache> factoryPriceCache = new ConcurrentHashMap<>();

    Map<String, OfficeProductDto> loadProducts(Set<String> productIds) {
        return catalogGateway.quotationProducts(productIds);
    }

    /** 同一次试算内按商品主档去重，每个变体仍使用自己的出厂价。 */
    Map<String, ProductPrice> priceProducts(Set<String> productIds, Map<String, OfficeProductDto> products) {
        Map<String, Map<String, BigDecimal>> pricesByRspu = new HashMap<>();
        Map<String, ProductPrice> result = new HashMap<>();
        for (String productId : productIds) {
            OfficeProductDto product = products.get(productId);
            BigDecimal price = null;
            if (product != null && StringUtils.hasText(product.getRspuId())) {
                Map<String, BigDecimal> prices = pricesByRspu.computeIfAbsent(product.getRspuId(), this::cachedFactoryPrices);
                price = prices.getOrDefault(productId, prices.get(""));
            }
            result.put(productId, new ProductPrice(product, price));
        }
        return result;
    }

    ProductPrice priceProduct(OfficeProductDto product) {
        if (product == null || !StringUtils.hasText(product.getRspuId())) {
            return new ProductPrice(product, null);
        }
        Map<String, BigDecimal> prices = cachedFactoryPrices(product.getRspuId());
        return new ProductPrice(product, prices.getOrDefault(product.getId(), prices.get("")));
    }

    private Map<String, BigDecimal> cachedFactoryPrices(String rspuId) {
        FactoryPriceCache cached = factoryPriceCache.get(rspuId);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < FACTORY_PRICE_CACHE_TTL_MS) {
            return cached.prices();
        }
        Map<String, BigDecimal> prices;
        try {
            prices = catalogGateway.getFactoryPrices(rspuId);
        } catch (RuntimeException failure) {
            log.warn("读取商品出厂价失败 rspuId={}：{}", rspuId, failure.getMessage());
            prices = Map.of();
        }
        factoryPriceCache.put(rspuId, new FactoryPriceCache(prices, System.currentTimeMillis()));
        return prices;
    }

    record ProductPrice(OfficeProductDto product, BigDecimal factoryPrice) {

        String productName() {
            return product == null ? "商品不可用" : product.getName();
        }

        String issue() {
            if (product == null) {
                return "商品已下架或不在当前商品目录，需重新选品";
            }
            return factoryPrice == null ? "缺出厂价，无法试算" : null;
        }
    }

    private record FactoryPriceCache(Map<String, BigDecimal> prices, long fetchedAt) {
    }
}
