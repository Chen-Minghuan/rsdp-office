package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 报价合计：缺出厂价的行单独计数，不静默按 0 计入。 */
@Data
public class QuotationTotalsDto {

    private Integer lineCount = 0;
    private Integer missingFactoryPriceLines = 0;
    private BigDecimal factoryTotal = BigDecimal.ZERO;
    private BigDecimal dealTotal = BigDecimal.ZERO;
}
