package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

@Data
public class SpatialTombstone {

    private String layer;
    private String objectId;
    private String reason;
    private Long deletedAtRevision;
}
