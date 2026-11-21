# Design notes

## PulseCheck

PulseCheck is a website uptime monitor built on the job scheduler: monitors are rows in Postgres, and
each check is an `HTTP_CHECK` job created by a recurring schedule.

### Data model (V5)

- **Dev user is seeded by code, not by a migration.** `DevUserSeeder` runs only under the `dev`
  profile, so a production database never gets a password-less account. Flyway migrations run in
  every environment, and Flyway has no clean way to apply a migration in only some profiles.
- **`check_results.id` is a `BIGINT` identity**, unlike the UUID keys elsewhere. Results are the
  high-volume table (one row per monitor per minute at most): a sequential 8-byte key keeps the
  primary-key index small and insert-friendly, and nothing outside the database needs to know a
  result's id in advance.
- **`alert_emails` is a comma-separated `TEXT` column** rather than a `text[]` or a child table. It is
  write-rarely, read-whole, and not queried by individual address yet; a child table can replace it
  if per-recipient settings appear.
- **History is deleted with the monitor** (`ON DELETE CASCADE` on `check_results` and `incidents`).
  A deleted monitor's history has no owner-facing view, and keeping orphaned rows would only grow the
  retention job's work.
- **The database enforces the business rules that must hold under concurrency:** one open incident
  per monitor (partial unique index), one monitor per URL per user (unique constraint), and the
  allowed intervals and statuses (CHECK constraints). Application code checks them too, for friendly
  error messages.

### SSRF-safe HTTP checker

- **OkHttp, because of its `Dns` hook.** `UrlSafetyValidator` resolves the host once and validates the
  addresses; `HttpChecker` then gives OkHttp a `Dns` that answers only with those addresses, so the
  connection goes to exactly what was validated (no second lookup, so DNS rebinding cannot swap in an
  internal IP). TLS still checks the certificate against the host name. `java.net.http.HttpClient`
  has no equivalent hook.
- **A host is rejected if *any* of its addresses is blocked**, not just the first: the client may
  fall back to another address, and a mixed public/private answer is a classic rebinding setup.
- **Blocked ranges go slightly beyond the required list:** also 192.0.0.0/24, 198.18.0.0/15,
  multicast and reserved space (224/4, 240/4), IPv6 multicast and deprecated site-local, and Teredo
  (2001::/32, whose embedded IPv4 is obfuscated). IPv4 addresses hidden inside IPv6
  (`::ffff:a.b.c.d`, NAT64 `64:ff9b::/96`, 6to4 `2002::/16`, IPv4-compatible `::a.b.c.d`) are
  unwrapped and checked against the IPv4 rules.
