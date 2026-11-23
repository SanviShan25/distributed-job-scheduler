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
