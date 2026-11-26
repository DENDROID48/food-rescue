package com.foodrescue.security;

import com.foodrescue.config.AccountProperties;
import com.foodrescue.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    /** A clock the test can move forward. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-26T10:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void fiveWrongPasswordsLockTheEmailForAQuarterOfAnHour() {
        TestClock clock = new TestClock();
        RateLimiter limiter = new RateLimiter(clock, new AccountProperties(null, null, false, true));
        for (int i = 0; i < 5; i++) {
            limiter.check(RateLimiter.Limit.FAILED_LOGIN_EMAIL, "a@x.ro");
            limiter.record(RateLimiter.Limit.FAILED_LOGIN_EMAIL, "a@x.ro");
        }
        assertThatThrownBy(() -> limiter.check(RateLimiter.Limit.FAILED_LOGIN_EMAIL, "a@x.ro"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("15 minutes");
        // Other emails are not affected.
        assertThatCode(() -> limiter.check(RateLimiter.Limit.FAILED_LOGIN_EMAIL, "b@x.ro")).doesNotThrowAnyException();

        clock.now = clock.now.plus(Duration.ofMinutes(15)).plusSeconds(1);
        assertThatCode(() -> limiter.check(RateLimiter.Limit.FAILED_LOGIN_EMAIL, "a@x.ro")).doesNotThrowAnyException();
    }

    @Test
    void aCorrectPasswordClearsTheCount() {
        RateLimiter limiter = new RateLimiter(new TestClock(), new AccountProperties(null, null, false, true));
        for (int i = 0; i < 5; i++) limiter.record(RateLimiter.Limit.FAILED_LOGIN_EMAIL, "a@x.ro");
        limiter.reset(RateLimiter.Limit.FAILED_LOGIN_EMAIL, "a@x.ro");
        assertThatCode(() -> limiter.check(RateLimiter.Limit.FAILED_LOGIN_EMAIL, "a@x.ro")).doesNotThrowAnyException();
    }

    @Test
    void turnedOffItNeverRefuses() {
        RateLimiter limiter = new RateLimiter(new TestClock(), new AccountProperties(null, null, false, false));
        for (int i = 0; i < 50; i++) limiter.hit(RateLimiter.Limit.REGISTER_IP, "1.2.3.4");
        assertThatCode(() -> limiter.hit(RateLimiter.Limit.REGISTER_IP, "1.2.3.4")).doesNotThrowAnyException();
    }
}
