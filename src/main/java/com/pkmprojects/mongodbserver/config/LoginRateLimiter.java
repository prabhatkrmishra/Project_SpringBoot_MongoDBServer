package com.pkmprojects.mongodbserver.config;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fixed-window login attempt counter backed by Redis. Redis keeps the counter
 * atomic and shared across app instances, and a lone {@code INCR}/{@code EXPIRE}
 * pair avoids the races of in-process state.
 */
@Component
public class LoginRateLimiter {

    private static final String KEY_PREFIX = "login-rate-limit:";

    private final StringRedisTemplate redisTemplate;

    public LoginRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Records an attempt for {@code clientKey} and reports whether it stays within
     * {@code maxAttempts} for the current {@code window}.
     */
    public boolean isAllowed(String clientKey, int maxAttempts, Duration window) {
        String key = KEY_PREFIX + clientKey;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, window);
        }
        return count == null || count <= maxAttempts;
    }
}
