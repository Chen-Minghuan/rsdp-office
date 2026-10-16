package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

@Data
public class ModuleRecommendationRequest {
    private String zoneId;
    private String moduleId;
    private Integer canvasRevision;
    private boolean allowRotation = true;
    private String productId;
    private boolean applyToSameModules;
}
