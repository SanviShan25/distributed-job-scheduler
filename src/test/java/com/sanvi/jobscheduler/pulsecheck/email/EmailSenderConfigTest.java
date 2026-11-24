package com.sanvi.jobscheduler.pulsecheck.email;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The startup safety checks. */
class EmailSenderConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(EmailSenderConfig.class);

    @Test
    void defaultsToTheLoggingSenderOnLocalhost() {
        runner.run(context -> assertThat(context).hasSingleBean(EmailSender.class)
            .getBean(EmailSender.class).isInstanceOf(LoggingEmailSender.class));
    }

    @Test
    void loggingIsAllowedForEveryLocalhostForm() {
        for (String url : new String[] {"http://localhost:8080", "http://127.0.0.1:3000/", "http://[::1]:8080"}) {
            runner.withPropertyValues("pulsecheck.app.base-url=" + url)
                .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Test
    void loggingRefusesToStartForARealDomain() {
        runner.withPropertyValues("pulsecheck.email.provider=logging",
                "pulsecheck.app.base-url=https://pulsecheck.example.com")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageContaining("only allowed when pulsecheck.app.base-url is a localhost URL"));
    }

    @Test
    void loggingRefusesToStartForALocalhostLookalike() {
        runner.withPropertyValues("pulsecheck.app.base-url=https://localhost.evil.example.com")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void resendRefusesToStartWithoutAnApiKey() {
        runner.withPropertyValues("pulsecheck.email.provider=resend")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageContaining("RESEND_API_KEY"));
        runner.withPropertyValues("pulsecheck.email.provider=resend", "pulsecheck.email.resend.api-key=  ")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void resendRefusesToStartWithoutASender() {
        runner.withPropertyValues("pulsecheck.email.provider=resend", "pulsecheck.email.resend.api-key=re_x",
                "pulsecheck.email.from= ")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageContaining("EMAIL_FROM"));
    }

    @Test
    void resendWithAKeyStartsForAnyDomain() {
        runner.withPropertyValues("pulsecheck.email.provider=resend", "pulsecheck.email.resend.api-key=re_x",
                "pulsecheck.app.base-url=https://pulsecheck.example.com")
            .run(context -> assertThat(context).getBean(EmailSender.class).isInstanceOf(ResendEmailSender.class));
    }
}
