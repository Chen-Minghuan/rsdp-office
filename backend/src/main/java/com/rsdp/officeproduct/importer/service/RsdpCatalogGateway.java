package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.CatalogProductPageDto;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.dto.response.CategoryDictDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.office.service.OfficeFurnitureRelevance;
import com.rsdp.officeproduct.importer.office.service.OfficeProductMatching;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 主项目商品数据网关：把 RSDP 主库的产品/变体/报价/字典响应映射为 office 既有 DTO，
 * 保持 office 前端契约不变。图片地址拼接为主项目绝对地址（主项目图片接口免登录）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RsdpCatalogGateway {

    /** 主项目一次拉取的页大小（循环翻页直到取完）。 */
    private static final int PAGE_SIZE = 500;
    private static final int VARIANT_FETCH_MAX_CONCURRENCY = 8;
    private static final int VARIANT_LOOKUP_BATCH_SIZE = 50;
    private static final int MAX_VARIANT_LOOKUP_CACHE_BATCHES = 256;
    private static final String CATALOG_PAGE_PATH = "/api/v1/products/catalog-page";
    private static final long LEGACY_CATALOG_RECHECK_MS = 60_000;

    private final RsdpClient rsdpClient;
    private final ObjectMapper objectMapper;

    /** 排版选品目录缓存（30s，按 viewMode 区分）：选品面板高频打开，避免每次都全量回源主项目。 */
    private static final long OFFICE_PRODUCTS_CACHE_TTL_MS = 30_000;
    private final Map<String, CacheEntry> officeProductsCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<CacheEntry>> officeProductsInFlight = new ConcurrentHashMap<>();

    private record CacheEntry(List<OfficeProductDto> data, long fetchedAt) {}

    private record VariantBatch(List<String> ids, JsonNode rows, long fetchedAt) {}

    private final Map<String, VariantBatch> variantLookupCache = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, VariantBatch> eldest) {
                return size() > MAX_VARIANT_LOOKUP_CACHE_BATCHES;
            }
        });

    private final Semaphore variantFetchPermits = new Semaphore(VARIANT_FETCH_MAX_CONCURRENCY);
    private volatile long legacyCatalogUntil;

    public Map<String, BigDecimal> retailPrices(String viewMode, Set<String> ids) {
        if (ids.isEmpty() || "none".equals(viewMode)) return Map.of();
        return withCatalogCompatibility(true, () -> loadRetailPrices(viewMode, ids), () -> {
            Map<String, BigDecimal> prices = new HashMap<>();
            for (OfficeProductDto product : listOfficeProducts(viewMode)) {
                if (ids.contains(product.getId()) && product.getPrice() != null && product.getPrice().signum() > 0) {
                    prices.putIfAbsent(product.getId(), product.getPrice());
                }
            }
            return prices;
        });
    }

    private Map<String, BigDecimal> loadRetailPrices(String viewMode, Set<String> ids) {
        Map<String, BigDecimal> prices = new HashMap<>();
        for (VariantBatch batch : readVariantBatches("/retail-prices", queryScope(viewMode), ids)) {
            for (String id : batch.ids()) {
                BigDecimal price = decimalOrNull(batch.rows(), id);
                if (price != null && price.signum() > 0) prices.put(id, price);
            }
        }
        return prices;
    }

    /** 报价展示只读取所选规格，出厂价仍从受权的 RSKU 接口读取。 */
    public Map<String, OfficeProductDto> quotationProducts(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return withCatalogCompatibility(true, () -> loadQuotationProducts(ids), () -> {
            Map<String, OfficeProductDto> products = new HashMap<>();
            for (OfficeProductDto product : listOfficeProducts("full")) {
                if (ids.contains(product.getId())) products.putIfAbsent(product.getId(), product);
            }
            return products;
        });
    }

    private Map<String, OfficeProductDto> loadQuotationProducts(Set<String> ids) {
        Map<String, OfficeProductDto> products = new HashMap<>();
        for (VariantBatch batch : readVariantBatches("/catalog-items", "", ids)) {
            for (String id : batch.ids()) {
                JsonNode row = batch.rows().path(id);
                String rspuId = textOrNull(row, "rspuId");
                if (!StringUtils.hasText(rspuId)) continue;
                OfficeProductDto product = new OfficeProductDto();
                product.setId(id);
                product.setRspuId(rspuId);
                product.setName(textOrNull(row, "productName"));
                product.setVariant(buildVariantText(row));
                String image = textOrNull(row, "primaryImageUrl");
                product.setImageUrl(image == null ? null : absolutize(image));
                products.put(id, product);
            }
        }
        return products;
    }

    private <T> T withCatalogCompatibility(boolean variantBatch, Supplier<T> modern, Supplier<T> legacy) {
        if (System.currentTimeMillis() < legacyCatalogUntil) return legacy.get();
        try {
            return modern.get();
        } catch (BusinessException error) {
            boolean absent = missingCatalogEndpoint(error);
            if (!absent && variantBatch && error.getCode() >= 500) {
                // Some older RSDP builds wrap an unmapped batch route in Result.code=500.
                try {
                    rsdpClient.get(CATALOG_PAGE_PATH + "?page=1&size=1&viewMode=full");
                } catch (BusinessException probe) {
                    absent = missingCatalogEndpoint(probe);
                }
            }
            if (!absent) throw error;
            legacyCatalogUntil = System.currentTimeMillis() + LEGACY_CATALOG_RECHECK_MS;
            return legacy.get();
        }
    }

    private boolean missingCatalogEndpoint(BusinessException error) {
        return error.getCode() == 404 || error.getCode() == 405;
    }

    private List<VariantBatch> readVariantBatches(String endpoint, String scope, Set<String> ids) {
        List<String> sorted = ids.stream().filter(id -> StringUtils.hasText(id) && id.length() <= 64).sorted().toList();
        List<VariantBatch> result = new ArrayList<>();
        for (int start = 0; start < sorted.size(); start += VARIANT_LOOKUP_BATCH_SIZE) {
            List<String> batch = sorted.subList(start, Math.min(start + VARIANT_LOOKUP_BATCH_SIZE, sorted.size()));
            var query = new StringJoiner("&", "/api/v1/products/variants" + endpoint + "?", "");
            if (!scope.isEmpty()) query.add(scope);
            batch.forEach(id -> query.add("variantId=" + encode(id)));
            String path = query.toString();
            VariantBatch entry = variantLookupCache.get(path);
            if (entry == null || System.currentTimeMillis() - entry.fetchedAt() >= OFFICE_PRODUCTS_CACHE_TTL_MS) {
                JsonNode data = rsdpClient.get(path);
                if (!data.isObject()) throw new BusinessException("主项目规格批量查询结果格式无效");
                entry = new VariantBatch(List.copyOf(batch), data, System.currentTimeMillis());
                variantLookupCache.put(path, entry);
            }
            result.add(entry);
        }
        return result;
    }

    /** 排版选品目录：主项目全量产品的有效变体平铺（含尺寸/价格/图片），30s 缓存。 */
    public List<OfficeProductDto> listOfficeProducts(String viewMode) {
        if ("none".equals(viewMode)) return List.of();
        CacheEntry cached = freshOfficeProducts(viewMode);
        if (cached != null) return cached.data();

        // The upstream service account is fixed. The view mode distinguishes the shared catalog
        // from the single managed factory binding that the caller has already authorized.
        var refresh = new CompletableFuture<CacheEntry>();
        var activeRefresh = officeProductsInFlight.putIfAbsent(viewMode, refresh);
        if (activeRefresh != null) {
            try {
                return activeRefresh.join().data();
            } catch (CompletionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw failure;
            }
        }

        try {
            // A previous refresh may have finished between checking the cache and taking ownership.
            cached = freshOfficeProducts(viewMode);
            if (cached == null) {
                cached = new CacheEntry(List.copyOf(fetchOfficeProducts(viewMode)), System.currentTimeMillis());
                officeProductsCache.put(viewMode, cached);
            }
            refresh.complete(cached);
            return cached.data();
        } catch (RuntimeException | Error failure) {
            refresh.completeExceptionally(failure);
            throw failure;
        } finally {
            officeProductsInFlight.remove(viewMode, refresh);
        }
    }

    private CacheEntry freshOfficeProducts(String viewMode) {
        CacheEntry cached = officeProductsCache.get(viewMode);
        return cached != null && System.currentTimeMillis() - cached.fetchedAt() < OFFICE_PRODUCTS_CACHE_TTL_MS
            ? cached : null;
    }

    public OfficeProductDto currentVariant(String viewMode, String rspuId, String variantId) {
        if ("none".equals(viewMode) || rspuId == null) return null;
        List<ProductListItemDto> matches = readProducts(viewMode, true, rspuId);
        if (matches.isEmpty()) {
            return null;
        }
        return toOfficeProductDtos(matches.getFirst()).stream()
            .filter(item -> variantId.equals(item.getId())).findFirst().orElse(null);
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
        return executor.submit(() -> {
            try {
                variantFetchPermits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            try {
                return toOfficeProductDtos(product);
            } finally {
                variantFetchPermits.release();
            }
        });
    }

    private List<OfficeProductDto> toOfficeProductDtos(ProductListItemDto product) {
        if (product.getStatus() != null && !"active".equals(product.getStatus())) return List.of();
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
            dto.setColorPrimaryName(product.getColorPrimaryName());
            dto.setCategoryPath(product.getCategoryPath());
            dto.setFurnitureTags(product.getFurnitureTags());
            dto.setVariantName(textOrNull(v, "displayName"));
            dto.setVariant(buildVariantText(v));
            dto.setPrice(product.getRetailPrice() != null ? product.getRetailPrice() : BigDecimal.ZERO);
            dto.setKind("other");
            dto.setUses(List.of("OTHER"));
            dto.setImageUrl(product.getPrimaryImageUrl());
            applyDimensions(dto, v.path("dimensions"));
            rows.add(dto);
        });
        // The main list carries categoryPath; sixDimTags normally arrive in product detail.
        // Enrich ambiguous products once per catalog refresh under the existing concurrency limit.
        if (rows.stream().anyMatch(row -> row.getWidthMm() != null && row.getDepthMm() != null && OfficeFurnitureRelevance.needsTags(row))) {
            enrichFurnitureTags(product.getRspuId(), rows);
        }
        return rows;
    }

    private void enrichFurnitureTags(String rspuId, List<OfficeProductDto> rows) {
        try {
            JsonNode rspu = rsdpClient.get("/api/v1/products/" + rspuId).path("rspu");
            List<String> path = textValues(embeddedJson(rspu.path("categoryPath")));
            List<String> tags = furnitureTags(rspu.path("sixDimTags"));
            for (OfficeProductDto row : rows) {
                if (!path.isEmpty()) row.setCategoryPath(path);
                row.setFurnitureTags(tags);
            }
        } catch (BusinessException failure) {
            log.debug("读取商品 {} 的六维标签失败", rspuId, failure);
        }
    }

    private List<String> furnitureTags(JsonNode raw) {
        JsonNode tags = embeddedJson(raw);
        List<String> values = new ArrayList<>();
        // E describes material, which is not evidence of the furniture's use.
        for (String dimension : List.of("A", "B", "C", "D", "F")) values.addAll(textValues(tags.path(dimension)));
        return values;
    }

    private JsonNode embeddedJson(JsonNode node) {
        if (!node.isTextual()) return node;
        String value = node.textValue().strip();
        if (value.startsWith("[") || value.startsWith("{")) {
            try { return objectMapper.readTree(value); }
            catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { return objectMapper.missingNode(); }
        }
        return node;
    }

    private List<String> textValues(JsonNode node) {
        if (node.isTextual() && !node.textValue().isBlank()) return List.of(node.textValue().strip());
        List<String> values = new ArrayList<>();
        if (node.isArray()) for (JsonNode value : node) {
            if (value.isTextual() && !value.textValue().isBlank()) values.add(value.textValue().strip());
        }
        return values;
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

    /** 主项目尺寸 JSON → 毫米 + 厘米上取整；支持当前 w/d/h 短键和既有 widthMm/widthCm 长键。 */
    private void applyDimensions(OfficeProductDto dto, JsonNode dimensions) {
        try {
            BigDecimal[] exact = readMainDimensions(dimensions);
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

    private BigDecimal[] readMainDimensions(JsonNode dimensions) {
        return OfficeProductMatching.dimensions(normalizeMainDimensions(embeddedJson(dimensions)));
    }

    private String formatMainDimensions(JsonNode dimensions) {
        BigDecimal[] sizeMm = readMainDimensions(dimensions);
        String[] axes = {"宽", "深", "高"};
        boolean labelAxes = sizeMm[0] == null || sizeMm[1] == null;
        StringJoiner label = new StringJoiner(" × ", "", " mm").setEmptyValue("");
        for (int axis = 0; axis < sizeMm.length; axis++) {
            if (sizeMm[axis] != null) {
                String prefix = labelAxes ? axes[axis] + " " : "";
                label.add(prefix + sizeMm[axis].stripTrailingZeros().toPlainString());
            }
        }
        return label.length() == 0 ? null : label.toString();
    }

    /** 主项目短键按毫米契约解释（含缺省 unit 的旧记录）；unit=cm 时改走厘米长键，其他单位仍由匹配器拒绝。 */
    private JsonNode normalizeMainDimensions(JsonNode source) {
        if (!source.isObject() || source.has("widthMm") || source.has("depthMm") || source.has("heightMm")
            || source.has("widthCm") || source.has("depthCm") || source.has("heightCm")
            || !(source.has("w") || source.has("d") || source.has("h"))) {
            return source;
        }
        String suffix = "cm".equalsIgnoreCase(source.path("unit").asText("")) ? "Cm" : "Mm";
        ObjectNode normalized = source.deepCopy();
        copyDimension(source, normalized, "w", "width" + suffix);
        copyDimension(source, normalized, "d", "depth" + suffix);
        copyDimension(source, normalized, "h", "height" + suffix);
        return normalized;
    }

    private void copyDimension(JsonNode source, ObjectNode target, String shortKey, String longKey) {
        if (source.has(shortKey)) {
            target.set(longKey, source.get(shortKey));
        }
    }

    /**
     * 拉取主项目产品列表（全量翻页）。
     *
     * @param viewMode own=仅本厂已报价产品；full=全库只读（主项目按服务账号身份控制）
     */
    public List<ProductListItemDto> listProducts(String viewMode) {
        return readProducts(viewMode, "full".equals(viewMode), null);
    }

    public List<ProductListItemDto> listSharedCatalogForBrowse() {
        return readProducts("full", false, null);
    }

    public CatalogProductPageDto pageProducts(String viewMode, int page, int size, String search,
                                               String status, String rspuId) {
        if ("none".equals(viewMode)) return CatalogProductPageDto.empty(page, size);
        return withCatalogCompatibility(false, () -> loadProductPage(viewMode, page, size, search, status, rspuId),
            () -> pageLegacyProducts(viewMode, page, size, search, status, rspuId));
    }

    private CatalogProductPageDto loadProductPage(String viewMode, int page, int size, String search,
                                                  String status, String rspuId) {
        String path = CATALOG_PAGE_PATH + "?page=" + page + "&size=" + size + "&" + queryScope(viewMode);
        if (StringUtils.hasText(search)) path += "&search=" + encode(search);
        if (StringUtils.hasText(status)) path += "&status=" + encode(status);
        if (StringUtils.hasText(rspuId)) path += "&rspuId=" + encode(rspuId);
        JsonNode result = rsdpClient.get(path);
        if (!result.path("rows").isArray() || !result.path("catalogTotal").isIntegralNumber()
                || !result.path("activeTotal").isIntegralNumber() || !result.path("total").isIntegralNumber()) {
            throw new BusinessException("主项目商品分页结果格式无效");
        }
        List<ProductListItemDto> rows = new ArrayList<>();
        result.path("rows").forEach(row -> rows.add(toListItem(row)));
        return new CatalogProductPageDto(result.path("total").asLong(), result.path("catalogTotal").asLong(),
            result.path("activeTotal").asLong(), page, size, rows);
    }

    private CatalogProductPageDto pageLegacyProducts(String viewMode, int page, int size, String search,
                                                     String status, String rspuId) {
        List<ProductListItemDto> visible = readLegacyProducts(viewMode, "full".equals(viewMode), null);
        String term = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        List<ProductListItemDto> matching = visible.stream()
            .filter(row -> rspuId == null || rspuId.equals(row.getRspuId()))
            .filter(row -> !StringUtils.hasText(status) || "active".equals(status) == "active".equals(row.getStatus()))
            .filter(row -> term.isEmpty() || java.util.stream.Stream.of(row.getProductName(), row.getRspuCode(),
                row.getExternalCode(), row.getRspuId()).anyMatch(value -> value != null && value.toLowerCase(Locale.ROOT).contains(term)))
            .toList();
        int start = (int) Math.min((page - 1L) * size, matching.size());
        long active = visible.stream().filter(row -> "active".equals(row.getStatus())).count();
        return new CatalogProductPageDto(matching.size(), visible.size(), active, page, size,
            matching.subList(start, Math.min(start + size, matching.size())));
    }

    private String queryScope(String viewMode) {
        if ("full".equals(viewMode)) return "viewMode=full";
        if (!"managed".equals(viewMode)) throw new BusinessException("商品目录范围无效");
        String factoryCode = rsdpClient.catalogFactoryCode();
        if (!StringUtils.hasText(factoryCode)) throw new BusinessException("尚未配置绑定工厂");
        return "viewMode=managed&factoryCode=" + encode(factoryCode);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 使用主库返回的可见列表。旧版主库会忽略列表请求中的 rspuId 参数。 */
    private List<ProductListItemDto> readProducts(String viewMode, boolean activeOnly, String targetId) {
        if ("managed".equals(viewMode)) {
            List<ProductListItemDto> products = new ArrayList<>();
            int size = targetId == null ? 100 : 1;
            for (int page = 1; ; page++) {
                CatalogProductPageDto result = pageProducts(viewMode, page, size, "", activeOnly ? "active" : "", targetId);
                products.addAll(result.rows());
                if (products.size() >= result.total() || result.rows().isEmpty()) return products;
            }
        }
        return readLegacyProducts(viewMode, activeOnly, targetId);
    }

    private List<ProductListItemDto> readLegacyProducts(String viewMode, boolean activeOnly, String targetId) {
        if ("none".equals(viewMode)) return List.of();
        String scope = queryScope(viewMode);
        boolean managed = "managed".equals(viewMode);
        // Older lists can ignore managed mode. Keep only ownership explicitly verified by RSDP.
        String factoryCode = managed ? rsdpClient.catalogFactoryCode() : null;
        List<ProductListItemDto> all = new ArrayList<>();
        long rowsRead = 0;
        int page = 1;
        while (true) {
            JsonNode data = rsdpClient.get("/api/v1/products?page=" + page + "&size=" + PAGE_SIZE
                + "&" + scope + (activeOnly ? "&status=active" : ""));
            JsonNode rows = data.path("rows");
            if (!rows.isArray() || rows.isEmpty()) {
                break;
            }
            for (JsonNode row : rows) {
                if (managed && (!row.path("ownerVerified").asBoolean()
                        || !factoryCode.equals(row.path("ownerFactoryCode").asText()))) continue;
                if (targetId == null) {
                    all.add(toListItem(row));
                } else if (targetId.equals(row.path("rspuId").asText())) {
                    return List.of(toListItem(row));
                }
            }
            rowsRead += rows.size();
            if (rowsRead >= data.path("total").asLong(rowsRead)) {
                break;
            }
            page++;
        }
        return all;
    }

    /** 读取指定商品在当前视图中的记录，详情与列表复用主库范围。 */
    public boolean inView(String rspuId, String viewMode) {
        return rspuId != null && !readProducts(viewMode, "full".equals(viewMode), rspuId).isEmpty();
    }

    /** 拉取商品资料供已核验的设计流程使用。 */
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
        dto.setSourceChannel(textOrNull(rspu, "sourceChannel"));
        dto.setSourceFactoryName(textOrNull(detail, "sourceFactoryName"));
        if (rspu.path("ownerVerified").asBoolean()) {
            dto.setOwnerFactoryCode(textOrNull(rspu, "ownerFactoryCode"));
        }
        dto.setColorPrimaryName(textOrNull(rspu, "colorPrimaryName"));
        dto.setDescription(textOrNull(rspu, "description"));
        dto.setRetailPrice(decimalOrNull(rspu, "retailPrice"));
        dto.setStatus(textOrNull(rspu, "status"));
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

        JsonNode variants = rsdpClient.get("/api/v1/products/" + rspuId + "/variants");
        if (variants.isArray()) {
            variants.forEach(v -> {
                ProductDetailDto.Variant variant = new ProductDetailDto.Variant();
                variant.setVariantId(v.path("variantId").asText());
                variant.setDisplayName(textOrNull(v, "displayName"));
                variant.setSizeCode(textOrNull(v, "sizeCode"));
                variant.setSizeText(textOrNull(v, "sizeText"));
                variant.setDimensions(textOrNull(v, "dimensions"));
                variant.setDimensionsText(formatMainDimensions(v.path("dimensions")));
                variant.setColorCode(textOrNull(v, "colorCode"));
                variant.setColorText(textOrNull(v, "colorText"));
                variant.setMaterialCode(textOrNull(v, "materialCode"));
                variant.setMaterialText(textOrNull(v, "materialText"));
                variant.setStatus(textOrNull(v, "status"));
                variant.setCreatedAt(dateOrNull(v, "createdAt"));
                variant.setUpdatedAt(dateOrNull(v, "updatedAt"));
                dto.getVariants().add(variant);
            });
        }
        return dto;
    }

    /** 读取主项目授权的工厂报价，仅供 Office 报价服务使用。 */
    public Map<String, BigDecimal> getFactoryPrices(String rspuId) {
        JsonNode quotes = rsdpClient.get("/api/v1/products/" + rspuId + "/rsku");
        Map<String, BigDecimal> prices = new HashMap<>();
        if (quotes.isArray()) {
            for (JsonNode quote : quotes) {
                BigDecimal price = decimalOrNull(quote, "factoryPrice");
                if (price != null) {
                    prices.merge(quote.path("variantId").asText(""), price, BigDecimal::min);
                }
            }
        }
        return prices;
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
        dto.setExternalCode(textOrNull(row, "externalCode"));
        dto.setSourceChannel(textOrNull(row, "sourceChannel"));
        dto.setSourceFactoryName(textOrNull(row, "sourceFactoryName"));
        dto.setOwnerFactoryCode(textOrNull(row, "ownerFactoryCode"));
        dto.setProductName(textOrNull(row, "productName"));
        dto.setColorPrimaryName(textOrNull(row, "colorPrimaryName"));
        dto.setCategoryPath(textValues(embeddedJson(row.path("categoryPath"))));
        dto.setFurnitureTags(furnitureTags(row.path("sixDimTags")));
        String imageUrl = textOrNull(row, "primaryImageUrl");
        dto.setPrimaryImageUrl(imageUrl == null ? null : absolutize(imageUrl));
        dto.setRetailPrice(decimalOrNull(row, "retailPrice"));
        dto.setStatus(textOrNull(row, "status"));
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
