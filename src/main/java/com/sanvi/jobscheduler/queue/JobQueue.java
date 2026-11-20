package com.sanvi.jobscheduler.queue;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.repo.JobRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JobQueue {
    private final StringRedisTemplate redis;
    public static final String READY = "queue:ready", DELAYED = "queue:delayed", DEAD = "queue:dead";

    // lower score = popped first; priority 1 is highest
    public void enqueue(UUID id, int priority) {
        double score = priority * 1e13 + System.currentTimeMillis();
        redis.opsForZSet().add(READY, id.toString(), score);
    }

    public void enqueueDelayed(UUID id, long runAtMillis) {
        redis.opsForZSet().add(DELAYED, id.toString(), runAtMillis);
    }

    public Optional<UUID> pop() {
        var t = redis.opsForZSet().popMin(READY);
        return t == null ? Optional.empty() : Optional.of(UUID.fromString(t.getValue()));
    }

    public void moveDueJobs(JobRepository repo) {
        var due = redis.opsForZSet().rangeByScore(DELAYED, 0, System.currentTimeMillis());
        if (due == null) return;
        for (String id : due) {
            if (redis.opsForZSet().remove(DELAYED, id) > 0) {   // only one mover wins
                repo.findById(UUID.fromString(id))
                    .ifPresent(j -> enqueue(j.getId(), j.getPriority()));
            }
        }
    }

    public void toDead(UUID id) { redis.opsForList().leftPush(DEAD, id.toString()); }
}