package dev.finflow.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration IDLE_EVICTION = Duration.ofMinutes(10);
    private static final long MAX_TRACKED_BUCKETS = 100_000;

    private final RateLimitProperties properties;
    private final TimeMeter timeMeter;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(IDLE_EVICTION)
            .maximumSize(MAX_TRACKED_BUCKETS)
            .build();

    @Autowired
    public RateLimiter(RateLimitProperties properties) {
        this(properties, TimeMeter.SYSTEM_MILLISECONDS);
    }

    RateLimiter(RateLimitProperties properties, TimeMeter timeMeter) {
        this.properties = properties;
        this.timeMeter = timeMeter;
    }

    public RateLimitDecision check(UUID userId, RateLimitGroup group) {
        if (!properties.enabled()) {
            return RateLimitDecision.permitted(Long.MAX_VALUE);
        }
        Bucket bucket = buckets.get(userId + ":" + group, key -> newBucket(group));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return RateLimitDecision.permitted(probe.getRemainingTokens());
        }
        long seconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
        return RateLimitDecision.denied(seconds);
    }

    private Bucket newBucket(RateLimitGroup group) {
        int limit = properties.limitFor(group);
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(limit).refillGreedy(limit, WINDOW).build())
                .withCustomTimePrecision(timeMeter)
                .build();
    }
}
