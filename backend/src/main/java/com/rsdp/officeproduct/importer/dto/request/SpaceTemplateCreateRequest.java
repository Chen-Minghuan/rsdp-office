package com.rsdp.officeproduct.importer.dto.request;

import com.rsdp.officeproduct.importer.dto.spatial.SpatialSceneSnapshot;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class SpaceTemplateCreateRequest {
    @NotBlank @Size(max = 120) private String name;
    @NotBlank @Size(max = 64) private String clientChangeId;
    @NotNull @Min(0) private Long sourceCanvasRevision;
    @Min(1) private Integer sourceAnalysisRevision;
    @NotNull private SpatialSceneSnapshot scene;
}
