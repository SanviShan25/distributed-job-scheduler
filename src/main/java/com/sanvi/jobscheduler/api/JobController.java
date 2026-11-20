package com.sanvi.jobscheduler.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobRepository;
import com.sanvi.jobscheduler.worker.JobHandlerRegistry;
import com.sanvi.jobscheduler.worker.WorkerProperties;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController @RequestMapping("/jobs")
@Profile("api") @RequiredArgsConstructor
public class JobController {
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final JobRepository repo;
    private final JobQueue queue;
    private final MeterRegistry metrics;
    private final JobHandlerRegistry handlers;
    private final WorkerProperties workerProperties;

    public record SubmitRequest(
        @NotBlank @Size(max = 100) String type,
        @Size(max = MAX_PAYLOAD_BYTES, message = "payload must be at most 64 KB") String payload,
        @Min(1) @Max(10) Integer priority,
        @Min(1) @Max(20) Integer maxAttempts,
        @Min(1) @Max(86_400) Integer timeoutSeconds) {}

    public record WorkerJobCount(String workerId, long count) {}

    public record JobStats(Map<JobStatus, Long> byStatus, List<WorkerJobCount> succeededByWorker) {}

    @PostMapping
    public Job submit(@RequestBody @Valid SubmitRequest r) {
        validate(r);
        Instant now = Instant.now();
        Job j = new Job();
        j.setId(UUID.randomUUID());
        j.setType(r.type());
        j.setPayload(r.payload());
        j.setPriority(r.priority() == null ? 5 : r.priority());
        j.setMaxAttempts(r.maxAttempts() == null ? 3 : r.maxAttempts());
        j.setTimeoutSeconds(r.timeoutSeconds() == null ? workerProperties.defaultTimeoutSeconds() : r.timeoutSeconds());
        j.setStatus(JobStatus.QUEUED);
        j.setRunAt(now);
        j.setCreatedAt(now);
        j.setUpdatedAt(now);
        repo.save(j);
        queue.enqueue(j.getId(), j.getPriority());
        metrics.counter("jobs_submitted_total").increment();
        return j;
    }

    private void validate(SubmitRequest r) {
        if (!handlers.supports(r.type())) {
            throw ApiProblems.badRequest("Unknown job type '" + r.type() + "'. Supported types: " + handlers.types());
        }
        if (r.payload() != null && r.payload().getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw ApiProblems.badRequest("payload must be at most 64 KB (UTF-8 encoded)");
        }
    }

    @GetMapping("/{id}")
    public Job get(@PathVariable UUID id) {
        return repo.findById(id).orElseThrow(() -> ApiProblems.notFound("Job " + id + " not found"));
    }

    @GetMapping public List<Job> byStatus(@RequestParam JobStatus status) { return repo.findByStatus(status); }

    @GetMapping("/dead")
    public List<Job> deadJobs() {
        return repo.findByStatus(JobStatus.DEAD);
    }

    @GetMapping("/stats")
    public JobStats stats() {
        Map<JobStatus, Long> byStatus = new LinkedHashMap<>();
        for (JobRepository.StatusCount result : repo.countJobsByStatus()) {
            byStatus.put(result.getStatus(), result.getCount());
        }

        List<WorkerJobCount> succeededByWorker = repo.countJobsByWorkerForStatus(JobStatus.SUCCEEDED).stream()
            .map(result -> new WorkerJobCount(result.getWorkerId(), result.getCount()))
            .toList();
        return new JobStats(byStatus, succeededByWorker);
    }

    @PostMapping("/{id}/requeue")
    public Job requeue(@PathVariable UUID id) {
        if (repo.requeueDead(id, Instant.now()) == 0) {
            Job existing = repo.findById(id).orElseThrow(() -> ApiProblems.notFound("Job " + id + " not found"));
            throw ApiProblems.conflict("Only DEAD jobs can be requeued (job is " + existing.getStatus() + ")");
        }
        Job job = repo.findById(id).orElseThrow();
        queue.removeFromDead(id);
        queue.enqueue(job.getId(), job.getPriority());
        return job;
    }
}
