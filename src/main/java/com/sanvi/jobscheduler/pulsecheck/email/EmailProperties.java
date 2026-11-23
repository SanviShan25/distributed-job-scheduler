package com.sanvi.jobscheduler.pulsecheck.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code pulsecheck.email.*}. Links in emails point at the web app, which will own these pages. */
@ConfigurationProperties(prefix = "pulsecheck.email")
public record EmailProperties(@DefaultValue("http://localhost:8080") String appBaseUrl) {

    public String link(String path, String token) {
        String base = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
        return base + path + "?token=" + token;
    }
}
