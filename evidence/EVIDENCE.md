# SILKROUTE EVIDENCE LEDGER

The spine of the project. **No claim without a row.** Every row must have a command a stranger can run and a measured result actually observed on this machine. Subagent claims are not evidence — ORCH-LEAD re-runs and records.

Row format:

```
| ID | claim | artifact | reproduce command | measured result |
```

Measurement honesty rules:
- Note the mode (sim/cloud) and the hardware context for perf numbers.
- Never edit a measured number after the fact; supersede with a new row.
- If a claim is currently unverifiable (e.g., no AliCloud account), the row must say so explicitly and point to the honest substitute (e.g., `terraform validate` output).

## Ledger

| ID | claim | artifact | reproduce command | measured result |
|---|---|---|---|---|
| E-001 | Sim-mode infrastructure network starts from a clean state and converges (MySQL 8, Kafka 3.8 KRaft dual-listener, Redis 7, MinIO RELEASE.2025-09-07, Toxiproxy 2.11.0) | docker-compose.yml, Makefile | `make down && make up && make ps && timeout 3 bash -c '</dev/tcp/127.0.0.1/39092'` | 2026-09-08: converged in ~28s — mysql/kafka/redis/minio `(healthy)`, toxiproxy Up with no healthcheck (distroless; readiness = API on :8474). Host binds 127.0.0.1-only; kafka HOST listener accepting on 127.0.0.1:39092 (probe output in artifact tail; as of 2026-09-09 the same probe lives in scripts/smoke.sh so every `make smoke` re-asserts it). Rootless Docker 29.8.0, host potato-linux. See evidence/runs/E-001-compose-ps.txt |
| E-002 | Sim network is reproducible end-to-end from a clean state and ALL 5 services pass authenticated connectivity assertions | docker-compose.yml, scripts/smoke.sh | `make down && make up && make smoke` | 2026-09-08: re-up converged, then `smoke OK (all 5 services) in 2s` — redis PING→PONG; mysqladmin alive + `SELECT VERSION()`=8.0.46 authenticated via container env; kafka broker-api on :9092 (in-container); minio /minio/health/live; toxiproxy /version=2.11.0. See evidence/runs/E-002-smoke.txt |
| E-003 | `make smoke` is falsifiable: it exits non-zero when a service is down (not a always-green check) | scripts/smoke.sh | `docker compose stop redis && make smoke; docker compose start redis && make smoke` | 2026-09-08: with redis stopped → `FAIL: redis PING did not return PONG`, exit 2; after `docker compose start redis` → `smoke OK (all 5 services) in 1s`, exit 0. See evidence/runs/E-003-smoke-negative-control.txt |
