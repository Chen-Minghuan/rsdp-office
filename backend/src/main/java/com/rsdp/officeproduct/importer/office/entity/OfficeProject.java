package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.config.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("office_project")
public class OfficeProject {

    @TableId
    private String projectId;

    private String projectName;
    private Integer area;
    private Integer headcount;
    private BigDecimal budget;
    private String style;
    private String description;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String contextJson;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
