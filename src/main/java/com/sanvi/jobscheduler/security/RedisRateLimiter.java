package com.sanvi.jobscheduler.security;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Token bucket in Redis, shared by all API instances. One Lua call per request reads the bucket,
 * refills it based on elapsed time (using Redis' own clock, so API hosts' clocks don't matter),
 * takes a token if available and writes it back atomically.
 */
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> TOKEN_BUCKET = new DefaultRedisScript<>("""
        local capacity = tonumber(ARGV[1])
        local rate = tonumber(ARGV[2])          -- tokens per millisecond
        local t = redis.call('TIME')
        local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
        local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
        local tokens = tonumber(state[1]) or capacity
        local ts = tonumber(state[2]) or now
        tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)
        local allowed = 0
        if tokens >= 1 then
          tokens = tokens - 1
          allowed = 1
        end
        redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
        redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate) + 1000)
        local retryAfterMs = 0
        if allowed == 0 then retryAfterMs = math.ceil((1 - tokens) / rate) end
        return {allowed, math.floor(tokens), retryAfterMs}
        """, List.class);

    private final StringRedisTemplate redis;

    public record Decision(boolean allowed, long remaining, long retryAfterMillis) {}

    public Decision tryConsume(String bucket, int capacity, double refillPerSecond) {
        @SuppressWarnings("unchecked")
        List<Object> result = redis.execute(TOKEN_BUCKET, List.of("ratelimit:" + bucket),
            String.valueOf(capacity), String.valueOf(refillPerSecond / 1000.0));
        if (result == null || result.size() < 3) {
            return new Decision(true, capacity, 0);
        }
        return new Decision(toLong(result.get(0)) == 1, toLong(result.get(1)), toLong(result.get(2)));
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
    }
}
