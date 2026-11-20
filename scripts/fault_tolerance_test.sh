#!/usr/bin/env bash
set -o pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
JOB_COUNT="${JOB_COUNT:-4}"
JOB_DELAY_MS="${JOB_DELAY_MS:-20000}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-240}"
# docker: SIGKILL the worker container automatically (simulates a crash).
# manual: you hard-kill the worker yourself (e.g. kill -9 <pid> for an IDE-launched worker).
# A graceful stop is NOT a crash: workers drain in-flight jobs on SIGTERM, so nothing would be recovered.
KILL_MODE="${KILL_MODE:-docker}"
API_KEY="${API_KEY:-}"
BASE_URL="${BASE_URL%/}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

for tool in curl jq; do
  command -v "$tool" >/dev/null 2>&1 || fail "required command not found: $tool"
done
command -v docker >/dev/null 2>&1 || fail "required command not found: docker (needed to check Redis worker heartbeats)"
[[ -n "$API_KEY" ]] || fail "set API_KEY to one of the keys in API_KEYS (.env)"
[[ "$KILL_MODE" == "docker" || "$KILL_MODE" == "manual" ]] || fail "KILL_MODE must be docker or manual"

api() {
  curl --silent --show-error --fail -H "X-API-Key: $API_KEY" "$@"
}

read_live_worker_ids() {
  docker compose -f "$PROJECT_DIR/docker-compose.yml" exec -T redis \
    sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "worker:*"'
}

