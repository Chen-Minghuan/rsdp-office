package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SpatialSpaceSnapshot {

    public static final String ORIGIN_VISION = "VISION";
    public static final String ORIGIN_CAD = "CAD";
    public static final String ORIGIN_MANUAL = "MANUAL";
    public static final String ORIGIN_AGENT = "AGENT";

    public static final String EVIDENCE_REAL_SERVICE = "REAL_SERVICE";
    public static final String EVIDENCE_ESTIMATED = "ESTIMATED";
    public static final String EVIDENCE_DEMO = "DEMO";
    public static final String EVIDENCE_MANUAL = "MANUAL";

    private String spaceId;
    private String kind;
    private String parentSpaceId;
    private String name;
    private String category;
    private Boolean includedInDesign;
    private String exclusionReason;
    private String boundaryBasis;
    private SpatialGeometry designGeometry;
    private SpatialRectangleFrame rectangleFrame;
    private String origin;
    private String evidenceKind;
    private SpatialSourceRef sourceRef;
    private BigDecimal confidence;
    private Long geometryRevision;
    private Long requirementRevision;
    private Long metadataRevision;
    private SpatialDerived derived;
    private SpatialSpaceConfirmation confirmation;
}
