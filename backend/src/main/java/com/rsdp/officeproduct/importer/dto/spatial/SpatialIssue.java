package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.util.List;

@Data
public class SpatialIssue {

    private String issueId;
    private String code;
    private String severity;
    private List<String> spaceIds;
    private List<String> objectIds;
    private String message;
    private List<Double> position;
    private Boolean blocksConfirmation;
}
