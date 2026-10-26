package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
