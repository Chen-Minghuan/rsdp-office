package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeWorkspace;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 公共库（主项目全库商品）可见性判定：两级开关取"与"。
 *
 * <p>工作区级开关（office_workspace.shared_catalog_enabled）由经销商主号控制，
 * 是工作区全员的门——关了所有人只看本厂产品；用户级开关（office_user.hide_shared_catalog）
 * 是成员自己的窗，仅在工作区级开启时生效。两级都按请求实时查库，切换即时生效。</p>
 */
@Service
@RequiredArgsConstructor
public class SharedCatalogService {

    private final CurrentUserService currentUserService;
    private final OfficeUserMapper userMapper;
    private final OfficeWorkspaceMapper workspaceMapper;

    /**
     * 解析当前用户请求主项目产品列表的 viewMode。
     *
     * <p>认证关闭（开发模式）、系统管理员、无工作区的平台内部账号默认全库（full）；
     * 工作区账号按两级开关判定。</p>
     *
     * @return "full"=全库只读 / "own"=仅本厂已报价产品
     */
    public String resolveViewMode() {
        if (!currentUserService.authEnabled()) {
            return "full";
        }
        OfficeUser user = currentUserService.currentUser();
        return canViewSharedCatalog(user) ? "full" : "own";
    }

    /** 当前用户是否可见公共库（工作区级的门 × 用户级的窗，取"与"）。 */
    public boolean canViewSharedCatalog(OfficeUser user) {
        if (user == null) {
            return true;
        }
        // 工作区级的门：仅约束归属工作区的账号；无工作区账号（管理员/内部账号）不受此限
        OfficeWorkspace workspace = user.getWorkspaceId() == null ? null
            : workspaceMapper.selectById(user.getWorkspaceId());
        if (workspace != null && !Boolean.TRUE.equals(workspace.getSharedCatalogEnabled())) {
            return false;
        }
        // 用户级的窗：任何账号（含管理员）都可以自己选择不看
        return !Boolean.TRUE.equals(user.getHideSharedCatalog());
    }

    /** 经销商主号切换工作区级公共库开关（即时生效，工作区全员受约束）。 */
    public void setWorkspaceSharedCatalog(boolean enabled) {
        OfficeUser user = currentUserService.currentUser();
        if (!"DEALER".equalsIgnoreCase(user.getRole())) {
            throw new AccessDeniedException("只有经销商主号可以切换公共库开关");
        }
        OfficeWorkspace workspace = user.getWorkspaceId() == null ? null
            : workspaceMapper.selectById(user.getWorkspaceId());
        if (workspace == null) {
            throw new BusinessException("当前账号未归属工作区");
        }
        workspace.setSharedCatalogEnabled(enabled);
        workspaceMapper.updateById(workspace);
    }

    /** 成员切换自己的公共库显示偏好（仅工作区级开启时生效）。 */
    public void setUserHideSharedCatalog(boolean hide) {
        OfficeUser user = currentUserService.currentUser();
        user.setHideSharedCatalog(hide);
        userMapper.updateById(user);
    }

    /** 当前用户的公共库开关状态（前端回显用）。 */
    public SharedCatalogStatus currentStatus() {
        if (!currentUserService.authEnabled()) {
            return new SharedCatalogStatus(true, false, true, true);
        }
        OfficeUser user = currentUserService.currentUser();
        OfficeWorkspace workspace = user.getWorkspaceId() == null ? null
            : workspaceMapper.selectById(user.getWorkspaceId());
        boolean workspaceEnabled = workspace == null || Boolean.TRUE.equals(workspace.getSharedCatalogEnabled());
        boolean userHides = Boolean.TRUE.equals(user.getHideSharedCatalog());
        boolean dealer = "DEALER".equalsIgnoreCase(user.getRole());
        return new SharedCatalogStatus(workspaceEnabled, userHides, dealer, workspaceEnabled && !userHides);
    }

    /**
     * 公共库开关状态。
     *
     * @param workspaceEnabled  工作区级开关（主号控制）
     * @param userHides         当前用户是否选择不看（仅工作区开启时生效）
     * @param dealer            当前用户是否经销商主号（可切换工作区级开关）
     * @param effectiveVisible  综合可见性（两级取与）
     */
    public record SharedCatalogStatus(boolean workspaceEnabled, boolean userHides, boolean dealer, boolean effectiveVisible) {
    }
}
