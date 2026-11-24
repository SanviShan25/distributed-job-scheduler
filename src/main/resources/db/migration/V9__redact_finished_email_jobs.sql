-- SEND_EMAIL jobs carry one-time tokens (verification / password-reset links) in a top-level "secret"
-- field of their JSON payload, because the worker needs the raw token to build the link. Once the job is
-- finished the token must not stay in the jobs table: this trigger removes "secret" whenever a SEND_EMAIL
-- job reaches a terminal state, whichever code path gets it there (worker, reaper recovery, the bulk
-- "attempts exhausted" update, cancellation). Only token hashes are stored anywhere else (auth_tokens).
CREATE FUNCTION redact_finished_email_job() RETURNS trigger AS $$
BEGIN
  IF NEW.type = 'SEND_EMAIL'
     AND NEW.status IN ('SUCCEEDED', 'DEAD', 'CANCELLED')
     AND NEW.payload IS NOT NULL
     AND NEW.payload LIKE '{%' THEN
    BEGIN
      IF (NEW.payload::jsonb) ? 'secret' THEN
        NEW.payload := ((NEW.payload::jsonb - 'secret') || '{"secretRedacted": true}'::jsonb)::text;
      END IF;
    EXCEPTION WHEN invalid_text_representation THEN
      -- Not valid JSON: leave it alone rather than block the status change.
      NULL;
    END;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_redact_finished_email_job
  BEFORE INSERT OR UPDATE OF status ON jobs
  FOR EACH ROW EXECUTE FUNCTION redact_finished_email_job();
