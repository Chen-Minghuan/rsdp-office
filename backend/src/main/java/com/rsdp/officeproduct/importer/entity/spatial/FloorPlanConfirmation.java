package com.rsdp.officeproduct.importer.entity.spatial;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.config.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("floor_plan_confirmation")
public class FloorPlanConfirmation {

    @TableId
    private String confirmationId;

    private String sceneId;
    private String kind;
    private String spaceId;
    private Long sceneRevision;
    private Long geometryRevision;
    private Long requirementRevision;
    private Long calibrationRevision;
    private Long scopeRevision;
    private String geometryPolicyVersion;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String snapshotJson;

    private String createdBy;
    private LocalDateTime createdAt;
}
