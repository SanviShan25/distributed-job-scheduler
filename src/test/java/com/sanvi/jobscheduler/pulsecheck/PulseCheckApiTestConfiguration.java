package com.sanvi.jobscheduler.pulsecheck;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.sanvi.jobscheduler.pulsecheck.check.HttpChecker;
import com.sanvi.jobscheduler.pulsecheck.check.SiteChecker;
import com.sanvi.jobscheduler.pulsecheck.check.UrlSafetyValidator;

/**
 * Keeps the PulseCheck API tests off the real network: {@code *.test} hosts resolve to a public
 * documentation-style address ({@code internal.test} to a private one), and checks return scripted results.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PulseCheckApiTestConfiguration {

    /** Scripted site results by URL; anything not scripted is up (HTTP 200 in 42 ms). */
    public static class ScriptedSiteChecker implements SiteChecker {
        private final Map<String, HttpChecker.Outcome> byUrl = new ConcurrentHashMap<>();

        public void script(String url, HttpChecker.Outcome outcome) {
            byUrl.put(url, outcome);
        }

        public void reset() {
            byUrl.clear();
        }

        @Override
        public HttpChecker.Outcome check(String url) {
            return byUrl.getOrDefault(url, HttpChecker.Outcome.up(200, 42));
        }
    }

    @Bean
    @Primary
    UrlSafetyValidator testUrlSafetyValidator() {
        return new UrlSafetyValidator(host -> {
            if (host.equals("internal.test")) {
                return new InetAddress[] {InetAddress.getByName("10.0.0.5")};
            }
            if (host.endsWith(".test")) {
                return new InetAddress[] {InetAddress.getByName("93.184.215.14")};
            }
            return InetAddress.getAllByName(host); // IP literals and localhost: no network lookup
        }, false);
    }

    @Bean
    @Primary
    ScriptedSiteChecker scriptedSiteChecker() {
        return new ScriptedSiteChecker();
    }
}
