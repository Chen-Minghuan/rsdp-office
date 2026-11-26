package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.config.RsdpMainProperties;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OfficeCatalogAccess {
    private final CurrentUserService currentUserService;
    private final RsdpMainProperties properties;

    public record Actor(String workspaceId, String actorId, String actorName, String factoryCode) {}

    public record Capabilities(boolean managed) {}

    public Capabilities capabilities() {
        return new Capabilities(isBound(currentUserService.currentUser()));
    }

    /** Scope legacy import history to the signed-in person in the bound workspace. */
    public Actor requireHistoryReader() {
        OfficeUser user = currentUserService.currentUser();
        if (!isBound(user)) {
            throw new AccessDeniedException("当前账号无权读取本厂导入记录");
        }
        return new Actor(user.getWorkspaceId(), user.getUserId(), user.getDisplayName(), properties.getCatalogFactoryCode());
    }

    private boolean isBound(OfficeUser user) {
        return user != null && boundFactoryCode(user.getWorkspaceId()) != null;
    }

    public String boundFactoryCode(String workspaceId) {
        return StringUtils.hasText(properties.getCatalogFactoryCode())
            && StringUtils.hasText(properties.getCatalogWorkspaceId())
            && properties.getCatalogWorkspaceId().equals(workspaceId) ? properties.getCatalogFactoryCode() : null;
    }
}
