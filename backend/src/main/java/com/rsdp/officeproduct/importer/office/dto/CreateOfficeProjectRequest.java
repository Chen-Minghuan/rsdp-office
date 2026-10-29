package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.util.List;

@Data
public class CreateOfficeProjectRequest {
    private String name;
    private String customerRequirements = "";
    private List<CreateOfficeFloorRequest> floors;
    /** 项目负责人；认证开启时必填，必须为有效用户。 */
    private String leaderUserId;
    private List<String> collaboratorUserIds;
    private List<ProjectReferenceImage> referenceImages;
}
