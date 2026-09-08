# SILKROUTE STATE

current_phase: 0
next_actions:
  1. Phase 1 kickoff: install JDK 17 + Maven wrapper; author WSDLs/XSDs first (OrderService, InventoryService, PricingService, typed faults)
  2. Phase 1: implement Spring Boot + Apache CXF services against those WSDLs (WSDL-first, contract frozen immediately after tests pass — C6 begins)
  3. Phase 1: seed data (50 SKUs, 8 stores, order generator) + Karate SOAP contract tests incl. fault paths
open_risks:
  - System Java is 11; Spring Boot 3.x needs 17+ → provision JDK 17 before Phase 1 (apt or tarball; record in session log)
  - No Maven/Gradle on host → repo must carry Maven Wrapper (mvnw) so builds self-provision
  - Docker daemon is rootless (user-level) — fine for sim mode; `docker context use rootless` documented in README
  - No AliCloud account currently available → Phase 4 will run in validated-plans mode per ADR-0002 unless an account materializes
  - CI workflow (.github/workflows/ci.yml) is UNVERIFIED — no git remote exists yet; steps were validated locally only. Owner must push to a remote; first green Actions run becomes an evidence row.
evidence_rows_added: [E-001, E-002]

## Session Log

### S1 — 2026-09-08
- slice: Phase 0 — Foundation (scaffolding, state machinery, mode decision, sim compose skeleton)
- done: git repo + full §3 tree committed (dirs carried via .gitkeep); ROADMAP/STATE/EVIDENCE machinery; ADR-0001 (dual-mode sim/cloud); ADR-0002 (sim-only now, validated-plans for Phase 4); docs/scenario-charter.md; docker-compose.yml sim network (mysql/kafka/redis/minio/toxiproxy, healthchecked, 127.0.0.1-only host binds, .env-indirected sim credentials); Makefile (up/down/ps/logs/smoke with jq guard + 5-service assertions); README; CI scaffold (unverified, no remote yet); .gitignore; commits
- measured: clean start converged in 28s — mysql/kafka/redis/minio `(healthy)`, toxiproxy Up with NO healthcheck (distroless image, in-container healthcheck impossible; readiness = API answering :8474/version) → E-001; repro `down -v → up` 33s + 5-service smoke green → E-002
- war stories: (1) system Docker daemon can't be started (no passwordless sudo); rootless dockerd was already alive at unix:///run/user/1000/docker.sock — first `dockerd-rootless.sh` attempt hit a RootlessKit lock collision proving another instance held the state dir; fixed with persistent `docker context use rootless`. (2) `shopify/toxiproxy:2.11.0` doesn't exist on Docker Hub (tags stop at 2.1.4) — GHCR carries the new releases; and GHCR images are distroless, so a `wget` healthcheck can't work — readiness must be asserted from the host side. (3) Port 6379 collides with a pre-existing local Redis → sim remaps host port to 16379 (container port unchanged).
- handoff: WSDLs not yet authored — Phase 1 session starts with contract-first authoring, NOT Java code; record JDK 17 setup in the S2 log
- critic: cycle 1 FAIL 7.80 (evidence row not bound to command/artifact; tree not in git; STATE over-claims; credential/port hygiene; jq undeclared; CI unverified) — all fixed same session, see ROADMAP gate log

## Working agreements (from MASTER_PROMPT.md — reminders)
- One session = one phase-slice. Update this file BEFORE committing, at session end, or immediately if context runs low.
- Critic Gate (§5) before any phase is marked DONE. Critic is always a fresh agent.
- Verify before recording: run it yourself, then write the EVIDENCE row. Row claim = reproduce command = artifact, 1:1.
- Never fabricate evidence. An honest "not yet verified" beats an invented green check.
