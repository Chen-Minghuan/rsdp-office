package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 项目报价试算结果：只读预览，不落库，不做底线拦截。 */
@Data
public class ProjectQuotationPreviewDto {

    private String projectId;
    private String projectName;
    /** COEFFICIENT=出厂价×系数；MARGIN=出厂价÷(1−毛利率)。 */
    private String pricingMode;
    /** 系数倍数，或毛利率小数（如 0.35 表示 35%）。 */
    private BigDecimal pricingValue;
    private LocalDateTime generatedAt;
    private List<QuotationFloorDto> floors = new ArrayList<>();
    private QuotationTotalsDto totals;
    private java.util.Map<String, String> proposalConfirmationIds = new java.util.LinkedHashMap<>();
}
