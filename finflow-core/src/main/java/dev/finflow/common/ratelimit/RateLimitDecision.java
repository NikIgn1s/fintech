package dev.finflow.common.ratelimit;

public record RateLimitDecision(boolean allowed, long remaining, long retryAfterSeconds) {

    static RateLimitDecision permitted(long remaining) {
        return new RateLimitDecision(true, remaining, 0);
    }

    static RateLimitDecision denied(long retryAfterSeconds) {
        return new RateLimitDecision(false, 0, retryAfterSeconds);
    }
}
