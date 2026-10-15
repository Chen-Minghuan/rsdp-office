package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

@Data
public class SpatialSourceRef {

    private String assetId;
    private String pageId;
    private String runId;
    private String sourceSceneId;
    private String sourceRoomId;
}
