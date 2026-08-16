package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Brute-force protection for the login form, bound from {@code app.login-rate-limit.*}.
 * Allows {@code maxAttempts} login POSTs per client (IP + submitted username) within
 * {@code window}; further attempts get HTTP 429 until the window passes.
 *
 * @param maxAttempts         how many login attempts a client may make per window
 * @param window              the rolling window length
 * @param trustXForwardedFor  honor {@code X-Forwarded-For} as the client identity
 *                            (only safe when the app sits behind a trusted reverse proxy)
 */
@ConfigurationProperties(prefix = "app.login-rate-limit")
public record LoginRateLimitProperties(int maxAttempts, Duration window, boolean trustXForwardedFor) {

    public LoginRateLimitProperties {
        if (maxAttempts <= 0) {
            maxAttempts = 5;
        }
        if (window == null) {
            window = Duration.ofMinutes(15);
        }
    }
}
