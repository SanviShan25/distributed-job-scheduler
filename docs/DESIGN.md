# Design notes

## PulseCheck

PulseCheck is a website uptime monitor built on the job scheduler: monitors are rows in Postgres, and
each check is an `HTTP_CHECK` job created by a recurring schedule.

### Data model (V5)

- **Dev user (removed).** Until real accounts existed, a `DevUserSeeder` under the `dev` profile
  created a password-less user that the API acted as. Both the seeder and the `dev` profile are gone
  (see "Removing the dev user" below), and V8 deletes any password-less accounts it created.
- **`check_results.id` is a `BIGINT` identity**, unlike the UUID keys elsewhere. Results are the
  high-volume table (one row per monitor per minute at most): a sequential 8-byte key keeps the
  primary-key index small and insert-friendly, and nothing outside the database needs to know a
  result's id in advance.
- **`alert_emails` is a comma-separated `TEXT` column** rather than a `text[]` or a child table. It is
  write-rarely, read-whole, and not queried by individual address yet; a child table can replace it
  if per-recipient settings appear. (It did: V11 replaced it with `alert_recipients`, see below.)
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

### Accounts: sign-up (V6)

- **Argon2id instead of BCrypt.** BCrypt only uses the first 72 bytes of a password (Spring Security
  rejects longer ones), but passwords may be up to 128 characters. Argon2id
  (`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`, BouncyCastle) has no such limit and is
  memory-hard. It is wrapped in a `DelegatingPasswordEncoder`, so hashes are stored as `{argon2}…` and
  the algorithm or its cost can be raised later without breaking existing accounts.
- **Emails are case-insensitive.** They are stored trimmed and lower-cased, and V6 replaces the plain
  unique constraint with a unique index on `lower(email)`, so even a row written outside the
  application cannot duplicate an address in another case.
- **Sign-up does not reveal whether an email is registered.** An existing email gets byte-for-byte the
  same `201` response as a new one, and nothing is changed (the existing password is kept). The insert
  is `ON CONFLICT ((lower(email))) DO NOTHING`, which also makes concurrent sign-ups race-free. The
  password is hashed before the insert on both paths, so response times are similar too.
- **The sign-up response has no id or creation time**, only `email`, `emailVerified` (always false)
  and a message. Returning the real id or `createdAt` for an existing account would reveal that it
  already existed.

### Login, sessions and CSRF

- **Two security filter chains.** `/api/**` (PulseCheck) uses browser sessions and CSRF; `/jobs/**`,
  `/schedules/**` and everything else keep the stateless `X-API-Key` chain exactly as before. They are
  separate on purpose: an API key does not log anyone into PulseCheck, and a PulseCheck session does
  not grant access to the scheduler API.
- **Sessions live in Redis** (Spring Session, *indexed* repository, namespace `pulsecheck:session`), so
  any API instance can serve any user and logins survive API restarts. "Indexed" means sessions can be
  looked up by user id (the principal name is the user id, not the email, so it never changes); the
  password reset in a later step uses that to end all of a user's sessions.
- **Session cookie** `PULSECHECK_SESSION`: `HttpOnly`, `SameSite=Lax`, `Path=/`. `Secure` is decided
  per request by `pulsecheck.session.secure-cookie`: `AUTO` (default) sets it for every host except
  `localhost`/`127.0.0.1`/`::1`, so plain-HTTP local development works and nothing else can ever get a
  non-Secure cookie; `ALWAYS`/`NEVER` override. Deciding by host name rather than
  `request.isSecure()` avoids depending on a TLS-terminating proxy forwarding the scheme correctly.
- **Timeouts:** 1 day idle and a browser-session cookie by default; with `rememberMe: true`, 30 days
  idle and a 30-day persistent cookie.
- **Session fixation:** login always ends up with a fresh session id (`changeSessionId()` on an
  existing session, otherwise a new session), so an id planted before login is useless afterwards.
  Login and logout also issue a new CSRF token.
- **Login errors are uniform:** unknown email, an account without a password, and a wrong password
  all return `401 Invalid email or password`. For an unknown email a dummy Argon2 hash is checked
  anyway, so timing does not reveal whether the account exists.
