package com.sanvi.jobscheduler.pulsecheck.email;

import java.util.List;

import org.springframework.web.util.HtmlUtils;

/**
 * The PulseCheck emails. Each one has a plain-text part and an HTML part built from the same content:
 * a heading, a few short paragraphs, one button, and the button's link again as plain text (for clients
 * that block buttons, and so the URL can be checked before clicking).
 *
 * <p>The HTML is deliberately simple: one centred table, inline CSS only (Gmail drops {@code <style>}
 * blocks in many contexts), system fonts, a light background and no images. All dynamic text is
 * HTML-escaped.
 */
public final class EmailTemplates {
    private EmailTemplates() {
    }

    /** The content of one email; rendered to text and HTML by {@link #render}. */
    public record Content(String subject, String heading, List<String> paragraphs, String buttonLabel,
        String buttonUrl, String footer, String unsubscribeUrl) {

        public Content(String subject, String heading, List<String> paragraphs, String buttonLabel, String buttonUrl,
                String footer) {
            this(subject, heading, paragraphs, buttonLabel, buttonUrl, footer, null);
        }
    }

    public static EmailSender.Email verification(String to, String link, long ttlHours) {
        return render(to, new Content(
            "Verify your PulseCheck email address",
            "Confirm your email address",
            List.of("Welcome to PulseCheck! Confirm your email address to start monitoring your websites."),
            "Verify email address",
            link,
            "This link works once and expires in " + ttlHours + " hours. If you didn't sign up for PulseCheck, "
                + "you can ignore this email."));
    }

    public static EmailSender.Email passwordReset(String to, String link, long ttlMinutes) {
        return render(to, new Content(
            "Reset your PulseCheck password",
            "Reset your password",
            List.of("Someone asked to reset the password for this PulseCheck account. If it was you, choose a "
                + "new password with the button below."),
            "Choose a new password",
            link,
            "This link works once and expires in " + ttlMinutes + " minutes. If you didn't ask for this, ignore "
                + "this email; your password stays the same."));
    }

    public static EmailSender.Email alreadyRegistered(String to, String forgotPasswordUrl) {
        return render(to, new Content(
            "You already have a PulseCheck account",
            "You already have an account",
            List.of("Someone (hopefully you) tried to sign up to PulseCheck with this email address, which "
                + "already has an account.", "Forgot your password? You can reset it here:"),
            "Reset my password",
            forgotPasswordUrl,
            "If this wasn't you, you can ignore this email. Nothing has changed on your account."));
    }

    // ------------------------------------------------------------------ alerts

    private static final java.time.format.DateTimeFormatter WHEN = java.time.format.DateTimeFormatter
        .ofPattern("d MMM yyyy 'at' HH:mm 'UTC'", java.util.Locale.ENGLISH).withZone(java.time.ZoneOffset.UTC);
    private static final String ALERT_FOOTER = "You're receiving this because you get PulseCheck alerts for this monitor.";

    /** "23 Nov 2026 at 14:05 UTC" */
    public static String formatTime(java.time.Instant instant) {
        return WHEN.format(instant);
    }

    /**
     * "less than a minute" (under 30 s), else rounded to the nearest minute: "1 minute", "12 minutes",
     * "2 hours 5 minutes", "1 day 3 hours".
     */
    public static String formatDuration(java.time.Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds < 30) {
            return "less than a minute";
        }
        long minutes = (seconds + 30) / 60;
        long days = minutes / (24 * 60);
        long hours = (minutes / 60) % 24;
        long mins = minutes % 60;
        if (days > 0) {
            return plural(days, "day") + (hours > 0 ? " " + plural(hours, "hour") : "");
        }
        if (hours > 0) {
            return plural(hours, "hour") + (mins > 0 ? " " + plural(mins, "minute") : "");
        }
        return plural(mins, "minute");
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    public static EmailSender.Email alertDown(String to, String name, String url, java.time.Instant since, String cause,
            String monitorLink, String unsubscribeLink) {
        return render(to, new Content(
            name + " is DOWN",
            name + " is down",
            List.of(url + " has not been responding since " + formatTime(since) + ".",
                "Cause: " + cause + ".",
                "We'll email you again as soon as it's back up."),
            "View monitor",
            monitorLink,
            ALERT_FOOTER,
            unsubscribeLink));
    }

    public static EmailSender.Email alertUp(String to, String name, String url, java.time.Instant downSince,
            java.time.Instant upAt, String cause, String monitorLink, String unsubscribeLink) {
        String downtime = formatDuration(java.time.Duration.between(downSince, upAt));
        return render(to, new Content(
            name + " is back UP after " + downtime,
            "Back up after " + downtime,
            List.of(url + " is responding again as of " + formatTime(upAt) + ".",
                "It was down from " + formatTime(downSince) + " (cause: " + cause + ")."),
            "View monitor",
            monitorLink,
            ALERT_FOOTER,
            unsubscribeLink));
    }

