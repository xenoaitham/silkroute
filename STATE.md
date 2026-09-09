# SILKROUTE STATE

current_phase: 0
next_actions:
  1. S2 START: spawn a FRESH CRITIC for Phase 0 cycle 3 (spawn failed twice on 09-09: quota/model errors — gate is OPEN, phase stays REVIEW until PASS). Prompt template: §5 with cycle-1/2 history from ROADMAP gate log.
  2. Phase 1 kickoff (after gate PASS): install JDK 17 + Maven wrapper; author WSDLs/XSDs first (OrderService, InventoryService, PricingService, typed faults)
  3. Phase 1: implement Spring Boot + Apache CXF services against those WSDLs (WSDL-first, contract frozen immediately after tests pass — C6 begins)
  4. Phase 1: seed data (50 SKUs, 8 stores, order generator) + Karate SOAP contract tests incl. fault paths
open_risks:
  - CRITIC gate for Phase 0 is OPEN (cycle 3 pending) — do NOT start Phase 1 build work before a fresh-agent PASS
  - System Java is 11; Spring Boot 3.x needs 17+ → provision JDK 17 before Phase 1 (apt or tarball; record in session log)
  - No Maven/Gradle on host → repo must carry Maven Wrapper (mvnw) so builds self-provision
  - Docker daemon is rootless (user-level) and does NOT auto-restart containers after a daemon/host restart (no restart policies on sim) — run `make up` to restore; forwards may need full down/up (see S1-resume war story)
  - No AliCloud account currently available → Phase 4 will run in validated-plans mode per ADR-0002 unless an account materializes
  - CI workflow (.github/workflows/ci.yml) is UNVERIFIED — no git remote exists yet; steps were validated locally only. Owner must push to a remote; first green Actions run becomes an evidence row.
evidence_rows_added: [E-001, E-002, E-003]

## Session Log

### S1 — 2026-09-08
- slice: Phase 0 — Foundation (scaffolding, state machinery, mode decision, sim compose skeleton)
- done: git repo + full §3 tree committed (dirs carried via .gitkeep); ROADMAP/STATE/EVIDENCE machinery; ADR-0001 (dual-mode sim/cloud); ADR-0002 (sim-only now, validated-plans for Phase 4); docs/scenario-charter.md; docker-compose.yml sim network (mysql/kafka/redis/minio/toxiproxy, healthchecked, 127.0.0.1-only host binds, .env-indirected sim credentials, kafka dual-listener w/ host port 39092); scripts/smoke.sh (falsifiable 5-service assertions, container-env credentials, negative control proven); Makefile (up/down/ps/logs/smoke with jq guard); README (prereqs + host-port table); CI scaffold (unverified, no remote yet); .gitignore; commits
- measured: clean start converged ~28s, 4 (healthy) + toxiproxy Up (distroless, API on :8474), kafka HOST listener accepting 127.0.0.1:39092 → E-001; repro `down -v → up` + `smoke OK (all 5 services) in 2s` → E-002; negative control: redis stopped → smoke exit 2, restored → exit 0 → E-003
- war stories: (1) system Docker daemon can't be started (no passwordless sudo); rootless dockerd was already alive — first `dockerd-rootless.sh` attempt hit a RootlessKit lock collision proving another instance held the state dir; fixed with persistent `docker context use rootless`. (2) `shopify/toxiproxy:2.11.0` doesn't exist on Docker Hub (tags stop at 2.1.4) — GHCR carries new releases; and GHCR images are distroless, so a `wget` healthcheck can't work — readiness must be asserted host-side. (3) Port 6379 collides with a pre-existing local Redis → sim remaps host port to 16379. (4) `make smoke` v2 could not fail: in `set -e` shells, a failing command that is a non-final member of an `&&` list is exempt from errexit — my `check && echo OK` lines never propagated failure; the critic stopped redis and smoke still said OK. Rewrote as scripts/smoke.sh with explicit `|| fail` on every assertion + committed negative-control evidence (E-003). (5) This is a SHARED host: an unrelated Airflow stack runs on the same rootless daemon, and a failed kafka bind leaked port 29092 inside the rootlesskit port driver — unrecoverable without restarting the daemon (would kill the other stack), so Kafka's host port moved to 39092. Lesson: on shared/rootless hosts, port choice is a scarce resource and daemon restarts are off the table.
- handoff: WSDLs not yet authored — Phase 1 session starts with contract-first authoring, NOT Java code; record JDK 17 setup in the S2 log
- critic: cycle 1 FAIL 7.80 (evidence row not bound to command/artifact; tree not in git; STATE over-claims; credential/port hygiene; jq undeclared; CI unverified) — fixed. Cycle 2 FAIL 7.75 (crit: smoke could not fail — set -e &&-list exemption, proven live by critic; hardcoded creds in smoke/healthcheck; kafka host listener unusable; README port omissions) — all fixed same session: scripts/smoke.sh with `|| fail` assertions + container-env credentials + negative-control evidence E-003 + kafka dual-listener on 39092. Cycle 3: critic spawn failed twice (quota/model errors) — gate OPEN, self-audit recorded, fresh critic required at S2 start. See ROADMAP gate log.

### S1-resume — 2026-09-09
- slice: cycle-3 critic attempt + ORCH-LEAD self-audit (no new build work)
- done: 2 critic spawn attempts failed (exceed quota limit / model request failed); pivoted to honest self-audit — secrets scan (only ${VAR:-default} remain, matching .env.example), compose config OK, git clean, §3 tree confirmed in git, negative path re-proven
- measured: recovered sim stack after a daemon/host restart (all containers Exited(255)) — `make up` restored 4 healthy + toxiproxy in ~21s with volumes intact; smoke then CAUGHT a dead toxiproxy host-forward (8474 refused) — falsifiable smoke proving its worth; `docker compose restart toxiproxy` did NOT re-establish the rootlesskit forward, only a full `down` + `up` did (8474 released and re-bound); re-ran positive/negative/restore smoke: exit 0 / 2 / 0 → E-003 artifact refreshed with full transcript
- war stories: (6) rootless Docker + host restart: containers don't come back on their own (no restart policies — deliberate for a sim stack), and a lost port forward is NOT recoverable via `docker compose restart <service>` — the binding must be fully torn down (`down`, without -v to keep volumes) and re-created. Without a smoke that can fail, the dead 8474 forward would have looked "green" all day.
- handoff: gate OPEN → S2 must start with fresh CRITIC cycle 3 (see next_actions), then Phase 1

## Working agreements (from MASTER_PROMPT.md — reminders)
- One session = one phase-slice. Update this file BEFORE committing, at session end, or immediately if context runs low.
- Critic Gate (§5) before any phase is marked DONE. Critic is always a fresh agent.
- Verify before recording: run it yourself, then write the EVIDENCE row. Row claim = reproduce command = artifact, 1:1.
- Never fabricate evidence. An honest "not yet verified" beats an invented green check.
