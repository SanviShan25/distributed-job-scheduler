#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
JOB_COUNT="${JOB_COUNT:-1000}"
JOB_DELAY_MS="${JOB_DELAY_MS:-200}"
CONCURRENCY="${CONCURRENCY:-20}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-600}"
BASE_URL="${BASE_URL%/}"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

for tool in curl jq xargs seq awk; do
  command -v "$tool" >/dev/null 2>&1 || fail "required command not found: $tool"
done
[[ "$JOB_COUNT" =~ ^[1-9][0-9]*$ ]] || fail "JOB_COUNT must be a positive integer"
[[ "$JOB_DELAY_MS" =~ ^[0-9]+$ ]] || fail "JOB_DELAY_MS must be a non-negative integer"
[[ "$CONCURRENCY" =~ ^[1-9][0-9]*$ ]] || fail "CONCURRENCY must be a positive integer"
[[ "$TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail "TIMEOUT_SECONDS must be a positive integer"

baseline_stats="$(curl --silent --show-error --fail "$BASE_URL/jobs/stats")" \
  || fail "could not read baseline stats from $BASE_URL/jobs/stats"
baseline_succeeded="$(jq -er '(.byStatus.SUCCEEDED // 0) | tonumber' <<<"$baseline_stats")" \
  || fail "baseline stats did not contain a numeric byStatus.SUCCEEDED count"
baseline_dead="$(jq -er '(.byStatus.DEAD // 0) | tonumber' <<<"$baseline_stats")" \
  || fail "baseline stats did not contain a numeric byStatus.DEAD count"
baseline_terminal=$((baseline_succeeded + baseline_dead))

response_file="$(mktemp "${TMPDIR:-/tmp}/jobscheduler-load.XXXXXX")"
trap 'rm -f "$response_file"' EXIT
export BASE_URL JOB_DELAY_MS

start_seconds="$(date +%s)"
if ! seq 1 "$JOB_COUNT" | xargs -P "$CONCURRENCY" -I '{}' sh -c '
  priority=$((($1 % 5) + 1))
  curl --silent --show-error --fail \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"EMAIL\",\"payload\":\"delayMs=${JOB_DELAY_MS}\",\"priority\":${priority}}" \
    "${BASE_URL}/jobs"
  printf "\n"
' _ '{}' > "$response_file"; then
  fail "one or more job submissions failed"
fi
end_seconds="$(date +%s)"

submitted_count="$(jq -s '[.[] | select(.id != null)] | length' "$response_file")"
[[ "$submitted_count" -eq "$JOB_COUNT" ]] || fail "submitted $submitted_count of $JOB_COUNT jobs"
submission_seconds=$((end_seconds - start_seconds))
(( submission_seconds > 0 )) || submission_seconds=1
submission_rate="$(awk -v count="$submitted_count" -v elapsed="$submission_seconds" 'BEGIN { printf "%.2f", count / elapsed }')"

printf 'Submitted %d jobs in %d seconds (%.2f submissions/sec).\n' \
  "$submitted_count" "$submission_seconds" "$submission_rate"
printf 'Job payload delay: %s ms; concurrent requests: %s.\n' "$JOB_DELAY_MS" "$CONCURRENCY"

deadline=$((start_seconds + TIMEOUT_SECONDS))
terminal_growth=0
while :; do
  current_stats="$(curl --silent --show-error --fail "$BASE_URL/jobs/stats")" \
    || fail "could not poll stats from $BASE_URL/jobs/stats"
  current_succeeded="$(jq -er '(.byStatus.SUCCEEDED // 0) | tonumber' <<<"$current_stats")" \
    || fail "stats response did not contain a numeric byStatus.SUCCEEDED count"
  current_dead="$(jq -er '(.byStatus.DEAD // 0) | tonumber' <<<"$current_stats")" \
    || fail "stats response did not contain a numeric byStatus.DEAD count"
  current_terminal=$((current_succeeded + current_dead))
  terminal_growth=$((current_terminal - baseline_terminal))
  (( terminal_growth >= 0 )) || terminal_growth=0
  printf 'Processing progress: %d/%d terminal jobs (SUCCEEDED=%d DEAD=%d)\n' \
    "$terminal_growth" "$JOB_COUNT" "$current_succeeded" "$current_dead"

  if (( terminal_growth >= JOB_COUNT )); then
    completed_seconds="$(date +%s)"
    break
  fi
  now="$(date +%s)"
  if (( now >= deadline )); then
    fail "timeout after ${TIMEOUT_SECONDS}s: only ${terminal_growth}/${JOB_COUNT} terminal jobs were added"
  fi
  sleep 3
done


end_to_end_seconds=$((completed_seconds - start_seconds))
(( end_to_end_seconds > 0 )) || end_to_end_seconds=1
end_to_end_throughput="$(awk -v count="$JOB_COUNT" -v elapsed="$end_to_end_seconds" 'BEGIN { printf "%.2f", count / elapsed }')"
printf 'End-to-end processing time: %d seconds.\n' "$end_to_end_seconds"
printf 'End-to-end throughput: %d jobs / %d seconds = %s jobs/sec.\n' \
  "$JOB_COUNT" "$end_to_end_seconds" "$end_to_end_throughput"