    public static EmailSender.Email alertsPaused(String to, String name, String url, String currentStatus,
            int maxPerHour, String monitorLink, String unsubscribeLink) {
        return render(to, new Content(
            "Alerts paused for " + name + " for an hour",
            "Alerts for " + name + " are paused for an hour",
            List.of(url + " has changed between up and down many times in the last hour, so we've paused alert "
                    + "emails for this monitor for an hour to avoid flooding your inbox (at most " + maxPerHour
                    + " emails per hour).",
                "Its status when we paused: " + currentStatus + ". Checks keep running; the monitor page shows the "
                    + "live status."),
            "View monitor",
            monitorLink,
            ALERT_FOOTER,
            unsubscribeLink));
    }

    public static EmailSender.Email confirmAlertRecipient(String to, String name, String url, String confirmLink,
            long ttlHours) {
        return render(to, new Content(
            "Confirm PulseCheck alerts for " + name,
            "Get alerts for " + name + "?",
            List.of("A PulseCheck user asked to send this address an email whenever " + url + " (monitored as \""
                    + name + "\") goes down or comes back up.",
                "You'll only get these alerts if you confirm. Every alert has a one-click unsubscribe link."),
            "Yes, send me alerts",
            confirmLink,
            "This link works once and expires in " + ttlHours + " hours. If you don't want these alerts, ignore "
                + "this email: you won't hear from this monitor."));
    }

    /** Builds both parts of an email from its content. */
    public static EmailSender.Email render(String to, Content content) {
        return new EmailSender.Email(to, content.subject(), text(content), html(content));
    }

    static String text(Content c) {
        StringBuilder text = new StringBuilder(c.heading()).append("\n\n");
        for (String paragraph : c.paragraphs()) {
            text.append(paragraph).append("\n\n");
        }
        text.append(c.buttonLabel()).append(": ").append(c.buttonUrl()).append("\n\n");
        if (c.footer() != null) {
            text.append(c.footer()).append("\n\n");
        }
        if (c.unsubscribeUrl() != null) {
            text.append("Unsubscribe from alerts for this monitor: ").append(c.unsubscribeUrl()).append("\n\n");
        }
        return text.append("-- \nPulseCheck").toString();
    }

    static String html(Content c) {
        String font = "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;";
        StringBuilder paragraphs = new StringBuilder();
        for (String paragraph : c.paragraphs()) {
            paragraphs.append("<p style=\"margin:0 0 16px;font-size:16px;line-height:24px;color:#374151;")
                .append(font).append("\">").append(escape(paragraph)).append("</p>");
        }
        String url = escape(c.buttonUrl());
        String footer = c.footer() == null ? "" : "<p style=\"margin:24px 0 0;font-size:13px;line-height:20px;"
            + "color:#6b7280;" + font + "\">" + escape(c.footer()) + "</p>";
        if (c.unsubscribeUrl() != null) {
            footer += "<p style=\"margin:8px 0 0;font-size:13px;line-height:20px;color:#6b7280;" + font + "\">"
                + "<a href=\"" + escape(c.unsubscribeUrl()) + "\" style=\"color:#6b7280;\">Unsubscribe</a>"
                + " from alerts for this monitor</p>";
        }

        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>" + escape(c.subject()) + "</title></head>"
            + "<body style=\"margin:0;padding:0;background-color:#f3f4f6;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"background-color:#f3f4f6;padding:32px 12px;\"><tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"max-width:560px;background-color:#ffffff;border-radius:8px;border:1px solid #e5e7eb;\">"
            + "<tr><td style=\"padding:32px;\">"
            + "<p style=\"margin:0 0 24px;font-size:14px;font-weight:600;color:#2563eb;letter-spacing:0.02em;"
            + font + "\">PulseCheck</p>"
            + "<h1 style=\"margin:0 0 16px;font-size:22px;line-height:30px;color:#111827;" + font + "\">"
            + escape(c.heading()) + "</h1>"
            + paragraphs
            + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:8px 0 16px;\"><tr>"
            + "<td style=\"border-radius:6px;background-color:#2563eb;\">"
            + "<a href=\"" + url + "\" style=\"display:inline-block;padding:12px 22px;font-size:16px;font-weight:600;"
            + "color:#ffffff;text-decoration:none;border-radius:6px;" + font + "\">" + escape(c.buttonLabel())
            + "</a></td></tr></table>"
            + "<p style=\"margin:0;font-size:13px;line-height:20px;color:#6b7280;" + font + "\">"
            + "Or copy this link into your browser:<br>"
            + "<a href=\"" + url + "\" style=\"color:#2563eb;word-break:break-all;\">" + url + "</a></p>"
            + footer
            + "</td></tr></table>"
            + "</td></tr></table></body></html>";
    }

    static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
