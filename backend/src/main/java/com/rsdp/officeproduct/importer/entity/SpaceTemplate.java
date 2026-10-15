package com.rsdp.officeproduct.importer.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SpaceTemplate {
    private String templateId;
    private String projectId;
    private String name;
    private String clientChangeId;
    private String contentHash;
    private Long sourceCanvasRevision;
    private String sourceAnalysisId;
    private Integer sourceAnalysisRevision;
    private int spaceCount;
    private BigDecimal areaM2;
    private String evidenceKind;
    private String sceneJson;
    private LocalDateTime createdAt;
}
