-- Single-use tokens for email verification and password reset. Only a SHA-256 hash of each token is
-- stored, so a database leak does not reveal usable links.
CREATE TABLE auth_tokens (
  id          UUID PRIMARY KEY,
  user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  purpose     VARCHAR(32) NOT NULL,
  token_hash  CHAR(64)    NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ NOT NULL,
  used_at     TIMESTAMPTZ NULL,
  CONSTRAINT ck_auth_tokens_purpose CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
  CONSTRAINT uq_auth_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_auth_tokens_user_purpose ON auth_tokens (user_id, purpose) WHERE used_at IS NULL;
