-- Fencing token + lease for RUNNING jobs, and a per-job execution timeout.
ALTER TABLE jobs ADD COLUMN lease_token UUID;
ALTER TABLE jobs ADD COLUMN lease_expires_at TIMESTAMP;
ALTER TABLE jobs ADD COLUMN timeout_seconds INT NOT NULL DEFAULT 300;

-- Jobs already RUNNING when this migrates get a token so every RUNNING row is fenced.
-- Their lease stays NULL; the heartbeat-based recovery still covers them.
UPDATE jobs SET lease_token = gen_random_uuid() WHERE status = 'RUNNING';
