package com.sanvi.jobscheduler.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Redis side of the queue. Postgres is the source of truth; everything here can be rebuilt from it
 * (see the drift sweeper in {@code Reaper}).
 *
 * <ul>
 *   <li>{@code queue:ready}   ZSET, score = priority * 1e13 + enqueue-millis (lower pops first)</li>
 *   <li>{@code queue:delayed} ZSET, score = run-at millis</li>
 *   <li>{@code queue:delayed:priority} HASH id -> priority, so promotion needs no DB lookup</li>
 *   <li>{@code queue:dead}    LIST of dead job ids</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class JobQueue {
    public static final String READY = "queue:ready", DELAYED = "queue:delayed", DEAD = "queue:dead";
    public static final String DELAYED_PRIORITY = "queue:delayed:priority";
    public static final String CANCEL_PREFIX = "job:cancel:";
    private static final double PRIORITY_WEIGHT = 1e13;
    private static final int DEFAULT_PRIORITY = 5;

    private static final DefaultRedisScript<Long> ENQUEUE_DELAYED = new DefaultRedisScript<>("""
        redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
        redis.call('ZADD', KEYS[1], ARGV[3], ARGV[1])
        return 1
        """, Long.class);

    /** Atomically promote due members of queue:delayed into queue:ready. */
    private static final DefaultRedisScript<Long> MOVE_DUE = new DefaultRedisScript<>("""
        local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'WITHSCORES', 'LIMIT', 0, tonumber(ARGV[2]))
        local moved = 0
        for i = 1, #due, 2 do
          local id = due[i]
          local runAt = tonumber(due[i + 1])
          local priority = tonumber(redis.call('HGET', KEYS[3], id)) or tonumber(ARGV[3])
          redis.call('ZREM', KEYS[1], id)
          redis.call('HDEL', KEYS[3], id)
          redis.call('ZADD', KEYS[2], priority * 10000000000000 + runAt, id)
          moved = moved + 1
        end
        return moved
        """, Long.class);

    /** Returns the subset of ARGV ids that are in neither queue:ready nor queue:delayed. */
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> FIND_MISSING = new DefaultRedisScript<>("""
        local missing = {}
        for _, id in ipairs(ARGV) do
          if not redis.call('ZSCORE', KEYS[1], id) and not redis.call('ZSCORE', KEYS[2], id) then
            table.insert(missing, id)
          end
        end
        return missing
        """, List.class);

    private final StringRedisTemplate redis;

    public void enqueue(UUID id, int priority) {
        redis.opsForZSet().add(READY, id.toString(), readyScore(priority, System.currentTimeMillis()));
    }

    public void enqueueDelayed(UUID id, int priority, long runAtMillis) {
        redis.execute(ENQUEUE_DELAYED, List.of(DELAYED, DELAYED_PRIORITY),
            id.toString(), String.valueOf(priority), String.valueOf(runAtMillis));
    }

    /** Ready queue if {@code runAt} has passed, delayed queue otherwise. */
    public void enqueueAt(UUID id, int priority, Instant runAt) {
        long runAtMillis = runAt == null ? 0 : runAt.toEpochMilli();
        if (runAtMillis <= System.currentTimeMillis()) {
            enqueue(id, priority);
        } else {
            enqueueDelayed(id, priority, runAtMillis);
        }
    }

    /** Blocking pop (BZPOPMIN); returns empty after {@code timeout} so callers can check for shutdown. */
    public Optional<UUID> pop(Duration timeout) {
        var t = redis.opsForZSet().popMin(READY, timeout);
        return t == null || t.getValue() == null ? Optional.empty() : Optional.of(UUID.fromString(t.getValue()));
    }

    /** Moves up to {@code limit} due delayed jobs to the ready queue; returns how many moved. */
    public long moveDueJobs(long nowMillis, int limit) {
        Long moved = redis.execute(MOVE_DUE, List.of(DELAYED, READY, DELAYED_PRIORITY),
            String.valueOf(nowMillis), String.valueOf(limit), String.valueOf(DEFAULT_PRIORITY));
        return moved == null ? 0 : moved;
    }

    /** Batch membership check: ids present in neither the ready nor the delayed queue. */
    @SuppressWarnings("unchecked")
    public List<UUID> findMissing(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Object[] args = ids.stream().map(UUID::toString).toArray();
        List<Object> missing = redis.execute(FIND_MISSING, List.of(READY, DELAYED), args);
        List<UUID> result = new ArrayList<>();
        if (missing != null) {
            for (Object id : missing) {
                result.add(UUID.fromString(id.toString()));
            }
        }
        return result;
    }

    /** Removes a not-yet-started job from both queues (cancellation). */
    public void remove(UUID id) {
        String member = id.toString();
        redis.opsForZSet().remove(READY, member);
        redis.opsForZSet().remove(DELAYED, member);
        redis.opsForHash().delete(DELAYED_PRIORITY, member);
    }

    /**
     * Fast-path hint that a RUNNING job should be cancelled; workers poll it every second. The
     * authoritative flag is {@code jobs.cancel_requested}, which also blocks lease renewal.
     */
    public void requestCancel(UUID id, Duration ttl) {
        redis.opsForValue().set(CANCEL_PREFIX + id, "1", ttl);
    }

    public boolean isCancelRequested(UUID id) {
        return Boolean.TRUE.equals(redis.hasKey(CANCEL_PREFIX + id));
    }

    public void clearCancel(UUID id) {
        redis.delete(CANCEL_PREFIX + id);
    }

    public void toDead(UUID id) { redis.opsForList().leftPush(DEAD, id.toString()); }

    public void removeFromDead(UUID id) { redis.opsForList().remove(DEAD, 0, id.toString()); }

    static double readyScore(int priority, long millis) {
        return priority * PRIORITY_WEIGHT + millis;
    }
}
