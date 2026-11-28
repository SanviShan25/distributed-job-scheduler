-- Demo mode changes from one shared account (V12) to a private, temporary sandbox account per visitor.
--   * Any number of demo accounts: the "at most one" index goes.
--   * demo_expires_at: when cleanup deletes the sandbox; set exactly for demo accounts.
--   * The shared account from V12 (it never expires) and its nightly reset are removed; demo mode makes sandboxes
--     on demand. Its monitors' schedules are not owned via a foreign key, so they go first; the rest cascades.
DELETE FROM job_schedules
 WHERE id IN (SELECT m.schedule_id FROM monitors m JOIN users u ON u.id = m.user_id WHERE u.demo);
DELETE FROM users WHERE demo;
DELETE FROM job_schedules WHERE name = 'pulsecheck:demo-reset';
-- Its job type no longer exists; pending runs would only fail.
DELETE FROM jobs WHERE type = 'DEMO_RESET' AND status IN ('SCHEDULED', 'QUEUED');

DROP INDEX uq_users_single_demo;
ALTER TABLE users ADD COLUMN demo_expires_at TIMESTAMPTZ NULL;
ALTER TABLE users ADD CONSTRAINT ck_users_demo_expiry CHECK (demo = (demo_expires_at IS NOT NULL));
-- Cleanup ("expired sandboxes") and the capacity check ("active sandboxes").
CREATE INDEX idx_users_demo_expiry ON users (demo_expires_at) WHERE demo;
