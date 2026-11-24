package com.rsdp.officeproduct.importer.office.dto;

import com.rsdp.officeproduct.importer.office.service.OfficeProjectPermission;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class UpdateProjectMembersRequest {
    private String leaderUserId;
    private List<String> collaboratorUserIds;
    private Map<String, List<OfficeProjectPermission>> permissions;
}
