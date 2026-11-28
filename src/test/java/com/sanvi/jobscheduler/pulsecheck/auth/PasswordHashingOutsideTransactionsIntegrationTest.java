package com.sanvi.jobscheduler.pulsecheck.auth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sanvi.jobscheduler.pulsecheck.demo.DemoAccount;
import com.sanvi.jobscheduler.pulsecheck.demo.DemoProperties;
import com.sanvi.jobscheduler.pulsecheck.email.EmailOutbox;
import com.sanvi.jobscheduler.pulsecheck.repo.AppUserRepository;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

/** Argon2 is slow on purpose: no hash or verification may run while a database transaction is open. */
class PasswordHashingOutsideTransactionsIntegrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder realEncoder;
    @Autowired AppUserRepository users;
    @Autowired AuthProtectionProperties protection;
    @Autowired AuthTokenService tokens;
    @Autowired EmailOutbox outbox;
    @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;
    @Autowired TransactionTemplate tx;
    @Autowired SignupProperties signupProps;
    @Autowired DemoProperties demoProps;
    @Autowired DemoAccount demo;

    /** Delegates to the real encoder and records whether a transaction was active during each call. */
    static final class RecordingEncoder implements PasswordEncoder {
        final PasswordEncoder delegate;
        final List<String> callsInsideTransactions = new ArrayList<>();
        int calls;

        RecordingEncoder(PasswordEncoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public String encode(CharSequence raw) {
            record("encode");
            return delegate.encode(raw);
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            record("matches");
            return delegate.matches(raw, encoded);
        }

        private void record(String method) {
            calls++;
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                callsInsideTransactions.add(method);
            }
        }
    }

    private RecordingEncoder encoder;
    private AccountService accounts;
    private AccountManagementService management;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
        encoder = new RecordingEncoder(realEncoder);
        accounts = new AccountService(jdbc, encoder, users, protection, tokens, outbox, tx, signupProps, demoProps);
        management = new AccountManagementService(users, accounts, tokens, encoder, jdbc, outbox, protection,
            sessions, tx, demo);
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    @Test
    void signupHashesBeforeItsTransaction() {
        accounts.signup("new@example.com", "a long enough password");

        assertThat(encoder.calls).isGreaterThanOrEqualTo(1);
        assertThat(encoder.callsInsideTransactions).isEmpty();
        assertThat(userId("new@example.com")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from jobs where type = 'SEND_EMAIL'", Integer.class))
            .as("account, token and email job still written together").isEqualTo(1);
    }

    @Test
    void duplicateSignupAlsoHashesOutsideTheTransaction() {
        accounts.signup("dup@example.com", "a long enough password");
        encoder.callsInsideTransactions.clear();

        accounts.signup("dup@example.com", "another long password");

        assertThat(encoder.callsInsideTransactions).isEmpty();
    }

    @Test
    void resetChangeAndDeleteHashAndVerifyOutsideTheirTransactions() {
        accounts.signup("owner@example.com", "original password");
        UUID owner = userId("owner@example.com");
        String reset = tokens.issue(owner, AuthTokenService.Purpose.PASSWORD_RESET, Duration.ofHours(1)).token();
        encoder.callsInsideTransactions.clear();
        int before = encoder.calls;

        management.resetPassword(reset, "reset password 1");
        management.changePassword(owner, "reset password 1", "changed password 2", null);
        management.deleteAccount(owner, "changed password 2");

        assertThat(encoder.calls - before).as("encode, matches+encode, matches").isEqualTo(4);
        assertThat(encoder.callsInsideTransactions).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
    }
}
