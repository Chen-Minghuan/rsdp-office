package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OfficeProjectDto {
    private String projectId;
    private String name;
    private String customerRequirements;
    private Integer requirementsRevision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<OfficeFloorDto> floors;
}
