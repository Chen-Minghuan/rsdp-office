package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.auth.PasswordHasher;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.entity.OfficeUserSession;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.mapper.OfficeUserSessionMapper;
import com.rsdp.officeproduct.importer.office.dto.AdminUserDto;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeUserRequest;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectMember;
import com.rsdp.officeproduct.importer.office.entity.OfficeWorkspace;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectMemberMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 系统管理员的用户管理：创建账号、启用/停用、重置密码、删除。
 * 系统管理员唯一：本接口只能创建设计师（普通 USER）账号，经销商主号经工作区创建；
 * 管理操作只能作用于设计师与经销商主号，不能操作任何系统管理员账号（含自己）。
 * 认证关闭（rsdp.auth.enabled=false，开发模式）时不校验管理员身份，目标角色规则仍生效。
 */
@Service
@RequiredArgsConstructor
public class OfficeUserAdminService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.\\-]{2,32}$");
    private static final int MIN_PASSWORD_LENGTH = 6;

    private final OfficeUserMapper userMapper;
    private final OfficeUserSessionMapper sessionMapper;
    private final OfficeProjectMemberMapper memberMapper;
    private final OfficeWorkspaceMapper workspaceMapper;
    private final CurrentUserService currentUserService;

    public List<AdminUserDto> listUsers() {
        currentUserService.requireAdmin();
        return userMapper.selectList(new QueryWrapper<OfficeUser>().orderByAsc("created_at"))
            .stream().map(this::toDto).toList();
    }

    @Transactional
    public AdminUserDto createUser(CreateOfficeUserRequest request) {
        currentUserService.requireAdmin();
        if (request == null) {
            throw new BusinessException("用户参数不能为空");
        }
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException("用户名需为 2-32 位字母、数字或 _ . -");
        }
        OfficeUser duplicate = userMapper.selectOne(new QueryWrapper<OfficeUser>().eq("username", username).last("limit 1"));
        if (duplicate != null) {
            throw new BusinessException("用户名已存在");
        }
        String displayName = request.getDisplayName() == null ? "" : request.getDisplayName().trim();
        if (!StringUtils.hasText(displayName)) {
            throw new BusinessException("显示名称不能为空");
        }
        String password = requirePassword(request.getPassword(), "初始密码至少 6 位");
        String role = StringUtils.hasText(request.getRole()) ? request.getRole().trim().toUpperCase() : "USER";
        if (!"USER".equals(role)) {
            throw new BusinessException("只能创建设计师账号；经销商主号请通过经销商工作区创建");
        }
        OfficeUser user = new OfficeUser();
        user.setUserId("USR-" + UUID.randomUUID().toString().replace("-", ""));
        user.setUsername(username);
        user.setPasswordHash(PasswordHasher.hash(password));
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setStatus("active");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return toDto(user);
    }

    @Transactional
    public AdminUserDto updateStatus(String userId, String status) {
        currentUserService.requireAdmin();
        OfficeUser user = requireUser(userId);
        requireManageable(user);
        String next = status == null ? "" : status.trim().toLowerCase();
        if (!"active".equals(next) && !"disabled".equals(next)) {
            throw new BusinessException("状态只能是 active 或 disabled");
        }
        user.setStatus(next);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        if ("disabled".equals(next)) {
            invalidateSessions(user.getUserId());
        }
        return toDto(user);
    }

    @Transactional
    public AdminUserDto resetPassword(String userId, String password) {
        currentUserService.requireAdmin();
        OfficeUser user = requireUser(userId);
        requireManageable(user);
        user.setPasswordHash(PasswordHasher.hash(requirePassword(password, "新密码至少 6 位")));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        invalidateSessions(user.getUserId());
        return toDto(user);
    }

    /**
     * 删除账号：协作者成员关系与会话一并删除；仍是项目负责人的账号禁止删除，
     * 避免项目失去负责人；仍拥有工作区的经销商主号禁止删除（一期无工作区转让/解散接口），
     * 工作区与项目保留，可由管理员先停用该主号。系统管理员账号不可删除。
     */
    @Transactional
    public void deleteUser(String userId) {
        currentUserService.requireAdmin();
        OfficeUser user = requireUser(userId);
        requireManageable(user);
        long leaderProjects = memberMapper.selectCount(new QueryWrapper<OfficeProjectMember>()
            .eq("user_id", user.getUserId()).eq("role", OfficeProjectMember.ROLE_LEADER));
        if (leaderProjects > 0) {
            throw new BusinessException("该用户仍是 " + leaderProjects + " 个项目的负责人，请先在项目中更换负责人");
        }
        long ownedWorkspaces = workspaceMapper.selectCount(new QueryWrapper<OfficeWorkspace>()
            .eq("owner_user_id", user.getUserId()));
        if (ownedWorkspaces > 0) {
            throw new BusinessException("该用户仍是经销商主号，请先停用或移交其工作区后再删除");
        }
        memberMapper.delete(new QueryWrapper<OfficeProjectMember>().eq("user_id", user.getUserId()));
        invalidateSessions(user.getUserId());
        userMapper.deleteById(user.getUserId());
    }

    /** 系统管理员唯一且不可被管理操作（停用/重置/删除）作用。 */
    private void requireManageable(OfficeUser user) {
        if ("ADMIN".equalsIgnoreCase(user.getRole())) {
            throw new BusinessException("不能操作系统管理员账号");
        }
    }

    private OfficeUser requireUser(String userId) {
        OfficeUser user = StringUtils.hasText(userId) ? userMapper.selectById(userId) : null;
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return user;
    }

    private String requirePassword(String password, String message) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(message);
        }
        return password;
    }

    private void invalidateSessions(String userId) {
        sessionMapper.delete(new QueryWrapper<OfficeUserSession>().eq("user_id", userId));
    }

    private AdminUserDto toDto(OfficeUser user) {
        AdminUserDto dto = new AdminUserDto();
        dto.setUserId(user.getUserId());
        dto.setUsername(user.getUsername());
        dto.setDisplayName(user.getDisplayName());
        dto.setRole(user.getRole());
        dto.setStatus(user.getStatus());
        dto.setLastLoginAt(user.getLastLoginAt());
        dto.setCreatedAt(user.getCreatedAt());
        return dto;
    }
}
