package com.sanvi.jobscheduler.pulsecheck;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.sanvi.jobscheduler.pulsecheck.check.HttpChecker;
import com.sanvi.jobscheduler.pulsecheck.check.SiteChecker;
import com.sanvi.jobscheduler.pulsecheck.check.UrlSafetyValidator;
import com.sanvi.jobscheduler.pulsecheck.domain.Incident;
import com.sanvi.jobscheduler.pulsecheck.domain.Monitor;
import com.sanvi.jobscheduler.pulsecheck.domain.MonitorStatus;
import com.sanvi.jobscheduler.pulsecheck.repo.CheckResultRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.IncidentRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.MonitorRepository;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;
import com.sanvi.jobscheduler.worker.NonRetryableJobException;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;

class MonitorCheckServiceIntegrationTest extends AbstractIntegrationTest {

    private static final HttpChecker.Outcome UP = HttpChecker.Outcome.up(200, 42);
    private static final HttpChecker.Outcome DOWN_500 = HttpChecker.Outcome.down(500, 30, "HTTP 500");
    private static final HttpChecker.Outcome TIMEOUT = HttpChecker.Outcome.down(null, 10_000, "timed out after 10s");

    @Autowired JdbcTemplate jdbc;
    @Autowired MonitorRepository monitors;
    @Autowired CheckResultRepository results;
    @Autowired IncidentRepository incidents;
    @Autowired PlatformTransactionManager txManager;
    @Autowired HttpCheckJobHandler beanHandler;
    @Autowired com.sanvi.jobscheduler.pulsecheck.alert.AlertService alerts;

