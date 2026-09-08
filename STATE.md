# SILKROUTE STATE

current_phase: 0
next_actions:
  1. Phase 1 kickoff: install JDK 17 + Maven wrapper; author WSDLs/XSDs first (OrderService, InventoryService, PricingService, typed faults)
  2. Phase 1: implement Spring Boot + Apache CXF services against those WSDLs (WSDL-first, contract frozen immediately after tests pass — C6 begins)
  3. Phase 1: seed data (50 SKUs, 8 stores, order generator) + Karate SOAP contract tests incl. fault paths
open_risks:
  - System Java is 11; Spring Boot 3.x needs 17+ → provision JDK 17 before Phase 1 (apt or tarball; record in session log)
  - No Maven/Gradle on host → repo must carry Maven Wrapper (mvnw) so builds self-provision
  - Docker daemon is rootless (user-level) — fine for sim mode; document `docker context use rootless` in README quickstart
  - No AliCloud account currently available → Phase 4 will run in validated-plans mode per ADR-0002 unless an account materializes
evidence_rows_added: [E-001, E-002]

## Session Log

### S1 — 2026-09-08
- slice: Phase 0 — Foundation (scaffolding, state machinery, mode decision, sim compose skeleton)
- done: git repo + full §3 tree; ROADMAP/STATE/EVIDENCE machinery; ADR-0001 (dual-mode sim/cloud); ADR-0002 (sim-only now, validated-plans for Phase 4); docs/scenario-charter.md; docker-compose.yml sim network (mysql/kafka/redis/minio/toxiproxy, healthchecked); Makefile (up/down/ps/logs); README; CI scaffold; .gitignore; first commits
- measured: `make up` → 5/5 services healthy on rootless Docker 29.8.0 (E-001); clean `down -v` → re-up reproducible <90s (E-002)
- war stories: system Docker daemon runs but can't be started (no passwordless sudo); rootless dockerd was already alive at unix:///run/user/1000/docker.sock — first `dockerd-rootless.sh` attempt hit a RootlessKit lock collision proving another instance held the state dir; fixed by creating a persistent `docker context use rootless` instead of exporting DOCKER_HOST per shell
- handoff: WSDLs not yet authored — Phase 1 session starts with contract-first authoring, NOT Java code; remember to record JDK 17 setup as part of S2 log

## Working agreements (from MASTER_PROMPT.md — reminders)
- One session = one phase-slice. Update this file BEFORE committing, at session end, or immediately if context runs low.
- Critic Gate (§5) before any phase is marked DONE. Critic is always a fresh agent.
- Verify before recording: run it yourself, then write the EVIDENCE row.
- Never fabricate evidence. An honest "not yet verified" beats an invented green check.
