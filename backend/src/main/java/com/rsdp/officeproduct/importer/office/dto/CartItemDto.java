package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

@Data
public class CartItemDto {

    private String lineId;
    private String projectId;
    private String zoneId;
    private String zoneName;
    private String productId;
    private Integer qty;
}
