package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SpatialDerived {

    private BigDecimal boundaryAreaM2;
    private BigDecimal areaM2;
    private RectangleDimensions rectangleDimensions;
    private SpatialBounds boundsMm;

    @Data
    public static class RectangleDimensions {
        private Double widthMm;
        private Double depthMm;
    }
}
