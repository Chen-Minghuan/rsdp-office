package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectMember;
import com.rsdp.officeproduct.importer.office.entity.OfficeWorkspace;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectMemberMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目级访问校验。认证关闭（开发模式）时全部放行，与 AuthFilter 的开关一致。
 * 认证开启时：项目唯一归属一个工作区——访问者须为该项目成员且账号同属该工作区，
 * 或为该工作区的经销商主号（可读并管理成员，用于指定项目负责人）。
 * workspace_id 为空的历史项目保持 PROJECT-004 的纯成员判定，待补录。
 */
@Service
@RequiredArgsConstructor
public class OfficeProjectAccessService {

    private final CurrentUserService currentUserService;
    private final OfficeProjectMemberMapper memberMapper;
    private final OfficeDesignFloorMapper floorMapper;
    private final OfficeDesignProjectMapper projectMapper;
    private final OfficeWorkspaceMapper workspaceMapper;

    public boolean enforcementEnabled() {
        return currentUserService.authEnabled();
    }

    /** 当前用户；认证关闭返回 null。 */
    public OfficeUser currentUserOrNull() {
        return currentUserService.currentUser();
    }

    /** 当前用户 ID；认证关闭返回 null。 */
    public String currentUserId() {
        OfficeUser user = currentUserOrNull();
        return user != null ? user.getUserId() : null;
    }

    public boolean isMember(String projectId, String userId) {
        if (!StringUtils.hasText(projectId) || !StringUtils.hasText(userId)) {
            return false;
        }
        return memberMapper.selectCount(new QueryWrapper<OfficeProjectMember>()
            .eq("project_id", projectId).eq("user_id", userId)) > 0;
    }

    public void requireMember(String projectId) {
        if (!enforcementEnabled()) {
            return;
        }
        if (!canAccessProject(currentUserOrNull(), requireProject(projectId))) {
            throw new AccessDeniedException("无权访问该项目");
        }
    }

    /** 成员管理：项目内负责人（同属工作区），或该工作区的经销商主号。 */
    public void requireLeader(String projectId) {
        if (!enforcementEnabled()) {
            return;
        }
        if (!canManageMembers(currentUserOrNull(), requireProject(projectId))) {
            throw new AccessDeniedException("只有项目负责人或经销商主号可以管理成员");
        }
    }

    /**
     * 历史画布等接口的路径参数名为 projectId，实际承载楼层工作区 ID；
     * 先由楼层查证所属项目，再校验成员权限。
     */
    public void requireFloorAccess(String floorId) {
        if (!enforcementEnabled()) {
            return;
        }
        OfficeDesignFloor floor = StringUtils.hasText(floorId) ? floorMapper.selectById(floorId) : null;
        if (floor == null) {
            throw new BusinessException("楼层不存在，请先创建项目和楼层");
        }
        requireMember(floor.getProjectId());
    }

    /** 当前用户可访问的项目 ID 集合；认证关闭返回 null 表示不过滤。 */
    public Set<String> accessibleProjectIds() {
        if (!enforcementEnabled()) {
            return null;
        }
        OfficeUser user = currentUserOrNull();
        if (user == null || !StringUtils.hasText(user.getUserId())) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        List<OfficeProjectMember> memberships = memberMapper.selectList(
            new QueryWrapper<OfficeProjectMember>().eq("user_id", user.getUserId()));
        if (!memberships.isEmpty()) {
            Set<String> projectIds = memberships.stream()
                .map(OfficeProjectMember::getProjectId).collect(Collectors.toSet());
            Map<String, OfficeDesignProject> projectsById = projectMapper.selectBatchIds(projectIds)
                .stream().collect(Collectors.toMap(OfficeDesignProject::getProjectId, p -> p));
            for (OfficeProjectMember membership : memberships) {
                OfficeDesignProject project = projectsById.get(membership.getProjectId());
                if (project != null && sameWorkspace(user, project)) {
                    result.add(membership.getProjectId());
                }
            }
        }
        OfficeWorkspace owned = ownedWorkspace(user);
        if (owned != null) {
            projectMapper.selectList(new QueryWrapper<OfficeDesignProject>()
                    .eq("workspace_id", owned.getWorkspaceId()))
                .forEach(project -> result.add(project.getProjectId()));
        }
        return result;
    }

    private boolean canAccessProject(OfficeUser user, OfficeDesignProject project) {
        if (user == null) {
            return false;
        }
        if (!StringUtils.hasText(project.getWorkspaceId())) {
            return isMember(project.getProjectId(), user.getUserId());
        }
        if (isWorkspaceOwner(user, project.getWorkspaceId())) {
            return true;
        }
        return sameWorkspace(user, project) && isMember(project.getProjectId(), user.getUserId());
    }

    private boolean canManageMembers(OfficeUser user, OfficeDesignProject project) {
        if (user == null) {
            return false;
        }
        if (StringUtils.hasText(project.getWorkspaceId()) && isWorkspaceOwner(user, project.getWorkspaceId())) {
            return true;
        }
        if (!sameWorkspace(user, project)) {
            return false;
        }
        return memberMapper.selectCount(new QueryWrapper<OfficeProjectMember>()
            .eq("project_id", project.getProjectId()).eq("user_id", user.getUserId())
            .eq("role", OfficeProjectMember.ROLE_LEADER)) > 0;
    }

    /** 历史项目（无工作区）不限制账号归属；有工作区的项目要求账号同属。 */
    private boolean sameWorkspace(OfficeUser user, OfficeDesignProject project) {
        if (!StringUtils.hasText(project.getWorkspaceId())) {
            return true;
        }
        return project.getWorkspaceId().equals(user.getWorkspaceId());
    }

    private boolean isWorkspaceOwner(OfficeUser user, String workspaceId) {
        OfficeWorkspace workspace = StringUtils.hasText(workspaceId) ? workspaceMapper.selectById(workspaceId) : null;
        return workspace != null && workspace.getOwnerUserId().equals(user.getUserId());
    }

    private OfficeWorkspace ownedWorkspace(OfficeUser user) {
        return workspaceMapper.selectOne(new QueryWrapper<OfficeWorkspace>()
            .eq("owner_user_id", user.getUserId()).last("limit 1"));
    }

    private OfficeDesignProject requireProject(String projectId) {
        OfficeDesignProject project = StringUtils.hasText(projectId) ? projectMapper.selectById(projectId) : null;
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        return project;
    }
}
