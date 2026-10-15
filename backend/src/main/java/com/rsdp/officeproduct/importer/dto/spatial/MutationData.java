package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.util.List;

@Data
public class MutationData {

    private String clientChangeId;
    private String sceneId;
    private Long previousRevision;
    private Long revision;
    private List<String> affectedSpaceIds;
    private List<String> invalidatedSpaceIds;
    private List<String> confirmationIds;
    private String confirmedSceneId;
    private SpatialSceneSnapshot scene;
}
