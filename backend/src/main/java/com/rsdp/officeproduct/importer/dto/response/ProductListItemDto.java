package com.rsdp.officeproduct.importer.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 已导入商品列表项。
 */
@Data
public class ProductListItemDto {

    private String rspuId;
    private String rspuCode;
    private String externalCode;
    private String productName;
    private String sourceChannel;
    private String ownerFactoryCode;
    private String sourceFactoryName;
    /** RSPU 主色原文，由主项目商品列表返回。 */
    private String colorPrimaryName;
    private List<String> categoryPath;
    @JsonIgnore
    private List<String> furnitureTags;
    private String primaryImageUrl;
    private BigDecimal retailPrice;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
