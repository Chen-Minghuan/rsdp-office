package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OfficeFloorDto {
    private String floorId;
    private String projectId;
    private String name;
    private Integer sortOrder;
    private List<ProjectReferenceImage> referenceImages = List.of();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
