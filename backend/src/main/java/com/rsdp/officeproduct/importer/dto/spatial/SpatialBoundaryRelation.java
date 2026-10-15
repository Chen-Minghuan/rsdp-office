package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

@Data
public class SpatialBoundaryRelation {

    public static final String KIND_WALL_SEPARATED = "WALL_SEPARATED";
    public static final String KIND_SHARED_PARTITION = "SHARED_PARTITION";

    private String relationId;
    private String kind;
    private String spaceAId;
    private String spaceBId;
    private String wallId;
    private SpatialEdgeReference edgeA;
    private SpatialEdgeReference edgeB;
}
