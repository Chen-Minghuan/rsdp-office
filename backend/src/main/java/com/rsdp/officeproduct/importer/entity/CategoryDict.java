package com.rsdp.officeproduct.importer.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("category_dict")
public class CategoryDict {

    @TableId
    private String dictType;

    @TableField("dict_code")
    private String dictCode;

    @TableField("dict_name")
    private String dictName;

    @TableField("dict_name_en")
    private String dictNameEn;

    @TableField("parent_code")
    private String parentCode;

    @TableField("sort_order")
    private Integer sortOrder;

    private String status;

    @TableField("aliases")
    private String aliases;
}
