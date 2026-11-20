-- 1. Timestamps become TIMESTAMPTZ.
--    Existing values were written as Instants through a session whose TimeZone is the JVM default,
--    so converting with the session time zone (the implicit cast) preserves the original instants.
ALTER TABLE jobs
  ALTER COLUMN run_at           TYPE TIMESTAMPTZ,
  ALTER COLUMN created_at       TYPE TIMESTAMPTZ,
  ALTER COLUMN updated_at       TYPE TIMESTAMPTZ,
  ALTER COLUMN lease_expires_at TYPE TIMESTAMPTZ;

-- 2. New columns.
ALTER TABLE jobs ADD COLUMN idempotency_key  VARCHAR(200) NULL;
ALTER TABLE jobs ADD COLUMN cron_schedule_id UUID NULL;
ALTER TABLE jobs ADD COLUMN completed_at     TIMESTAMPTZ NULL;
ALTER TABLE jobs ADD CONSTRAINT uq_jobs_idempotency_key UNIQUE (idempotency_key);

UPDATE jobs SET completed_at = updated_at WHERE status IN ('SUCCEEDED', 'DEAD');

-- 3. Statuses: PENDING/FAILED were never written; map defensively, then pin the allowed set.
UPDATE jobs SET status = 'QUEUED' WHERE status = 'PENDING';
UPDATE jobs SET status = 'DEAD'   WHERE status = 'FAILED';
ALTER TABLE jobs ADD CONSTRAINT ck_jobs_status
  CHECK (status IN ('SCHEDULED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'DEAD', 'CANCELLED'));

-- 4. Indexes for the sweeps and listings. (status) alone is covered by the composites.
DROP INDEX IF EXISTS idx_jobs_status;
CREATE INDEX idx_jobs_status_updated_at   ON jobs (status, updated_at);
CREATE INDEX idx_jobs_status_run_at       ON jobs (status, run_at);
CREATE INDEX idx_jobs_running_worker      ON jobs (worker_id)                WHERE status = 'RUNNING';
CREATE INDEX idx_jobs_running_lease       ON jobs (status, lease_expires_at) WHERE status = 'RUNNING';
