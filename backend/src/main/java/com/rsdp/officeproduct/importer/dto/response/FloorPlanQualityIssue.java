package com.rsdp.officeproduct.importer.dto.response;

import lombok.Data;

/** CAD 解析质量提示。 */
@Data
public class FloorPlanQualityIssue {

    private String level;
    private String code;
    private String message;
}
