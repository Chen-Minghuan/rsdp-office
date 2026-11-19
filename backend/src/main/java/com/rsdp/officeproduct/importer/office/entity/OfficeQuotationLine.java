package com.rsdp.officeproduct.importer.office.entity;

import lombok.Data;

import java.math.BigDecimal;

/** 报价明细行：保存确认当时的价格与展示信息，供查询、导出与回溯。 */
@Data
public class OfficeQuotationLine {

    private String quotationId;
    private Integer lineNo;
    private String floorId;
    private String floorName;
    private String zoneId;
    private String zoneName;
    private String productId;
    private String rspuId;
    private String productName;
    private String variant;
    private String imageUrl;
    private Integer qty;
    private BigDecimal factoryPrice;
    private BigDecimal unitPrice;
    private String issue;
}
