package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_design_floor")
public class OfficeDesignFloor {

    @TableId
    private String floorId;
    private String projectId;
    private String name;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
