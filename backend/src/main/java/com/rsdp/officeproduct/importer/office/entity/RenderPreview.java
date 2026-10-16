package com.rsdp.officeproduct.importer.office.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class RenderPreview {
    private String previewId;
    private String projectId;
    private String zoneId;
    private String zoneName;
    private Integer canvasRevision;
    private String purpose = "CONCEPT_PREVIEW";
    private String model;
    private String style;
    private String view;
    private String instructions;
    private String status;
    private String imageUrl;
    private String referenceUrl;
    private String errorMessage;
    private String providerTaskId;
    private String providerRequestId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @JsonIgnore private String clientChangeId;
    @JsonIgnore private String contentHash;
    @JsonIgnore private String snapshotJson;
    @JsonIgnore private String prompt;
    /** Keep the submission region so changing configuration cannot poll another region. */
    @JsonIgnore private String providerBaseUrl;
}
