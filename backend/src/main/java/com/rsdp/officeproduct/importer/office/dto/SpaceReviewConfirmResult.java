package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

@Data
public class SpaceReviewConfirmResult {
    private String spaceId;
    /** 写入确认后的最新画布修订。 */
    private Integer canvasRevision;
    /** CONFIRMED */
    private String status;
    private Integer confirmedCanvasRevision;
    private String confirmedByUserId;
    private String confirmedAt;
}
