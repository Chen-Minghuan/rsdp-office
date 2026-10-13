package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.config.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_canvas")
public class OfficeCanvas {

    @TableId
    private Long canvasId;

    private String projectId;
    private Integer revision;
    private String mode;
    private String designZoneId;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String objectsJson;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String filesJson;

    private Boolean blank;
    private LocalDateTime createdAt;
}
