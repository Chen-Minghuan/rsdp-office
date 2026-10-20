package com.rsdp.officeproduct.importer.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptGuardTest {

    @Test
    void requiresCaptchaAfterThresholdAndLocksAfterMoreFailures() {
        LoginAttemptGuard guard = new LoginAttemptGuard(2, 5, 900);
        String key = "alice|127.0.0.1";
        assertFalse(guard.requiresCaptcha(key));
        guard.recordFailure(key);
        assertFalse(guard.requiresCaptcha(key));
        guard.recordFailure(key);
        assertTrue(guard.requiresCaptcha(key));
        guard.recordFailure(key);
        guard.recordFailure(key);
        assertEquals(0, guard.lockedRemainingSeconds(key));
        guard.recordFailure(key);
        assertTrue(guard.lockedRemainingSeconds(key) > 0);
    }

    @Test
    void resetClearsFailures() {
        LoginAttemptGuard guard = new LoginAttemptGuard(2, 5, 900);
        String key = "alice|127.0.0.1";
        guard.recordFailure(key);
        guard.recordFailure(key);
        assertTrue(guard.requiresCaptcha(key));
        guard.reset(key);
        assertFalse(guard.requiresCaptcha(key));
        assertEquals(0, guard.lockedRemainingSeconds(key));
    }
}
