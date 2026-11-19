package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 已确认报价单：头信息 + 明细行，只读。 */
@Data
public class ProjectQuotationRecordDto {

    private String quotationId;
    private String projectId;
    /** 确认时的项目名称快照。 */
    private String projectName;
    private Integer revision;
    private String status;
    private String pricingMode;
    private BigDecimal pricingValue;
    private BigDecimal goodsTotal;
    private BigDecimal serviceFee;
    private BigDecimal logisticsFee;
    private List<QuotationFeeDto> fees = new ArrayList<>();
    private BigDecimal totalAmount;
    private Integer lineCount;
    private Integer missingPriceLines;
    private String createdBy;
    private LocalDateTime createdAt;
    private List<QuotationRecordLineDto> lines = new ArrayList<>();
}
