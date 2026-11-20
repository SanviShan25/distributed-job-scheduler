package com.sanvi.jobscheduler.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobRepository;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

@RestController @RequestMapping("/jobs")
@Profile("api") @RequiredArgsConstructor
public class JobController {
    private final JobRepository repo;
    private final JobQueue queue;
    private final MeterRegistry metrics;

    public record SubmitRequest(@NotBlank String type, String payload, Integer priority, Integer maxAttempts) {}

    public record WorkerJobCount(String workerId, long count) {}

    public record JobStats(Map<JobStatus, Long> byStatus, List<WorkerJobCount> succeededByWorker) {}

    @PostMapping
    public Job submit(@RequestBody @Valid SubmitRequest r) {
        Job j = new Job();
        j.setId(UUID.randomUUID());
        j.setType(r.type());
        j.setPayload(r.payload());
        Integer requestedPriority = r.priority();
        Integer requestedMaxAttempts = r.maxAttempts();
        j.setPriority(requestedPriority == null ? 5 : requestedPriority);
        j.setMaxAttempts(requestedMaxAttempts == null ? 3 : requestedMaxAttempts);
        j.setStatus(JobStatus.QUEUED);
        j.setRunAt(Instant.now());
        j.setCreatedAt(Instant.now());
        j.setUpdatedAt(Instant.now());
        repo.save(j);
        queue.enqueue(j.getId(), j.getPriority());
        metrics.counter("jobs_submitted_total").increment();
        return j;
    }

    @GetMapping("/{id}") public Job get(@PathVariable UUID id) { return repo.findById(id).orElseThrow(); }
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
        Job job = repo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
        if (job.getStatus() != JobStatus.DEAD) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only DEAD jobs can be requeued");
        }

        job.setAttempts(0);
        job.setStatus(JobStatus.QUEUED);
        job.setUpdatedAt(Instant.now());
        repo.save(job);
        queue.enqueue(job.getId(), job.getPriority());
        return job;
    }
}