package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

@Data
public class SpatialLayerAvailability {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String PARTIAL = "PARTIAL";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    private String walls = UNKNOWN;
    private String openings = UNKNOWN;
    private String obstacles = UNKNOWN;
}
