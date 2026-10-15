package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.util.List;

@Data
public class SpatialWall {

    private String wallId;
    private List<String> spaceIds;
    private SpatialGeometry geometry;
    private Double thicknessMm;
}
