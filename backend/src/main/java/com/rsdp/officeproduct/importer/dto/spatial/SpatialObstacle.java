package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

@Data
public class SpatialObstacle {

    public static final String KIND_COLUMN = "COLUMN";
    public static final String KIND_SHAFT = "SHAFT";
    public static final String KIND_NO_PLACEMENT = "NO_PLACEMENT";
    public static final String KIND_OTHER = "OTHER";

    private String obstacleId;
    private String kind;
    private String spaceId;
    private SpatialGeometry geometry;
    private Boolean excludesFloorArea;
}
