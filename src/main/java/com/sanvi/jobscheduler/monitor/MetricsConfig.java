package com.sanvi.jobscheduler.monitor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sanvi.jobscheduler.queue.JobQueue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;

@Configuration
@Profile("api")
public class MetricsConfig {

    @Bean
    MeterBinder schedulerGauges(StringRedisTemplate redis) {
        return registry -> {
            registerQueueGauge(registry, redis, "ready", JobQueue.READY);
            registerQueueGauge(registry, redis, "delayed", JobQueue.DELAYED);
            Gauge.builder("queue_depth", redis, template -> valueOrZero(template.opsForList().size(JobQueue.DEAD)))
                .tag("queue", "dead")
                .description("Number of jobs in the dead-letter queue")
                .register(registry);
            Gauge.builder("workers_alive", redis, MetricsConfig::countAliveWorkers)
                .description("Number of workers with a live heartbeat key")
                .register(registry);
        };
    }

    private static void registerQueueGauge(io.micrometer.core.instrument.MeterRegistry registry,
            StringRedisTemplate redis, String queueName, String redisKey) {
        Gauge.builder("queue_depth", redis, template -> valueOrZero(template.opsForZSet().zCard(redisKey)))
            .tag("queue", queueName)
            .description("Number of jobs in the Redis queue")
            .register(registry);
    }

    private static double countAliveWorkers(StringRedisTemplate redis) {
        Long count = redis.execute((RedisCallback<Long>) connection -> {
            long workerCount = 0;
            try (Cursor<byte[]> cursor = connection.keyCommands()
                    .scan(ScanOptions.scanOptions().match("worker:*").count(1000).build())) {
                while (cursor.hasNext()) {
                    cursor.next();
                    workerCount++;
                }
            }
            return workerCount;
        });
        return valueOrZero(count);
    }

    private static double valueOrZero(Number value) {
        return value == null ? 0 : value.doubleValue();
    }
}
