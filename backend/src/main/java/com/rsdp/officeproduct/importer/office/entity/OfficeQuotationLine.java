package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 报价明细行：保存确认当时的价格与展示信息，供查询、导出与回溯。 */
@Data
@TableName("office_quotation_line")
public class OfficeQuotationLine {

    @TableId
    private String lineId;

    private String quotationId;
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
    private Integer sortOrder;
}
