-- Extra alert recipients per monitor, with double opt-in. Replaces monitors.alert_emails.
CREATE TABLE alert_recipients (
  id               UUID         PRIMARY KEY,
  monitor_id       UUID         NOT NULL REFERENCES monitors (id) ON DELETE CASCADE,
  email            VARCHAR(320) NOT NULL,
  status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
  confirmed_at     TIMESTAMPTZ  NULL,
  unsubscribed_at  TIMESTAMPTZ  NULL,
  CONSTRAINT ck_alert_recipients_status CHECK (status IN ('PENDING', 'CONFIRMED', 'UNSUBSCRIBED')),
  CONSTRAINT uq_alert_recipients_monitor_email UNIQUE (monitor_id, email)
);
CREATE INDEX idx_alert_recipients_email ON alert_recipients (email);

-- Single-use tokens for confirmation and unsubscribe links; only SHA-256 hashes are stored.
-- recipient_id NULL means the monitor's owner (who can unsubscribe from one monitor's alerts too).
CREATE TABLE alert_recipient_tokens (
  id            UUID        PRIMARY KEY,
  monitor_id    UUID        NOT NULL REFERENCES monitors (id) ON DELETE CASCADE,
  recipient_id  UUID        NULL REFERENCES alert_recipients (id) ON DELETE CASCADE,
  purpose       VARCHAR(16) NOT NULL,
  token_hash    CHAR(64)    NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at    TIMESTAMPTZ NULL,
  used_at       TIMESTAMPTZ NULL,
  CONSTRAINT ck_alert_recipient_tokens_purpose CHECK (purpose IN ('CONFIRM', 'UNSUBSCRIBE')),
  CONSTRAINT ck_alert_recipient_tokens_confirm_has_recipient CHECK (purpose = 'UNSUBSCRIBE' OR recipient_id IS NOT NULL),
  CONSTRAINT uq_alert_recipient_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_alert_recipient_tokens_recipient ON alert_recipient_tokens (recipient_id, purpose, created_at);

-- The owner gets alerts unless they unsubscribed from this monitor.
ALTER TABLE monitors ADD COLUMN notify_owner BOOLEAN NOT NULL DEFAULT true;

-- Carry over existing addresses as PENDING (they never opted in); the owner can send them a
-- confirmation from the API. At most 5 per monitor, the new limit.
INSERT INTO alert_recipients (id, monitor_id, email, status)
SELECT gen_random_uuid(), monitor_id, email, 'PENDING'
FROM (
  SELECT monitor_id, email, row_number() OVER (PARTITION BY monitor_id ORDER BY first_seen) AS n
  FROM (
    SELECT m.id AS monitor_id, lower(trim(t.e)) AS email, min(t.ord) AS first_seen
    FROM monitors m
    CROSS JOIN LATERAL unnest(string_to_array(m.alert_emails, ',')) WITH ORDINALITY AS t(e, ord)
    JOIN users u ON u.id = m.user_id
    WHERE m.alert_emails IS NOT NULL AND trim(t.e) <> '' AND lower(trim(t.e)) <> lower(u.email)
    GROUP BY m.id, lower(trim(t.e))
  ) distinct_addresses
) numbered
WHERE n <= 5;

ALTER TABLE monitors DROP COLUMN alert_emails;
