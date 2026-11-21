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
