package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeWorkspace;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 公共库两级开关判定测试：工作区级（主号控制）与用户级（成员自选）取"与"。
 */
class SharedCatalogServiceTest {

    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final OfficeUserMapper userMapper = mock(OfficeUserMapper.class);
    private final OfficeWorkspaceMapper workspaceMapper = mock(OfficeWorkspaceMapper.class);
    private final SharedCatalogService service = new SharedCatalogService(currentUserService, userMapper, workspaceMapper);

    @Test
    void authDisabledDefaultsToFull() {
        when(currentUserService.authEnabled()).thenReturn(false);
        assertEquals("full", service.resolveViewMode());
    }

    @Test
    void userWithoutWorkspaceSeesFull() {
        OfficeUser admin = user(null, "ADMIN", false);
        assertTrue(service.canViewSharedCatalog(admin));
    }

    @Test
    void userWithoutWorkspaceCanStillHideForHimself() {
        // 无工作区账号（如系统管理员）不受工作区开关约束，但用户级"不看"偏好仍然生效
        OfficeUser admin = user(null, "ADMIN", true);
        assertFalse(service.canViewSharedCatalog(admin));
    }

    @Test
    void workspaceOnAndUserNotHidingSeesFull() {
        workspace("WS1", true);
        assertTrue(service.canViewSharedCatalog(user("WS1", "USER", false)));
    }

    @Test
    void workspaceOffBlocksEveryoneEvenUserNotHiding() {
        workspace("WS1", false);
        assertFalse(service.canViewSharedCatalog(user("WS1", "USER", false)));
        assertFalse(service.canViewSharedCatalog(user("WS1", "DEALER", false)));
    }

    @Test
    void workspaceOnButUserHidesSeesOwn() {
        workspace("WS1", true);
        assertFalse(service.canViewSharedCatalog(user("WS1", "USER", true)));
    }

    @Test
    void onlyDealerCanToggleWorkspaceSwitch() {
        when(currentUserService.authEnabled()).thenReturn(true);
        when(currentUserService.currentUser()).thenReturn(user("WS1", "USER", false));
        assertThrows(AccessDeniedException.class, () -> service.setWorkspaceSharedCatalog(false));
    }

    @Test
    void dealerTogglePersistsWorkspaceSwitch() {
        OfficeWorkspace ws = workspace("WS1", true);
        when(currentUserService.authEnabled()).thenReturn(true);
        when(currentUserService.currentUser()).thenReturn(user("WS1", "DEALER", false));

        service.setWorkspaceSharedCatalog(false);

        assertEquals(false, ws.getSharedCatalogEnabled());
        verify(workspaceMapper).updateById(ws);
    }

    private OfficeUser user(String workspaceId, String role, boolean hideSharedCatalog) {
        OfficeUser user = new OfficeUser();
        user.setUserId("U1");
        user.setUsername("u1");
        user.setRole(role);
        user.setStatus("active");
        user.setWorkspaceId(workspaceId);
        user.setHideSharedCatalog(hideSharedCatalog);
        return user;
    }

    private OfficeWorkspace workspace(String workspaceId, boolean enabled) {
        OfficeWorkspace workspace = new OfficeWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setSharedCatalogEnabled(enabled);
        lenient().when(workspaceMapper.selectById(workspaceId)).thenReturn(workspace);
        return workspace;
    }
}