- **CSRF uses the single-page-app double-submit pattern** (`csrf.spa()` with a JavaScript-readable
  `XSRF-TOKEN` cookie). Login and sign-up are protected too (prevents login CSRF). How the frontend must
  use it:
  1. On start-up call `GET /api/auth/csrf` (with credentials). This sets the `XSRF-TOKEN` cookie and
     returns `{headerName: "X-XSRF-TOKEN", cookieName: "XSRF-TOKEN", token}`.
  2. Send the token in the `X-XSRF-TOKEN` header on every `POST`/`PATCH`/`PUT`/`DELETE` to `/api/**`.
     The simplest robust approach is to read the current `XSRF-TOKEN` cookie right before each request
     (Axios does this automatically with its default `xsrfCookieName`/`xsrfHeaderName`).
  3. The token changes on login and logout. Reading the cookie per request handles this; an app that
     caches the token must call `GET /api/auth/csrf` again after login/logout.
  4. All requests need `credentials: "include"` (fetch) or `withCredentials: true` (Axios).
  A missing or wrong token gets `403` with a message pointing to `GET /api/auth/csrf`.
- **Interim state:** `/api/monitors/**` now requires a logged-in user, but `CurrentUser` still resolves
  to the dev user until the next step switches it to the logged-in user.
- **Testing note:** Spring Security's `csrf()` MockMvc helper permanently replaces the CSRF repository
  inside the shared filter chain with a session-based one, which silently breaks real cookie CSRF in
  later tests sharing the Spring context. Tests use `CsrfTestSupport.validCsrf()` (a real
  cookie + header pair) instead.

### Per-user data

- **`CurrentUser` is the logged-in user** (the `SessionUser` in the session), replacing the dev user.
  It also checks that the account still exists, so a session that outlives a deleted account behaves
  as logged out (401) instead of acting on a user that is gone.
- **Ownership is part of every query, not a separate check.** Monitors are loaded with
  `findByIdAndUserId`, and the checks and incidents endpoints load the monitor through the same method
  before reading anything, so a missing owner filter cannot slip into one endpoint.
- **Someone else's monitor is a 404, not a 403**, with the same body as a monitor that does not exist
  at all. A 403 would confirm that the id is real, which lets ids be probed.
- **Limits and duplicates are per user:** two users may monitor the same URL, and one user's
  10-monitor limit does not affect anyone else.

### Brute-force protection

- **Rate limits are Redis token buckets** (the same Lua limiter as `POST /jobs`), shared by every API
  instance: login 10/min per client IP and 5/min per email, sign-up 5/hour per client IP. Over the limit
  is `429` with `Retry-After`. Every attempt counts, successful or not. Email buckets are keyed by a
  SHA-256 fingerprint, so addresses never appear in Redis key names. If Redis is unavailable the limiter
  fails open; the database-backed lockout below still applies.
- **Client IP is the TCP peer address.** `X-Forwarded-For` is not read directly (it is trivially
  spoofed); behind a reverse proxy, set `server.forward-headers-strategy` so the container resolves
  the real client address from a trusted proxy.
- **Lockout:** 10 wrong passwords in a row lock the account for 15 minutes. The counter and lock are
  updated in one atomic SQL statement (parallel failures cannot under-count) using the database clock.
  Reaching the limit restarts the counter, so after the lock expires it takes another 10 failures to
  lock again; a successful login resets it. Attempts during a lock do not extend it.
- **A locked account still answers "Invalid email or password".** A distinct "account locked" message
  would confirm that the email has an account. The trade-off is that a locked-out user sees the same
  message as a typo; the alert emails planned for Day 4 can tell them their account was locked.
- **Every login path does exactly one Argon2 verification** (unknown email: a dummy hash; locked account:
  the real hash, result ignored), so response time does not reveal whether an account exists or is
  locked. A test checks that unknown emails take between 0.5× and 2× as long as wrong passwords.
- **Known trade-off: lockout can be abused to lock someone out on purpose** (deliberate wrong
  passwords). The 5/min per-email limit means an attacker needs at least two minutes per 15-minute lock.
  Password reset (next step) works during a lock.
- **Tests:** the shared test profile turns the rate limits off (many tests sign up repeatedly from
  127.0.0.1); `AuthRateLimitIntegrationTest` turns them on in its own context.

### Email verification, password reset and account deletion (V7)

- **`EmailSender` with a `LoggingEmailSender` for now.** It logs each email including its link, so
  the flows can be exercised end to end without a provider. Those log lines contain live tokens, so it
  must be replaced (Day 4) before real users exist.
