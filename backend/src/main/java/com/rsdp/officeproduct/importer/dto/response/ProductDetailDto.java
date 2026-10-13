package com.rsdp.officeproduct.importer.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 商品详情（含变体、报价、图片）。 */
@Data
public class ProductDetailDto {

    private String rspuId;
    private String rspuCode;
    private String externalCode;
    private String categoryCode;
    private String categoryPath;
    private String positioningLabel;
    private String productName;
    private String colorPrimaryName;
    private String description;
    private BigDecimal retailPrice;
    private String referencePriceBand;
    private String productLevel;
    private Integer warrantyYears;
    private String sixDimTags;
    private String materialTags;
    private String sceneTags;
    private String keySpecs;
    private String primaryImageUrl;
    private String detailImageUrls;
    private String status;
    private String auditStatus;
    private String auditRemark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    private List<Variant> variants = new ArrayList<>();
    private List<Image> images = new ArrayList<>();

    @Data
    public static class Variant {
        private String variantId;
        private String displayName;
        private String sizeCode;
        private String sizeText;
        private String dimensions;
        private String colorCode;
        private String colorText;
        private String materialCode;
        private String materialText;
        private String referencePriceBand;
        private String productLevel;
        private String status;
        private BigDecimal factoryPrice;
        private Integer moq;
        private Integer leadTimeDays;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class Image {
        private String imageId;
        private String variantId;
        private String imageType;
        private String storageUrl;
        private Boolean primary;
        private Long fileSize;
        private Integer width;
        private Integer height;
        private String format;
        private LocalDateTime createdAt;
    }
}
