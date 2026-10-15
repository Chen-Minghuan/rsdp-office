package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

@Data
public class SpatialDeletion {

    public static final String LAYER_SPACES = "spaces";
    public static final String LAYER_WALLS = "walls";
    public static final String LAYER_OPENINGS = "openings";
    public static final String LAYER_OBSTACLES = "obstacles";
    public static final String LAYER_ANNOTATIONS = "annotations";
    public static final String LAYER_BOUNDARY_RELATIONS = "boundaryRelations";

    private String layer;
    private String objectId;
    private String reason;
}
