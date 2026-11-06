package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

/** 系统管理员创建经销商工作区及其主号。 */
@Data
public class CreateWorkspaceRequest {
    private String name;
    private String ownerUsername;
    private String ownerDisplayName;
    private String ownerPassword;
}
