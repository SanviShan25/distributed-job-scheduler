package com.sanvi.jobscheduler.pulsecheck.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pulsecheck.alerts.*}.
 *
 * @param maxEmailsPerMonitorPerHour alert emails one monitor may cause in any rolling hour, including the
 *                                   "alerts paused" note that replaces the last one
 */
@ConfigurationProperties(prefix = "pulsecheck.alerts")
public record AlertProperties(@DefaultValue("6") int maxEmailsPerMonitorPerHour) {
}
