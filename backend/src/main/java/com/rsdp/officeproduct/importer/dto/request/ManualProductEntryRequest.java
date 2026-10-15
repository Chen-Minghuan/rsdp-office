package com.rsdp.officeproduct.importer.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 手工录入/编辑商品请求（RSPU + 默认变体 + 报价）。 */
@Data
public class ManualProductEntryRequest {

    // RSPU 字段
    private String productName;
    private String colorPrimaryName;
    private String description;
    private BigDecimal retailPrice;
    private String externalCode;

    // 默认变体字段
    private String variantDisplayName;
    private String sizeCode;
    private String sizeText;
    private Integer widthMm;
    private Integer depthMm;
    private Integer heightMm;
    private String colorCode;
    private String colorText;
    private String materialCode;
    private String materialText;

    // 工厂报价（默认工厂）
    private BigDecimal factoryPrice;
    private Integer moq;
    private Integer leadTimeDays;

    // 图片
    private List<String> imageUrls = new ArrayList<>();
}
