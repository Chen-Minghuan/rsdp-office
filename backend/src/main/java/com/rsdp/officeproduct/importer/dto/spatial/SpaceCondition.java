package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

@Data
public class SpaceCondition {

    private String spaceId;
    private Long geometryRevision;
    private Long requirementRevision;
}