[[ "$JOB_COUNT" =~ ^[1-9][0-9]*$ ]] || fail "JOB_COUNT must be a positive integer"
[[ "$JOB_DELAY_MS" =~ ^[1-9][0-9]*$ ]] || fail "JOB_DELAY_MS must be a positive integer"
[[ "$TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail "TIMEOUT_SECONDS must be a positive integer"

live_worker_keys="$(read_live_worker_ids 2>/dev/null)" \
  || fail "could not check worker heartbeats with docker compose; start the Redis service and retry"
live_worker_ids=()
if [[ -n "$live_worker_keys" ]]; then
  while IFS= read -r worker_key; do
    [[ -n "$worker_key" ]] && live_worker_ids+=("${worker_key#worker:}")
  done <<< "$live_worker_keys"
fi
if [[ "${#live_worker_ids[@]}" -lt 2 ]]; then
  fail "Need at least 2 live workers; start Worker 1 and Worker 2"
fi
printf 'Live workers: %s\n' "${live_worker_ids[*]}"

stats_json="$(api "$BASE_URL/jobs/stats")" || fail "could not read baseline stats from $BASE_URL/jobs/stats"
baseline_succeeded="$(jq -er '(.byStatus.SUCCEEDED // 0) | tonumber' <<<"$stats_json")" \
  || fail "baseline stats did not contain a numeric byStatus.SUCCEEDED count"
baseline_worker_counts="$(jq -c '.succeededByWorker // []' <<<"$stats_json")" \
  || fail "could not read baseline succeededByWorker counts"

printf 'Baseline SUCCEEDED: %s\n' "$baseline_succeeded"
printf 'Baseline succeededByWorker: %s\n' "$baseline_worker_counts"

job_ids=()
for ((i = 1; i <= JOB_COUNT; i++)); do
  response="$(api \
    -H 'Content-Type: application/json' \
    -d "{\"type\":\"EMAIL\",\"payload\":\"delayMs=$JOB_DELAY_MS\",\"priority\":1}" \
    "$BASE_URL/jobs")" || fail "job submission $i/$JOB_COUNT failed"
  job_id="$(jq -er '.id | strings | select(length > 0)' <<<"$response")" \
    || fail "job submission $i/$JOB_COUNT returned no id"
  job_ids+=("$job_id")
done

printf '\nSubmitted %s jobs:\n' "$JOB_COUNT"
printf '  %s\n' "${job_ids[@]}"
sleep 2

printf '\nCurrently RUNNING jobs:\n'
running_found=0
running_workers=()
for job_id in "${job_ids[@]}"; do
  response="$(api "$BASE_URL/jobs/$job_id")" \
    || fail "could not inspect submitted job $job_id"
  status="$(jq -r '.status // "UNKNOWN"' <<<"$response")"
  if [[ "$status" == "RUNNING" ]]; then
    running_found=1
    worker_id="$(jq -r '.workerId // "unknown"' <<<"$response")"
    printf '  %s workerId=%s\n' "$job_id" "$worker_id"
    running_workers+=("$worker_id")
  fi
done
if [[ "$running_found" -eq 0 ]]; then
  printf '  No submitted jobs are RUNNING yet.\n'
  printf 'No worker can be recommended yet; stop a worker only after one of these jobs is RUNNING.\n'
else
  unique_workers=()
  for worker_id in "${running_workers[@]}"; do
    already_listed=0
    for existing_worker in "${unique_workers[@]}"; do
      if [[ "$worker_id" == "$existing_worker" ]]; then
        already_listed=1
        break
      fi
    done
    if [[ "$already_listed" -eq 0 ]]; then
      unique_workers+=("$worker_id")
    fi
  done

  most_running_worker=""
  most_running_count=0
  for candidate_worker in "${unique_workers[@]}"; do
    candidate_count=0
    for running_worker in "${running_workers[@]}"; do
      [[ "$running_worker" == "$candidate_worker" ]] && ((candidate_count += 1))
    done
    if [[ "$candidate_count" -gt "$most_running_count" ]]; then
      most_running_count="$candidate_count"
      most_running_worker="$candidate_worker"
    fi
  done
  printf 'Worker holding the most RUNNING jobs: %s (%d job(s)).\n' \
    "$most_running_worker" "$most_running_count"
fi

if [[ "$KILL_MODE" == "docker" ]]; then
  [[ "$running_found" -eq 1 ]] || fail "no submitted job is RUNNING yet; rerun with a larger JOB_DELAY_MS"
  # In Compose the worker id is the container hostname, i.e. the short container id.
  printf '\nSending SIGKILL to worker container %s (simulated crash)...\n' "$most_running_worker"
  docker kill --signal KILL "$most_running_worker" >/dev/null \
    || fail "could not kill container $most_running_worker (is this a Compose worker?)"
else
  printf '\nHard-kill the recommended worker now (kill -9 <pid>; a graceful stop lets it finish its jobs).\n'
  printf 'Press ENTER once it is dead.\n'
  IFS= read -r _ || fail "no ENTER received; rerun the test interactively"
fi

if ! live_worker_keys="$(read_live_worker_ids 2>/dev/null)"; then
  printf 'WARNING: could not check Redis heartbeats after the stop prompt; jobs may not complete if no worker is alive.\n' >&2
else
  live_worker_count=0
  if [[ -n "$live_worker_keys" ]]; then
    while IFS= read -r worker_key; do
      [[ -n "$worker_key" ]] && ((live_worker_count += 1))
    done <<< "$live_worker_keys"
  fi
  if [[ "$live_worker_count" -eq 0 ]]; then
    printf 'WARNING: no worker is alive; jobs cannot complete until a worker is started.\n' >&2
  else
    printf 'Workers still alive after stop: %s\n' "$live_worker_keys"
  fi
fi

deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
job_statuses=()
job_workers=()
job_attempts=()
job_errors=()

while :; do
  queued_count=0
  running_count=0
  succeeded_count=0
  dead_count=0
  terminal_count=0
  job_statuses=()
  job_workers=()
  job_attempts=()
  job_errors=()

  for job_id in "${job_ids[@]}"; do
    response="$(api "$BASE_URL/jobs/$job_id")" \
      || fail "could not poll submitted job $job_id"
    status="$(jq -r '.status // "UNKNOWN"' <<<"$response")"
    worker_id="$(jq -r '.workerId // "null"' <<<"$response")"
    attempts="$(jq -r '.attempts // 0' <<<"$response")"
    last_error="$(jq -r '.lastError // "null"' <<<"$response")"
    job_statuses+=("$status")
    job_workers+=("$worker_id")
    job_attempts+=("$attempts")
    job_errors+=("$last_error")

    case "$status" in
      QUEUED) ((queued_count += 1)) ;;
      RUNNING) ((running_count += 1)) ;;
      SUCCEEDED) ((succeeded_count += 1)); ((terminal_count += 1)) ;;
      DEAD|CANCELLED) ((dead_count += 1)); ((terminal_count += 1)) ;;
    esac
  done

  printf 'Progress: QUEUED=%d RUNNING=%d SUCCEEDED=%d DEAD=%d (total=%d)\n' \
    "$queued_count" "$running_count" "$succeeded_count" "$dead_count" "$JOB_COUNT"

  [[ "$terminal_count" -eq "$JOB_COUNT" ]] && break
  now="$(date +%s)"
  [[ "$now" -ge "$deadline" ]] && break
  sleep 3
