package com.rsdp.officeproduct.importer.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 户型图单个空间响应。 */
@Data
public class FloorPlanRoomResponse {

    private String roomId;
    private String roomType;
    private String label;

    /** 房间外环顶点，毫米坐标 [[x,y],...]。 */
    private List<List<Double>> polygon;

    /** 保证位于房间内部的标签锚点（毫米坐标）。 */
    private FloorPlanPoint labelPoint;

    /** 空间框（归一化坐标 [0,1]）。 */
    private FloorPlanBBox bbox;

    private Integer widthMm;
    private Integer depthMm;
    private BigDecimal areaM2;

    /** 尺寸来源：ocr_text / scale_calc / ai_estimate / manual / user_calib。 */
    private String dimensionSource;

    /** high / mid / low。 */
    private String dimensionConfidence;

    /** 图上尺寸标注原文。 */
    private String dimensionText;

    private Integer sortOrder;

    /** AUTO / CONFIRMED。 */
    private String confirmationStatus;

    /** 几何来源：ai_vision / cad_geometry / manual_geometry。 */
    private String geometrySource;
}
