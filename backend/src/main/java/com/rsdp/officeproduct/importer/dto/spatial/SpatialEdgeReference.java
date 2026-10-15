package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.util.List;

@Data
public class SpatialEdgeReference {

    private String spaceId;
    private List<String> vertexIds;
}
