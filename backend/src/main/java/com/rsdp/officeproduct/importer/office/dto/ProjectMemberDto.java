package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

@Data
public class ProjectMemberDto {
    private String userId;
    private String username;
    private String displayName;
    /** LEADER / COLLABORATOR */
    private String role;
}
