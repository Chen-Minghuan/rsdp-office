package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 单行报价：价格为本次试算快照，缺出厂价的行不参与金额合计。 */
@Data
public class QuotationLineDto {

    private String productId;
    private String rspuId;
    private String productName;
    private String imageUrl;
    private String variant;
    private Integer qty;
    private BigDecimal factoryPrice;
    /** 成交单价（系数或毛利口径）。 */
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
    /** 无法计价的原因；为空表示可计价。 */
    private String issue;
}
