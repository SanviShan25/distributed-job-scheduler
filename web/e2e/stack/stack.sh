#!/usr/bin/env bash
# The end-to-end test stack: Postgres, Redis, the API (serving the web app) and a worker, built from this
# checkout, as its own Compose project with its own settings (e2e.env), so it never touches your .env or
# your development stack's data.
#
#   stack.sh up      build and start it from empty data, then wait until http://localhost:8080 answers
#   stack.sh down    stop it and delete its data
#   stack.sh logs    the API and worker logs
#   stack.sh psql    run SQL from stdin against its database (used to seed screenshot data)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
compose=(docker compose --project-name pulsecheck-e2e --project-directory "$root"
  --env-file "$here/e2e.env" -f "$root/docker-compose.yml" -f "$here/compose.e2e.yml")
url="${E2E_BASE_URL:-http://localhost:8080}"

case "${1:-}" in
  up)
    # Always from empty data: leftover demos and rate-limit counters from earlier runs would change results.
    "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
    "${compose[@]}" up --detach --build postgres redis api worker
    echo "Waiting for $url ..."
    for _ in $(seq 1 120); do
      if curl --silent --fail "$url/api/config" >/dev/null; then
        echo "The stack is up at $url"
        exit 0
      fi
      sleep 2
    done
    echo "The stack did not come up in 4 minutes. API log:" >&2
    "${compose[@]}" logs --tail 100 api >&2
    exit 1
    ;;
  down)
    "${compose[@]}" down --volumes --remove-orphans
    ;;
  logs)
    "${compose[@]}" logs api worker
    ;;
  psql)
    "${compose[@]}" exec -T postgres psql --quiet --set ON_ERROR_STOP=1 -U jobs -d jobs
    ;;
  *)
    echo "usage: $0 up|down|logs|psql" >&2
    exit 2
    ;;
esac
