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
 * 户型 Scene 家具对象实体（从 RSDP 抽取并简化）。
 *
 * <p>逻辑键 (analysis_id, analysis_revision, furniture_id)。</p>
 */
@Data
@TableName("floor_plan_furniture")
public class FloorPlanFurniture {

    public static final String SOURCE_KIND_INSERT = "INSERT";
    public static final String SOURCE_KIND_EXPLODED = "EXPLODED";
    public static final String SOURCE_KIND_MANUAL = "MANUAL";

    public static final String CONFIRM_AUTO = "AUTO";
    public static final String CONFIRM_CONFIRMED = "CONFIRMED";
    public static final String CONFIRM_MANUAL_ADDED = "MANUAL_ADDED";
    public static final String CONFIRM_MANUAL_EDITED = "MANUAL_EDITED";

    @TableId
    private String furnitureId;

    private String analysisId;
    private Integer analysisRevision;

    /** INSERT / EXPLODED / MANUAL。 */
    private String sourceKind;

    /** SOFA / TABLE / CHAIR / CABINET / OTHER / UNKNOWN。 */
    private String category;

    private String semanticLabel;

    /** 归属房间（floor_plan_room.room_id）。 */
    private String roomId;

    private String blockName;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sourceHandles;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String insertPath;

    /** 插入点 {"x":..,"y":..} 毫米。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String position;

    private BigDecimal rotationDeg;

    /** 占地轮廓 [[x,y],...] 闭合多边形，毫米。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String footprintPolygon;

    private Integer geometryWidthMm;
    private Integer geometryDepthMm;
    private Integer annotatedWidthMm;
    private Integer annotatedDepthMm;
    private Integer effectiveWidthMm;
    private Integer effectiveDepthMm;
    private Integer heightMm;

    private String dimensionSource;
    private String semanticSource;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String evidence;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String qualityFlags;

    private String detectionConfidence;
    private String categoryConfidence;

    /** AUTO / CONFIRMED / MANUAL_ADDED / MANUAL_EDITED。 */
    private String confirmationStatus;

    private String matchedFromObjectId;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String derivedFromIds;

    private Integer sortOrder;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
