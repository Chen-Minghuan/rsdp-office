package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 系统管理员看到的用户条目；绝不包含密码哈希。 */
@Data
public class AdminUserDto {
    private String userId;
    private String username;
    private String displayName;
    private String role;
    private String status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
}
