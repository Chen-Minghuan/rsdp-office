package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

@Data
public class SpaceReviewConfirmRequest {
    /** 必须等于数据库当前画布修订，否则拒绝并提示刷新。 */
    private Integer baseCanvasRevision;
}
