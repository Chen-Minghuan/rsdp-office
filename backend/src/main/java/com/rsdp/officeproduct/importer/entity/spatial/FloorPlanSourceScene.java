package com.rsdp.officeproduct.importer.entity.spatial;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.config.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("floor_plan_source_scene")
public class FloorPlanSourceScene {

    @TableId
    private String sourceSceneId;

    private String pageId;
    private String analysisId;
    private Integer analysisRevision;
    private String runId;
    private String inputHash;
    private String serviceVersion;
    private String parseStatus;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sourceJson;

    private String rawResultHash;
    private String createdBy;
    private LocalDateTime createdAt;
}
