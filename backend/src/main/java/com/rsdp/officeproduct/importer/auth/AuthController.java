package com.rsdp.officeproduct.importer.auth;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.entity.OfficeUserSession;
import javax.imageio.ImageIO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    public static final String CAPTCHA_COOKIE = "RSDP_CAPTCHA";

    private final AuthService auth;
    private final LoginAttemptGuard attempts;
    private final CaptchaService captcha;

    @PostMapping("/login")
    public ResponseEntity<Result<?>> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest,
                                           HttpServletResponse response) {
        String key = attemptKey(request.username(), httpRequest);
        long lockedSeconds = attempts.lockedRemainingSeconds(key);
        if (lockedSeconds > 0) {
            return ResponseEntity.status(429).body(fail(HttpServletResponse.SC_UNAUTHORIZED,
                "尝试次数过多，请 " + (lockedSeconds / 60 + 1) + " 分钟后再试"));
        }
        boolean captchaRequired = attempts.requiresCaptcha(key);
        if (captchaRequired && !captcha.verify(captchaId(httpRequest), request.captchaCode())) {
            attempts.recordFailure(key);
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                .body(fail(HttpServletResponse.SC_UNAUTHORIZED, "验证码错误或已过期", true));
        }
        OfficeUser user = auth.authenticate(request.username(), request.password());
        if (user == null) {
            attempts.recordFailure(key);
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                .body(fail(HttpServletResponse.SC_UNAUTHORIZED, "账号或密码错误", attempts.requiresCaptcha(key)));
        }
        attempts.reset(key);
        boolean rememberMe = Boolean.TRUE.equals(request.rememberMe());
        response.addHeader("Set-Cookie", sessionCookie(auth.issueSession(user, rememberMe, clientIp(httpRequest)), rememberMe));
        return ResponseEntity.ok(Result.ok(toResponse(user)));
    }

    @GetMapping(value = "/captcha", produces = MediaType.IMAGE_PNG_VALUE)
    public void captcha(HttpServletResponse response) throws IOException {
        String id = captcha.create();
        ResponseCookie cookie = ResponseCookie.from(CAPTCHA_COOKIE, id)
            .httpOnly(true).secure(auth.cookieSecure()).sameSite("Lax").path("/api/auth").maxAge(300).build();
        response.addHeader("Set-Cookie", cookie.toString());
        response.setContentType(MediaType.IMAGE_PNG_VALUE);
        ImageIO.write(captcha.render(id), "png", response.getOutputStream());
    }

    @PostMapping("/logout")
    public ResponseEntity<Result<Void>> logout(HttpServletRequest request, HttpServletResponse response) {
        auth.revokeSession(request);
        ResponseCookie cookie = ResponseCookie.from(AuthService.COOKIE_NAME, "")
            .httpOnly(true).secure(auth.cookieSecure()).sameSite("Lax").path("/").maxAge(0).build();
        response.addHeader("Set-Cookie", cookie.toString());
        return ResponseEntity.ok(Result.ok(null));
    }

    @GetMapping("/me")
    public ResponseEntity<Result<UserResponse>> me(HttpServletRequest request, HttpServletResponse response) {
        AuthService.SessionCheck check = auth.checkSession(request);
        if (check.user() == null) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).body(fail(HttpServletResponse.SC_UNAUTHORIZED, "请先登录"));
        }
        if (check.renewedToken() != null) {
            response.addHeader("Set-Cookie", renewedCookie(check));
        }
        return ResponseEntity.ok(Result.ok(toResponse(check.user())));
    }

    private String renewedCookie(AuthService.SessionCheck check) {
        return sessionCookie(check.renewedToken(), Boolean.TRUE.equals(check.session().getRememberMe()));
    }

    private String sessionCookie(String token, boolean rememberMe) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(AuthService.COOKIE_NAME, token)
            .httpOnly(true).secure(auth.cookieSecure()).sameSite("Lax").path("/");
        if (rememberMe) builder.maxAge(auth.rememberTtlSeconds());
        return builder.build().toString();
    }

    private static UserResponse toResponse(OfficeUser user) {
        return new UserResponse(user.getUserId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }

    private String attemptKey(String username, HttpServletRequest request) {
        return (username == null ? "" : username.trim().toLowerCase()) + "|" + clientIp(request);
    }

    private String captchaId(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (CAPTCHA_COOKIE.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private static <T> Result<T> fail(int code, String message) {
        Result<T> result = Result.fail(message);
        result.setCode(code);
        return result;
    }

    private static Result<Map<String, Object>> fail(int code, String message, Boolean captchaRequired) {
        Result<Map<String, Object>> result = Result.fail(message);
        result.setCode(code);
        if (captchaRequired != null) result.setData(Map.of("captchaRequired", captchaRequired));
        return result;
    }

    public record LoginRequest(String username, String password, Boolean rememberMe, String captchaCode) {}
    public record UserResponse(String userId, String username, String displayName, String role) {}
}
