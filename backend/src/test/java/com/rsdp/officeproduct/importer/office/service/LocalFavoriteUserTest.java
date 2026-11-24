package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.auth.AuthService;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalFavoriteUserTest {
    private final AuthService auth = mock(AuthService.class);
    private final OfficeUserMapper users = mock(OfficeUserMapper.class);
    private final CurrentUserService service = new CurrentUserService(auth, users);

    @Test
    void localFavoritesEnsureTheirForeignKeyIdentityOnEveryRequest() {
        assertEquals("LOCAL-DEV", service.ensureLocalFavoriteUser());
        assertEquals("LOCAL-DEV", service.ensureLocalFavoriteUser());
        verify(users, times(2)).insertLocalFavoriteUser();
    }

    @Test
    void authenticatedModeCannotCreateOrUseTheLocalIdentity() {
        when(auth.isEnabled()).thenReturn(true);
        assertThrows(AccessDeniedException.class, service::ensureLocalFavoriteUser);
        verifyNoInteractions(users);
    }
}
