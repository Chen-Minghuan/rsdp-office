package com.rsdp.officeproduct.importer.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.entity.OfficeUserSession;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.mapper.OfficeUserSessionMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/** Database-backed authentication with signed HttpOnly cookies backed by server-side session rows. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final String COOKIE_NAME = "RSDP_SESSION";

    private final OfficeUserMapper users;
    private final OfficeUserSessionMapper sessions;

    @Value("${rsdp.auth.enabled:false}")
    private boolean enabled;
    @Value("${rsdp.auth.secret:local-only-change-me}")
    private String secretValue;
    @Value("${rsdp.auth.session-ttl-seconds:43200}")
    private long ttlSeconds;
    @Value("${rsdp.auth.remember-ttl-seconds:2592000}")
    private long rememberTtlSeconds;
    @Value("${rsdp.auth.cookie-secure:false}")
    private boolean cookieSecure;
    @Value("${rsdp.auth.bootstrap-username:}")
    private String bootstrapUsername;
    @Value("${rsdp.auth.bootstrap-password:}")
    private String bootstrapPassword;

    @PostConstruct
    void validateConfiguration() {
        if (enabled && (secretValue == null || secretValue.length() < 16)) {
            throw new IllegalStateException("公网认证已开启，但 RSDP_AUTH_SECRET 少于 16 位");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrapUser() {
        if (!enabled || bootstrapUsername == null || bootstrapUsername.isBlank()
            || bootstrapPassword == null || bootstrapPassword.isBlank()) return;
        OfficeUser existing = findByUsername(bootstrapUsername.trim());
        if (existing != null) return;
        OfficeUser user = new OfficeUser();
        user.setUserId("USR-" + UUID.randomUUID().toString().replace("-", ""));
        user.setUsername(bootstrapUsername.trim());
        user.setPasswordHash(PasswordHasher.hash(bootstrapPassword));
        user.setDisplayName(bootstrapUsername.trim());
        user.setRole("USER");
        user.setStatus("active");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        users.insert(user);
        log.info("已创建首个数据库用户 {}，后续登录使用 office_user 中的哈希密码", user.getUsername());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public long rememberTtlSeconds() {
        return rememberTtlSeconds;
    }

    public boolean cookieSecure() {
        return cookieSecure;
    }

    public OfficeUser authenticate(String username, String password) {
        if (username == null || password == null) return null;
        OfficeUser user = findByUsername(username.trim());
        if (user == null || !"active".equalsIgnoreCase(user.getStatus())) return null;
        if (!PasswordHasher.matches(password, user.getPasswordHash())) return null;
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        users.updateById(user);
        return user;
    }

    /** 校验会话并在剩余有效期不足一半时滑动续期。 */
    public SessionCheck checkSession(HttpServletRequest request) {
        if (!enabled) return SessionCheck.anonymous();
        String token = sessionToken(request);
        if (token == null) return SessionCheck.anonymous();
        OfficeUserSession session = verify(token);
        if (session == null) return SessionCheck.anonymous();
        OfficeUser user = users.selectById(session.getUserId());
        if (user == null || !"active".equalsIgnoreCase(user.getStatus())) return SessionCheck.anonymous();
        String renewedToken = null;
        long ttl = Boolean.TRUE.equals(session.getRememberMe()) ? rememberTtlSeconds : ttlSeconds;
        long remaining = java.time.Duration.between(LocalDateTime.now(), session.getExpiresAt()).getSeconds();
        if (remaining < ttl / 2) {
            session.setExpiresAt(LocalDateTime.now().plusSeconds(ttl));
            renewedToken = token(session.getSessionId(), session.getExpiresAt());
        }
        session.setLastUsedAt(LocalDateTime.now());
        sessions.updateById(session);
        return new SessionCheck(user, session, renewedToken);
    }

    public OfficeUser sessionUser(HttpServletRequest request) {
        SessionCheck check = checkSession(request);
        return check == null ? null : check.user();
    }

    /** 退出登录：删除服务端会话记录，旧令牌立即失效。 */
    public void revokeSession(HttpServletRequest request) {
        String token = sessionToken(request);
        if (token == null) return;
        String sessionId = sessionIdOf(token);
        if (sessionId != null) sessions.deleteById(sessionId);
    }

    private String sessionToken(HttpServletRequest request) {
        if (!enabled) return null;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    /** 登录成功后创建服务端会话并返回签名令牌。 */
    public String issueSession(OfficeUser user, boolean rememberMe, String clientIp) {
        OfficeUserSession session = new OfficeUserSession();
        session.setSessionId("SES-" + UUID.randomUUID().toString().replace("-", ""));
        session.setUserId(user.getUserId());
        session.setRememberMe(rememberMe);
        long ttl = rememberMe ? rememberTtlSeconds : ttlSeconds;
        session.setExpiresAt(LocalDateTime.now().plusSeconds(ttl));
        session.setCreatedIp(clientIp);
        session.setCreatedAt(LocalDateTime.now());
        sessions.insert(session);
        return token(session.getSessionId(), session.getExpiresAt());
    }

    private String token(String sessionId, LocalDateTime expiresAt) {
        String payload = sessionId + "|" + expiresAt.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        return encode(payload.getBytes(StandardCharsets.UTF_8)) + "." + encode(sign(payload));
    }

    public OfficeUser findByUsername(String username) {
        return users.selectOne(new QueryWrapper<OfficeUser>().eq("username", username).last("limit 1"));
    }

    private OfficeUserSession verify(String token) {
        try {
            String sessionId = sessionIdOf(token);
            if (sessionId == null) return null;
            OfficeUserSession session = sessions.selectById(sessionId);
            if (session == null) return null;
            return session.getExpiresAt().isAfter(LocalDateTime.now()) ? session : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String sessionIdOf(String token) {
        try {
            String[] parts = token.split("\\.", 2);
            if (parts.length != 2) return null;
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(sign(payload), Base64.getUrlDecoder().decode(parts[1]))) return null;
            String[] fields = payload.split("\\|", 2);
            if (fields.length != 2 || Long.parseLong(fields[1]) <= Instant.now().getEpochSecond()) return null;
            return fields[0];
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretValue.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("无法创建登录会话", ex);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public record SessionCheck(OfficeUser user, OfficeUserSession session, String renewedToken) {
        static SessionCheck anonymous() {
            return new SessionCheck(null, null, null);
        }
    }
}
