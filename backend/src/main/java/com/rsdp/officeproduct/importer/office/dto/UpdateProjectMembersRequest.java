package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateProjectMembersRequest {
    private String leaderUserId;
    private List<String> collaboratorUserIds;
}
