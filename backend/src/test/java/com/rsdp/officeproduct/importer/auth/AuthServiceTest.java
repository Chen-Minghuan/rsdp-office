package com.rsdp.officeproduct.importer.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.entity.OfficeUserSession;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.mapper.OfficeUserSessionMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {

    @Test
    void issuesAndValidatesServerBackedSession() {
        OfficeUserMapper mapper = mock(OfficeUserMapper.class);
        OfficeUserSessionMapper sessionMapper = mock(OfficeUserSessionMapper.class);
        OfficeUser user = user();
        when(mapper.selectOne(any())).thenReturn(user);
        when(mapper.selectById("USR-1")).thenReturn(user);
        AuthService auth = configured(mapper, sessionMapper, 600);
        when(sessionMapper.selectById(any())).thenAnswer(invocation -> {
            OfficeUserSession session = new OfficeUserSession();
            session.setSessionId(invocation.getArgument(0));
            session.setUserId("USR-1");
            session.setRememberMe(false);
            session.setExpiresAt(java.time.LocalDateTime.now().plusSeconds(600));
            return session;
        });
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthService.COOKIE_NAME, auth.issueSession(user, false, "127.0.0.1")));

        assertNotNull(auth.authenticate("alice", "secret-password"));
        assertNull(auth.authenticate("alice", "wrong"));
        assertEquals("alice", auth.sessionUser(request).getUsername());
    }

    @Test
    void rejectsTamperedAndExpiredSessions() {
        OfficeUserMapper mapper = mock(OfficeUserMapper.class);
        OfficeUserSessionMapper sessionMapper = mock(OfficeUserSessionMapper.class);
        OfficeUser user = user();
        when(mapper.selectById("USR-1")).thenReturn(user);
        AuthService auth = configured(mapper, sessionMapper, 600);
        MockHttpServletRequest tampered = new MockHttpServletRequest();
        tampered.setCookies(new Cookie(AuthService.COOKIE_NAME, auth.issueSession(user, false, null) + "x"));
        assertNull(auth.sessionUser(tampered));

        AuthService expiredAuth = configured(mapper, sessionMapper, -1);
        MockHttpServletRequest expired = new MockHttpServletRequest();
        expired.setCookies(new Cookie(AuthService.COOKIE_NAME, expiredAuth.issueSession(user, false, null)));
        assertNull(expiredAuth.sessionUser(expired));
    }

    @Test
    void revokedSessionsAreRejectedImmediately() {
        OfficeUserMapper mapper = mock(OfficeUserMapper.class);
        OfficeUserSessionMapper sessionMapper = mock(OfficeUserSessionMapper.class);
        OfficeUser user = user();
        when(mapper.selectById("USR-1")).thenReturn(user);
        AuthService auth = configured(mapper, sessionMapper, 600);
        String token = auth.issueSession(user, false, null);
        when(sessionMapper.selectById(any())).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthService.COOKIE_NAME, token));
        assertNull(auth.sessionUser(request));
    }

    @Test
    void renewsSessionWhenRemainingLifeIsShort() {
        OfficeUserMapper mapper = mock(OfficeUserMapper.class);
        OfficeUserSessionMapper sessionMapper = mock(OfficeUserSessionMapper.class);
        OfficeUser user = user();
        when(mapper.selectById("USR-1")).thenReturn(user);
        AuthService auth = configured(mapper, sessionMapper, 600);
        OfficeUserSession session = new OfficeUserSession();
        session.setSessionId("SES-1");
        session.setUserId("USR-1");
        session.setRememberMe(false);
        session.setExpiresAt(java.time.LocalDateTime.now().plusSeconds(60));
        when(sessionMapper.selectById("SES-1")).thenReturn(session);

        String token = auth.issueSession(user, false, null);
        OfficeUserSession issued = sessionWithIdFrom(token);
        when(sessionMapper.selectById(issued.getSessionId())).thenReturn(session);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthService.COOKIE_NAME, token));
        AuthService.SessionCheck check = auth.checkSession(request);
        assertNotNull(check.user());
        assertNotNull(check.renewedToken());
        assertTrue(session.getExpiresAt().isAfter(java.time.LocalDateTime.now().plusSeconds(500)));
    }

    private static OfficeUserSession sessionWithIdFrom(String token) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.", 2)[0]));
        OfficeUserSession session = new OfficeUserSession();
        session.setSessionId(payload.split("\\|", 2)[0]);
        return session;
    }

    private static OfficeUser user() {
        OfficeUser user = new OfficeUser();
        user.setUserId("USR-1");
        user.setUsername("alice");
        user.setPasswordHash(PasswordHasher.hash("secret-password"));
        user.setStatus("active");
        return user;
    }

    private static AuthService configured(OfficeUserMapper mapper, OfficeUserSessionMapper sessions, long ttl) {
        AuthService auth = new AuthService(mapper, sessions);
        ReflectionTestUtils.setField(auth, "enabled", true);
        ReflectionTestUtils.setField(auth, "secretValue", "test-signing-secret");
        ReflectionTestUtils.setField(auth, "ttlSeconds", ttl);
        ReflectionTestUtils.setField(auth, "rememberTtlSeconds", 2592000L);
        return auth;
    }
}
