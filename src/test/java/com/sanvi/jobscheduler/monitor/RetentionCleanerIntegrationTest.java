package com.sanvi.jobscheduler.monitor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

class RetentionCleanerIntegrationTest extends AbstractIntegrationTest {

    @Autowired RetentionCleaner cleaner;
    @Autowired SchedulerProperties props;

    private Job finished(JobStatus status, Instant completedAt) {
        Job job = job(status, 1, 3);
        job.setCompletedAt(completedAt);
        return repo.save(job);
    }

    @Test
    void deletesOnlyOldSucceededAndCancelledJobsInBatches() {
        Instant old = Instant.now().minus(Duration.ofDays(props.retentionDays() + 1));
        Instant recent = Instant.now().minus(Duration.ofDays(1));
        int oldCount = props.retentionBatchSize() * 2 + 3;
        for (int i = 0; i < oldCount; i++) {
            finished(i % 2 == 0 ? JobStatus.SUCCEEDED : JobStatus.CANCELLED, old);
        }
        Job oldDead = finished(JobStatus.DEAD, old);
        Job recentSucceeded = finished(JobStatus.SUCCEEDED, recent);
        Job queued = repo.save(job(JobStatus.QUEUED, 0, 3));
        repo.flush();

        assertThat(cleaner.deleteExpired(Instant.now(), null)).isEqualTo(oldCount);

        assertThat(repo.count()).isEqualTo(3);
        assertThat(repo.existsById(oldDead.getId())).as("DEAD jobs are kept").isTrue();
        assertThat(repo.existsById(recentSucceeded.getId())).isTrue();
        assertThat(repo.existsById(queued.getId())).isTrue();
    }
}
