package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 经销商工作区视图；不含任何凭据字段。 */
@Data
public class WorkspaceDto {
    private String workspaceId;
    private String name;
    private String ownerUserId;
    private String ownerUsername;
    private String ownerDisplayName;
    private String ownerStatus;
    private long userCount;
    private long projectCount;
    private LocalDateTime createdAt;
}
