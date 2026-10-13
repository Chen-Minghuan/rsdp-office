package com.rsdp.officeproduct.importer.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rspu_style")
public class RspuStyle {

    @TableId
    private String rspuId;

    private String dictType;

    @TableField("style_code")
    private String styleCode;

    @TableField("is_primary")
    private Boolean primary;

    private LocalDateTime createdAt;
}
