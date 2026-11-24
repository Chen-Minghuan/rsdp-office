package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OfficeFloorDto {
    private String floorId;
    private String projectId;
    private String name;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 当前购物车方案是否与最新已确认快照一致（与方案页确认状态同口径）。 */
    private Boolean proposalConfirmed;
}
