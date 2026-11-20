package com.sanvi.jobscheduler.api;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

class JobControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JobQueue queue;

    @Test
    void missingJobIs404ProblemDetail() throws Exception {
        mvc.perform(get("/jobs/{id}", UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.detail", containsString("not found")));
    }

    @Test
    void malformedIdIs400() throws Exception {
        mvc.perform(get("/jobs/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void submitStoresJobAndEnqueuesIt() throws Exception {
        String body = mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EMAIL\",\"payload\":\"hi\",\"priority\":2}"))
            .andExpect(status().is2xxSuccessful())
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.timeoutSeconds").value(300))
            .andExpect(jsonPath("$.leaseToken").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
        assertThat(inReadyQueue(id)).isTrue();
    }

    @Test
    void outOfRangeFieldsAre400() throws Exception {
        for (String json : new String[] {
                "{\"type\":\"EMAIL\",\"priority\":0}",
                "{\"type\":\"EMAIL\",\"priority\":11}",
                "{\"type\":\"EMAIL\",\"maxAttempts\":0}",
                "{\"type\":\"EMAIL\",\"maxAttempts\":21}",
                "{\"type\":\"\"}",
                "{\"type\":\"EMAIL\",\"timeoutSeconds\":0}"}) {
            mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        assertThat(repo.count()).isZero();
    }

    @Test
    void unknownTypeIs400() throws Exception {
        mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"SMS\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail", containsString("Unknown job type")));
    }

    @Test
    void payloadOver64KbIs400() throws Exception {
        String payload = "é".repeat(40 * 1024); // 40K chars but 80 KB in UTF-8
        mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EMAIL\",\"payload\":\"" + payload + "\"}"))
            .andExpect(status().isBadRequest());
        assertThat(repo.count()).isZero();
    }

    @Test
    void requeueResetsDeadJobAndRemovesItFromDeadList() throws Exception {
        Job dead = job(JobStatus.DEAD, 3, 3);
        dead.setLastError("boom");
        repo.saveAndFlush(dead);
        queue.toDead(dead.getId());

        mvc.perform(post("/jobs/{id}/requeue", dead.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.attempts").value(0))
            .andExpect(jsonPath("$.lastError").doesNotExist());

        assertThat(redis.opsForList().size(JobQueue.DEAD)).isZero();
        assertThat(inReadyQueue(dead.getId())).isTrue();
        assertThat(reload(dead.getId()).getLastError()).isNull();
    }

    @Test
    void requeueOfNonDeadJobIs409AndMissingIs404() throws Exception {
        Job queued = repo.saveAndFlush(job(JobStatus.QUEUED, 0, 3));
        mvc.perform(post("/jobs/{id}/requeue", queued.getId()))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/jobs/{id}/requeue", UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }
}
