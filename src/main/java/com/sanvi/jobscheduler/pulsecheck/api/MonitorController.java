package com.sanvi.jobscheduler.pulsecheck.api;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
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

import com.sanvi.jobscheduler.api.PageResponse;
import com.sanvi.jobscheduler.pulsecheck.domain.CheckResult;
import com.sanvi.jobscheduler.pulsecheck.domain.Incident;
import com.sanvi.jobscheduler.pulsecheck.domain.Monitor;
import com.sanvi.jobscheduler.pulsecheck.domain.MonitorStatus;
import com.sanvi.jobscheduler.pulsecheck.repo.CheckResultRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * PulseCheck monitors. TEMPORARY auth: the scheduler's {@code X-API-Key}, acting as the dev user
 * (see {@link CurrentUser}).
 */
@RestController
@RequestMapping("/api/monitors")
@Profile("api")
@RequiredArgsConstructor
public class MonitorController {
    private final MonitorService monitors;
    private final CurrentUser currentUser;

    public record CreateMonitorRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 2048) String url,
        /* One of 60, 300, 900, 3600. */
        @NotNull Integer intervalSeconds,
        @Size(max = MonitorService.MAX_ALERT_EMAILS) List<String> alertEmails) {}

    /** All fields optional. */
    public record UpdateMonitorRequest(
        @Size(min = 1, max = 200) String name,
        @Size(min = 1, max = 2048) String url,
        Integer intervalSeconds,
        @Size(max = MonitorService.MAX_ALERT_EMAILS) List<String> alertEmails) {}

    public record UptimeView(Double last24h, Double last7d, Double last30d, Integer avgResponseMs24h, Long checks24h) {
        static UptimeView of(CheckResultRepository.UptimeStats s) {
            return new UptimeView(s.getUptime24h(), s.getUptime7d(), s.getUptime30d(), s.getAvgResponseMs24h(),
                s.getChecks24h());
        }
    }

    public record MonitorView(UUID id, String name, String url, int intervalSeconds, boolean enabled,
        MonitorStatus status, int consecutiveFailures, Instant lastCheckedAt, List<String> alertEmails,
        Instant createdAt, Instant updatedAt, UptimeView uptime) {

        static MonitorView of(Monitor m, UptimeView uptime) {
            return new MonitorView(m.getId(), m.getName(), m.getUrl(), m.getIntervalSeconds(), m.isEnabled(),
                m.getCurrentStatus(), m.getConsecutiveFailures(), m.getLastCheckedAt(),
                MonitorService.splitEmails(m.getAlertEmails()), m.getCreatedAt(), m.getUpdatedAt(), uptime);
        }
    }

    public record CheckView(Instant checkedAt, boolean up, Integer statusCode, Integer responseMs, String error) {
        static CheckView of(CheckResult r) {
            return new CheckView(r.getCheckedAt(), r.isUp(), r.getStatusCode(), r.getResponseMs(), r.getError());
        }
    }

    public record IncidentView(UUID id, Instant startedAt, Instant resolvedAt, boolean ongoing, long durationSeconds,
        String cause) {
        static IncidentView of(Incident i) {
            Instant end = i.getResolvedAt() == null ? Instant.now() : i.getResolvedAt();
            return new IncidentView(i.getId(), i.getStartedAt(), i.getResolvedAt(), i.getResolvedAt() == null,
                Math.max(0, Duration.between(i.getStartedAt(), end).toSeconds()), i.getCause());
        }
    }

    @PostMapping
    public ResponseEntity<MonitorView> create(@RequestBody @Valid CreateMonitorRequest request) {
        Monitor created = monitors.create(currentUser.id(), new MonitorService.MonitorChanges(
            request.name(), request.url(), request.intervalSeconds(), request.alertEmails()));
        return ResponseEntity.created(URI.create("/api/monitors/" + created.getId()))
            .body(MonitorView.of(created, null));
    }

    @GetMapping
    public List<MonitorView> list() {
        return monitors.list(currentUser.id()).stream().map(m -> MonitorView.of(m, null)).toList();
    }

    /** Includes the uptime summary (24 h / 7 d / 30 d and average response time). */
    @GetMapping("/{id}")
    public MonitorView get(@PathVariable UUID id) {
        Monitor monitor = monitors.get(currentUser.id(), id);
        return MonitorView.of(monitor, UptimeView.of(monitors.uptime(id)));
    }

    @PatchMapping("/{id}")
    public MonitorView update(@PathVariable UUID id, @RequestBody @Valid UpdateMonitorRequest request) {
        Monitor updated = monitors.update(currentUser.id(), id, new MonitorService.MonitorChanges(
            request.name(), request.url(), request.intervalSeconds(), request.alertEmails()));
        return MonitorView.of(updated, null);
    }

    @PostMapping("/{id}/pause")
    public MonitorView pause(@PathVariable UUID id) {
        return MonitorView.of(monitors.pause(currentUser.id(), id), null);
    }

    @PostMapping("/{id}/resume")
    public MonitorView resume(@PathVariable UUID id) {
        return MonitorView.of(monitors.resume(currentUser.id(), id), null);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        monitors.delete(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    /** Check history, newest first. {@code from}/{@code to} are ISO-8601 instants (to is exclusive). */
    @GetMapping("/{id}/checks")
    public PageResponse<CheckView> checks(@PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("checkedAt"), Sort.Order.desc("id")));
        return PageResponse.of(monitors.checks(currentUser.id(), id, from, to, pageable).map(CheckView::of));
    }

    /** Incidents, newest first. */
    @GetMapping("/{id}/incidents")
    public PageResponse<IncidentView> incidents(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("startedAt")));
        return PageResponse.of(monitors.incidents(currentUser.id(), id, pageable).map(IncidentView::of));
    }
}
