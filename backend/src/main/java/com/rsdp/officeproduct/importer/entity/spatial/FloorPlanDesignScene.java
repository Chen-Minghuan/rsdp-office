package com.rsdp.officeproduct.importer.entity.spatial;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("floor_plan_design_scene")
public class FloorPlanDesignScene {

    @TableId
    private String sceneId;

    private String projectId;
    private String pageId;
    private String sourceSceneId;
    private String failedRunId;
    private Long currentRevision;
    private Long scopeRevision;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
