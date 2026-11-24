package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_combo")
public class OfficeCombo {

    @TableId
    private String comboId;
    private String userId;
    private String name;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
