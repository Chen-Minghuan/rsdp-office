package com.rsdp.officeproduct.importer.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("six_dim_schema")
public class SixDimSchema {

    @TableId
    private Long id;

    @TableField("category_code")
    private String categoryCode;

    @TableField("dim_code")
    private String dimCode;

    @TableField("dim_name")
    private String dimName;

    @TableField("allowed_values")
    private String allowedValues;
}
