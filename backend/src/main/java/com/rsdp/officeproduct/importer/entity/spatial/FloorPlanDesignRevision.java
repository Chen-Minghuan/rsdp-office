package com.rsdp.officeproduct.importer.entity.spatial;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.config.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("floor_plan_design_revision")
public class FloorPlanDesignRevision {

    private String sceneId;
    private Long revision;
    private Long parentRevision;
    private String clientChangeId;
    private String action;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String snapshotJson;

    private String createdBy;
    private LocalDateTime createdAt;
}
