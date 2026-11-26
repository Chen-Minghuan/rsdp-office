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
    /** 楼层参考图 JSON 数组（[{url,name}]），与项目级参考图同一契约。 */
    private String referenceImages;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
