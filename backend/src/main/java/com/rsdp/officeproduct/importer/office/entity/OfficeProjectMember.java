package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目成员关系；数据库主键为 (project_id, user_id)，查询一律走 QueryWrapper，
 * 不使用 BaseMapper 的单主键方法。
 */
@Data
@TableName("office_project_member")
public class OfficeProjectMember {

    public static final String ROLE_LEADER = "LEADER";
    public static final String ROLE_COLLABORATOR = "COLLABORATOR";

    @TableId
    private String projectId;
    private String userId;
    private String role;
    private LocalDateTime createdAt;
}
