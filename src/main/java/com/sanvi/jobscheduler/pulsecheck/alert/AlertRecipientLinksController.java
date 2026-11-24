package com.sanvi.jobscheduler.pulsecheck.alert;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import lombok.RequiredArgsConstructor;

/**
 * The confirmation and unsubscribe links in alert emails. Public: the token in the link is the only credential,
 * so these work without logging in (see {@code SecurityConfig}).
 *
 * <p>GET never changes anything; it shows a page with a button that POSTs. Mail security scanners and link
 * previews fetch links in emails, and must not confirm or unsubscribe anyone. Mail clients' own "Unsubscribe"
 * buttons use RFC 8058 one-click: a POST to the {@code List-Unsubscribe} URL with
 * {@code List-Unsubscribe=One-Click}, handled by the same POST endpoint.
 */
@RestController
@RequestMapping("/alert-recipients")
@Profile("api")
@RequiredArgsConstructor
public class AlertRecipientLinksController {
    private final AlertRecipientService recipients;

    @GetMapping("/confirm")
    public ResponseEntity<String> confirmPage(@RequestParam(defaultValue = "") String token) {
        return page(HttpStatus.OK, "Confirm PulseCheck alerts",
            "Do you want to receive PulseCheck alert emails for this monitor?",
            form("confirm", token, "Yes, send me alerts"));
    }

    @PostMapping(value = "/confirm")
    public ResponseEntity<String> confirm(@RequestParam(defaultValue = "") String token) {
        return switch (recipients.confirm(token)) {
            case DONE, ALREADY_DONE -> page(HttpStatus.OK, "Alerts confirmed",
                "You'll now get an email when this monitor goes down or comes back up. Every alert has an "
                    + "unsubscribe link.", "");
            case USED -> page(HttpStatus.GONE, "Link already used",
                "This confirmation link has already been used or has been replaced by a newer one.", "");
            case INVALID -> page(HttpStatus.NOT_FOUND, "Link not valid",
                "This confirmation link is not valid or has expired. Ask the monitor's owner to send a new one.", "");
        };
    }

    @GetMapping("/unsubscribe")
    public ResponseEntity<String> unsubscribePage(@RequestParam(defaultValue = "") String token) {
        return page(HttpStatus.OK, "Unsubscribe from alerts",
            "Stop receiving PulseCheck alert emails for this monitor? Other monitors are not affected.",
            form("unsubscribe", token, "Unsubscribe"));
    }

    /** Also the RFC 8058 one-click endpoint (the body {@code List-Unsubscribe=One-Click} is accepted and ignored). */
    @PostMapping(value = "/unsubscribe")
    public ResponseEntity<String> unsubscribe(@RequestParam(defaultValue = "") String token) {
        return switch (recipients.unsubscribe(token)) {
            case DONE, ALREADY_DONE -> page(HttpStatus.OK, "Unsubscribed",
                "You won't get any more alert emails for this monitor.", "");
            case USED -> page(HttpStatus.GONE, "Link already used",
                "This unsubscribe link has already been used. Use the link in a more recent alert email.", "");
            case INVALID -> page(HttpStatus.NOT_FOUND, "Link not valid", "This unsubscribe link is not valid.", "");
        };
    }

    private static String form(String action, String token, String button) {
        return "<form method=\"post\" action=\"" + action + "\">"
            + "<input type=\"hidden\" name=\"token\" value=\"" + HtmlUtils.htmlEscape(token) + "\">"
            + "<button type=\"submit\" style=\"padding:10px 18px;font-size:16px;font-weight:600;color:#fff;"
            + "background:#2563eb;border:0;border-radius:6px;cursor:pointer;\">" + button + "</button></form>";
    }

    private static ResponseEntity<String> page(HttpStatus status, String title, String message, String extra) {
        String html = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + title
            + " - PulseCheck</title></head><body style=\"margin:0;background:#f3f4f6;font-family:-apple-system,"
            + "BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\">"
            + "<main style=\"max-width:480px;margin:48px auto;padding:32px;background:#fff;border:1px solid #e5e7eb;"
            + "border-radius:8px;\"><p style=\"margin:0 0 16px;color:#2563eb;font-weight:600;\">PulseCheck</p>"
            + "<h1 style=\"margin:0 0 12px;font-size:22px;color:#111827;\">" + title + "</h1>"
            + "<p style=\"margin:0 0 20px;color:#374151;line-height:24px;\">" + message + "</p>" + extra
            + "</main></body></html>";
        return ResponseEntity.status(status)
            .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer") // the token is in the URL
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'")
            .body(html);
    }
}
