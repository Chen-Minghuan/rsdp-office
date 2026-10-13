package com.rsdp.officeproduct.importer.dto.response;

import lombok.Data;

/** 归一化包围盒 [0,1]。 */
@Data
public class FloorPlanBBox {

    private Double x;
    private Double y;
    private Double w;
    private Double h;
}
