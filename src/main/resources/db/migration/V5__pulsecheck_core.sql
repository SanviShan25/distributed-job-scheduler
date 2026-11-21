-- PulseCheck: website uptime monitoring on top of the job scheduler.

CREATE TABLE users (
  id            UUID PRIMARY KEY,
  email         VARCHAR(320) NOT NULL,
  password_hash VARCHAR(200) NULL,            -- set once login exists
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE monitors (
  id                   UUID PRIMARY KEY,
  user_id              UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  name                 VARCHAR(200)  NOT NULL,
  url                  VARCHAR(2048) NOT NULL,
  interval_seconds     INT           NOT NULL,
  enabled              BOOLEAN       NOT NULL DEFAULT TRUE,
  alert_emails         TEXT          NULL,     -- comma-separated; used once alerting exists
  current_status       VARCHAR(16)   NOT NULL DEFAULT 'UNKNOWN',
  consecutive_failures INT           NOT NULL DEFAULT 0,
  last_checked_at      TIMESTAMPTZ   NULL,
  schedule_id          UUID          NULL REFERENCES job_schedules (id) ON DELETE SET NULL,
  created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
  CONSTRAINT ck_monitors_status   CHECK (current_status IN ('UP', 'DOWN', 'UNKNOWN', 'PAUSED')),
  CONSTRAINT ck_monitors_interval CHECK (interval_seconds IN (60, 300, 900, 3600)),
  CONSTRAINT ck_monitors_failures CHECK (consecutive_failures >= 0),
  CONSTRAINT uq_monitors_user_url UNIQUE (user_id, url)
);
CREATE INDEX idx_monitors_user ON monitors (user_id);

CREATE TABLE check_results (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  monitor_id  UUID        NOT NULL REFERENCES monitors (id) ON DELETE CASCADE,
  checked_at  TIMESTAMPTZ NOT NULL,
  up          BOOLEAN     NOT NULL,
  status_code INT         NULL,
  response_ms INT         NULL,
  error       VARCHAR(500) NULL
);
CREATE INDEX idx_check_results_monitor_time ON check_results (monitor_id, checked_at DESC);
-- Retention deletes by age across all monitors.
CREATE INDEX idx_check_results_checked_at ON check_results (checked_at);

CREATE TABLE incidents (
  id          UUID PRIMARY KEY,
  monitor_id  UUID         NOT NULL REFERENCES monitors (id) ON DELETE CASCADE,
  started_at  TIMESTAMPTZ  NOT NULL,
  resolved_at TIMESTAMPTZ  NULL,
  cause       VARCHAR(500) NULL,
  CONSTRAINT ck_incidents_resolved_after_start CHECK (resolved_at IS NULL OR resolved_at >= started_at)
);
CREATE INDEX idx_incidents_monitor ON incidents (monitor_id, started_at DESC);
-- At most one open incident per monitor: this is what makes incident creation idempotent.
CREATE UNIQUE INDEX uq_incidents_one_open_per_monitor ON incidents (monitor_id) WHERE resolved_at IS NULL;
