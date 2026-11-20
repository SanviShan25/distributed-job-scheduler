-- Recurring job definitions.
CREATE TABLE job_schedules (
  id              UUID PRIMARY KEY,
  name            VARCHAR(200) NOT NULL,
  cron_expression VARCHAR(120) NOT NULL,
  timezone        VARCHAR(64)  NOT NULL DEFAULT 'UTC',
  type            VARCHAR(100) NOT NULL,
  payload         TEXT,
  priority        INT          NOT NULL DEFAULT 5,
  max_attempts    INT          NOT NULL DEFAULT 3,
  timeout_seconds INT          NOT NULL DEFAULT 300,
  enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
  next_run_at     TIMESTAMPTZ,
  last_run_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uq_job_schedules_name UNIQUE (name)
);
CREATE INDEX idx_job_schedules_due ON job_schedules (next_run_at) WHERE enabled;

ALTER TABLE jobs ADD CONSTRAINT fk_jobs_cron_schedule
  FOREIGN KEY (cron_schedule_id) REFERENCES job_schedules (id) ON DELETE SET NULL;
CREATE INDEX idx_jobs_cron_schedule ON jobs (cron_schedule_id) WHERE cron_schedule_id IS NOT NULL;

-- Cancellation of RUNNING jobs is cooperative: the API sets the flag, the worker acts on it.
ALTER TABLE jobs ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;

-- Listing / retention.
CREATE INDEX idx_jobs_created_at          ON jobs (created_at);
CREATE INDEX idx_jobs_status_completed_at ON jobs (status, completed_at);
