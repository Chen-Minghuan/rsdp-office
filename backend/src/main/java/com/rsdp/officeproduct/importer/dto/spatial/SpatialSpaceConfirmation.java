package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

@Data
public class SpatialSpaceConfirmation {

    public static final String STATUS_UNCONFIRMED = "UNCONFIRMED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_EXCLUDED = "EXCLUDED";

    private String status;
    private String confirmationId;
    private Long confirmedRevision;
    private Long confirmedGeometryRevision;
    private Long calibrationRevision;
}
