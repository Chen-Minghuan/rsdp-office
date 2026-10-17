package com.rsdp.officeproduct.importer.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 户型图分析详情响应。 */
@Data
public class FloorPlanAnalysisResponse {

    private String analysisId;
    private String imageId;
    private String imageUrl;
    private String referenceImageUrl;
    private String previewImageId;
    private String previewUrl;
    private String status;
    private String taskId;
    private BigDecimal scaleRatio;
    private String projectId;
    private String sourceName;
    private String errorMessage;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<FloorPlanRoomResponse> rooms;

    /** ai_vision / cad_geometry / manual_geometry。 */
    private String geometrySource;

    private List<FloorPlanQualityIssue> qualityIssues;

    private FloorPlanDrawingBounds drawingBounds;
    private FloorPlanDrawingBounds previewBounds;

    /** 本次响应装配的 Analysis Revision（默认 working ?? active ?? latest）。 */
    private Integer analysisRevision;

    private String revisionStatus;
    private Integer activeAnalysisRevision;
    private Integer workingAnalysisRevision;
    private Integer latestAnalysisRevision;
    private Integer roomRevision;
    private Integer furnitureRevision;
    private String parseStatus;
    private Object unitResolution;
    private DimensionCalibrationReport dimensionCalibration;
    private String imageSha256;
    /** Manual design overlay only; rooms above remain the new recognition output. */
    private List<DesignContourCorrection> designCorrections;

    private String inputMode;
    private JsonNode layout;
    private Long layoutRevision;
}
