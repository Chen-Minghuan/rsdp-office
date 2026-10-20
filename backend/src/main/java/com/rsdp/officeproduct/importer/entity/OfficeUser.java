package com.rsdp.officeproduct.importer.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_user")
public class OfficeUser {
    @TableId
    private String userId;
    private String username;
    private String passwordHash;
    private String displayName;
    private String role;
    private String status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
