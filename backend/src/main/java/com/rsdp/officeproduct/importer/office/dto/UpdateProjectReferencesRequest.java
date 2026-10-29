package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateProjectReferencesRequest {
    private List<ProjectReferenceImage> referenceImages;
}
