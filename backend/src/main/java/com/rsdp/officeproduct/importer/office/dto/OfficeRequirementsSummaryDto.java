package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.util.List;

@Data
public class OfficeRequirementsSummaryDto {
    private List<String> keyPoints;
    private Integer sourceRevision;
    private boolean cacheHit;
}
