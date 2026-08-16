package com.pkmprojects.mongodbserver.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the in-process {@link LoginRateLimiter}: a fixed window of
 * {@code maxAttempts} per client key, window expiry, and independent keys.
 */
class LoginRateLimiterTest {

    private static final Instant START = Instant.parse("2026-08-17T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock(START);
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock);

    @Test
    void allowsUpToMaxAttemptsThenBlocks() {
        assertThat(limiter.isAllowed("1.2.3.4:bob", 5, WINDOW)).isTrue();
        assertThat(limiter.isAllowed("1.2.3.4:bob", 5, WINDOW)).isTrue();
        assertThat(limiter.isAllowed("1.2.3.4:bob", 5, WINDOW)).isTrue();
        assertThat(limiter.isAllowed("1.2.3.4:bob", 5, WINDOW)).isTrue();
        assertThat(limiter.isAllowed("1.2.3.4:bob", 5, WINDOW)).isTrue();
        assertThat(limiter.isAllowed("1.2.3.4:bob", 5, WINDOW)).isFalse();
    }

    @Test
    void windowExpiryResetsTheCount() {
        assertThat(limiter.isAllowed("1.2.3.4:bob", 2, WINDOW)).isTrue();
        assertThat(limiter.isAllowed("1.2.3.4:bob", 2, WINDOW)).isTrue();
        assertThat(limiter.isAllowed("1.2.3.4:bob", 2, WINDOW)).isFalse(); // blocked at max 2

        clock.advanceBy(WINDOW);
        assertThat(limiter.isAllowed("1.2.3.4:bob", 2, WINDOW)).isTrue();
        assertThat(limiter.isAllowed("1.2.3.4:bob", 2, WINDOW)).isTrue();
        assertThat(limiter.isAllowed("1.2.3.4:bob", 2, WINDOW)).isFalse();
    }

    @Test
    void windowBoundaryKeepsTheExistingCount() {
        // exactly at the boundary the old window is still active; just before
        // the expiry instant the count carries over (isBefore, not isBeforeOrEqual)
        assertThat(limiter.isAllowed("1.2.3.4:bob", 2, WINDOW)).isTrue();
        clock.advanceBy(WINDOW.minusSeconds(1));
        assertThat(limiter.isAllowed("1.2.3.4:bob", 2, WINDOW)).isTrue();
        assertThat(limiter.isAllowed("1.2.3.4:bob", 2, WINDOW)).isFalse();
    }

    @Test
    void distinctClientKeysAreIndependent() {
        assertThat(limiter.isAllowed("1.2.3.4:bob", 1, WINDOW)).isTrue();
        assertThat(limiter.isAllowed("1.2.3.4:bob", 1, WINDOW)).isFalse();

        assertThat(limiter.isAllowed("5.6.7.8:alice", 1, WINDOW)).isTrue();
        assertThat(limiter.isAllowed("1.2.3.4:carol", 1, WINDOW)).isTrue();
    }

    /**
     * A clock whose reading can be advanced deterministically in tests.
     */
    private static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceBy(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
