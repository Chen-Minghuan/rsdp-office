package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

@Data
public class SpatialRectangleFrame {

    private Double angleDeg;
    private RectangleCornerVertexIds cornerVertexIds;

    @Data
    public static class RectangleCornerVertexIds {
        private String u0v0;
        private String u1v0;
        private String u1v1;
        private String u0v1;
    }
}
