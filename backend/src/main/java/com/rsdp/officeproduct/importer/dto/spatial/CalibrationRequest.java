package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.util.List;

@Data
public class CalibrationRequest {

    private String schemaVersion;
    private Long baseRevision;
    private String clientChangeId;
    private Long calibrationRevision;
    private String sourcePageId;
    private List<List<Double>> referencePoints;
    private Double referenceLengthMm;
}
