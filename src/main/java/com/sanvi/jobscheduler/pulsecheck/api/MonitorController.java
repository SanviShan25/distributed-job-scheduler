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

import com.sanvi.jobscheduler.api.ApiProblems;
import com.sanvi.jobscheduler.api.PageResponse;
import com.sanvi.jobscheduler.pulsecheck.alert.AlertRecipientService;
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
    private final AlertRecipientService recipients;

    public record CreateMonitorRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 2048) String url,
        /* One of 60, 300, 900, 3600. */
        @NotNull Integer intervalSeconds,
        /* Email alerts to the account's own address (default true). */
        Boolean notifyOwner,
        /* Replaced by /api/monitors/{id}/recipients; rejected if present so it is not silently ignored. */
        Object alertEmails) {}

    /** All fields optional. */
    public record UpdateMonitorRequest(
        @Size(min = 1, max = 200) String name,
        @Size(min = 1, max = 2048) String url,
        Integer intervalSeconds,
        Boolean notifyOwner,
        Object alertEmails) {}

    public record AddRecipientRequest(@NotBlank @Size(max = 320) String email) {}

    public record RecipientView(UUID id, String email, AlertRecipientService.Status status, Instant createdAt,
        Instant confirmedAt, Instant unsubscribedAt) {
        static RecipientView of(AlertRecipientService.Recipient r) {
            return new RecipientView(r.id(), r.email(), r.status(), r.createdAt(), r.confirmedAt(), r.unsubscribedAt());
        }
    }

    public record UptimeView(Double last24h, Double last7d, Double last30d, Integer avgResponseMs24h, Long checks24h) {
        static UptimeView of(CheckResultRepository.UptimeStats s) {
            return new UptimeView(s.getUptime24h(), s.getUptime7d(), s.getUptime30d(), s.getAvgResponseMs24h(),
                s.getChecks24h());
        }

        /** The list's summary: 24 h figures only (7 d / 30 d null); no checks in 24 h gives nulls and 0. */
        static UptimeView of24h(CheckResultRepository.DailyStats s) {
            return s == null
                ? new UptimeView(null, null, null, null, 0L)
                : new UptimeView(s.getUptime24h(), null, null, s.getAvgResponseMs24h(), s.getChecks24h());
        }
    }

    public record MonitorView(UUID id, String name, String url, int intervalSeconds, boolean enabled,
        MonitorStatus status, int consecutiveFailures, Instant lastCheckedAt, boolean notifyOwner,
        List<RecipientView> recipients, Instant createdAt, Instant updatedAt, UptimeView uptime) {

        static MonitorView of(Monitor m, List<AlertRecipientService.Recipient> recipients, UptimeView uptime) {
            return new MonitorView(m.getId(), m.getName(), m.getUrl(), m.getIntervalSeconds(), m.isEnabled(),
                m.getCurrentStatus(), m.getConsecutiveFailures(), m.getLastCheckedAt(), m.isNotifyOwner(),
                recipients.stream().map(RecipientView::of).toList(), m.getCreatedAt(), m.getUpdatedAt(), uptime);
        }
    }

    private MonitorView view(Monitor m, UptimeView uptime) {
        return MonitorView.of(m, recipients.list(m.getId()), uptime);
    }

    private static void rejectAlertEmails(Object alertEmails) {
        if (alertEmails != null) {
            throw ApiProblems.badRequest("alertEmails is no longer supported: add recipients with "
                + "POST /api/monitors/{id}/recipients (each one must confirm by email)");
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
        rejectAlertEmails(request.alertEmails());
        Monitor created = monitors.create(currentUser.id(), new MonitorService.MonitorChanges(
            request.name(), request.url(), request.intervalSeconds(), request.notifyOwner()));
        return ResponseEntity.created(URI.create("/api/monitors/" + created.getId()))
            .body(view(created, null));
    }

    /** Each monitor includes its last-24 h uptime and average response time ({@code last7d}/{@code last30d} null). */
    @GetMapping
    public List<MonitorView> list() {
        List<Monitor> mine = monitors.list(currentUser.id());
        List<UUID> ids = mine.stream().map(Monitor::getId).toList();
        var byMonitor = recipients.listFor(ids);
        var daily = monitors.last24h(ids);
        return mine.stream().map(m -> MonitorView.of(m, byMonitor.getOrDefault(m.getId(), List.of()),
            UptimeView.of24h(daily.get(m.getId())))).toList();
    }

    /** Includes the uptime summary (24 h / 7 d / 30 d and average response time). */
    @GetMapping("/{id}")
    public MonitorView get(@PathVariable UUID id) {
        Monitor monitor = monitors.get(currentUser.id(), id);
        return view(monitor, UptimeView.of(monitors.uptime(id)));
    }

    @PatchMapping("/{id}")
    public MonitorView update(@PathVariable UUID id, @RequestBody @Valid UpdateMonitorRequest request) {
        rejectAlertEmails(request.alertEmails());
        Monitor updated = monitors.update(currentUser.id(), id, new MonitorService.MonitorChanges(
            request.name(), request.url(), request.intervalSeconds(), request.notifyOwner()));
        return view(updated, null);
    }

    @PostMapping("/{id}/pause")
    public MonitorView pause(@PathVariable UUID id) {
        return view(monitors.pause(currentUser.id(), id), null);
    }

    @PostMapping("/{id}/resume")
    public MonitorView resume(@PathVariable UUID id) {
        return view(monitors.resume(currentUser.id(), id), null);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        monitors.delete(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ extra alert recipients

    /** Adds a PENDING recipient and emails them a confirmation link; they get alerts once they confirm. */
    @PostMapping("/{id}/recipients")
    public ResponseEntity<RecipientView> addRecipient(@PathVariable UUID id,
            @RequestBody @Valid AddRecipientRequest request) {
        RecipientView added = RecipientView.of(recipients.add(currentUser.id(), id, request.email()));
        return ResponseEntity.created(URI.create("/api/monitors/" + id + "/recipients/" + added.id())).body(added);
    }

    @PostMapping("/{id}/recipients/{recipientId}/resend")
    public RecipientView resendConfirmation(@PathVariable UUID id, @PathVariable UUID recipientId) {
        return RecipientView.of(recipients.resendConfirmation(currentUser.id(), id, recipientId));
    }

    @DeleteMapping("/{id}/recipients/{recipientId}")
    public ResponseEntity<Void> removeRecipient(@PathVariable UUID id, @PathVariable UUID recipientId) {
        recipients.remove(currentUser.id(), id, recipientId);
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

    public record ResponseTimeBucketView(Instant start, long checks, long failures, Integer avgResponseMs,
        Integer maxResponseMs) {
        static ResponseTimeBucketView of(CheckResultRepository.ResponseTimeBucket b) {
            return new ResponseTimeBucketView(Instant.ofEpochSecond(b.getStartEpochSecond()), b.getChecks(),
                b.getFailures(), b.getAvgResponseMs(), b.getMaxResponseMs());
        }
    }

    public record ResponseTimesView(String range, long bucketSeconds, List<ResponseTimeBucketView> buckets) {}

    /**
     * Response times for a chart, bucketed in SQL: {@code range=24h} (default) gives 5-minute buckets,
     * {@code range=7d} 1-hour buckets. Every bucket is listed oldest first; empty ones have 0 checks and a null
     * average. Averages are of successful checks only.
     */
    @GetMapping("/{id}/response-times")
    public ResponseTimesView responseTimes(@PathVariable UUID id,
            @RequestParam(defaultValue = "24h") String range) {
        MonitorService.ResponseTimeRange r = MonitorService.ResponseTimeRange.of(range);
        var buckets = monitors.responseTimes(currentUser.id(), id, r).stream().map(ResponseTimeBucketView::of).toList();
        return new ResponseTimesView(r.key, r.bucket.toSeconds(), buckets);
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
