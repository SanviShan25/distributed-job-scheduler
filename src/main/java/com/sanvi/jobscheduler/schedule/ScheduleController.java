package com.sanvi.jobscheduler.schedule;

import java.net.URI;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sanvi.jobscheduler.api.JobService;
import com.sanvi.jobscheduler.api.PageResponse;
import com.sanvi.jobscheduler.domain.JobSchedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/schedules")
@Profile("api")
@RequiredArgsConstructor
public class ScheduleController {
    private final ScheduleService schedules;

    public record CreateScheduleRequest(
        @NotBlank @Size(max = 200) String name,
        /* 6-field Spring cron (sec min hour dom mon dow), 5-field Unix cron, or a macro like @hourly. */
        @NotBlank @Size(max = 120) String cron,
        @Size(max = 64) String timezone,
        @NotBlank @Size(max = 100) String type,
        @Size(max = JobService.MAX_PAYLOAD_BYTES) String payload,
        @Min(1) @Max(10) Integer priority,
        @Min(1) @Max(20) Integer maxAttempts,
        @Min(1) @Max(86_400) Integer timeoutSeconds,
        Boolean enabled) {

        ScheduleService.ScheduleSpec toSpec() {
            return new ScheduleService.ScheduleSpec(name, cron, timezone, type, payload, priority, maxAttempts,
                timeoutSeconds, enabled);
        }
    }

    /** All fields optional; omitted fields are unchanged. */
    public record UpdateScheduleRequest(
        @Size(min = 1, max = 200) String name,
        @Size(min = 1, max = 120) String cron,
        @Size(max = 64) String timezone,
        @Size(min = 1, max = 100) String type,
        @Size(max = JobService.MAX_PAYLOAD_BYTES) String payload,
        @Min(1) @Max(10) Integer priority,
        @Min(1) @Max(20) Integer maxAttempts,
        @Min(1) @Max(86_400) Integer timeoutSeconds,
        Boolean enabled) {

        ScheduleService.ScheduleSpec toSpec() {
            return new ScheduleService.ScheduleSpec(name, cron, timezone, type, payload, priority, maxAttempts,
                timeoutSeconds, enabled);
        }
    }

    @PostMapping
    public ResponseEntity<JobSchedule> create(@RequestBody @Valid CreateScheduleRequest request) {
        JobSchedule created = schedules.create(request.toSpec());
        return ResponseEntity.created(URI.create("/schedules/" + created.getId())).body(created);
    }

    @GetMapping
    public PageResponse<JobSchedule> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(schedules.list(PageRequest.of(page, size, Sort.by("name"))));
    }

    @GetMapping("/{id}")
    public JobSchedule get(@PathVariable UUID id) {
        return schedules.get(id);
    }

    @PatchMapping("/{id}")
    public JobSchedule update(@PathVariable UUID id, @RequestBody @Valid UpdateScheduleRequest request) {
        return schedules.update(id, request.toSpec());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        schedules.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pause")
    public JobSchedule pause(@PathVariable UUID id) {
        return schedules.pause(id);
    }

    @PostMapping("/{id}/resume")
    public JobSchedule resume(@PathVariable UUID id) {
        return schedules.resume(id);
    }
}
