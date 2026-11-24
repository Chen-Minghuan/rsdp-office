package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_combo_item")
public class OfficeComboItem {

    private String comboId;
    private String rspuId;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
