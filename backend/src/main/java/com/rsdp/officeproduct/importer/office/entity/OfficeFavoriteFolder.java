package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_favorite_folder")
public class OfficeFavoriteFolder {

    @TableId
    private String folderId;
    private String userId;
    private String name;
    @TableField("is_default")
    private Boolean defaultFolder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
