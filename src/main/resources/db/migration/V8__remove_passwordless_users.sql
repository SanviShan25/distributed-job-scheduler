-- Real accounts now always have a password. The only password-less rows were created by the removed
-- development seeder (dev@pulsecheck.local). Nobody can log in as them, but password reset could still
-- claim one, so delete them, with their monitors (cascade) and their monitors' recurring schedules.
DELETE FROM job_schedules
 WHERE id IN (SELECT m.schedule_id
                FROM monitors m
                JOIN users u ON u.id = m.user_id
               WHERE u.password_hash IS NULL
                 AND m.schedule_id IS NOT NULL);

DELETE FROM users WHERE password_hash IS NULL;
