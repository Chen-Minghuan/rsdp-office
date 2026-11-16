package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.entity.OfficeWorkspace;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 公共库工作区级单开关判定测试：仅经销商主号可切换，成员（负责人/协作者）无个人开关、完全跟随。
 */
class SharedCatalogServiceTest {

    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final OfficeWorkspaceMapper workspaceMapper = mock(OfficeWorkspaceMapper.class);
    private final SharedCatalogService service = new SharedCatalogService(currentUserService, workspaceMapper);

    @Test
    void authDisabledDefaultsToFull() {
        when(currentUserService.authEnabled()).thenReturn(false);
        assertEquals("full", service.resolveViewMode());
    }

    @Test
    void userWithoutWorkspaceSeesFull() {
        OfficeUser admin = user(null, "ADMIN");
        assertTrue(service.canViewSharedCatalog(admin));
    }

    @Test
    void workspaceOnSeesFull() {
        workspace("WS1", true);
        assertTrue(service.canViewSharedCatalog(user("WS1", "USER")));
        assertTrue(service.canViewSharedCatalog(user("WS1", "DEALER")));
    }

    @Test
    void workspaceOffBlocksEveryone() {
        workspace("WS1", false);
        assertFalse(service.canViewSharedCatalog(user("WS1", "USER")));
        assertFalse(service.canViewSharedCatalog(user("WS1", "DEALER")));
    }

    @Test
    void memberCannotToggleWorkspaceSwitch() {
        when(currentUserService.authEnabled()).thenReturn(true);
        when(currentUserService.currentUser()).thenReturn(user("WS1", "USER"));
        assertThrows(AccessDeniedException.class, () -> service.setWorkspaceSharedCatalog(false));
    }

    @Test
    void dealerTogglePersistsWorkspaceSwitch() {
        OfficeWorkspace ws = workspace("WS1", true);
        when(currentUserService.authEnabled()).thenReturn(true);
        when(currentUserService.currentUser()).thenReturn(user("WS1", "DEALER"));

        service.setWorkspaceSharedCatalog(false);

        assertEquals(false, ws.getSharedCatalogEnabled());
        verify(workspaceMapper).updateById(ws);
    }

    @Test
    void memberStatusFollowsWorkspaceSwitch() {
        workspace("WS1", false);
        when(currentUserService.authEnabled()).thenReturn(true);
        when(currentUserService.currentUser()).thenReturn(user("WS1", "USER"));

        SharedCatalogService.SharedCatalogStatus status = service.currentStatus();

        assertFalse(status.workspaceEnabled());
        assertFalse(status.dealer());
        assertFalse(status.effectiveVisible());
    }

    private OfficeUser user(String workspaceId, String role) {
        OfficeUser user = new OfficeUser();
        user.setUserId("U1");
        user.setUsername("u1");
        user.setRole(role);
        user.setStatus("active");
        user.setWorkspaceId(workspaceId);
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
