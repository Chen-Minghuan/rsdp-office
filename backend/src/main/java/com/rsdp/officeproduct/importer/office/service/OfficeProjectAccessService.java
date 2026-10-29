package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectMember;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目级访问校验。认证关闭（开发模式）时全部放行，与 AuthFilter 的开关一致；
 * 认证开启时按 office_project_member 判定，没有成员关系的历史项目不对已登录用户默认放开。
 */
@Service
@RequiredArgsConstructor
public class OfficeProjectAccessService {

    private final CurrentUserService currentUserService;
    private final OfficeProjectMemberMapper memberMapper;
    private final OfficeDesignFloorMapper floorMapper;

    public boolean enforcementEnabled() {
        return currentUserService.authEnabled();
    }

    /** 当前用户 ID；认证关闭返回 null。 */
    public String currentUserId() {
        OfficeUser user = currentUserService.currentUser();
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
        if (!isMember(projectId, currentUserId())) {
            throw new AccessDeniedException("无权访问该项目");
        }
    }

    public void requireLeader(String projectId) {
        if (!enforcementEnabled()) {
            return;
        }
        String userId = currentUserId();
        boolean leader = StringUtils.hasText(userId) && memberMapper.selectCount(new QueryWrapper<OfficeProjectMember>()
            .eq("project_id", projectId).eq("user_id", userId)
            .eq("role", OfficeProjectMember.ROLE_LEADER)) > 0;
        if (!leader) {
            throw new AccessDeniedException("只有项目负责人可以管理成员");
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

    /** 当前用户参与的项目 ID 集合；认证关闭返回 null 表示不过滤。 */
    public Set<String> accessibleProjectIds() {
        if (!enforcementEnabled()) {
            return null;
        }
        String userId = currentUserId();
        if (!StringUtils.hasText(userId)) {
            return Set.of();
        }
        List<OfficeProjectMember> memberships = memberMapper.selectList(
            new QueryWrapper<OfficeProjectMember>().eq("user_id", userId));
        return memberships.stream().map(OfficeProjectMember::getProjectId).collect(Collectors.toSet());
    }
}
