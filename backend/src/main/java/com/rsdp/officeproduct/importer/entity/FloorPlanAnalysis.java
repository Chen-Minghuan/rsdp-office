package com.rsdp.officeproduct.importer.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.config.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 户型图分析批次实体（从 RSDP 抽取并简化）。
 *
 * <p>状态机：pending → analyzing → awaiting_confirm → confirmed；失败为 failed。</p>
 */
@Data
@TableName("floor_plan_analysis")
public class FloorPlanAnalysis {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ANALYZING = "analyzing";
    public static final String STATUS_AWAITING_CONFIRM = "awaiting_confirm";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_FAILED = "failed";

    public static final String INPUT_MODE_IMAGE = "image";
    public static final String INPUT_MODE_PDF = "pdf";
    public static final String INPUT_MODE_CAD = "cad";
    public static final String INPUT_MODE_MANUAL = "manual";

    public static final String GEOMETRY_SOURCE_AI_VISION = "ai_vision";
    public static final String GEOMETRY_SOURCE_CAD_GEOMETRY = "cad_geometry";
    public static final String GEOMETRY_SOURCE_MANUAL_GEOMETRY = "manual_geometry";

    @TableId
    private String analysisId;

    private String projectId;
    private String sourceName;
    private String status;
    private String inputMode;
    private String geometrySource;

    private String imageId;
    private String previewImageId;
    private String taskId;

    /** 像素:实际毫米比例（视觉/PDF 校准后）。 */
    private BigDecimal scaleRatio;

    /** AI / CAD 原始识别结果快照。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String rawResult;

    /** 人工确认后的空间列表（legacy 兼容）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String confirmedRooms;

    /** 识别质量提示。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String qualityIssues;

    /** CAD 原文件对象键（重解析用）。 */
    private String cadObjectKey;

    /** 手画布局草稿 JSON。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String layoutJson;

    /** 手画布局乐观锁版本。 */
    private Long layoutRevision;

    /** 当前业务正式使用的 Revision。 */
    private Integer activeAnalysisRevision;

    /** 当前正在编辑的工作 Revision。 */
    private Integer workingAnalysisRevision;

    /** 已创建的最大 Revision 序号。 */
    private Integer latestAnalysisRevision;

    private String errorMessage;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
