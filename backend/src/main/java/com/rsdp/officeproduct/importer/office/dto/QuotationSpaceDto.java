package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 报价按空间（画布 zone）分组；未绑定空间的商品归入“未分区商品”。 */
@Data
public class QuotationSpaceDto {

    private String zoneId;
    private String zoneName;
    private List<QuotationLineDto> lines = new ArrayList<>();
    private BigDecimal factoryTotal = BigDecimal.ZERO;
    private BigDecimal dealTotal = BigDecimal.ZERO;
}
