-- One row per incident transition (DOWN when an incident opens, UP when it resolves) that PulseCheck
-- considered alerting about, and what it did:
--   SENT          alert email queued
--   PAUSED        the hourly cap was reached: an "alerts paused" note was queued instead of the alert
--   SKIPPED       suppressed because alerts for this monitor are paused (flapping)
--   NO_RECIPIENT  nobody to send to (owner's email not verified)
-- The unique (incident_id, transition) makes each transition alert at most once, even if the code that
-- reacts to it runs twice. Rows also drive the per-monitor hourly cap.
CREATE TABLE alert_events (
  id          UUID PRIMARY KEY,
  monitor_id  UUID        NOT NULL REFERENCES monitors (id) ON DELETE CASCADE,
  incident_id UUID        NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
  transition  VARCHAR(4)  NOT NULL,
  outcome     VARCHAR(16) NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_alert_events_transition CHECK (transition IN ('DOWN', 'UP')),
  CONSTRAINT ck_alert_events_outcome CHECK (outcome IN ('SENT', 'PAUSED', 'SKIPPED', 'NO_RECIPIENT')),
  CONSTRAINT uq_alert_events_transition UNIQUE (incident_id, transition)
);
CREATE INDEX idx_alert_events_monitor_time ON alert_events (monitor_id, created_at DESC);
