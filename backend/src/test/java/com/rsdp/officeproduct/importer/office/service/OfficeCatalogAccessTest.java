package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.config.RsdpMainProperties;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OfficeCatalogAccessTest {
    private final CurrentUserService users = mock(CurrentUserService.class);

    @Test
    void boundWorkspaceCanReadHistoryAsTheCurrentMember() {
        var properties = new RsdpMainProperties();
        properties.setCatalogWorkspaceId("WORKSPACE-1");
        properties.setCatalogFactoryCode("F001");
        var access = new OfficeCatalogAccess(users, properties);
        when(users.currentUser()).thenReturn(user("OTHER", "EDITOR-1", "DEALER"));
        assertThrows(AccessDeniedException.class, access::requireHistoryReader);
        when(users.currentUser()).thenReturn(user("WORKSPACE-1", "OTHER", "USER"));
        assertTrue(access.capabilities().managed());
        when(users.currentUser()).thenReturn(user("WORKSPACE-1", "EDITOR-1", "USER"));
        assertEquals("F001", access.requireHistoryReader().factoryCode());
        when(users.currentUser()).thenReturn(user("WORKSPACE-1", "OWNER", "DEALER"));
        assertEquals("OWNER", access.requireHistoryReader().actorId());
    }

    private OfficeUser user(String workspace, String id, String role) {
        var user = new OfficeUser();
        user.setWorkspaceId(workspace);
        user.setUserId(id);
        user.setRole(role);
        return user;
    }
}
