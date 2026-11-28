-- Demo mode: one shared, password-less account that visitors log into with "Try the demo".
--   * users.demo marks it. At most one row may have it (partial unique index).
--   * It can never get a password: nothing can turn it into a normal account (e.g. via a password reset).
ALTER TABLE users ADD COLUMN demo BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX uq_users_single_demo ON users (demo) WHERE demo;
ALTER TABLE users ADD CONSTRAINT ck_users_demo_no_password CHECK (NOT demo OR password_hash IS NULL);

-- Alerts for the demo account's monitors are recorded but never emailed:
--   DEMO  the monitor belongs to the demo account, so no email was queued
ALTER TABLE alert_events DROP CONSTRAINT ck_alert_events_outcome;
ALTER TABLE alert_events ADD CONSTRAINT ck_alert_events_outcome
  CHECK (outcome IN ('SENT', 'PAUSED', 'SKIPPED', 'NO_RECIPIENT', 'DEMO'));
