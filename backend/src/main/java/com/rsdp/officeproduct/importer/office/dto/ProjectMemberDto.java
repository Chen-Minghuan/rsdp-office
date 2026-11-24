package com.rsdp.officeproduct.importer.office.dto;

import com.rsdp.officeproduct.importer.office.service.OfficeProjectPermission;
import lombok.Data;

import java.util.List;

@Data
public class ProjectMemberDto {
    private String userId;
    private String username;
    private String displayName;
    /** LEADER / COLLABORATOR */
    private String role;
    private List<OfficeProjectPermission> permissions;
}
