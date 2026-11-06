package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 授权相关操作审计：成员替换、开户、建工作区等。 */
@Data
@TableName("office_audit_log")
public class OfficeAuditLog {

    @TableId(type = IdType.AUTO)
    private Long auditId;
    private String actorUserId;
    private String action;
    private String targetType;
    private String targetId;
    private String detail;
    private LocalDateTime createdAt;
}
