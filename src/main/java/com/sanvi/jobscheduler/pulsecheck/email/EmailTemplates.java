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
        String buttonUrl, String footer) {
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
