package com.rsdp.officeproduct.importer.dto.response;

import com.rsdp.officeproduct.importer.dto.spatial.SpatialSceneSnapshot;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SpaceTemplateResponse {
    private String templateId;
    private String projectId;
    private String name;
    private int version = 1;
    private String contentHash;
    private Long sourceCanvasRevision;
    private String sourceAnalysisId;
    private Integer sourceAnalysisRevision;
    private int spaceCount;
    /** Sum of individual room areas, not the union of overlapping rooms. */
    private BigDecimal areaM2;
    private String evidenceKind;
    private LocalDateTime createdAt;
    private SpatialSceneSnapshot scene;
}
