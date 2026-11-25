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
 * 认证开启时：访问者须为同工作区的已授权项目成员，或为项目所在工作区的主号。
 * workspace_id 为空的历史项目按其成员授权判断。
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

    /** 经销商主号在自己拥有的工作区创建项目；认证关闭时返回 null。 */
    public String workspaceForProjectCreation() {
        if (!enforcementEnabled()) {
            return null;
        }
        OfficeUser user = currentUserOrNull();
        if (!"DEALER".equalsIgnoreCase(user.getRole())) {
            throw new AccessDeniedException("请联系经销商创建项目并分配给你");
        }
        OfficeWorkspace workspace = ownedWorkspace(user);
        if (workspace == null) {
            throw new AccessDeniedException("当前经销商主号没有工作区，无法创建项目");
        }
        return workspace.getWorkspaceId();
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

    public boolean can(String projectId, OfficeProjectPermission permission) {
        if (!enforcementEnabled()) return true;
        return can(currentUserOrNull(), requireProject(projectId), permission);
    }

    public void requirePermission(String projectId, OfficeProjectPermission permission) {
        if (!can(projectId, permission)) throw new AccessDeniedException("无权执行该项目操作");
    }

    /** 认证关闭仅用于开发模式；已登录时核验实际工作区归属。 */
    public boolean ownsProject(String projectId) {
        return !enforcementEnabled() || isWorkspaceOwner(currentUserOrNull(), requireProject(projectId).getWorkspaceId());
    }

    /**
     * 经销商主号专属入口（项目报价等）：认证开启时要求当前账号是项目所属工作区的主号。
     * 历史无工作区项目没有主号归属，按拒绝处理，避免退化成全员可见。
     */
    public void requireWorkspaceOwner(String projectId) {
        if (!enforcementEnabled()) {
            return;
        }
        OfficeUser user = currentUserOrNull();
        OfficeDesignProject project = requireProject(projectId);
        if (user == null || !"DEALER".equalsIgnoreCase(user.getRole())
            || !isWorkspaceOwner(user, project.getWorkspaceId())) {
            throw new AccessDeniedException("只有经销商主号可以查看项目报价");
        }
    }

    /** 成员管理：拥有成员管理权限的项目成员或该工作区的主号。 */
    public void requireLeader(String projectId) {
        if (!enforcementEnabled()) {
            return;
        }
        if (!can(projectId, OfficeProjectPermission.MANAGE_MEMBERS)) {
            throw new AccessDeniedException("无权管理项目成员");
        }
    }

    /**
     * 历史画布等接口的路径参数名为 projectId，实际承载楼层工作区 ID；
     * 先由楼层查证所属项目，再校验成员权限。
     */
    public void requireFloorAccess(String floorId) {
        requireFloorPermission(floorId, OfficeProjectPermission.READ);
    }

    public void requireFloorPermission(String floorId, OfficeProjectPermission permission) {
        if (!StringUtils.hasText(floorId)) {
            throw new BusinessException("请先选择项目和楼层");
        }
        if (!enforcementEnabled()) {
            return;
        }
        OfficeDesignFloor floor = floorMapper.selectById(floorId);
        if (floor == null) {
            throw new BusinessException("楼层不存在，请先创建项目和楼层");
        }
        if (permission == OfficeProjectPermission.READ) requireMember(floor.getProjectId());
        else requirePermission(floor.getProjectId(), permission);
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
                if (project != null && sameWorkspace(user, project)
                        && OfficeProjectPermission.READ.granted(membership.getPermissionFlags())) {
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
        return can(user, project, OfficeProjectPermission.READ);
    }

    private boolean can(OfficeUser user, OfficeDesignProject project, OfficeProjectPermission permission) {
        if (user == null) return false;
        if (isWorkspaceOwner(user, project.getWorkspaceId())) return true;
        if (!sameWorkspace(user, project)) return false;
        return memberMapper.selectList(new QueryWrapper<OfficeProjectMember>()
            .eq("project_id", project.getProjectId()).eq("user_id", user.getUserId())).stream()
            .anyMatch(member -> project.getProjectId().equals(member.getProjectId())
                && permission.granted(member.getPermissionFlags()));
    }

    /** 历史项目（无工作区）不限制账号归属；有工作区的项目要求账号同属。 */
    private boolean sameWorkspace(OfficeUser user, OfficeDesignProject project) {
        if (!StringUtils.hasText(project.getWorkspaceId())) {
            return true;
        }
        return project.getWorkspaceId().equals(user.getWorkspaceId());
    }

    private boolean isWorkspaceOwner(OfficeUser user, String workspaceId) {
        OfficeWorkspace workspace = user != null && StringUtils.hasText(workspaceId) ? workspaceMapper.selectById(workspaceId) : null;
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
