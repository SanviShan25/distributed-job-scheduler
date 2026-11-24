package com.sanvi.jobscheduler.pulsecheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sanvi.jobscheduler.pulsecheck.email.EmailSender;
import com.sanvi.jobscheduler.pulsecheck.email.SendEmailJobHandler;
import com.sanvi.jobscheduler.worker.NonRetryableJobException;

/**
 * Captures outgoing emails so tests can follow the links in them. Emails are sent by SEND_EMAIL jobs, so
 * reading the inbox first delivers every queued email job through the real {@link SendEmailJobHandler}
 * (playing the worker) and marks it SUCCEEDED or DEAD, which also exercises the redaction trigger.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingEmailConfiguration {

    public static class RecordingEmailSender implements EmailSender {
        private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");
        private final List<Email> sent = new ArrayList<>();
        private Runnable beforeRead = () -> { };

        void setBeforeRead(Runnable beforeRead) {
            this.beforeRead = beforeRead;
        }

        @Override
        public synchronized void send(Email email) {
            sent.add(email);
        }

        /** Emails sent so far, without delivering queued jobs first. */
        public synchronized List<Email> sentSoFar() {
            return List.copyOf(sent);
        }

        public synchronized List<Email> to(String address) {
            beforeRead.run();
            return sent.stream().filter(e -> e.to().equalsIgnoreCase(address)).toList();
        }

        public synchronized List<Email> all() {
            beforeRead.run();
            return List.copyOf(sent);
        }

        public synchronized void clear() {
            beforeRead.run(); // deliver anything pending first, so it does not show up later
            sent.clear();
        }

        /** The token from the link in the most recent email to {@code address} with this subject prefix. */
        public synchronized String latestToken(String address, String subjectPrefix) {
            Email email = to(address).stream().filter(e -> e.subject().startsWith(subjectPrefix))
                .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("no '" + subjectPrefix + "' email to " + address));
            Matcher matcher = TOKEN.matcher(email.text());
            if (!matcher.find()) {
                throw new AssertionError("no token link in: " + email.text());
            }
            return matcher.group(1);
        }
    }

    /** Plays the worker for SEND_EMAIL jobs. */
    public static class EmailDelivery {
        private final JdbcTemplate jdbc;
        private final SendEmailJobHandler handler;

        EmailDelivery(JdbcTemplate jdbc, SendEmailJobHandler handler) {
            this.jdbc = jdbc;
            this.handler = handler;
        }

        /** @return how many queued email jobs were processed */
        public int deliverAll() {
            List<Map<String, Object>> queued = jdbc.queryForList(
                "select id, payload from jobs where type = 'SEND_EMAIL' and status = 'QUEUED' order by created_at");
            for (Map<String, Object> job : queued) {
                UUID id = (UUID) job.get("id");
                try {
                    handler.handle((String) job.get("payload"));
                    jdbc.update("update jobs set status = 'SUCCEEDED', attempts = attempts + 1, completed_at = now() "
                        + "where id = ?", id);
                } catch (NonRetryableJobException e) {
                    jdbc.update("update jobs set status = 'DEAD', attempts = attempts + 1, last_error = ?, "
                        + "completed_at = now() where id = ?", e.getMessage(), id);
                }
            }
            return queued.size();
        }
    }

    @Bean
    @Primary
    RecordingEmailSender recordingEmailSender() {
        return new RecordingEmailSender();
    }

    @Bean
    EmailDelivery emailDelivery(JdbcTemplate jdbc, SendEmailJobHandler handler, RecordingEmailSender sender) {
        EmailDelivery delivery = new EmailDelivery(jdbc, handler);
        sender.setBeforeRead(delivery::deliverAll);
        return delivery;
    }
}
