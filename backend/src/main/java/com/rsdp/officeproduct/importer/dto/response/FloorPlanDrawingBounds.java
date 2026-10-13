package com.rsdp.officeproduct.importer.dto.response;

import lombok.Data;

/** CAD 图纸范围（毫米坐标系）。 */
@Data
public class FloorPlanDrawingBounds {

    private Double minX;
    private Double minY;
    private Double maxX;
    private Double maxY;
}
