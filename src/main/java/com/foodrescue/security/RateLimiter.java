package com.foodrescue.security;

import com.foodrescue.config.AccountProperties;
import com.foodrescue.exception.BusinessRuleException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits how often something may be tried, for example wrong passwords for one email address,
 * so passwords cannot be guessed by trying thousands of them.
 *
 * <p>Kept in memory: enough for one server. With several servers behind a load balancer each
 * keeps its own count, which still slows guessing down a lot.</p>
 */
@Component
public class RateLimiter {

    /** What is limited, how many times, and within which period. */
    public enum Limit {
        /** Wrong passwords for one email address. */
        FAILED_LOGIN_EMAIL(5, Duration.ofMinutes(15)),
        /** Wrong passwords from one network address, whatever the email. */
        FAILED_LOGIN_IP(30, Duration.ofMinutes(15)),
        /** New accounts from one network address. */
        REGISTER_IP(10, Duration.ofHours(1)),
        /** "Forgot password" requests from one network address. */
        RESET_IP(10, Duration.ofHours(1)),
        /** Password checks while signed in (change password, change email, delete account). */
        PASSWORD_CHECK_USER(10, Duration.ofMinutes(15));

        final int max;
        final Duration period;

        Limit(int max, Duration period) {
            this.max = max;
            this.period = period;
        }
    }

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;
    private final boolean enabled;

    public RateLimiter(Clock clock, AccountProperties properties) {
        this.clock = clock;
        this.enabled = properties.rateLimits();
    }

    /** Refuses with 429 when the limit is already used up; does not count this attempt. */
    public void check(Limit limit, String key) {
        if (!enabled) return;
        Deque<Instant> times = hits.get(limit + ":" + key);
        if (times == null) return;
        synchronized (times) {
            prune(times, limit);
            if (times.size() >= limit.max) {
                Duration wait = Duration.between(clock.instant(), times.peekFirst().plus(limit.period));
                long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
                throw new BusinessRuleException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many attempts. Please try again in " + minutes + (minutes == 1 ? " minute" : " minutes"));
            }
        }
    }

    /** Counts one attempt. */
    public void record(Limit limit, String key) {
        if (!enabled) return;
        Deque<Instant> times = hits.computeIfAbsent(limit + ":" + key, k -> new ArrayDeque<>());
        synchronized (times) {
            prune(times, limit);
            times.addLast(clock.instant());
        }
    }

    /** Checks and counts in one go, for things limited however they turn out (sign-ups, reset requests). */
    public void hit(Limit limit, String key) {
        check(limit, key);
        record(limit, key);
    }

    /** A correct password clears the count of wrong ones for that email. */
    public void reset(Limit limit, String key) {
        hits.remove(limit + ":" + key);
    }

    private void prune(Deque<Instant> times, Limit limit) {
        Instant cutoff = clock.instant().minus(limit.period);
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) times.pollFirst();
    }

    /** Forgets old entries so memory does not grow. */
    @Scheduled(fixedDelay = 600_000)
    public void cleanUp() {
        hits.forEach((key, times) -> {
            Limit limit = Limit.valueOf(key.substring(0, key.indexOf(':')));
            synchronized (times) {
                prune(times, limit);
                if (times.isEmpty()) hits.remove(key, times);
            }
        });
    }
}
