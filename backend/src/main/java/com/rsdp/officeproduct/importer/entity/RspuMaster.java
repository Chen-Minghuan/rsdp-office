package com.rsdp.officeproduct.importer.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.config.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("rspu_master")
public class RspuMaster {

    @TableId
    private String rspuId;

    private String rspuCode;
    private String externalCode;
    private String categoryCode;
    private String categoryPath;
    private String positioningLabel;
    private String productName;
    private String colorPrimaryName;
    private String description;
    private BigDecimal retailPrice;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sixDimTags;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String materialTags;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sceneTags;

    private String referencePriceBand;
    private String productLevel;
    private Integer warrantyYears;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String keySpecs;

    private String primaryImageUrl;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String detailImageUrls;

    private String status;
    private String auditStatus;
    private String auditRemark;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
