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
@TableName("rspu_variant")
public class RspuVariant {

    @TableId
    private String variantId;

    private String rspuId;
    private String displayName;
    private String sizeCode;
    private String sizeText;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String dimensions;
    private Integer packageCount;
    private BigDecimal packageVolumeCbm;
    private BigDecimal grossWeight;

    private String colorCode;
    private String colorText;
    private String materialCode;
    private String materialText;
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
