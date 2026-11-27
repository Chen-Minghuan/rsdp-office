package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.util.List;

@Data
public class ModuleRecommendationRequest {
    private String zoneId;
    private String moduleId;
    private Integer canvasRevision;
    private String schemeToken;
    private boolean allowRotation = true;
    private String productId;
    private boolean applyToSameModules;
    private List<String> targetIds;
}
