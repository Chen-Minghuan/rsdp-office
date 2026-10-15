package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.util.List;

@Data
public class SpatialUnderlay {

    private String underlayId;
    private String sourcePageId;
    private String assetId;
    private Integer widthPx;
    private Integer heightPx;
    private List<Double> imagePixelToWorld;
    private Long transformRevision;
}
