package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_favorite_item")
public class OfficeFavoriteItem {

    private String folderId;
    private String rspuId;
    private LocalDateTime createdAt;
}
