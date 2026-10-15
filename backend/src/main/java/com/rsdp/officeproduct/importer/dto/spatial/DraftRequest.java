package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.util.List;

@Data
public class DraftRequest {

    private String schemaVersion;
    private Long baseRevision;
    private String clientChangeId;
    private String workingCoordinateSystem;
    private Long calibrationRevision;

    private List<SpatialSpaceInput> spaces;
    private List<SpatialWall> walls;
    private List<SpatialOpening> openings;
    private List<SpatialObstacle> obstacles;
    private List<SpatialAnnotation> annotations;
    private List<SpatialBoundaryRelation> boundaryRelations;
    private List<SpatialIssueDisposition> issueDispositions;
    private List<SpatialDeletion> deletions;
}
