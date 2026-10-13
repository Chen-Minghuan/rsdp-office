package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProjectDto {

    private String projectId;
    private String projectName;
    private Integer area;
    private Integer headcount;
    private BigDecimal budget;
    private String style;
    private String description;
}
