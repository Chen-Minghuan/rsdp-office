package com.rsdp.officeproduct.importer.entity.spatial;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.config.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("floor_plan_calibration")
public class FloorPlanCalibration {

    private String sceneId;
    private Long calibrationRevision;
    private String pageId;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String referencePoints;

    private BigDecimal referenceLengthMm;
    private BigDecimal mmPerSourceUnit;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sourceToWorld;

    private String basis;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
}
