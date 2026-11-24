package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_project_favorite_item")
public class OfficeProjectFavoriteItem {

    private String projectId;
    private String userId;
    private String rspuId;
    private LocalDateTime createdAt;
}
