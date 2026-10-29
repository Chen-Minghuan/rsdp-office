package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.auth.AuthService;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.util.CurrentUserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 从已校验的服务端会话解析当前用户。请求体中的 userId、前端默认值不可信。
 * 认证关闭（rsdp.auth.enabled=false，开发模式）时不存在当前用户，返回 null。
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final AuthService authService;
    private final OfficeUserMapper userMapper;

    public boolean authEnabled() {
        return authService.isEnabled();
    }

    /** 认证开启时返回会话对应的有效用户；认证关闭返回 null。 */
    public OfficeUser currentUser() {
        if (!authService.isEnabled()) {
            return null;
        }
        String username = CurrentUserContext.currentUsername();
        OfficeUser user = StringUtils.hasText(username) && !"system".equals(username)
            ? userMapper.selectOne(new QueryWrapper<OfficeUser>().eq("username", username).last("limit 1"))
            : null;
        if (user == null || !"active".equalsIgnoreCase(user.getStatus())) {
            throw new AccessDeniedException("请先登录");
        }
        return user;
    }

    /** 认证开启时要求当前用户是系统管理员（role=ADMIN）；认证关闭（开发模式）不校验。 */
    public void requireAdmin() {
        if (!authService.isEnabled()) {
            return;
        }
        OfficeUser user = currentUser();
        if (!"ADMIN".equalsIgnoreCase(user.getRole())) {
            throw new AccessDeniedException("只有系统管理员可以管理用户");
        }
    }
}