done

printf '\nFinal submitted job results:\n'
for ((i = 0; i < ${#job_ids[@]}; i++)); do
  printf '  id=%s status=%s workerId=%s attempts=%s lastError=%s\n' \
    "${job_ids[$i]}" "${job_statuses[$i]}" "${job_workers[$i]}" \
    "${job_attempts[$i]}" "${job_errors[$i]}"
done

printf '\nRecovered jobs (lastError contains "worker lost"/"lease expired" or attempts > 1):\n'
recovered_found=0
recovered_count=0
invalid_retry_found=0
for ((i = 0; i < ${#job_ids[@]}; i++)); do
  if [[ "${job_errors[$i]}" == *"worker lost"* || "${job_errors[$i]}" == *"lease expired"* ]] \
      || [[ "${job_attempts[$i]}" =~ ^[0-9]+$ && "${job_attempts[$i]}" -gt 1 ]]; then
    recovered_found=1
    ((recovered_count += 1))
    printf '  %s\n' "${job_ids[$i]}"
  fi
  if [[ "${job_attempts[$i]}" =~ ^[0-9]+$ && "${job_attempts[$i]}" -gt 1 \
      && "${job_errors[$i]}" != *"worker lost"* && "${job_errors[$i]}" != *"lease expired"* ]]; then
    invalid_retry_found=1
    printf '  ERROR: %s has attempts=%s without a recovery marker\n' \
      "${job_ids[$i]}" "${job_attempts[$i]}" >&2
  fi
done
if [[ "$recovered_found" -eq 0 ]]; then
  printf '  None\n'
fi

new_stats="$(api "$BASE_URL/jobs/stats")" \
  || fail "could not read final stats from $BASE_URL/jobs/stats"
new_succeeded="$(jq -er '(.byStatus.SUCCEEDED // 0) | tonumber' <<<"$new_stats")" \
  || fail "final stats did not contain a numeric byStatus.SUCCEEDED count"
expected_succeeded=$((baseline_succeeded + JOB_COUNT))
printf '\nFinal /jobs/stats:\n'
jq . <<<"$new_stats"

if [[ "$terminal_count" -ne "$JOB_COUNT" ]]; then
  if [[ "$running_count" -gt 0 ]]; then
    fail "stuck RUNNING job(s) at timeout: $running_count still RUNNING after ${TIMEOUT_SECONDS}s"
  fi
  fail "timeout: only $terminal_count of $JOB_COUNT submitted jobs reached SUCCEEDED or DEAD within ${TIMEOUT_SECONDS}s"
fi

if [[ "$succeeded_count" -ne "$JOB_COUNT" ]]; then
  fail "wrong count: $dead_count submitted job(s) ended DEAD; expected all $JOB_COUNT to SUCCEED"
fi

if [[ "$invalid_retry_found" -eq 1 ]]; then
  fail 'one or more jobs had attempts > 1 without a recovery marker'
fi

if [[ "$recovered_count" -eq 0 ]]; then
  printf 'INCONCLUSIVE: no job was interrupted. Kill a worker while it has a RUNNING job\n'
  exit 2
fi

if [[ "$new_succeeded" -ne "$expected_succeeded" ]]; then
  fail "wrong count: SUCCEEDED total is $new_succeeded; expected $expected_succeeded (baseline $baseline_succeeded + $JOB_COUNT)"
fi

printf 'PASS\n'
exit 0
