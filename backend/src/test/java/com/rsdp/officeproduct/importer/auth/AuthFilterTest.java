package com.rsdp.officeproduct.importer.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthFilterTest {

    private AuthFilter filter(boolean authEnabled) {
        AuthService auth = mock(AuthService.class);
        when(auth.isEnabled()).thenReturn(authEnabled);
        return new AuthFilter(auth, new ObjectMapper());
    }

    @Test
    void filtersApiAndUploadPathsBehindForwardedPrefix() {
        AuthFilter filter = filter(true);

        // /office 子路径入口：X-Forwarded-Prefix 并入 requestURI 与 contextPath 后仍必须过滤。
        MockHttpServletRequest projects = new MockHttpServletRequest("GET", "/office/api/v1/office/projects");
        projects.setContextPath("/office");
        assertFalse(filter.shouldNotFilter(projects));

        MockHttpServletRequest uploads = new MockHttpServletRequest("GET", "/office/uploads/images/a.png");
        uploads.setContextPath("/office");
        assertFalse(filter.shouldNotFilter(uploads));

        // 登录相关接口与静态资源保持放行。
        MockHttpServletRequest authMe = new MockHttpServletRequest("GET", "/office/api/auth/me");
        authMe.setContextPath("/office");
        assertTrue(filter.shouldNotFilter(authMe));

        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/office/actuator/health");
        health.setContextPath("/office");
        assertTrue(filter.shouldNotFilter(health));
    }

    @Test
    void rootEntryBehaviorUnchanged() {
        AuthFilter filter = filter(true);

        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/products")));
        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/uploads/images/a.png")));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/auth/login")));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/assets/index.js")));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("OPTIONS", "/api/v1/products")));
    }

    @Test
    void skipsEverythingWhenAuthDisabled() {
        AuthFilter filter = filter(false);
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/products")));
    }
}
