package com.rsdp.officeproduct.importer.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CaptchaServiceTest {

    @Test
    void verifiesOnceAndRejectsReuse() {
        CaptchaService captcha = new CaptchaService();
        String id = captcha.create();
        assertNotNull(captcha.render(id));
        assertFalse(captcha.verify(id, "wrong-code"));
        // 验证码一次性：错误验证也消耗该 id
        assertFalse(captcha.verify(id, "anything"));
    }

    @Test
    void rejectsUnknownAndBlank() {
        CaptchaService captcha = new CaptchaService();
        assertFalse(captcha.verify(null, "ABCD"));
        assertFalse(captcha.verify("missing", "ABCD"));
        assertFalse(captcha.verify(captcha.create(), null));
    }
}
