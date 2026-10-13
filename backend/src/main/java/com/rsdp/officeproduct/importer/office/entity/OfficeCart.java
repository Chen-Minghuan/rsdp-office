package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_cart")
public class OfficeCart {

    @TableId
    private String lineId;

    private String projectId;
    private String zoneId;
    private String zoneName;
    private String productId;
    private Integer qty;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
