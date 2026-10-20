package com.rsdp.officeproduct.importer.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流：连续失败达到阈值后要求验证码，再继续失败则锁定一段时间。
 * 计数保存在单实例内存中，服务重启后清零；多实例部署时各实例独立计数。
 */
@Component
public class LoginAttemptGuard {

    private final int captchaThreshold;
    private final int lockThreshold;
    private final long lockSeconds;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginAttemptGuard(
        @Value("${rsdp.auth.captcha-after-failures:2}") int captchaThreshold,
        @Value("${rsdp.auth.lock-after-failures:5}") int lockThreshold,
        @Value("${rsdp.auth.lock-seconds:900}") long lockSeconds) {
        this.captchaThreshold = captchaThreshold;
        this.lockThreshold = lockThreshold;
        this.lockSeconds = lockSeconds;
    }

    public boolean requiresCaptcha(String key) {
        return failures(key) >= captchaThreshold;
    }

    public long lockedRemainingSeconds(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null || attempt.lockedUntilEpoch == 0) return 0;
        long remaining = attempt.lockedUntilEpoch - Instant.now().getEpochSecond();
        return Math.max(remaining, 0);
    }

    public void recordFailure(String key) {
        attempts.compute(key, (k, current) -> {
            Attempt next = current == null ? new Attempt() : current;
            if (next.lockedUntilEpoch > 0 && next.lockedUntilEpoch <= Instant.now().getEpochSecond()) {
                next.failures = 0;
                next.lockedUntilEpoch = 0;
            }
            next.failures++;
            if (next.failures >= lockThreshold) {
                next.lockedUntilEpoch = Instant.now().getEpochSecond() + lockSeconds;
            }
            return next;
        });
    }

    public void reset(String key) {
        attempts.remove(key);
    }

    private int failures(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) return 0;
        if (attempt.lockedUntilEpoch > 0 && attempt.lockedUntilEpoch <= Instant.now().getEpochSecond()) return 0;
        return attempt.failures;
    }

    private static final class Attempt {
        private int failures;
        private long lockedUntilEpoch;
    }
}
