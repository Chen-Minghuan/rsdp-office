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
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 系统管理员的用户管理：创建账号、启用/停用、重置密码。
 * 全局角色只有 ADMIN / USER 两档；项目内负责人/协作者与全局角色无关。
 * 认证关闭（rsdp.auth.enabled=false，开发模式）时不校验管理员身份。
 */
@Service
@RequiredArgsConstructor
public class OfficeUserAdminService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.\\-]{2,32}$");
    private static final int MIN_PASSWORD_LENGTH = 6;

    private final OfficeUserMapper userMapper;
    private final OfficeUserSessionMapper sessionMapper;
    private final OfficeProjectMemberMapper memberMapper;
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
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException("角色只能是 USER 或 ADMIN");
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
        String next = status == null ? "" : status.trim().toLowerCase();
        if (!"active".equals(next) && !"disabled".equals(next)) {
            throw new BusinessException("状态只能是 active 或 disabled");
        }
        OfficeUser operator = currentUserService.currentUser();
        if ("disabled".equals(next)) {
            if (operator != null && operator.getUserId().equals(user.getUserId())) {
                throw new BusinessException("不能停用自己的账号");
            }
            if ("ADMIN".equalsIgnoreCase(user.getRole()) && countActiveAdmins(user.getUserId()) == 0) {
                throw new BusinessException("至少保留一位系统管理员");
            }
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
        user.setPasswordHash(PasswordHasher.hash(requirePassword(password, "新密码至少 6 位")));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        invalidateSessions(user.getUserId());
        return toDto(user);
    }

    /**
     * 删除账号：协作者成员关系与会话一并删除；仍是项目负责人的账号禁止删除，
     * 避免项目失去负责人。不能删除自己，且至少保留一位在用系统管理员。
     */
    @Transactional
    public void deleteUser(String userId) {
        currentUserService.requireAdmin();
        OfficeUser user = requireUser(userId);
        OfficeUser operator = currentUserService.currentUser();
        if (operator != null && operator.getUserId().equals(user.getUserId())) {
            throw new BusinessException("不能删除自己的账号");
        }
        if ("ADMIN".equalsIgnoreCase(user.getRole())
            && "active".equalsIgnoreCase(user.getStatus())
            && countActiveAdmins(user.getUserId()) == 0) {
            throw new BusinessException("至少保留一位系统管理员");
        }
        long leaderProjects = memberMapper.selectCount(new QueryWrapper<OfficeProjectMember>()
            .eq("user_id", user.getUserId()).eq("role", OfficeProjectMember.ROLE_LEADER));
        if (leaderProjects > 0) {
            throw new BusinessException("该用户仍是 " + leaderProjects + " 个项目的负责人，请先在项目中更换负责人");
        }
        memberMapper.delete(new QueryWrapper<OfficeProjectMember>().eq("user_id", user.getUserId()));
        invalidateSessions(user.getUserId());
        userMapper.deleteById(user.getUserId());
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

    /** 除 excludeUserId 外的在用管理员数量，用于阻止停掉最后一位管理员。 */
    private long countActiveAdmins(String excludeUserId) {
        return userMapper.selectList(new QueryWrapper<OfficeUser>()
                .eq("role", "ADMIN").eq("status", "active"))
            .stream().filter(user -> !user.getUserId().equals(excludeUserId)).count();
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
