package com.rsdp.officeproduct.importer.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_user")
public class OfficeUser {
    @TableId
    private String userId;
    private String username;
    private String passwordHash;
    private String displayName;
    private String role;
    private String status;
    /** 账号归属的经销商工作区；系统管理员与历史账号为空。 */
    private String workspaceId;
    /** 来源标记（DEALER_STAFF 经销商员工 / FACTORY_SALES 厂家业务员）；一期不参与鉴权。 */
    private String source;
    /** 用户级公共库偏好：true=本人选择不看（仅所在工作区开启公共库时生效）。 */
    private Boolean hideSharedCatalog;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
