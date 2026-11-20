package com.sanvi.jobscheduler.schedule;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobSchedule;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.repo.JobScheduleRepository;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

class ScheduleIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JobScheduleRepository schedules;
    @Autowired ScheduleRunner runner;

    @BeforeEach
    void clearSchedules() {
        schedules.deleteAllInBatch();
    }

    private UUID create(String body) throws Exception {
        String response = mvc.perform(post("/schedules").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asString());
    }

    private void makeDue(UUID id, Instant nextRunAt) {
        JobSchedule s = schedules.findById(id).orElseThrow();
        s.setNextRunAt(nextRunAt);
        schedules.saveAndFlush(s);
    }

    @Test
    void crudPauseAndResume() throws Exception {
        UUID id = create("{\"name\":\"nightly\",\"cron\":\"0 0 3 * * *\",\"timezone\":\"Asia/Kolkata\","
            + "\"type\":\"EMAIL\",\"payload\":\"report\",\"priority\":2}");

        mvc.perform(get("/schedules/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("nightly"))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.nextRunAt").isNotEmpty());
        mvc.perform(get("/schedules")).andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(post("/schedules/{id}/pause", id))
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.nextRunAt").doesNotExist());
        mvc.perform(post("/schedules/{id}/resume", id))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.nextRunAt").isNotEmpty());

        mvc.perform(patch("/schedules/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cron\":\"*/5 * * * *\",\"priority\":7}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cron").value("0 */5 * * * *"))
            .andExpect(jsonPath("$.priority").value(7))
            .andExpect(jsonPath("$.payload").value("report"));
        JobSchedule patched = schedules.findById(id).orElseThrow();
        assertThat(patched.getNextRunAt()).isBetween(Instant.now(), Instant.now().plus(Duration.ofMinutes(5)));

        mvc.perform(delete("/schedules/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/schedules/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void invalidSchedulesAreRejected() throws Exception {
        String[] bad = {
            "{\"name\":\"x\",\"cron\":\"not cron\",\"type\":\"EMAIL\"}",
            "{\"name\":\"x\",\"cron\":\"0 * * * * *\",\"timezone\":\"Mars/Olympus\",\"type\":\"EMAIL\"}",
            "{\"name\":\"x\",\"cron\":\"0 * * * * *\",\"type\":\"SMS\"}",
            "{\"name\":\"x\",\"cron\":\"0 * * * * *\",\"type\":\"EMAIL\",\"priority\":99}",
            "{\"cron\":\"0 * * * * *\",\"type\":\"EMAIL\"}"};
        for (String body : bad) {
            mvc.perform(post("/schedules").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        create("{\"name\":\"dup\",\"cron\":\"@hourly\",\"type\":\"EMAIL\"}");
        mvc.perform(post("/schedules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dup\",\"cron\":\"@hourly\",\"type\":\"EMAIL\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void dueScheduleCreatesOneQueuedRunAndAdvances() throws Exception {
        UUID id = create("{\"name\":\"minutely\",\"cron\":\"0 * * * * *\",\"type\":\"EMAIL\",\"priority\":3}");
        Instant fireTime = Instant.now().minusSeconds(5);
        makeDue(id, fireTime);

        assertThat(runner.fireDue(Instant.now())).isEqualTo(1);

        List<Job> runs = repo.findAll();
        assertThat(runs).hasSize(1);
        Job run = runs.getFirst();
        assertThat(run.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(run.getCronScheduleId()).isEqualTo(id);
        assertThat(run.getPriority()).isEqualTo(3);
        assertThat(run.getIdempotencyKey()).isEqualTo(ScheduleRunner.idempotencyKey(id, schedules.findById(id)
            .orElseThrow().getLastRunAt()));
        assertThat(inReadyQueue(run.getId())).isTrue();

        JobSchedule after = schedules.findById(id).orElseThrow();
        assertThat(after.getNextRunAt()).isAfter(Instant.now());
        assertThat(runner.fireDue(Instant.now())).as("not due any more").isZero();
    }

    @Test
    void sameFireTimeIsNeverCreatedTwice() throws Exception {
        UUID id = create("{\"name\":\"replayed\",\"cron\":\"0 * * * * *\",\"type\":\"EMAIL\"}");
        Instant fireTime = Instant.parse("2026-05-01T10:00:00Z");
        makeDue(id, fireTime);
        runner.fireDue(Instant.now());
        makeDue(id, fireTime); // e.g. a stale instance replays the same fire

        assertThat(runner.fireDue(Instant.now())).isZero();
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void misfiresRunOnceAndSkipMissedRuns() throws Exception {
        UUID id = create("{\"name\":\"every-minute\",\"cron\":\"0 * * * * *\",\"type\":\"EMAIL\"}");
        makeDue(id, Instant.now().minus(Duration.ofDays(1))); // ~1440 missed fire times

        assertThat(runner.fireDue(Instant.now())).isEqualTo(1);
        assertThat(repo.count()).isEqualTo(1);
        assertThat(schedules.findById(id).orElseThrow().getNextRunAt()).isAfter(Instant.now());
    }

    @Test
    void pausedSchedulesDoNotFire() throws Exception {
        UUID id = create("{\"name\":\"paused\",\"cron\":\"0 * * * * *\",\"type\":\"EMAIL\",\"enabled\":false}");
        makeDue(id, Instant.now().minusSeconds(5));

        assertThat(runner.fireDue(Instant.now())).isZero();
    }

    @Test
    void concurrentRunnersFireEachScheduleOnce() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID id = create("{\"name\":\"s" + i + "\",\"cron\":\"0 * * * * *\",\"type\":\"EMAIL\"}");
            makeDue(id, Instant.now().minusSeconds(5));
        }
        int runners = 4;
        ExecutorService pool = Executors.newFixedThreadPool(runners);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < runners; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return runner.fireDue(Instant.now());
                }));
            }
            start.countDown();
            int created = 0;
            for (Future<Integer> f : results) {
                created += f.get();
            }
            assertThat(created).isEqualTo(20);
            assertThat(repo.count()).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deletingScheduleKeepsItsJobs() throws Exception {
        UUID id = create("{\"name\":\"temp\",\"cron\":\"0 * * * * *\",\"type\":\"EMAIL\"}");
        makeDue(id, Instant.now().minusSeconds(5));
        runner.fireDue(Instant.now());

        mvc.perform(delete("/schedules/{id}", id)).andExpect(status().isNoContent());

        Job run = repo.findAll().getFirst();
        assertThat(run.getCronScheduleId()).isNull();
    }
}
