package com.rsdp.officeproduct.importer.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.config.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 户型 Scene Revision 头表实体（从 RSDP 抽取并简化）。
 *
 * <p>联合主键 (analysis_id, analysis_revision)。不标 {@code @TableId}，查询/更新走 Mapper 自定义条件。</p>
 */
@Data
@TableName("floor_plan_scene_revision")
public class FloorPlanSceneRevision {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_EDITING = "EDITING";
    public static final String STATUS_READY_TO_ACTIVATE = "READY_TO_ACTIVATE";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    public static final String TRIGGER_INITIAL_PARSE = "INITIAL_PARSE";
    public static final String TRIGGER_MANUAL_EDIT = "MANUAL_EDIT";
    public static final String TRIGGER_LEGACY_IMPORT = "LEGACY_IMPORT";

    private String analysisId;
    private Integer analysisRevision;
    private Integer parentAnalysisRevision;

    /** INITIAL_PARSE / MANUAL_EDIT / LEGACY_IMPORT。 */
    private String revisionTrigger;

    /** SUCCESS / PARTIAL / NEEDS_REVIEW / FAILED。 */
    private String parseStatus;

    /** PROCESSING / READY / EDITING / READY_TO_ACTIVATE / ACTIVE / FAILED / SUPERSEDED。 */
    private String revisionStatus;

    private Integer roomRevision;
    private Integer furnitureRevision;
    private Integer roomSubmitBase;
    private String roomSubmitHash;
    private Integer furnitureSubmitBase;
    private String furnitureSubmitHash;

    private String parserVersion;
    private String parserConfigHash;
    private String sourceProfileVersion;
    private String sceneVersion;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String unitResolution;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String rawResult;

    private String previewImageId;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String qualityIssues;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String targetSceneCandidates;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String drawingBounds;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String previewBounds;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime readyAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime activatedAt;
    private String activatedBy;
}