    private UUID userId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
        userId = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)", userId, "checks@example.com");
    }

    /** Hands out outcomes in order; counts calls. */
    private static final class ScriptedChecker implements SiteChecker {
        final Deque<HttpChecker.Outcome> outcomes = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();

        ScriptedChecker(HttpChecker.Outcome... script) {
            outcomes.addAll(List.of(script));
        }

        @Override
        public synchronized HttpChecker.Outcome check(String url) {
            calls.incrementAndGet();
            return outcomes.isEmpty() ? UP : outcomes.pop();
        }
    }

    private MonitorCheckService service(SiteChecker checker) {
        return new MonitorCheckService(monitors, results, incidents, checker, txManager, alerts);
    }

    private Monitor monitor(MonitorStatus status, boolean enabled) {
        Instant now = Instant.now();
        Monitor m = new Monitor();
        m.setId(UUID.randomUUID());
        m.setUserId(userId);
        m.setName("Site");
        m.setUrl("https://example.com/" + UUID.randomUUID());
        m.setIntervalSeconds(60);
        m.setEnabled(enabled);
        m.setCurrentStatus(status);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        return monitors.saveAndFlush(m);
    }

    private Monitor reloadMonitor(UUID id) {
        return monitors.findById(id).orElseThrow();
    }

    private List<Incident> incidentsOf(UUID monitorId) {
        return incidents.findAll().stream().filter(i -> i.getMonitorId().equals(monitorId)).toList();
    }

    // ------------------------------------------------------------------ status transitions

    @Test
    void firstSuccessMarksUnknownMonitorUpAndRecordsTheResult() {
        Monitor m = monitor(MonitorStatus.UNKNOWN, true);

        MonitorCheckService.Result result = service(new ScriptedChecker(UP)).runCheck(m.getId());

        assertThat(result.checked()).isTrue();
        Monitor after = reloadMonitor(m.getId());
        assertThat(after.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(after.getConsecutiveFailures()).isZero();
        assertThat(after.getLastCheckedAt()).isNotNull();
        var saved = results.findAll().getFirst();
        assertThat(saved.isUp()).isTrue();
        assertThat(saved.getStatusCode()).isEqualTo(200);
        assertThat(saved.getResponseMs()).isEqualTo(42);
    }

    @Test
    void oneFailureIsNotEnoughToGoDown() {
        Monitor m = monitor(MonitorStatus.UP, true);

        service(new ScriptedChecker(DOWN_500)).runCheck(m.getId());

        Monitor after = reloadMonitor(m.getId());
        assertThat(after.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(after.getConsecutiveFailures()).isEqualTo(1);
        assertThat(incidentsOf(m.getId())).isEmpty();
        assertThat(results.findAll().getFirst().getError()).isEqualTo("HTTP 500");
    }

    @Test
    void secondConsecutiveFailureGoesDownAndOpensAnIncidentStartingAtTheFirstFailure() {
        Monitor m = monitor(MonitorStatus.UP, true);
        MonitorCheckService service = service(new ScriptedChecker(DOWN_500, TIMEOUT));

        service.runCheck(m.getId());
        Instant firstFailure = results.findAll().getFirst().getCheckedAt();
        MonitorCheckService.Result second = service.runCheck(m.getId());

        assertThat(second.status()).isEqualTo(MonitorStatus.DOWN);
        assertThat(second.incidentOpened()).isTrue();
        Monitor after = reloadMonitor(m.getId());
        assertThat(after.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(after.getConsecutiveFailures()).isEqualTo(2);
        Incident incident = incidentsOf(m.getId()).getFirst();
        assertThat(incident.getResolvedAt()).isNull();
        assertThat(incident.getCause()).isEqualTo("timed out after 10s");
        assertThat(incident.getStartedAt()).isEqualTo(firstFailure);
    }

    @Test
    void unknownMonitorStaysUnknownAfterOneFailureThenGoesDown() {
        Monitor m = monitor(MonitorStatus.UNKNOWN, true);
        MonitorCheckService service = service(new ScriptedChecker(DOWN_500, DOWN_500));

        service.runCheck(m.getId());
        assertThat(reloadMonitor(m.getId()).getCurrentStatus()).isEqualTo(MonitorStatus.UNKNOWN);
        service.runCheck(m.getId());
        assertThat(reloadMonitor(m.getId()).getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
    }

    @Test
    void staysDownWithOneIncidentWhileFailuresContinue() {
        Monitor m = monitor(MonitorStatus.UP, true);
        MonitorCheckService service = service(new ScriptedChecker(DOWN_500, DOWN_500, DOWN_500, TIMEOUT, DOWN_500));

        for (int i = 0; i < 5; i++) {
            service.runCheck(m.getId());
        }

        assertThat(reloadMonitor(m.getId()).getConsecutiveFailures()).isEqualTo(5);
        assertThat(incidentsOf(m.getId())).hasSize(1);
    }

    @Test
    void oneSuccessRecoversAndResolvesTheIncident() {
        Monitor m = monitor(MonitorStatus.UP, true);
        MonitorCheckService service = service(new ScriptedChecker(DOWN_500, DOWN_500, UP));
        service.runCheck(m.getId());
        service.runCheck(m.getId());

        MonitorCheckService.Result recovery = service.runCheck(m.getId());

        assertThat(recovery.status()).isEqualTo(MonitorStatus.UP);
        assertThat(recovery.incidentResolved()).isTrue();
        Monitor after = reloadMonitor(m.getId());
        assertThat(after.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(after.getConsecutiveFailures()).isZero();
        Incident incident = incidentsOf(m.getId()).getFirst();
        assertThat(incident.getResolvedAt()).isNotNull().isAfterOrEqualTo(incident.getStartedAt());
    }

    @Test
    void aSecondOutageOpensASecondIncident() {
        Monitor m = monitor(MonitorStatus.UP, true);
        MonitorCheckService service = service(new ScriptedChecker(DOWN_500, DOWN_500, UP, DOWN_500, DOWN_500));
        for (int i = 0; i < 5; i++) {
            service.runCheck(m.getId());
        }

        List<Incident> all = incidentsOf(m.getId());
        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(i -> i.getResolvedAt() == null).hasSize(1);
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void retriedJobNeverCreatesASecondIncident() {
        Monitor m = monitor(MonitorStatus.UP, true);
        MonitorCheckService service = service(new ScriptedChecker(DOWN_500, DOWN_500, DOWN_500, DOWN_500));
        service.runCheck(m.getId());
        service.runCheck(m.getId()); // DOWN, incident opened

        // The worker committed the result but died before marking the job done: the scheduler re-runs it.
        MonitorCheckService.Result retry1 = service.runCheck(m.getId());
        MonitorCheckService.Result retry2 = service.runCheck(m.getId());

        assertThat(retry1.incidentOpened()).isFalse();
        assertThat(retry2.incidentOpened()).isFalse();
        assertThat(incidentsOf(m.getId())).hasSize(1);
    }

    @Test
    void openingIsIdempotentEvenIfStatusAndIncidentDisagree() {
        // E.g. the monitor row says UP but an incident is still open (a crash between statements is not
        // possible inside one transaction, but the database rule must hold regardless).
        Monitor m = monitor(MonitorStatus.UP, true);
        jdbc.update("insert into incidents (id, monitor_id, started_at, cause) values (?, ?, now(), 'earlier')",
            UUID.randomUUID(), m.getId());
        m.setConsecutiveFailures(1);
        monitors.saveAndFlush(m);

        service(new ScriptedChecker(DOWN_500)).runCheck(m.getId());

        assertThat(reloadMonitor(m.getId()).getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(incidentsOf(m.getId())).hasSize(1);
    }

    @Test
    void concurrentFailingChecksOpenExactlyOneIncident() throws Exception {
        Monitor m = monitor(MonitorStatus.UP, true);
        m.setConsecutiveFailures(1);
        monitors.saveAndFlush(m);
        MonitorCheckService service = service(url -> DOWN_500);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MonitorCheckService.Result>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<MonitorCheckService.Result> call = () -> {
                    start.await();
                    return service.runCheck(m.getId());
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            int opened = 0;
            for (Future<MonitorCheckService.Result> f : futures) {
                opened += f.get().incidentOpened() ? 1 : 0;
            }
            assertThat(opened).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(incidentsOf(m.getId())).hasSize(1);
        assertThat(reloadMonitor(m.getId()).getConsecutiveFailures()).isEqualTo(1 + threads);
        assertThat(results.countByMonitorId(m.getId())).isEqualTo(threads);
    }

    // ------------------------------------------------------------------ no-ops and job semantics

    @Test
    void deletedMonitorIsAQuietNoOp() throws Exception {
        UUID missing = UUID.randomUUID();
        ScriptedChecker checker = new ScriptedChecker(UP);

        new HttpCheckJobHandler(service(checker)).handle(missing.toString());

        assertThat(checker.calls.get()).isZero();
        assertThat(results.count()).isZero();
    }

    @Test
    void pausedMonitorIsNotChecked() {
        Monitor m = monitor(MonitorStatus.PAUSED, false);
        ScriptedChecker checker = new ScriptedChecker(DOWN_500);

        MonitorCheckService.Result result = service(checker).runCheck(m.getId());

        assertThat(result.checked()).isFalse();
        assertThat(checker.calls.get()).isZero();
        assertThat(reloadMonitor(m.getId()).getCurrentStatus()).isEqualTo(MonitorStatus.PAUSED);
    }

    @Test
    void resultIsDiscardedIfTheMonitorIsPausedOrDeletedDuringTheCheck() {
        Monitor paused = monitor(MonitorStatus.UP, true);
        MonitorCheckService.Result r1 = service(url -> {
            jdbc.update("update monitors set enabled = false, current_status = 'PAUSED' where id = ?", paused.getId());
            return DOWN_500;
        }).runCheck(paused.getId());

        Monitor deleted = monitor(MonitorStatus.UP, true);
        MonitorCheckService.Result r2 = service(url -> {
            jdbc.update("delete from monitors where id = ?", deleted.getId());
            return DOWN_500;
        }).runCheck(deleted.getId());

        assertThat(r1.checked()).isFalse();
        assertThat(r2.checked()).isFalse();
        assertThat(reloadMonitor(paused.getId()).getCurrentStatus()).isEqualTo(MonitorStatus.PAUSED);
        assertThat(results.count()).isZero();
    }

    @Test
    void aDownWebsiteIsASuccessfulJob() throws Exception {
        Monitor m = monitor(MonitorStatus.UP, true);
        HttpCheckJobHandler handler = new HttpCheckJobHandler(service(url -> TIMEOUT));

        handler.handle(m.getId().toString()); // must not throw: the check itself worked
        handler.handle(m.getId().toString());

        assertThat(reloadMonitor(m.getId()).getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
    }

    @Test
    void unexpectedErrorsFailTheJobSoItIsRetried() {
        Monitor m = monitor(MonitorStatus.UP, true);
        HttpCheckJobHandler handler = new HttpCheckJobHandler(service(url -> {
            throw new IllegalStateException("database unavailable");
        }));

        assertThatThrownBy(() -> handler.handle(m.getId().toString()))
            .isInstanceOf(IllegalStateException.class)
            .isNotInstanceOf(NonRetryableJobException.class);
    }

    @Test
    void invalidPayloadIsNotRetried() {
        assertThatThrownBy(() -> beanHandler.handle("not-a-uuid")).isInstanceOf(NonRetryableJobException.class);
        assertThat(beanHandler.type()).isEqualTo("HTTP_CHECK");
    }

    @Test
    void endToEndWithTheRealCheckerAgainstALocalServer() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start(InetAddress.getByName("127.0.0.1"), 0);
            server.enqueue(new MockResponse.Builder().code(500).build());
            server.enqueue(new MockResponse.Builder().code(503).build());
            server.enqueue(new MockResponse.Builder().code(200).build());
            Monitor m = monitor(MonitorStatus.UNKNOWN, true);
            m.setUrl(server.url("/health").toString());
            monitors.saveAndFlush(m);
            var config = new PulseCheckProperties.Checker(Duration.ofSeconds(5), 5, 65_536, "PulseCheck/1.0", true);
            HttpChecker real = new HttpChecker(new UrlSafetyValidator(InetAddress::getAllByName, true), config);
            HttpCheckJobHandler handler = new HttpCheckJobHandler(service(real));

            handler.handle(m.getId().toString());
            handler.handle(m.getId().toString());
            assertThat(reloadMonitor(m.getId()).getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
            assertThat(incidentsOf(m.getId()).getFirst().getCause()).isEqualTo("HTTP 503");

            handler.handle(m.getId().toString());
            assertThat(reloadMonitor(m.getId()).getCurrentStatus()).isEqualTo(MonitorStatus.UP);
            assertThat(incidentsOf(m.getId()).getFirst().getResolvedAt()).isNotNull();
            assertThat(results.countByMonitorId(m.getId())).isEqualTo(3);
        }
    }
}
