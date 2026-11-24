package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 办公工作区。一个工作区主号（office_user.role='DEALER'）恰好拥有一个工作区，
 * 项目唯一归属一个工作区（office_design_project.workspace_id）。
 */
@Data
@TableName("office_workspace")
public class OfficeWorkspace {

    @TableId
    private String workspaceId;
    private String name;
    /** 经销商或工厂工作区，用于标识业务主体。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String workspaceType;
    private String ownerUserId;
    private Integer dealerSlotNo;
    /** 公共库开关（经销商主号控制）：关=工作区全员仅见本厂产品；开=成员可自行选择不看。 */
    private Boolean sharedCatalogEnabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
