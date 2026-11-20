package com.sanvi.jobscheduler.lock;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimal Redis lease lock (SET NX PX + compare-and-delete / compare-and-expire). Good enough to keep
 * singleton background tasks from running concurrently; all guarded work is also safe to run twice
 * because the underlying state transitions are conditional.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DistributedLock {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) "
            + "else return 0 end",
        Long.class);

    private final StringRedisTemplate redis;

    public Optional<Handle> tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? Optional.of(new Handle(key, token, ttl)) : Optional.empty();
    }

    /** Runs {@code task} only if the lock is free; returns false if another instance holds it. */
    public boolean runExclusively(String key, Duration ttl, java.util.function.Consumer<Handle> task) {
        Optional<Handle> handle = tryAcquire(key, ttl);
        if (handle.isEmpty()) {
            return false;
        }
        try (Handle h = handle.get()) {
            task.accept(h);
        }
        return true;
    }

    public final class Handle implements AutoCloseable {
        private final String key;
        private final String token;
        private final Duration ttl;

        private Handle(String key, String token, Duration ttl) {
            this.key = key;
            this.token = token;
            this.ttl = ttl;
        }

        /** Extends the lock; returns false if it was lost (expired and taken by someone else). */
        public boolean renew() {
            Long result = redis.execute(RENEW, List.of(key), token, String.valueOf(ttl.toMillis()));
            return result != null && result == 1L;
        }

        @Override
        public void close() {
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (RuntimeException e) {
                log.warn("Unable to release lock {}; it will expire automatically", key, e);
            }
        }
    }
}
