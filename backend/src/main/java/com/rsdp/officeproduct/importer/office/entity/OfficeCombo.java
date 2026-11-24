package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.officeproduct.importer.office.mapper.VarcharArrayTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "office_combo", autoResultMap = true)
public class OfficeCombo {

    @TableId
    private String comboId;
    private String userId;
    private String name;
    @TableField(typeHandler = VarcharArrayTypeHandler.class)
    private List<String> rspuIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
