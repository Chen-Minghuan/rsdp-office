package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.config.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 项目报价单头：确认后不可修改，新版本递增 revision 并把旧版置为 SUPERSEDED。 */
@Data
@TableName("office_quotation")
public class OfficeQuotation {

    @TableId
    private String quotationId;

    private String projectId;
    /** 确认时的项目名称快照，项目改名不影响历史报价单。 */
    private String projectName;
    private String workspaceId;
    private Integer revision;
    private String status;
    private String pricingMode;
    private BigDecimal pricingValue;
    private BigDecimal goodsTotal;
    private BigDecimal serviceFee;
    private BigDecimal logisticsFee;
    private BigDecimal totalAmount;
    private Integer lineCount;
    private Integer missingPriceLines;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String snapshotJson;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime supersededAt;
}
