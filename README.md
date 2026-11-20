# Distributed Job Scheduler

## Docker Compose quick start

Build and start PostgreSQL, Redis, the API, Prometheus, Grafana, and three scalable worker containers:

```bash
docker compose up -d --build --scale worker=3
```

The API is available at [http://localhost:8080](http://localhost:8080), Prometheus at [http://localhost:9090](http://localhost:9090), and Grafana at [http://localhost:3000](http://localhost:3000). Grafana provisions the Prometheus data source and the **Job Scheduler** dashboard automatically (default local Grafana credentials: `admin` / `admin`).

The Compose database is published on host port `5433`; containers connect to `postgres:5432` on the Compose network. Each worker uses four job-processing threads by default; set `WORKER_CONCURRENCY` to change this for Compose workers. Local VS Code runs also default to four threads and can set `WORKER_CONCURRENCY` in the launch environment.

For a concurrent submission load, run `./scripts/load_test.sh`. It records the baseline SUCCEEDED/DEAD counts, submits 1,000 EMAIL jobs by default with mixed priorities and a 200 ms handler delay, then waits for that many additional terminal jobs (up to 600 seconds). It reports submission rate, end-to-end processing time, and end-to-end throughput. Override `JOB_COUNT`, `JOB_DELAY_MS`, `CONCURRENCY`, `TIMEOUT_SECONDS`, or `BASE_URL` as needed.

## Fault tolerance test

Start the API and both workers in VS Code, and make sure PostgreSQL and Redis are running. Then run this interactive crash-recovery test from the project root:

```bash
./scripts/fault_tolerance_test.sh
```

The script submits four EMAIL jobs with a 20-second delay, identifies the worker holding the most submitted RUNNING jobs, and asks you to stop that worker using VS Code's red stop button. It then monitors recovery until the jobs finish or the timeout expires. `PASS` requires every submitted job to reach `SUCCEEDED`, at least one job to show recovery, no job with multiple attempts to be missing the `worker lost` marker, and the overall `SUCCEEDED` count to increase by exactly the number submitted. If all jobs succeed but none show recovery, the script prints `INCONCLUSIVE` and exits with status 2.

Optional environment variables: `BASE_URL` (default `http://localhost:8080`), `JOB_COUNT` (default `4`), `JOB_DELAY_MS` (default `20000`), and `TIMEOUT_SECONDS` (default `240`). The script requires `curl` and `jq`.
