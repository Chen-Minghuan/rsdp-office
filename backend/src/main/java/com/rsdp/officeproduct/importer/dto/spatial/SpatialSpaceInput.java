package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

@Data
public class SpatialSpaceInput {

    public static final String KIND_ROOM = "ROOM";
    public static final String KIND_FUNCTION_ZONE = "FUNCTION_ZONE";

    public static final String BOUNDARY_INNER_WALL_FACE = "INNER_WALL_FACE";
    public static final String BOUNDARY_FUNCTIONAL_DIVISION = "FUNCTIONAL_DIVISION";
    public static final String BOUNDARY_UNRESOLVED = "UNRESOLVED";

    private String spaceId;
    private String kind;
    private String parentSpaceId;
    private String name;
    private String category;
    private Boolean includedInDesign;
    private String exclusionReason;
    private String boundaryBasis;
    private SpatialGeometry designGeometry;
    private SpatialRectangleFrame rectangleFrame;
}
