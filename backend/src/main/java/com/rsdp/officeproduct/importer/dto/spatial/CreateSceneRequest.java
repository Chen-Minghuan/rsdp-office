package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

@Data
public class CreateSceneRequest {

    public static final String MODE_FROM_RECOGNITION = "FROM_RECOGNITION";
    public static final String MODE_EMPTY_PAGE = "EMPTY_PAGE";

    private String schemaVersion;
    private String clientChangeId;
    private String projectId;
    private String pageId;
    private String initializationMode;
    private String sourceSceneId;
    private String failedRunId;
}
