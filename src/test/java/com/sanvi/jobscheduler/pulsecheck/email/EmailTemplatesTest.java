package com.sanvi.jobscheduler.pulsecheck.email;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class EmailTemplatesTest {
    private static final String LINK = "https://pulsecheck.example.com/verify-email?token=abc_DEF-123";

    @Test
    void everyEmailHasATextAndAnHtmlPartWithTheLink() {
        for (EmailSender.Email email : List.of(
                EmailTemplates.verification("a@example.com", LINK, 24),
                EmailTemplates.passwordReset("a@example.com", LINK, 60),
                EmailTemplates.alreadyRegistered("a@example.com", LINK))) {
            assertThat(email.to()).isEqualTo("a@example.com");
            assertThat(email.subject()).isNotBlank();
            assertThat(email.text()).contains(LINK).doesNotContain("<");
            assertThat(email.html()).startsWith("<!DOCTYPE html>")
                .contains("href=\"" + LINK + "\"")          // the button
                .contains(">" + LINK + "</a>")              // the plain link under it
                .doesNotContain("<style")                   // inline CSS only
                .doesNotContain("<img");
        }
    }

    @Test
    void htmlHasExactlyOneButtonAndALightBackground() {
        String html = EmailTemplates.verification("a@example.com", LINK, 24).html();
        assertThat(html.split("display:inline-block", -1)).hasSize(2);
        assertThat(html).contains("background-color:#f3f4f6").contains("Verify email address");
    }

    @Test
    void expiryIsStatedInBothParts() {
        EmailSender.Email email = EmailTemplates.passwordReset("a@example.com", LINK, 60);
        assertThat(email.text()).contains("60 minutes");
        assertThat(email.html()).contains("60 minutes");
    }

    @Test
    void dynamicTextIsEscapedInHtml() {
        EmailSender.Email email = EmailTemplates.render("a@example.com", new EmailTemplates.Content(
            "Shop <b>down</b>", "<script>alert(1)</script>", List.of("Tom & Jerry's \"site\""), "Open",
            "https://example.com/?a=1&b=2", null));

        assertThat(email.html()).doesNotContain("<script>").contains("&lt;script&gt;")
            .contains("Tom &amp; Jerry&#39;s &quot;site&quot;")
            .contains("href=\"https://example.com/?a=1&amp;b=2\"");
        assertThat(email.text()).contains("<script>alert(1)</script>"); // plain text needs no escaping
    }
}
