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
| E-001 | Sim-mode infrastructure network starts from clean state and all services converge (MySQL 8, Kafka KRaft, Redis 7, MinIO, Toxiproxy) | docker-compose.yml, Makefile | `make down && make up && make ps` | 2026-09-08: clean start converged in 28s — mysql/kafka/redis/minio `(healthy)`, toxiproxy Up (distroless image, no in-container healthcheck possible; API verified on :8474). Rootless Docker 29.8.0, host potato-linux. See evidence/runs/E-001-compose-ps.txt |
| E-002 | Sim network is reproducible end-to-end from a clean state (down -v wipes volumes, up re-converges) + connectivity smoke | Makefile | `make down && make up && make smoke` | 2026-09-08: re-up 33s wall time, 5/5 Up, then verified live: `SELECT VERSION()` → 8.0.46, redis-cli PING → PONG, GET :8474/version → 2.11.0. See evidence/runs/E-002-reup-ps.txt |
