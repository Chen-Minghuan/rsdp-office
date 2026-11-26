package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OfficeProjectDto {
    private String projectId;
    private String name;
    /** 由项目负责人或所属工作区主号人工维护：DESIGNING / DELIVERING / COMPLETED。 */
    private String status;
    /** 当前用户是否可修改项目阶段（负责人或工作区主号）。 */
    private boolean canManageStatus;
    private String customerRequirements;
    private Integer requirementsRevision;
    private ProjectMemberDto leader;
    private List<ProjectMemberDto> collaborators;
    private List<ProjectReferenceImage> referenceImages;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<OfficeFloorDto> floors;
    /** 所有楼层购物车中可定价商品的当前零售参考价合计。 */
    private BigDecimal budgetTotal;
    /** COMPLETE / PENDING_PRICING / UNAVAILABLE。 */
    private String budgetStatus;
    /** 所有楼层最新画布中的区域(kind=zone)总数；仅项目列表返回。 */
    private Integer zoneTotal;
    /** 所在楼层方案已确认且购物车有量（交付中）的区域数；仅项目列表返回。 */
    private Integer zoneDelivered;
}
