package com.rsdp.officeproduct.importer.entity.spatial;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.config.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("floor_plan_operation_receipt")
public class FloorPlanOperationReceipt {

    private String actorId;
    private String scopeKey;
    private String clientChangeId;
    private String action;
    private String requestHash;
    private Integer httpStatus;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String responseJson;

    private String sceneId;
    private Long sceneRevision;
    private LocalDateTime createdAt;
}
