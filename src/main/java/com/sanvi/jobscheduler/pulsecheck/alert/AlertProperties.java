package com.sanvi.jobscheduler.pulsecheck.alert;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pulsecheck.alerts.*}.
 *
 * @param maxEmailsPerMonitorPerHour     alert emails one monitor may send each recipient in any rolling hour,
 *                                       including the "alerts paused" note that replaces the last one
 * @param maxExtraRecipients             extra (non-owner) recipients per monitor, pending or confirmed
 * @param confirmationTtl                lifetime of a recipient's confirmation link
 * @param confirmationsPerOwnerPerHour   confirmation emails one account may cause per rolling hour
 * @param confirmationsPerAddressPerDay  confirmation emails one address may receive per rolling day, from
 *                                       all accounts together
 */
@ConfigurationProperties(prefix = "pulsecheck.alerts")
public record AlertProperties(
    @DefaultValue("6") int maxEmailsPerMonitorPerHour,
    @DefaultValue("5") int maxExtraRecipients,
    @DefaultValue("72h") Duration confirmationTtl,
    @DefaultValue("10") int confirmationsPerOwnerPerHour,
    @DefaultValue("3") int confirmationsPerAddressPerDay) {
}
