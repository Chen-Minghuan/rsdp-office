package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class QuotationFeeDto {

    private String name;
    private BigDecimal amount;
}
