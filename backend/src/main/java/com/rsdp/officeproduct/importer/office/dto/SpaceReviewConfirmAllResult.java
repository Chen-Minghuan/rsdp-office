package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.util.List;

/** 一键复核全部空间的结果：逐空间列出本次确认、已确认与无法确认（含原因）。 */
@Data
public class SpaceReviewConfirmAllResult {
    /** 写入确认后的最新画布修订；无新增确认时为当前修订。 */
    private Integer canvasRevision;
    private List<SpaceReviewConfirmResult> confirmed = List.of();
    private List<SpaceReviewConfirmResult> alreadyConfirmed = List.of();

    @Data
    public static class Failure {
        private String spaceId;
        private String spaceName;
        private String reason;
    }

    private List<Failure> failed = List.of();
}
