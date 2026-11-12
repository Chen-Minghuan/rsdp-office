package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.dto.response.CategoryDictDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.office.service.OfficeProductMatching;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 主项目商品数据网关：把 RSDP 主库的产品/变体/报价/字典响应映射为 office 既有 DTO，
 * 保持 office 前端契约不变。图片地址拼接为主项目绝对地址（主项目图片接口免登录）。
 */
@Service
@RequiredArgsConstructor
public class RsdpCatalogGateway {

    /** 主项目一次拉取的页大小（循环翻页直到取完）。 */
    private static final int PAGE_SIZE = 500;

    private final RsdpClient rsdpClient;
    private final ObjectMapper objectMapper;

    /** 排版选品目录缓存（30s，按 viewMode 区分）：选品面板高频打开，避免每次都全量回源主项目。 */
    private static final long OFFICE_PRODUCTS_CACHE_TTL_MS = 30_000;
    private final Map<String, CacheEntry> officeProductsCache = new ConcurrentHashMap<>();

    private record CacheEntry(List<OfficeProductDto> data, long fetchedAt) {}

    /** 排版选品目录：主项目全量产品的有效变体平铺（含尺寸/价格/图片），30s 缓存。 */
    public List<OfficeProductDto> listOfficeProducts(String viewMode) {
        CacheEntry cached = officeProductsCache.get(viewMode);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < OFFICE_PRODUCTS_CACHE_TTL_MS) {
            return cached.data();
        }
        List<OfficeProductDto> result = fetchOfficeProducts(viewMode);
        officeProductsCache.put(viewMode, new CacheEntry(result, System.currentTimeMillis()));
        return result;
    }

    private List<OfficeProductDto> fetchOfficeProducts(String viewMode) {
        List<ProductListItemDto> products = listProducts(viewMode);
        if (products.isEmpty()) {
            return List.of();
        }
        // 逐产品并发拉变体（虚拟线程，主项目同机部署延迟低）
        List<OfficeProductDto> result = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<OfficeProductDto>>> futures = products.stream()
                .map(p -> submitVariantFetch(executor, p))
                .toList();
            for (Future<List<OfficeProductDto>> future : futures) {
                result.addAll(future.get());
            }
        } catch (Exception e) {
            throw new BusinessException("拉取主项目商品变体失败: " + e.getMessage());
        }
        return result;
    }

    private Future<List<OfficeProductDto>> submitVariantFetch(ExecutorService executor, ProductListItemDto product) {
        return executor.submit(() -> toOfficeProductDtos(product));
    }

    private List<OfficeProductDto> toOfficeProductDtos(ProductListItemDto product) {
        JsonNode variants = rsdpClient.get("/api/v1/products/" + product.getRspuId() + "/variants");
        List<OfficeProductDto> rows = new ArrayList<>();
        if (!variants.isArray()) {
            return rows;
        }
        variants.forEach(v -> {
            if (!"active".equals(v.path("status").asText("active"))) {
                return;
            }
            OfficeProductDto dto = new OfficeProductDto();
            dto.setId(v.path("variantId").asText());
            dto.setRspuId(product.getRspuId());
            dto.setVariantId(v.path("variantId").asText());
            dto.setName(product.getProductName());
            dto.setVariant(buildVariantText(v));
            dto.setPrice(product.getRetailPrice() != null ? product.getRetailPrice() : BigDecimal.ZERO);
            dto.setKind("other");
            dto.setUses(List.of("OTHER"));
            dto.setImageUrl(product.getPrimaryImageUrl());
            applyDimensions(dto, textOrNull(v, "dimensions"));
            rows.add(dto);
        });
        return rows;
    }

    /** 变体摘要文本：显示名 / 尺寸 / 颜色 / 材质（与本地实现口径一致）。 */
    private String buildVariantText(JsonNode v) {
        StringBuilder sb = new StringBuilder();
        appendVariantPart(sb, textOrNull(v, "displayName"));
        appendVariantPart(sb, textOrNull(v, "sizeText"));
        appendVariantPart(sb, textOrNull(v, "colorText"));
        appendVariantPart(sb, textOrNull(v, "materialText"));
        return sb.toString();
    }

    private void appendVariantPart(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(part);
        }
    }

    /** 尺寸 JSON（{"w":560,"d":580,"h":780,"unit":"mm"}）→ 毫米 + 厘米上取整。 */
    private void applyDimensions(OfficeProductDto dto, String dimensions) {
        if (dimensions == null || dimensions.isBlank()) {
            return;
        }
        try {
            BigDecimal[] exact = OfficeProductMatching.dimensions(objectMapper.readTree(dimensions));
            dto.setWidthMm(exact[0]);
            dto.setDepthMm(exact[1]);
            dto.setHeightMm(exact[2]);
            if (exact[0] != null && exact[1] != null) {
                dto.setWidthCm(exact[0].divide(BigDecimal.TEN, 0, RoundingMode.CEILING).intValueExact());
                dto.setDepthCm(exact[1].divide(BigDecimal.TEN, 0, RoundingMode.CEILING).intValueExact());
            }
        } catch (Exception ignored) {
            // 尺寸未知保持为空，不臆造
        }
    }

    /**
     * 拉取主项目产品列表（全量翻页）。
     *
     * @param viewMode own=仅本厂已报价产品；full=全库只读（主项目按服务账号身份控制）
     */
    public List<ProductListItemDto> listProducts(String viewMode) {
        List<ProductListItemDto> all = new ArrayList<>();
        int page = 1;
        while (true) {
            JsonNode data = rsdpClient.get("/api/v1/products?page=" + page + "&size=" + PAGE_SIZE + "&viewMode=" + viewMode);
            JsonNode rows = data.path("rows");
            if (!rows.isArray() || rows.isEmpty()) {
                break;
            }
            rows.forEach(row -> all.add(toListItem(row)));
            if (all.size() >= data.path("total").asInt(0) || rows.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return all;
    }

    /** 拉取主项目产品详情（主档 + 变体 + 本厂报价 + 图片）。 */
    public ProductDetailDto getProductDetail(String rspuId) {
        JsonNode detail = rsdpClient.get("/api/v1/products/" + rspuId);
        JsonNode rspu = detail.path("rspu");
        if (rspu.isMissingNode() || rspu.isNull()) {
            throw new BusinessException("主项目中不存在该商品: " + rspuId);
        }

        ProductDetailDto dto = new ProductDetailDto();
        dto.setRspuId(rspu.path("rspuId").asText());
        dto.setRspuCode(textOrNull(rspu, "rspuCode"));
        dto.setExternalCode(textOrNull(rspu, "externalCode"));
        dto.setProductName(textOrNull(rspu, "productName"));
        dto.setColorPrimaryName(textOrNull(rspu, "colorPrimaryName"));
        dto.setDescription(textOrNull(rspu, "description"));
        dto.setRetailPrice(decimalOrNull(rspu, "retailPrice"));
        dto.setStatus(textOrNull(rspu, "status"));
        // 主项目无审核流概念：主库商品视为已通过审核
        dto.setAuditStatus("approved");
        dto.setCreatedAt(dateOrNull(rspu, "createdAt"));
        dto.setUpdatedAt(dateOrNull(rspu, "updatedAt"));

        JsonNode images = detail.path("images");
        if (images.isArray()) {
            images.forEach(img -> {
                ProductDetailDto.Image image = new ProductDetailDto.Image();
                image.setImageId(img.path("imageId").asText());
                image.setVariantId(textOrNull(img, "variantId"));
                image.setImageType(textOrNull(img, "imageType"));
                image.setStorageUrl(imageUrl(img.path("imageId").asText()));
                image.setPrimary(img.path("primary").asBoolean(false));
                image.setFileSize(img.path("fileSize").isNumber() ? img.path("fileSize").asLong() : null);
                image.setWidth(img.path("width").isNumber() ? img.path("width").asInt() : null);
                image.setHeight(img.path("height").isNumber() ? img.path("height").asInt() : null);
                image.setFormat(textOrNull(img, "format"));
                image.setCreatedAt(dateOrNull(img, "createdAt"));
                dto.getImages().add(image);
            });
        }
        dto.getImages().stream().filter(img -> Boolean.TRUE.equals(img.getPrimary())).findFirst()
            .ifPresent(img -> dto.setPrimaryImageUrl(img.getStorageUrl()));

        // 本厂报价按变体聚合（数据范围限定本厂，出厂价主项目已解密本厂部分）
        Map<String, JsonNode> ownQuoteByVariant = new HashMap<>();
        JsonNode quotes = rsdpClient.get("/api/v1/products/" + rspuId + "/rsku");
        if (quotes.isArray()) {
            quotes.forEach(quote -> {
                String variantId = quote.path("variantId").asText("");
                // 同一变体多条本厂报价时保留出厂价最低的一条
                ownQuoteByVariant.merge(variantId, quote, (existing, incoming) -> {
                    double existingPrice = existing.path("factoryPrice").asDouble(Double.MAX_VALUE);
                    double incomingPrice = incoming.path("factoryPrice").asDouble(Double.MAX_VALUE);
                    return incomingPrice < existingPrice ? incoming : existing;
                });
            });
        }

        JsonNode variants = rsdpClient.get("/api/v1/products/" + rspuId + "/variants");
        if (variants.isArray()) {
            variants.forEach(v -> {
                ProductDetailDto.Variant variant = new ProductDetailDto.Variant();
                variant.setVariantId(v.path("variantId").asText());
                variant.setDisplayName(textOrNull(v, "displayName"));
                variant.setSizeCode(textOrNull(v, "sizeCode"));
                variant.setSizeText(textOrNull(v, "sizeText"));
                variant.setDimensions(textOrNull(v, "dimensions"));
                variant.setColorCode(textOrNull(v, "colorCode"));
                variant.setColorText(textOrNull(v, "colorText"));
                variant.setMaterialCode(textOrNull(v, "materialCode"));
                variant.setMaterialText(textOrNull(v, "materialText"));
                variant.setStatus(textOrNull(v, "status"));
                variant.setCreatedAt(dateOrNull(v, "createdAt"));
                variant.setUpdatedAt(dateOrNull(v, "updatedAt"));
                // 报价优先精确匹配变体，其次回落到产品级（无变体归属）报价
                JsonNode quote = ownQuoteByVariant.getOrDefault(variant.getVariantId(), ownQuoteByVariant.get(""));
                if (quote != null) {
                    variant.setFactoryPrice(decimalOrNull(quote, "factoryPrice"));
                    variant.setMoq(quote.path("moq").isNumber() ? quote.path("moq").asInt() : null);
                    variant.setLeadTimeDays(quote.path("leadTimeDays").isNumber() ? quote.path("leadTimeDays").asInt() : null);
                }
                dto.getVariants().add(variant);
            });
        }
        return dto;
    }

    /** 拉取主项目字典。 */
    public List<CategoryDictDto> listDicts(String dictType) {
        JsonNode data = rsdpClient.get("/api/v1/dicts/" + dictType);
        List<CategoryDictDto> dicts = new ArrayList<>();
        if (data.isArray()) {
            data.forEach(d -> {
                CategoryDictDto dict = new CategoryDictDto();
                dict.setDictType(dictType);
                dict.setDictCode(d.path("dictCode").asText());
                dict.setDictName(d.path("dictName").asText());
                dict.setDictNameEn(textOrNull(d, "dictNameEn"));
                dict.setParentCode(textOrNull(d, "parentCode"));
                dict.setSortOrder(d.path("sortOrder").isNumber() ? d.path("sortOrder").asInt() : 0);
                dict.setStatus(textOrNull(d, "status"));
                dicts.add(dict);
            });
        }
        return dicts;
    }

    private ProductListItemDto toListItem(JsonNode row) {
        ProductListItemDto dto = new ProductListItemDto();
        dto.setRspuId(row.path("rspuId").asText());
        dto.setRspuCode(textOrNull(row, "rspuCode"));
        dto.setExternalCode(null);
        dto.setProductName(textOrNull(row, "productName"));
        String imageUrl = textOrNull(row, "primaryImageUrl");
        dto.setPrimaryImageUrl(imageUrl == null ? null : absolutize(imageUrl));
        dto.setRetailPrice(decimalOrNull(row, "retailPrice"));
        dto.setStatus(textOrNull(row, "status"));
        dto.setAuditStatus("approved");
        dto.setCreatedAt(dateOrNull(row, "createdAt"));
        dto.setUpdatedAt(dateOrNull(row, "updatedAt"));
        return dto;
    }

    /** 主项目返回的相对图片路径转绝对地址（图片接口免登录）。 */
    private String absolutize(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return rsdpClient.baseUrl() + (path.startsWith("/") ? path : "/" + path);
    }

    private String imageUrl(String imageId) {
        return rsdpClient.baseUrl() + "/api/v1/images/" + imageId;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }

    private LocalDateTime dateOrNull(JsonNode node, String field) {
        String text = textOrNull(node, field);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (Exception e) {
            return null;
        }
    }
}
