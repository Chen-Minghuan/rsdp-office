package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 已确认报价的明细行（含楼层与空间快照）。 */
@Data
public class QuotationRecordLineDto {

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
    private BigDecimal subtotal;
    private String issue;
}
