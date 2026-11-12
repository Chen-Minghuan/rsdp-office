package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RsdpMainProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * office 商品录入/导入的写路径网关：把 office 客户维护的商品转发写入主项目（RSDP），
 * 数据落主库并归属服务账号的本厂身份（factoryCode 取主项目 /auth/me 的关联工厂）。
 *
 * <p>office 本地不存商品数据；本类只做"组装主项目录入请求 + 转发 + 读取结果"。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MainProductEntryGateway {

    private final RsdpClient rsdpClient;
    private final RsdpMainProperties properties;

    /** 待录入的一行商品（office 导入行/手工录入表单的归一化形态）。 */
    public record EntryPayload(
        String externalCode,
        String productName,
        String colorPrimaryName,
        String description,
        BigDecimal retailPrice,
        String variantDisplayName,
        String sizeCode,
        String sizeText,
        Integer widthMm,
        Integer depthMm,
        Integer heightMm,
        String colorCode,
        String colorText,
        String materialCode,
        String materialText,
        BigDecimal factoryPrice,
        Integer moq,
        Integer leadTimeDays,
        List<String> imageUrls
    ) {}

    /** 录入结果。 */
    public record EntryResult(String rspuId, String variantId, String rskuId, String rspuCode, int imageCount) {}

    /** 按外部编码在主项目查找已有商品（更新判定用），无则返回 null。 */
    public String findRspuIdByExternalCode(String externalCode) {
        if (externalCode == null || externalCode.isBlank()) {
            return null;
        }
        String encoded = URLEncoder.encode(externalCode.trim(), StandardCharsets.UTF_8);
        JsonNode data = rsdpClient.get("/api/v1/products?externalCode=" + encoded + "&viewMode=full&size=1");
        JsonNode rows = data.path("rows");
        if (!rows.isArray() || rows.isEmpty()) {
            return null;
        }
        return rows.get(0).path("rspuId").asText();
    }

    /**
     * 转发录入一个新商品（主项目 factory-entry：建 RSPU + 默认变体 + 本厂报价 + 图片）。
     */
    public EntryResult forwardFactoryEntry(EntryPayload payload) {
        return forwardFactoryEntry(payload,
            properties.getDefaultCategoryCode(), properties.getDefaultStyleCode(), properties.getDefaultProductLevel());
    }

    /** 材质码兜底：行内码/原文都无法归一为主项目字典时，用配置的默认材质码（主项目要求变体主材质码必填）。 */
    public String effectiveMaterialCode(String resolvedCode) {
        return resolvedCode != null ? resolvedCode : properties.getDefaultMaterialCode();
    }

    /**
     * 转发录入一个新商品（主项目 factory-entry：建 RSPU + 默认变体 + 本厂报价 + 图片）。
     */
    public EntryResult forwardFactoryEntry(EntryPayload payload, String categoryCode, String styleCode, String productLevel) {
        String factoryCode = rsdpClient.mainFactoryCode();
        Map<String, Object> request = new HashMap<>();
        request.put("categoryCode", categoryCode);
        request.put("positioningLabel", styleCode);
        request.put("productLevel", productLevel);
        request.put("productName", payload.productName());
        request.put("externalCode", payload.externalCode());
        request.put("colorPrimaryName", payload.colorPrimaryName());
        request.put("factoryCode", factoryCode);
        request.put("variantDisplayName",
            payload.variantDisplayName() != null && !payload.variantDisplayName().isBlank()
                ? payload.variantDisplayName() : "默认规格");
        request.put("sizeCode", payload.sizeCode());
        request.put("dimensions", dimensionsJson(payload));
        request.put("colorCode", payload.colorCode());
        request.put("variantMaterialCode", payload.materialCode());
        request.put("factoryPrice", payload.factoryPrice());
        request.put("materialDescription", payload.materialText());
        request.put("leadTimeDays", payload.leadTimeDays());
        request.put("moq", payload.moq());

        List<RsdpClient.ImagePart> images = resolveImages(payload.imageUrls());
        JsonNode data = rsdpClient.postMultipart("/api/v1/products/factory-entry", request, images);
        EntryResult result = new EntryResult(
            data.path("rspuId").asText(),
            data.path("variantId").asText(),
            data.path("rskuId").asText(),
            data.path("rspuCode").asText(null),
            images.size());

        // factory-entry 不含零售价/描述，补上（主项目 PUT 产品接口）
        Map<String, Object> patch = new HashMap<>();
        if (payload.retailPrice() != null) {
            patch.put("retailPrice", payload.retailPrice());
        }
        if (payload.description() != null && !payload.description().isBlank()) {
            patch.put("description", payload.description());
        }
        if (!patch.isEmpty()) {
            rsdpClient.putJson("/api/v1/products/" + result.rspuId(), patch);
        }
        return result;
    }

    /**
     * 给主项目已有商品追加变体，返回 variantId。
     */
    public String addVariant(String rspuId, EntryPayload payload) {
        Map<String, Object> request = new HashMap<>();
        request.put("displayName",
            payload.variantDisplayName() != null && !payload.variantDisplayName().isBlank()
                ? payload.variantDisplayName() : "默认规格");
        request.put("sizeCode", payload.sizeCode());
        request.put("dimensions", dimensionsJson(payload));
        request.put("colorCode", payload.colorCode());
        request.put("materialCode", payload.materialCode());
        JsonNode data = rsdpClient.postJson("/api/v1/products/" + rspuId + "/variants", request);
        return data.path("variantId").asText();
    }

    /** 给主项目已有商品/变体追加本厂报价，返回 rskuId。 */
    public String addQuote(String rspuId, String variantId, EntryPayload payload) {
        if (payload.factoryPrice() == null) {
            return null;
        }
        Map<String, Object> request = new HashMap<>();
        request.put("factoryCode", rsdpClient.mainFactoryCode());
        request.put("variantId", variantId);
        request.put("factoryPrice", payload.factoryPrice());
        request.put("materialCode", payload.materialCode());
        request.put("materialDescription", payload.materialText());
        request.put("leadTimeDays", payload.leadTimeDays());
        request.put("moq", payload.moq());
        JsonNode data = rsdpClient.postJson("/api/v1/products/" + rspuId + "/rsku", request);
        return data.path("rskuId").asText(null);
    }

    /** 更新主项目已有商品的基础字段（更新模式）。 */
    public void updateBaseFields(String rspuId, EntryPayload payload) {
        Map<String, Object> patch = new HashMap<>();
        if (payload.productName() != null && !payload.productName().isBlank()) {
            patch.put("productName", payload.productName());
        }
        if (payload.colorPrimaryName() != null && !payload.colorPrimaryName().isBlank()) {
            patch.put("colorPrimaryName", payload.colorPrimaryName());
        }
        if (payload.retailPrice() != null) {
            patch.put("retailPrice", payload.retailPrice());
        }
        if (payload.description() != null && !payload.description().isBlank()) {
            patch.put("description", payload.description());
        }
        if (!patch.isEmpty()) {
            rsdpClient.putJson("/api/v1/products/" + rspuId, patch);
        }
    }

    /** 主项目已有商品的变体列表（更新模式的变体匹配用）。 */
    public JsonNode listVariants(String rspuId) {
        return rsdpClient.get("/api/v1/products/" + rspuId + "/variants");
    }

    /** 尺寸毫米值转主项目变体 dimensions JSON（宽/深/高全有才下发）。 */
    private String dimensionsJson(EntryPayload payload) {
        if (payload.widthMm() == null || payload.depthMm() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{\"w\":").append(payload.widthMm())
            .append(",\"d\":").append(payload.depthMm());
        if (payload.heightMm() != null) {
            sb.append(",\"h\":").append(payload.heightMm());
        }
        sb.append(",\"unit\":\"mm\"}");
        return sb.toString();
    }

    /** 图片地址（office 本地上传或外部 URL）→ 下载字节转 multipart 部件。 */
    private List<RsdpClient.ImagePart> resolveImages(List<String> imageUrls) {
        List<RsdpClient.ImagePart> parts = new ArrayList<>();
        if (imageUrls == null) {
            return parts;
        }
        for (String url : imageUrls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            try {
                parts.add(new RsdpClient.ImagePart(filenameOf(url), readImageBytes(url)));
            } catch (Exception e) {
                log.warn("转发图片读取失败，跳过: {}", url, e);
            }
        }
        return parts;
    }

    private byte[] readImageBytes(String url) throws IOException {
        // office 本地上传的图片（/uploads/images/xxx.png 或同源绝对地址）从磁盘读
        String path = URI.create(url).getPath();
        if (path != null && path.startsWith("/uploads/images/")) {
            Path file = Paths.get(System.getProperty("user.dir"), "uploads", "images",
                path.substring("/uploads/images/".length()));
            return Files.readAllBytes(file);
        }
        // 外部 URL 走 HTTP 下载
        try (var in = URI.create(url).toURL().openStream()) {
            return in.readAllBytes();
        }
    }

    private String filenameOf(String url) {
        String path = URI.create(url).getPath();
        String name = path == null ? "image.png" : path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() ? "image.png" : name;
    }
}
