package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.config.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_design_project")
public class OfficeDesignProject {

    @TableId
    private String projectId;
    private String name;
    private String customerRequirements;
    private Integer requirementsRevision;

    /** 项目参考图片 JSON：[{"url": "/uploads/images/xxx.png", "name": "参考"}] */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String referenceImages;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
