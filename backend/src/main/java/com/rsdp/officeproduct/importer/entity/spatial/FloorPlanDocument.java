package com.rsdp.officeproduct.importer.entity.spatial;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("floor_plan_document")
public class FloorPlanDocument {

    @TableId
    private String assetId;

    private String projectId;
    private String sha256;
    private String mediaType;
    private String originalFilename;
    private String createdBy;
    private LocalDateTime createdAt;
}
