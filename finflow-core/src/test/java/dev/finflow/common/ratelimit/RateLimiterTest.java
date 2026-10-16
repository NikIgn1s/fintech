package dev.finflow.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final class ManualTimeMeter implements TimeMeter {

        private final AtomicLong nanos = new AtomicLong();

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }

    private final ManualTimeMeter time = new ManualTimeMeter();

    private RateLimiter limiter(boolean enabled, int transfers, int cash) {
        return new RateLimiter(new RateLimitProperties(enabled, transfers, cash), time);
    }

    @Test
    void allowsUpToTheLimitThenDenies() {
        RateLimiter limiter = limiter(true, 3, 20);
        UUID user = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.check(user, RateLimitGroup.TRANSFERS).allowed()).isTrue();
        }
        RateLimitDecision denied = limiter.check(user, RateLimitGroup.TRANSFERS);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 21L);
    }

    @Test
    void reportsRemainingTokens() {
        RateLimiter limiter = limiter(true, 5, 20);
        UUID user = UUID.randomUUID();

        assertThat(limiter.check(user, RateLimitGroup.TRANSFERS).remaining()).isEqualTo(4);
        assertThat(limiter.check(user, RateLimitGroup.TRANSFERS).remaining()).isEqualTo(3);
    }

    @Test
    void refillsGraduallyOverTime() {
        RateLimiter limiter = limiter(true, 3, 20);
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            limiter.check(user, RateLimitGroup.TRANSFERS);
        }
        assertThat(limiter.check(user, RateLimitGroup.TRANSFERS).allowed()).isFalse();

        time.advance(Duration.ofSeconds(21));

        assertThat(limiter.check(user, RateLimitGroup.TRANSFERS).allowed()).isTrue();
        assertThat(limiter.check(user, RateLimitGroup.TRANSFERS).allowed()).isFalse();
    }

    @Test
    void fullyRefillsAfterAWholeWindow() {
        RateLimiter limiter = limiter(true, 3, 20);
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            limiter.check(user, RateLimitGroup.TRANSFERS);
        }

        time.advance(Duration.ofMinutes(1));

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.check(user, RateLimitGroup.TRANSFERS).allowed()).isTrue();
        }
    }

    @Test
    void usersAndGroupsAreIsolated() {
        RateLimiter limiter = limiter(true, 1, 1);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        assertThat(limiter.check(alice, RateLimitGroup.TRANSFERS).allowed()).isTrue();
        assertThat(limiter.check(alice, RateLimitGroup.TRANSFERS).allowed()).isFalse();

        assertThat(limiter.check(bob, RateLimitGroup.TRANSFERS).allowed()).isTrue();
        assertThat(limiter.check(alice, RateLimitGroup.CASH).allowed()).isTrue();
    }

    @Test
    void disabledLimiterAllowsEverything() {
        RateLimiter limiter = limiter(false, 1, 1);
        UUID user = UUID.randomUUID();

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.check(user, RateLimitGroup.TRANSFERS).allowed()).isTrue();
        }
    }
}
