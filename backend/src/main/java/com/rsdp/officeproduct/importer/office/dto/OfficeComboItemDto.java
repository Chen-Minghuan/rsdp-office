package com.rsdp.officeproduct.importer.office.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfficeComboItemDto {

    private String rspuId;
    private String productName;
    private String primaryImageUrl;
    private BigDecimal retailPrice;
    private boolean available;
}
