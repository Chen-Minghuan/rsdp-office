package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.util.List;

@Data
public class SpatialGeometry {

    public static final String TYPE_POLYGON = "POLYGON";
    public static final String COORDINATE_SOURCE_PAGE = "SOURCE_PAGE";
    public static final String COORDINATE_WORLD_MM = "WORLD_MM";

    private String type;
    private String coordinateSystem;
    private List<List<Double>> outer;
    private List<String> outerVertexIds;
    private List<List<List<Double>>> holes;
    private List<List<String>> holeVertexIds;
}
