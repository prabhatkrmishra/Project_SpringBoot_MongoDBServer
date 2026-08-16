package com.pkmprojects.mongodbserver.config;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window login attempt counter held in-process. Single-instance brute-force
 * protection: the counter lives in JVM memory, so a restart resets every client's
 * window (acceptable for a single-admin control plane - a restart is a rare,
 * admin-driven event). Kept intentionally simple; if the app ever scales to
 * multiple instances behind a load balancer, swap this for a shared store (e.g.
 * Redis {@code INCR}/{@code EXPIRE}) - the {@link LoginRateLimitFilter} contract
 * stays identical.
 */
@Component
public class LoginRateLimiter {

    private record WindowEntry(int count, Instant windowStart) {
    }

    private final ConcurrentHashMap<String, WindowEntry> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records an attempt for {@code clientKey} and reports whether it stays within
     * {@code maxAttempts} for the current {@code window}.
     */
    public boolean isAllowed(String clientKey, int maxAttempts, Duration window) {
        Instant now = clock.instant();
        WindowEntry updated = attempts.compute(clientKey, (key, entry) -> {
            if (entry == null || !entry.windowStart().plus(window).isAfter(now)) {
                return new WindowEntry(1, now);
            }
            return new WindowEntry(entry.count() + 1, entry.windowStart());
        });
        return updated.count() <= maxAttempts;
    }
}
