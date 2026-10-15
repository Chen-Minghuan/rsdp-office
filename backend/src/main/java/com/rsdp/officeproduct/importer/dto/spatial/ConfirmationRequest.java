package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.util.List;

@Data
public class ConfirmationRequest {

    public static final String SCOPE_SPACES = "SPACES";
    public static final String SCOPE_SCENE = "SCENE";

    private String schemaVersion;
    private Long baseRevision;
    private String clientChangeId;
    private String scope;
    private Long calibrationRevision;
    private Long scopeRevision;
    private String geometryPolicyVersion;
    private List<SpaceCondition> spaces;
}
