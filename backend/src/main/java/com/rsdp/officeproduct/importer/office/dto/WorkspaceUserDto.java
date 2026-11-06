package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 工作区内账号视图；不含密码哈希。 */
@Data
public class WorkspaceUserDto {
    private String userId;
    private String username;
    private String displayName;
    private String role;
    private String source;
    private String status;
    private LocalDateTime createdAt;
}
