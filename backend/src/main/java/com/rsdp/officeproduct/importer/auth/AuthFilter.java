package com.rsdp.officeproduct.importer.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.util.CurrentUserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    private final AuthService auth;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!auth.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        // /office 子路径入口经 X-Forwarded-Prefix 处理后，前缀并入 requestURI 与 contextPath，
        // 直接 startsWith 会让过滤器被整体跳过（登录态校验与用户上下文均失效），先剥离 contextPath。
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.startsWith("/api/auth/")
            || "/actuator/health".equals(path)
            || !(path.startsWith("/api/v1/") || path.startsWith("/uploads/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        AuthService.SessionCheck check = auth.checkSession(request);
        if (check.user() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            Result<Void> result = Result.fail("请先登录");
            result.setCode(HttpServletResponse.SC_UNAUTHORIZED);
            objectMapper.writeValue(response.getWriter(), result);
            return;
        }
        if (check.renewedToken() != null) {
            ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(AuthService.COOKIE_NAME, check.renewedToken())
                .httpOnly(true).secure(auth.cookieSecure()).sameSite("Lax").path("/");
            if (Boolean.TRUE.equals(check.session().getRememberMe())) cookie.maxAge(auth.rememberTtlSeconds());
            response.addHeader("Set-Cookie", cookie.build().toString());
        }
        try {
            CurrentUserContext.set(check.user().getUsername());
            chain.doFilter(request, response);
        } finally {
            CurrentUserContext.clear();
        }
    }
}
