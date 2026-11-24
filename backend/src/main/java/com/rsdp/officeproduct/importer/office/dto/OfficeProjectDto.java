package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OfficeProjectDto {
    private String projectId;
    private String name;
    /** 由方案确认数据派生：全部楼层存在未失效确认 → DELIVERING，否则 DESIGNING。 */
    private String status;
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
}