- **Tokens:** 256 random bits, URL-safe base64, sent only in the email. The database keeps only the
  SHA-256 (V7 `auth_tokens`), so a database leak does not expose usable links. A plain hash is enough
  because, unlike a password, the token is long and random. Using a token is one conditional
  `UPDATE … SET used_at = now() WHERE … used_at IS NULL AND expires_at > now() RETURNING user_id`, so
  it is single-use even under parallel requests, and expiry uses the database clock. Issuing a new
  token of the same kind invalidates the user's older unused ones (only the latest email's link works).
- **Verification:** a 24 h link is sent on sign-up. `POST /api/auth/verify` is public (the link may be
  opened on a device where the user is not logged in). Resending needs a login and is limited to
  3/hour per user. Unverified users can log in and look around, but creating a monitor returns
  `403 Email not verified` with instructions; it is the action that makes the server send requests on
  the user's behalf.
- **Duplicate sign-up sends the owner an email** ("you already have an account", with a reset link) and
  creates no token. The API response stays identical, so only the real owner of the address learns
  about the attempt.
- **Forgot password:** always `202` with the same message, also for unknown or malformed addresses.
  Limited to 3/hour per email (so nobody can flood an inbox) and 10/hour per IP. The reset link lasts
  1 hour.
- **Reset password:** the new password is validated *before* the token is used, so a too-short
  password does not burn the link. A successful reset sets the new hash, clears the failed-login counter
  and any lock (so a locked-out owner can always recover), marks the email verified (they just proved
  they read it), invalidates other reset links, and **deletes every session of the user** via Spring
  Session's per-user index.
- **Change password** needs the current password. It ends all of the user's *other* sessions and
  rotates the current session's id. Ordering matters: the other sessions are deleted while the current
  one still has its stored id, because Spring Session only renames a session in Redis when the response
  is committed.
- **Delete account** needs the password. In one transaction it deletes the user's monitor schedules
  (`job_schedules` rows; they are not owned via a foreign key) and then the user, which cascades to
  monitors, check results, incidents and tokens. Then it ends every session. Already-queued checks of the
  deleted monitors run as no-ops.
- **A wrong current password returns `403 Your password is incorrect`**, not the login-style 401: the
  caller is already authenticated, so there is no account enumeration to protect against, and a 401
  would make a browser app think the session had ended.

### Removing the dev user (V8)

- **No dev seeder at all, not even a local-only one.** With the logging email sender, a new developer
  can sign up, copy the verification link from `docker compose logs api` and log in within a minute,
  using exactly the code path real users take. A seeded account (especially one with a known password)
  is the kind of convenience that ends up switched on in the wrong environment, so it is not worth
  keeping. Tests create their own users directly.
- **V8 deletes password-less accounts** (the only ones were created by the old seeder) together with
  their monitors (cascade) and those monitors' schedules. Nobody could log in as them, but a password
  reset could have claimed one.
- **docker-compose runs the API with only the `api` profile** again.

### Sending email through Resend

- **Provider switch:** `pulsecheck.email.provider` is `logging` (default: local development and tests)
  or `resend`. One factory method (`EmailSenderConfig`) creates the sender and refuses to start when:
  - the provider is `logging` and `pulsecheck.app.base-url` is not a localhost URL. The logging sender
    writes working verification and reset links to the log, so it must never serve real users. The host
    is parsed, not string-matched, so `https://localhost.evil.example.com` does not pass;
  - the provider is `resend` without `RESEND_API_KEY` or without `EMAIL_FROM`.
- **`pulsecheck.app.base-url` replaces `pulsecheck.email.app-base-url`** (same `PULSECHECK_APP_BASE_URL`
  variable). It is where the web app lives, used for every link, not only email links.
