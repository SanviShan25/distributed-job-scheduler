package com.sanvi.jobscheduler.pulsecheck;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.sanvi.jobscheduler.pulsecheck.email.EmailSender;

/** Captures outgoing emails so tests can follow the links in them. */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingEmailConfiguration {

    public static class RecordingEmailSender implements EmailSender {
        private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");
        private final List<Email> sent = new ArrayList<>();

        @Override
        public synchronized void send(Email email) {
            sent.add(email);
        }

        public synchronized List<Email> to(String address) {
            return sent.stream().filter(e -> e.to().equalsIgnoreCase(address)).toList();
        }

        public synchronized List<Email> all() {
            return List.copyOf(sent);
        }

        public synchronized void clear() {
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

    @Bean
    @Primary
    RecordingEmailSender recordingEmailSender() {
        return new RecordingEmailSender();
    }
}
