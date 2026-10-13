package com.rsdp.officeproduct.importer.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 已导入商品列表项。
 */
@Data
public class ProductListItemDto {

    private String rspuId;
    private String rspuCode;
    private String externalCode;
    private String categoryCode;
    private String categoryPath;
    private String productName;
    private String positioningLabel;
    private String primaryImageUrl;
    private BigDecimal retailPrice;
    private String referencePriceBand;
    private String productLevel;
    private Integer warrantyYears;
    private String sixDimTags;
    private String status;
    private String auditStatus;
    private String auditRemark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
