package com.sanvi.jobscheduler.monitor;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code scheduler.*} settings for the background maintenance tasks. */
@ConfigurationProperties(prefix = "scheduler")
public record SchedulerProperties(
    @DefaultValue Sweeper sweeper,
    @DefaultValue Mover mover) {

    /**
     * @param staleAfter     only consider jobs untouched for this long (skips jobs mid-submit)
     * @param recheckDelay   wait between the two membership checks (covers a worker between pop and claim)
     * @param pageSize       ids fetched / checked per round trip
     * @param maxCandidates  cap on ids held in memory per sweep; the rest are picked up next sweep
     * @param lockTtl        TTL of each sweep lock; renewed after every page
     */
    public record Sweeper(
        @DefaultValue("60s") Duration staleAfter,
        @DefaultValue("2s") Duration recheckDelay,
        @DefaultValue("500") int pageSize,
        @DefaultValue("50000") int maxCandidates,
        @DefaultValue("60s") Duration lockTtl) {
    }

    /** @param batchSize members moved per Lua call */
    public record Mover(
        @DefaultValue("1000") int batchSize,
        @DefaultValue("5s") Duration lockTtl) {
    }
}