- **Redirects are followed manually** (OkHttp's own redirect following is off), up to 5, and every
  hop goes through the same validation and pinning. A redirect to a blocked address ends the check as
  DOWN with `redirect blocked: …`; the private address is never contacted.
- **The 10 s timeout is a budget for the whole check**, across all redirect hops (each hop gets what
  is left), so a redirect chain cannot stretch one check to 60 s and tie up a worker.
- **3xx semantics:** a 3xx *with* a `Location` is followed; the final status decides. A 3xx without
  `Location` (e.g. 304) counts as up. Exceeding 5 redirects counts as down.
- **No proxy** (`Proxy.NO_PROXY`): a proxy would resolve the host itself and bypass the pinning.
- **Only the first 64 KB of the body is read**, so a huge or endless response cannot hold a worker.
- **Error messages are written for non-technical users** ("timed out after 10s", "connection refused",
  "HTTP 503", "could not resolve host …"). Never-resolving hosts are reported as a DNS failure, not as
  "blocked", because the site may simply be misconfigured or down.
- **`pulsecheck.checker.allow-loopback`** (default `false`) exists only so tests can hit a local
  MockWebServer. It unblocks 127.0.0.0/8 and ::1 and nothing else, and logs a warning at startup when on.

### HTTP_CHECK jobs, status and incidents

- **The HTTP request runs outside any transaction.** `MonitorCheckService` loads the monitor, runs the
  check (up to 10 s), and only then opens a short transaction to record it. Holding a DB connection
  across a slow website would let a handful of slow sites exhaust the connection pool.
- **The recording transaction locks the monitor row** (`SELECT … FOR UPDATE`). An immediate check and
  a scheduled check of the same monitor can overlap; the lock makes their read-modify-write of
  `consecutive_failures` and `current_status` sequential, so no failure is lost.
- **Incidents are idempotent by construction.** Opening is `INSERT … ON CONFLICT (monitor_id) WHERE
  resolved_at IS NULL DO NOTHING` against the one-open-incident partial unique index, and resolving is
  `UPDATE … WHERE resolved_at IS NULL`. The rule is simply "DOWN ⇒ an incident is open, UP ⇒ none is",
  applied on every check, so a retried job, a duplicate job or two concurrent checks converge on
  exactly one incident. Using `ON CONFLICT` rather than catching a unique violation matters: in Postgres
  a failed statement aborts the whole transaction.
- **Status rule:** DOWN after 2 consecutive failures; UP after 1 success. One failure keeps the
  previous status (UP stays UP, UNKNOWN stays UNKNOWN), which filters out one-off network blips.
- **`incidents.started_at` is the time of the first failure in the streak**, not the moment DOWN was
  confirmed, so the reported outage duration is honest (it is one interval longer than "confirmed DOWN").
- **A down website is a successful job.** The checker reports site problems as data; only unexpected
  errors (e.g. the database being unreachable) throw, which makes the scheduler retry the job. A
  malformed payload is a `NonRetryableJobException` (straight to DEAD).
- **Deleted or paused monitors are no-ops**, checked twice: before the request (to avoid needless
  traffic) and again under the row lock (in case the user paused or deleted it mid-check, in which case
  the result is discarded).

### Monitor lifecycle and REST API

- **Monitor and schedule change in one transaction.** The recurring check is a `job_schedules` row
  (named `monitor:{id}`, type `HTTP_CHECK`, payload = monitor id) in the same Postgres database as the
  monitor, so create/update/pause/resume/delete change both atomically. Redis is not involved in
  keeping them in sync.
- **The immediate check is enqueued after commit.** Its job row is inserted in the same transaction
  (status QUEUED) and pushed to Redis in an `afterCommit` hook. Enqueueing before commit could let a
  worker pop the id before the row is visible, fail the claim and drop it. If Redis is down at that
  moment, the row is still QUEUED in Postgres and the scheduler's drift sweeper enqueues it within
  about a minute; that is the recovery path, so no extra outbox is needed.
- **Each monitor gets its own cron offset.** The second (and, for 5/15/60-minute intervals, the minute)
  is derived from the monitor id, e.g. `37 3/5 * * * *`. Monitors with the same interval are spread
  across the period instead of all firing at second 0. The schedule's misfire policy (run once, skip
  missed runs) means a long scheduler outage produces one catch-up check, not a burst.
- **Limits are race-free.** Creating a monitor locks the user row (`SELECT … FOR UPDATE`) before
  counting, so two parallel requests cannot both create monitor #10. Duplicate URLs are rejected by an
  application check (friendly message) backed by the `(user_id, url)` unique constraint. URLs are
  compared in normalised form (lower-case scheme and host, `/` for an empty path).
- **Changing a monitor's URL starts over:** status UNKNOWN, failure count 0, any open incident is
  resolved (it described a different site), and a check runs immediately.
- **Pausing leaves an open incident open.** Whether the outage ended is unknown until the next check
  after resuming; that check resolves it if the site is back, or keeps it (no duplicate) if not.
- **Uptime is computed in SQL** with `FILTER` aggregates over one indexed range scan of the last 30
  days, using the database clock: uptime % for 24 h / 7 d / 30 d, and the average response time of
  *successful* checks in the last 24 h (timeouts would otherwise dominate it). Windows with no checks
  return null rather than 0 % or 100 %.
- **Temporary auth.** `/api/**` uses the scheduler's `X-API-Key`, and every request acts as the dev
  user (`CurrentUser`), seeded only under the `dev` profile, which docker-compose now enables for the
  API. If the dev user is missing, the API answers 503 with an explanation instead of failing obscurely.
  `CurrentUser` is the single place to change when real login arrives.
- **Retention:** check results older than 90 days are deleted daily at 03:30 UTC in batches of 5,000,
  under a Redis lock so only one API instance does it.
