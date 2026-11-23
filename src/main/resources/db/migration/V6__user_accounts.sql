-- PulseCheck accounts: case-insensitive emails, verification and lockout state.

-- Emails are compared case-insensitively. The application stores them lower-cased; the expression index
-- enforces uniqueness regardless of how a row was written, and supports lookups by lower(email).
ALTER TABLE users DROP CONSTRAINT uq_users_email;
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

ALTER TABLE users ADD COLUMN email_verified     BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN failed_login_count INT         NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until       TIMESTAMPTZ NULL;
ALTER TABLE users ADD CONSTRAINT ck_users_failed_login_count CHECK (failed_login_count >= 0);
