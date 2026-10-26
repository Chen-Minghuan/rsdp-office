package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.util.List;

@Data
public class CreateOfficeProjectRequest {
    private String name;
    private String customerRequirements = "";
    private List<CreateOfficeFloorRequest> floors;
}
