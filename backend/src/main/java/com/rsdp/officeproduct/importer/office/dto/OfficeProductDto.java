package com.rsdp.officeproduct.importer.office.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OfficeProductDto {

    private String id;
    private String rspuId;
    private String variantId;
    private BigDecimal widthMm;
    private BigDecimal depthMm;
    private BigDecimal heightMm;
    private String name;
    private List<String> categoryPath;
    @JsonIgnore
    private List<String> furnitureTags;
    @JsonIgnore
    private String variantName;
    private String desc;
    private String variant;
    private BigDecimal price;
    private String kind;
    private List<String> uses;
    private Integer widthCm;
    private Integer depthCm;
    private String imageUrl;
}
