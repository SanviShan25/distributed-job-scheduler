package com.sanvi.jobscheduler.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JobFeaturesIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JobQueue queue;
    @Autowired ObjectMapper json;

    private JsonNode submit(String body, String idempotencyKey, int expectedStatus) throws Exception {
        var request = post("/jobs").contentType(MediaType.APPLICATION_JSON).content(body);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        MvcResult result = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------ delayed jobs

    @Test
    void delaySecondsCreatesScheduledJobInDelayedQueue() throws Exception {
        JsonNode job = submit("{\"type\":\"EMAIL\",\"delaySeconds\":120}", null, 201);
        UUID id = UUID.fromString(job.get("id").asString());

        assertThat(job.get("status").asString()).isEqualTo("SCHEDULED");
        assertThat(inDelayedQueue(id)).isTrue();
        assertThat(inReadyQueue(id)).isFalse();
        Double score = redis.opsForZSet().score(JobQueue.DELAYED, id.toString());
        assertThat(score.longValue()).isEqualTo(reload(id).getRunAt().toEpochMilli());
    }

    @Test
    void futureRunAtIsScheduledAndPastRunAtRunsNow() throws Exception {
        Instant future = Instant.now().plus(Duration.ofHours(2));
        JsonNode scheduled = submit("{\"type\":\"EMAIL\",\"runAt\":\"" + future + "\"}", null, 201);
        assertThat(scheduled.get("status").asString()).isEqualTo("SCHEDULED");

        JsonNode immediate = submit("{\"type\":\"EMAIL\",\"runAt\":\"2020-01-01T00:00:00Z\"}", null, 201);
        assertThat(immediate.get("status").asString()).isEqualTo("QUEUED");
        assertThat(inReadyQueue(UUID.fromString(immediate.get("id").asString()))).isTrue();
    }

    @Test
    void scheduledJobIsClaimableOnlyOnceDue() throws Exception {
        JsonNode job = submit("{\"type\":\"EMAIL\",\"delaySeconds\":1}", null, 201);
        UUID id = UUID.fromString(job.get("id").asString());
        assertThat(repo.claim(id, "w", UUID.randomUUID(), 30)).as("not due yet").isZero();
        Thread.sleep(1_200);
        assertThat(repo.claim(id, "w", UUID.randomUUID(), 30)).isEqualTo(1);
    }

    @Test
    void staleReadyQueueEntryForFutureRetryIsNotClaimedEarly() {
        // A retry waiting out its backoff, but an old duplicate of its id is still in queue:ready
        // (e.g. re-added by a sweep before the retry was scheduled).
        Job retry = job(JobStatus.SCHEDULED, 1, 3);
        retry.setRunAt(Instant.now().plus(Duration.ofMinutes(5)));
        repo.saveAndFlush(retry);
        queue.enqueue(retry.getId(), retry.getPriority());

        assertThat(queue.pop(Duration.ofMillis(200))).contains(retry.getId());
        assertThat(repo.claim(retry.getId(), "eager-worker", UUID.randomUUID(), 30)).isZero();

        Job after = reload(retry.getId());
        assertThat(after.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(after.getAttempts()).isEqualTo(1);
    }

    @Test
    void runAtAndDelaySecondsTogetherIs400() throws Exception {
        mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EMAIL\",\"delaySeconds\":5,\"runAt\":\"2030-01-01T00:00:00Z\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EMAIL\",\"runAt\":\"" + Instant.now().plus(Duration.ofDays(400)) + "\"}"))
            .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ idempotency

    @Test
    void repeatedIdempotencyKeyReturnsTheOriginalJobWith200() throws Exception {
        JsonNode first = submit("{\"type\":\"EMAIL\",\"payload\":\"a\"}", "order-42", 201);
        JsonNode second = submit("{\"type\":\"EMAIL\",\"payload\":\"a\"}", "order-42", 200);

        assertThat(second.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(repo.count()).isEqualTo(1);
        assertThat(redis.opsForZSet().zCard(JobQueue.READY)).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithSameKeyCreateExactlyOneJob() throws Exception {
        int callers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<MvcResult> call = () -> {
                    start.await();
                    return mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "race-key").content("{\"type\":\"EMAIL\"}")).andReturn();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            Set<String> ids = new HashSet<>();
            int created = 0;
            for (Future<MvcResult> result : results) {
                MvcResult r = result.get();
                assertThat(r.getResponse().getStatus()).isIn(200, 201);
                created += r.getResponse().getStatus() == 201 ? 1 : 0;
                ids.add(json.readTree(r.getResponse().getContentAsString()).get("id").asString());
            }
            assertThat(created).isEqualTo(1);
            assertThat(ids).hasSize(1);
            assertThat(repo.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oversizedIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k".repeat(201)).content("{\"type\":\"EMAIL\"}"))
            .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ cancel

    @Test
    void cancellingQueuedOrScheduledJobIsImmediate() throws Exception {
        JsonNode queued = submit("{\"type\":\"EMAIL\"}", null, 201);
        JsonNode scheduled = submit("{\"type\":\"EMAIL\",\"delaySeconds\":600}", null, 201);

        for (JsonNode job : List.of(queued, scheduled)) {
            UUID id = UUID.fromString(job.get("id").asString());
            mvc.perform(post("/jobs/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
            assertThat(inReadyQueue(id)).isFalse();
            assertThat(inDelayedQueue(id)).isFalse();
            assertThat(redis.opsForHash().hasKey(JobQueue.DELAYED_PRIORITY, id.toString())).isFalse();
            assertThat(repo.claim(id, "w", UUID.randomUUID(), 30)).isZero();
        }
    }

    @Test
    void cancellingRunningJobRequestsCooperativeCancel() throws Exception {
        Job job = running("w1", 1, 3);

        mvc.perform(post("/jobs/{id}/cancel", job.getId()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.cancelRequested").value(true));

        assertThat(queue.isCancelRequested(job.getId())).isTrue();
        // The owning worker can no longer renew its lease, which forces it to look at the flag.
        assertThat(repo.renewLease(job.getId(), "w1", job.getLeaseToken(), 30)).isZero();
        assertThat(repo.markCancelled(job.getId(), "w1", job.getLeaseToken(), Instant.now())).isEqualTo(1);
        assertThat(reload(job.getId()).getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void cancellingFinishedJobIs409AndMissingIs404() throws Exception {
        Job done = repo.saveAndFlush(job(JobStatus.SUCCEEDED, 1, 3));
        mvc.perform(post("/jobs/{id}/cancel", done.getId()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail", containsString("SUCCEEDED")));
        mvc.perform(post("/jobs/{id}/cancel", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ listing

    @Test
    void listingSupportsFiltersAndPagination() throws Exception {
        Instant base = Instant.parse("2026-03-01T00:00:00Z");
        for (int i = 0; i < 7; i++) {
            Job job = job(i < 5 ? JobStatus.SUCCEEDED : JobStatus.DEAD, 1, 3);
            job.setWorkerId(i % 2 == 0 ? "worker-a" : "worker-b");
            job.setCreatedAt(base.plus(Duration.ofHours(i)));
            repo.save(job);
        }
        Job other = job(JobStatus.QUEUED, 0, 3);
        other.setType("OTHER");
        repo.saveAndFlush(other);

        mvc.perform(get("/jobs").param("status", "SUCCEEDED").param("size", "2").param("page", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(5))
            .andExpect(jsonPath("$.totalPages").value(3))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.content.length()").value(2));

        mvc.perform(get("/jobs").param("status", "SUCCEEDED", "DEAD").param("workerId", "worker-a"))
            .andExpect(jsonPath("$.totalElements").value(4));

        mvc.perform(get("/jobs").param("type", "OTHER")).andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/jobs")
                .param("createdFrom", base.plus(Duration.ofHours(2)).toString())
                .param("createdTo", base.plus(Duration.ofHours(4)).toString()))
            .andExpect(jsonPath("$.totalElements").value(2))
            // newest first
            .andExpect(jsonPath("$.content[0].createdAt").value(containsString("T03:00")));
    }

    @Test
    void pageSizeOver100Is400() throws Exception {
        mvc.perform(get("/jobs").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/jobs").param("status", "NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void submitReturns201WithLocation() throws Exception {
        mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"EMAIL\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", containsString("/jobs/")));
    }
}
