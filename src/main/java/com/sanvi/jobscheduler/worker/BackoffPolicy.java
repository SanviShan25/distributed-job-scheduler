package com.sanvi.jobscheduler.worker;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/**
 * Exponential backoff with full jitter: delay = random(0, min(cap, base * 2^attempt)).
 * Full jitter spreads retries of jobs that failed together (e.g. a downstream outage).
 */
public class BackoffPolicy {
    private final long baseMillis;
    private final long capMillis;
    /** Given an exclusive upper bound n, returns a value in [0, n). Swappable for tests. */
    private final LongUnaryOperator random;

    public BackoffPolicy(Duration base, Duration cap) {
        this(base, cap, bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    public BackoffPolicy(Duration base, Duration cap, LongUnaryOperator random) {
        if (base.isNegative() || base.isZero() || cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("backoff requires 0 < base <= cap");
        }
        this.baseMillis = base.toMillis();
        this.capMillis = cap.toMillis();
        this.random = random;
    }

    /** @param attempt the attempt that just failed (1-based) */
    public Duration delayFor(int attempt) {
        return Duration.ofMillis(random.applyAsLong(ceilingFor(attempt) + 1));
    }

    long ceilingFor(int attempt) {
        int exponent = Math.max(0, Math.min(attempt, 30));
        long ceiling = baseMillis << exponent;
        return ceiling <= 0 ? capMillis : Math.min(capMillis, ceiling);
    }
}
