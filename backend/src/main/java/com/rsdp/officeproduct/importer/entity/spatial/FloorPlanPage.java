package com.rsdp.officeproduct.importer.entity.spatial;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.config.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("floor_plan_page")
public class FloorPlanPage {

    @TableId
    private String pageId;

    private String assetId;
    private Integer pageNumber;
    private Integer processingRevision;
    private Integer widthPx;
    private Integer heightPx;
    private String underlayAssetId;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String pageTransform;

    private String createdBy;
    private LocalDateTime createdAt;
}