- **Error classification** (`EmailSendException.isRetryable()`): 429, 5xx, 409 (Resend's "concurrent
  request with the same idempotency key"), timeouts and network errors are retryable; any other 4xx
  (validation error, unverified sender domain, bad API key) is not, because the same request would fail
  the same way. Non-retryable failures are logged at ERROR because they usually mean a configuration
  problem. The API key never appears in errors or logs, and email content is never logged by the Resend
  sender (it may contain one-time links).
- **No HTTP-level retries in the sender** (`retryOnConnectionFailure(false)`): retrying is the job
  scheduler's job, with backoff (next step). The email's idempotency key is passed as Resend's
  `Idempotency-Key` header, so a retry after an ambiguous failure (e.g. a timeout after Resend accepted
  the email) does not deliver it twice.
- **Templates:** every email has a plain-text and an HTML part built from the same content (heading,
  short paragraphs, one button, the same link as plain text under it). The HTML is a single centred
  table with inline CSS only (Gmail drops `<style>` blocks in many contexts), system fonts, a light
  background and no images. All dynamic text is HTML-escaped.
- **Interim:** emails are still sent inside the HTTP request in this step; a provider failure is
  logged instead of failing sign-up or forgot-password. The next step moves sending to the job
  scheduler.
- **Configuration:** `PULSECHECK_EMAIL_PROVIDER`, `RESEND_API_KEY`, `EMAIL_FROM` and
  `PULSECHECK_APP_BASE_URL` are passed to both the API and the workers (workers send emails from the
  next step on); placeholders are in `.env.example`.

### Emails as `SEND_EMAIL` jobs (V9)

- **Emails are jobs.** Sign-up, resend-verification, forgot-password and the "you already have an
  account" notice queue a `SEND_EMAIL` job; workers send it with the scheduler's retries and backoff
  (8 attempts, exponential backoff with jitter, capped by `worker.max-backoff-seconds`). The HTTP request
  never waits for Resend, so a slow or failing provider cannot slow down or break sign-up.
- **The jobs table is the outbox.** The job row is inserted in the same transaction as what the email is
  about (the new account and its token), and pushed to Redis after commit. A rollback means no email; a
  commit means the email will go out even if the process dies a millisecond later (the drift sweeper
  enqueues QUEUED rows Redis missed).
- **Idempotency:** each email job has a unique `idempotency_key`, inserted with `ON CONFLICT DO NOTHING`,
  and the worker passes the same key to Resend as `Idempotency-Key`. Keys:
  `email:verify:{tokenId}`, `email:reset:{tokenId}`, and `email:already-registered:{userId}:{yyyyMMddHH}`
  (so repeated sign-up attempts for an existing account produce at most one notice per hour).
- **The payload is a template, not a rendered email:** `{template, to, params, idempotencyKey, secret}`.
  The worker renders it at send time with the base URL from its own configuration. A one-time token
  travels only in `secret`, which makes redaction a single, well-defined field.
- **Redaction is a database trigger (V9).** Whenever a `SEND_EMAIL` job becomes SUCCEEDED, DEAD or
  CANCELLED, `secret` is removed from its payload and `"secretRedacted": true` is added. A trigger rather
  than application code because jobs reach terminal states through several paths (worker completion,
  reaper recovery, the bulk "attempts exhausted" update, cancellation, a manual SQL fix) and every one of
  them must redact. Only `SEND_EMAIL` payloads are touched. While a job is still pending or retrying it
  keeps the token, because the worker needs it to build the link; `auth_tokens` only ever stores hashes.
- **Consequences:** a finished email job cannot be re-sent by requeueing it. Its token is gone, so the
  handler sends it straight to DEAD ("already redacted"); the user asks for a new link instead. Pending
  email jobs (and their tokens) are visible through the scheduler's admin `/jobs` API, which requires the
  operator API key.
- **Failure handling:** retryable provider errors (429, 5xx, 409, timeouts) fail the job so it is retried;
  non-retryable ones (validation errors, unverified domain, bad key) and unrenderable payloads throw
  `NonRetryableJobException` and go straight to DEAD.
- **Tests** deliver queued email jobs through the real `SendEmailJobHandler` before reading the test
  inbox; a worker-profile end-to-end test runs real workers against a MockWebServer standing in for
  Resend (503 then 200 → one delivery after a retry; 422 → DEAD after one attempt).

### Keeping email payloads out of logs and errors

An audit of every place a `SEND_EMAIL` payload (which may hold a one-time token) could surface:

- **Worker logs and job errors:** `WorkerLoop` logs only ids and the failure message. `SendEmailJobHandler`
  never puts the payload, a parser message, or a parser exception into its errors: a malformed payload
  becomes `malformed SEND_EMAIL payload (<exception type>)` with no cause attached, because JSON parser
  messages can quote the input, and failure messages become `last_error` and log lines.
- **Postgres error details:** for constraint violations Postgres adds `DETAIL: Failing row contains (…)`
  with the whole row, payload included, and pgjdbc copies it into exception messages by default. The
  datasource sets `logServerErrorDetail=false`, so database exceptions (and any log line or 500 that
  prints them) keep the constraint name but not row contents.
- **Admin jobs API:** `/jobs`, `/jobs/{id}`, `/jobs/dead` etc. serialize a pending `SEND_EMAIL` job's
  `secret` as `[redacted]` (finished jobs no longer contain it at all, see V9). Other job types are
  unchanged.
- **Unchanged by design:** `LoggingEmailSender` logs rendered emails including links. That is its
  purpose, and it refuses to run unless the app is on localhost. Nothing else logs payloads: Reaper,
  sweeper and worker log lines carry ids only, MDC holds jobId/workerId/attempt, Redis holds only job ids.

### Password hashing outside transactions

Argon2id is deliberately slow and memory-hard; doing it inside a database transaction would hold a pooled
connection for the whole hash. Sign-up, reset-password, change-password and delete-account therefore hash
or verify first and then run a short `TransactionTemplate` block that only touches the database; ending
sessions (Redis) happens after commit. A test wraps the real encoder and fails if any hash or verification
runs while a transaction is active.

### DOWN/UP alert emails (V10)

- **Same transaction as the incident.** `MonitorCheckService` opens or resolves the incident and calls
  `AlertService` inside the same transaction (which holds the monitor's row lock); `AlertService` is
  `Propagation.MANDATORY` so it cannot be called outside one. The alert's email job is written in that
  transaction (the jobs table is the outbox), so an alert is never lost if the worker dies right after the
  check, and never sent for a transition that rolled back. If Redis misses the enqueue, the sweeper
  re-enqueues the QUEUED row.
- **Exactly once per transition.** `alert_events` has one row per (incident, DOWN|UP), unique, recording
  what was done (`SENT`, `PAUSED`, `SKIPPED`, `NO_RECIPIENT`). A transition that was already handled is
  ignored, and the email job's idempotency key is `alert:{incidentId}:down|up`. Retried check jobs never
  re-open an incident (the partial unique index), so they cannot cause a second alert either.
- **Recipients:** the owner's account email, only if verified (otherwise `NO_RECIPIENT`, logged).
- **Content:** site name and URL, times as "23 Nov 2026 at 14:05 UTC" (UTC with an explicit label, as
  users have no time-zone setting yet), the cause from the failing check, a "View monitor" button linking to
  `{app base URL}/monitors/{id}`, and for UP the downtime in words ("Back up after 12 minutes", measured from
  the incident's start, i.e. the first failed check).
- **Flapping cap: at most 6 alert emails per monitor in any rolling hour, counting the pause note.** The
  first 5 transitions in an hour get their alerts; the 6th gets an "alerts paused for this monitor for an
  hour" note *instead of* its alert; later transitions are skipped (recorded as `SKIPPED` and logged) until
  an hour after the note. Counting uses `alert_events` and the database clock; decisions are serialized by
  the monitor row lock. Configurable as `pulsecheck.alerts.max-emails-per-monitor-per-hour`.
- **Changing a monitor's URL** resolves its open incident without an UP alert: it says nothing about
  whether the old site recovered.

### Extra alert recipients with double opt-in (V11)

- **`alert_recipients` replaces `monitors.alert_emails`.** Each row is one address for one monitor with a status:
  `PENDING` (added by the owner, gets only the confirmation email), `CONFIRMED` (clicked the link, gets alerts)
  or `UNSUBSCRIBED`. Existing `alert_emails` were copied as `PENDING` (at most 5, never the owner's own address)
  because nobody had opted in; the owner can send them a confirmation with the resend endpoint. The API rejects
  the old `alertEmails` field instead of silently ignoring it.
- **API:** monitors show `notifyOwner` and `recipients` (id, email, status, timestamps).
  `POST /api/monitors/{id}/recipients` adds one, `POST .../recipients/{rid}/resend` sends a new confirmation
  (the old link stops working), `DELETE .../recipients/{rid}` removes one. Max 5 `PENDING` + `CONFIRMED` per
  monitor (unsubscribed rows are kept so the owner sees who left, and do not count); the monitor row is locked
  while adding so concurrent requests cannot exceed it. Re-adding an unsubscribed address makes it `PENDING`
  again: they must opt in again.
- **Tokens** live in `alert_recipient_tokens`: SHA-256 only, single-use (`used_at`, consumed atomically).
  Confirmation links expire after 72 h. Unsubscribe links do not expire (people unsubscribe from old emails),
  and **each alert email gets its own token**, minted when the alert is queued and carried in the job's `secret`
  (redacted by V9 once sent). Stored hashes can't be turned back into links, so a stable per-recipient token
  would have to be stored reversibly; a token per email avoids that.
- **Unsubscribe is per address, per monitor**, needs no login and is idempotent: using the same link again shows
  "Unsubscribed" without changing anything. Because links are single-use, a link used before the recipient
  re-subscribed no longer works (410). The owner can unsubscribe from one monitor too (their links have no
  recipient id); that sets `monitors.notify_owner = false`, which they can turn back on with
  `PATCH {"notifyOwner": true}`.
- **Links never act on GET.** `/alert-recipients/confirm` and `/alert-recipients/unsubscribe` show a page with a
  button that POSTs, because mail security scanners and link previews fetch links. Mail clients' own Unsubscribe
  button uses RFC 8058: every alert has `List-Unsubscribe: <url>` and
  `List-Unsubscribe-Post: List-Unsubscribe=One-Click`, and the POST endpoint accepts that request. These two
  paths have their own stateless security chain with CSRF off (the token is the credential; a one-click POST from
  a mail provider can't carry a CSRF token), `Cache-Control: no-store` and `Referrer-Policy: no-referrer`.
- **Recipients of an alert:** the owner (verified, `notify_owner`) plus `CONFIRMED` recipients. One email job per
  recipient: the owner's keeps the key `alert:{incidentId}:down|up`, others append `:{recipientId}`, so a bad
  address can't delay or fail the others' alerts. The flapping cap is per monitor, so each recipient gets at most
  6 alert emails an hour.
- **Confirmation email limits**: 10 per account per hour and 3 per address per day across all accounts, as Redis
  buckets (so deleting and re-adding a recipient doesn't reset them). Unlike the login limits, these **fail closed**
  (503) if Redis is down, since the emails go to people who never signed up.

### Web app: scaffold and design system (`web/`)

- **Stack:** React 19 + TypeScript (strict, plus `noUncheckedIndexedAccess`) + Vite 8, Tailwind CSS 4,
  React Router 8, TanStack Query 5, ESLint 10 (typescript-eslint `strictTypeChecked`) + Prettier, Vitest 5 +
  Testing Library.
- **Dependency versions are at least two weeks old, including transitive ones.** Direct dependencies are
  pinned exactly (`web/.npmrc` has `save-exact=true`), and installs were run with
  `npm install --before=<today minus 14 days>` so npm also resolves every transitive dependency to a version
  published before that date. Exceptions to "latest":
  - **TypeScript 6.0, not 7.** TypeScript 7 is the native (Go) compiler; typescript-eslint supports only
    TypeScript < 6.1 so far.
  - **jsdom 29, not 31.** jsdom 30+ require Node ≥ 24.15, and older Node 24 releases are still common locally.
  - **No `eslint-plugin-jsx-a11y`.** Its latest release supports ESLint ≤ 9. Accessibility is checked by the
    tests instead: they find elements by role and accessible name (`getByRole`, `getByLabelText`), so an
    unlabelled control fails its test.
  - **`overrides: { minimatch: "11.0.5" }`** fixes a moderate ReDoS advisory (GHSA-g3hv-75wg-m249) in
    ESLint's and typescript-eslint's dependency (dev tooling only). The upstream fix, typescript-eslint
    8.74, is too new; remove the override once it can be upgraded.
- **Dev proxy instead of CORS.** `npm run dev` serves on :5173 and Vite forwards `/api` to :8080, keeping
  the browser's `Host` (`changeOrigin: false`). The browser sees one origin, so the session and
  `XSRF-TOKEN` cookies are first-party and the CSRF flow is identical to production, where Spring Boot
  serves the built app. The backend needs no CORS configuration at all.
- **Colours:** the brand colour is indigo, deliberately neither green nor red, so a button or link is
  never mistaken for a status. Status colours (UP green-700, DOWN red-700, UNKNOWN/PAUSED slate) are
  never the only signal: `StatusBadge` always shows a word and an icon with a different *shape* per
  status (check-circle, warning triangle, question-circle, pause-circle), which works for colour-blind
  users and in greyscale. Every text colour is at least 4.5:1 on its background; input borders use
  `line-strong` (4.8:1) because WCAG 1.4.11 requires 3:1 for the edges of form controls, which the usual
  light-grey border does not meet.
- **System fonts**, no web font: nothing to download, no third-party request (simpler Content Security
  Policy later), and text looks native on every device.
- **Accessibility details:** 44 px minimum touch targets; 16 px input text (iOS Safari zooms into
  smaller inputs); one `:focus-visible` outline for every control; animations off under
  `prefers-reduced-motion`. Input hints sit between the label and the field (read before typing) and,
  like errors, are linked with `aria-describedby`; invalid fields get `aria-invalid`. `Alert` uses
  `role="alert"` for errors and warnings and `role="status"` for info and success.
- **A loading `Button` stays enabled** and uses `aria-disabled` + `aria-busy` instead of `disabled`: a
  disabled button drops keyboard focus to the page body mid-submit. Its click handler cancels the
  event while loading, which also blocks a double submit from pressing Enter in a field (browsers turn
  that into a click on the submit button).
- **`/styleguide`** shows every token and component, in development only: the route sits behind
  `import.meta.env.DEV`, which the production build replaces with `false`, so the page is not in the
  bundle (checked after building).
- **No source maps in the production build**: they would ship about 1.7 MB of source inside the jar.

### Web app: API client, auth state and layout

- **One API client** (`web/src/api/client.ts`); nothing else calls `fetch`. Every request sends
  `credentials: "include"`. Before each `POST`/`PATCH`/`PUT`/`DELETE` it reads the `XSRF-TOKEN` cookie *at
  that moment* and sends it as `X-XSRF-TOKEN`, so the token rotation at login and logout needs no
  bookkeeping. The app also calls `GET /api/auth/csrf` on start-up and after logout (and the login page
  will after login), as DESIGN's CSRF section asks. If the cookie is missing anyway, the client fetches
  one first; a 403 whose detail mentions CSRF (e.g. the cookie was cleared mid-session) gets one fresh
  token and one retry. Other 403s ("Your password is incorrect") are never retried.
- **Not every 401 means "session expired".** Login answers a wrong password with 401, and `GET
  /api/auth/me` answers "not logged in" with 401. Callers say so with `on401: "error"`; every other 401
  ends the session in the app: the cached user is set to `null` and every other cached query is removed
  (no private data stays in memory), and the route guard sends the user to `/login?next=<page>` with
  "Your session has ended. Please log in again." A logout that gets 401 counts as a successful logout.
- **Why the guard decides the redirect, not the API client.** Two things react to an ended session: the
  cached user turning `null`, and a navigation. If both triggered navigations they would race (e.g. a
  voluntary logout being redirected to `/login?next=/dashboard`). Instead, `auth/session.ts` records *why*
  the session ended (`expired` or `logged-out`) and `RequireAuth` is the only place that redirects,
  choosing the target and message from that reason. The login page clears the reason.
- **`?next=` survives a reload** (unlike router state) and is checked by `safeNextPath`: only paths
  starting with a single `/` are accepted, so `//evil.example` or `/\evil.example`, which browsers treat as
  another site, cannot turn the login page into an open redirect.
- **Error messages:** the backend's `detail` is shown when it is written for people (most are, e.g. "This
  link is invalid or has expired. Please request a new one."). Details that look technical (CSRF
  instructions, "Request validation failed", framework messages) are replaced by a plain fallback for the
  status, 5xx is always "Something went wrong on our side…", and a failed `fetch` is "We couldn't reach
  PulseCheck…". 429 uses `Retry-After`: "Please try again in N seconds", or minutes from 90 seconds up
  (resend-verification allows 3 per hour, so waits can be 20 minutes).
- **Queries retry only what may pass on its own** (network errors, 5xx; twice). 4xx answers and mutations
  are never retried automatically.
- **Layout:** a skip link, the header (logo, account menu) and, for unverified users, a "Please verify
  your email" banner with "Resend link" on every logged-in page. The account menu is a disclosure
  (`aria-expanded` button and a panel) rather than an ARIA `menu`, which would oblige full arrow-key menu
  semantics for two items; it closes on Escape (returning focus to the button) and on outside clicks.
  Each page sets the tab title (`usePageTitle`), the first thing screen readers announce after
  navigation.
