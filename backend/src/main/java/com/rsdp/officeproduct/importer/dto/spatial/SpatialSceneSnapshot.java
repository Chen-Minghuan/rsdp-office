package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.util.List;

@Data
public class SpatialSceneSnapshot {

    private String schemaVersion;
    private String sceneId;
    private String projectId;
    private Long revision;
    private Long scopeRevision;
    private String geometryPolicyVersion;
    private String sourcePageId;
    private String sourceSceneId;
    private String failedRunId;
    private String workingCoordinateSystem;
    private SpatialCalibrationSnapshot calibration;
    private List<SpatialUnderlay> underlays;
    private List<SpatialSpaceSnapshot> spaces;
    private List<SpatialWall> walls;
    private List<SpatialOpening> openings;
    private List<SpatialObstacle> obstacles;
    private List<SpatialAnnotation> annotations;
    private List<SpatialBoundaryRelation> boundaryRelations;
    private List<SpatialIssueDisposition> issueDispositions;
    private SpatialLayerAvailability layerAvailability;
    private List<SpatialIssue> qualityIssues;
    private List<SpatialTombstone> tombstones;
    private String confirmedSceneId;
}
