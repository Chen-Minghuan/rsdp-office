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
 * 户型图空间识别明细实体（从 RSDP 抽取并简化）。
 *
 * <p>逻辑键：analysis_id + analysis_revision + room_id。</p>
 */
@Data
@TableName("floor_plan_room")
public class FloorPlanRoom {

    public static final String CONFIRMATION_STATUS_AUTO = "AUTO";
    public static final String CONFIRMATION_STATUS_CONFIRMED = "CONFIRMED";

    @TableId
    private String roomId;

    private String analysisId;
    private Integer analysisRevision;

    /** 空间类型（引用 room_type 字典，如 OPEN_OFFICE / MEETING_ROOM）。 */
    private String roomType;

    /** 空间名称（未命名时预填“未命名空间 N”）。 */
    private String label;

    /** 归一化 bbox {x,y,w,h} [0,1]。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String bbox;

    /** 房间外环顶点，毫米坐标 [[x,y],...]。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String polygon;

    /** 质心 {"x":..,"y":..} 毫米。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String centroid;

    /** 标签锚点 {"x":..,"y":..} 毫米。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String labelPoint;

    private Integer widthMm;
    private Integer depthMm;
    private BigDecimal areaM2;

    /** 尺寸来源：ocr_text / scale_calc / ai_estimate / manual / user_calib。 */
    private String dimensionSource;

    /** high / mid / low。 */
    private String dimensionConfidence;

    /** 图上尺寸标注原文（如 "4200×3800"）。 */
    private String dimensionText;

    /** ai_vision / cad_geometry / manual_geometry。 */
    private String geometrySource;

    /** AUTO / CONFIRMED。 */
    private String confirmationStatus;

    /** manual_geometry 的矩形组成块 JSON。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String shapeParts;

    /** 主布置矩形 JSON。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String placementZone;

    /** 全部布置区。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String placementZones;

    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
