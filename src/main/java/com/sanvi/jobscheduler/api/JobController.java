package com.sanvi.jobscheduler.api;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.repo.JobRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController @RequestMapping("/jobs")
@Profile("api") @RequiredArgsConstructor
public class JobController {
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final JobService jobs;
    private final JobRepository repo;

    public record SubmitRequest(
        @NotBlank @Size(max = 100) String type,
        @Size(max = JobService.MAX_PAYLOAD_BYTES, message = "payload must be at most 64 KB") String payload,
        @Min(1) @Max(10) Integer priority,
        @Min(1) @Max(20) Integer maxAttempts,
        @Min(1) @Max(86_400) Integer timeoutSeconds,
        /* ISO-8601 instant, e.g. 2026-01-01T10:00:00Z. Mutually exclusive with delaySeconds. */
        Instant runAt,
        @Min(0) @Max(31_536_000) Long delaySeconds) {}

    public record WorkerJobCount(String workerId, long count) {}

    public record JobStats(Map<JobStatus, Long> byStatus, List<WorkerJobCount> succeededByWorker) {}

    /** 201 for a new job; 200 with the original job when the Idempotency-Key was seen before. */
    @PostMapping
    public ResponseEntity<Job> submit(@RequestBody @Valid SubmitRequest r,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        JobService.Submission submission = jobs.submit(new JobService.NewJob(r.type(), r.payload(), r.priority(),
            r.maxAttempts(), r.timeoutSeconds(), r.runAt(), r.delaySeconds()), idempotencyKey);
        if (!submission.created()) {
            return ResponseEntity.ok(submission.job());
        }
        return ResponseEntity.created(URI.create("/jobs/" + submission.job().getId())).body(submission.job());
    }

    @GetMapping("/{id}")
    public Job get(@PathVariable UUID id) {
        return jobs.get(id);
    }

    @GetMapping
    public PageResponse<Job> list(
            @RequestParam(required = false) List<JobStatus> status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String workerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var filter = new JobService.JobFilter(status, type, workerId, createdFrom, createdTo);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        return PageResponse.of(jobs.list(filter, pageable));
    }

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
        return jobs.requeue(id);
    }

    /** 200 when cancelled immediately; 202 when a RUNNING job was asked to stop. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Job> cancel(@PathVariable UUID id) {
        JobService.Cancellation result = jobs.cancel(id);
        HttpStatus status = result.outcome() == JobService.CancelOutcome.CANCELLED ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(result.job());
    }
}
