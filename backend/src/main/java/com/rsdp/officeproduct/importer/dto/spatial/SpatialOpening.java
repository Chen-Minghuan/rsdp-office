package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.util.List;

@Data
public class SpatialOpening {

    public static final String KIND_DOOR = "DOOR";
    public static final String KIND_WINDOW = "WINDOW";

    private String openingId;
    private String kind;
    private String wallId;
    private List<String> spaceIds;
    private List<List<Double>> segment;
    private Double widthMm;
}
