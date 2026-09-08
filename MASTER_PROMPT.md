# 🧭 PROJECT SILKROUTE — Master Build Prompt
### Multi-Agent · Multi-Session · Critic-Gated Portfolio Build

**Purpose:** Produce an evidence-backed, interview-defensible portfolio project that covers the four areas the recruiter probed: **AliCloud environments**, **ESB/SOAP/ETL enterprise integration**, **Asia-market rollout architecture & compliance**, and **Canada–APAC context**.
**Owner:** Haitham · **Started:** 2026-09 · **Repo root:** `/home/potato/Haitham/ALIBABA`

---

## 0. HOW TO USE THIS FILE (read first, EVERY session)

This file is the constitution of the project. It does not change unless a decision is recorded as an ADR.

- **Start of every session:** read `MASTER_PROMPT.md` → `ROADMAP.md` → `STATE.md` → ADRs touched by the current phase.
- **End of every session:** update `STATE.md` (session log, war stories, evidence ledger) and commit.
- One session = one phase-slice. Never start new work on top of unrecorded state.
- If context runs low mid-session: IMMEDIATELY write `STATE.md` and stop cleanly.

**Session kickoff line — paste this into every fresh session:**

> You are the ORCH-LEAD agent for Project SILKROUTE. Read `/home/potato/Haitham/ALIBABA/MASTER_PROMPT.md`, `ROADMAP.md`, and `STATE.md`. Follow the session start ritual (§7), state the current phase and this session's slice in 5 lines, then execute using the Agent Team & Spawn Protocol (§4). The Critic Gate (§5) applies before any phase is marked done. Never fabricate evidence.

---

## 1. MISSION

Design, build, test, deploy, and document a **multi-region enterprise integration platform on Alibaba Cloud** for a **fictional Canadian retailer — "Maple Retail Group" (clearly labeled fictional in every artifact)** — expanding into **Singapore and mainland China**.

The platform must demonstrate, with real running code and measured evidence:

1. **AliCloud environments** — landing-zone Infrastructure-as-Code (Terraform `aliyun/alicloud` provider): VPC, RAM least-privilege + STS, KMS, SLS, OSS, ApsaraDB RDS, ACK/SAE compute, API Gateway, CloudMonitor, budget alarms; region strategy (Singapore international hub + China data-residency partition).
2. **Enterprise integration** — an ESB-pattern integration hub (Apache Camel): WSDL-first SOAP services, SOAP↔REST mediation, XSLT transformation, canonical data model, content-based routing, retry / circuit breaker / DLQ, idempotent consumption, saga with compensation.
3. **ETL pipelines** — change-data-capture (Debezium → Message Queue for Apache Kafka) and batch (OSS landing zone → bronze/silver/gold) with data-quality gates, reconciliation reports, and freshness SLAs.
4. **Asia-market compliance & delivery** — data residency (China PIPL + Singapore PDPA + Canada PIPEDA), cross-border transfer controls, region-based PII masking, audit trail (ActionTrail → SLS), ICP filing runbook, delivery-model documentation (env promotion, ADRs, runbooks, RACI, cost model).

**THE PRIME DIRECTIVE — everything must be REAL.**
No stub passed off as done. No invented metrics. Every claim this project produces must map to a file, a passing test, or a measured run recorded in `evidence/EVIDENCE.md`. The project exists so its owner survives a hostile deep-dive SA interview. Faked depth = project failure, no matter how pretty it looks.

**Honest framing (non-negotiable):** this is a **self-directed reference implementation** of a realistic enterprise scenario. On the resume it lives under *Projects*, never under *Experience*. The scenario company is always labeled fictional. See §9.

---

## 2. THE SCENARIO (drives every design decision — the Critic tests against this)

**Maple Retail Group (fictional)** — 300 stores across Canada plus an e-commerce platform — is entering Southeast Asia (Singapore operations hub) and mainland China (marketplace + stores).

**Starting estate:**
- On-prem legacy ERP exposing **SOAP 1.2 / WSDL services** (orders, inventory, pricing) — untouchable, must be integrated as-is.
- New digital stack: REST microservices + event streaming.
- Analytics needs a governed lake with region-aware data.

**Hard constraints (memorize — every design decision must trace to one):**
| # | Constraint | Consequence |
|---|---|---|
| C1 | Chinese-customer PII must remain in the China region (PIPL) | Region-pinned storage, masked egress for analytics, residency tests in CI |
| C2 | Singapore region is the international hub | No ICP needed for internal APIs; public web presence in mainland CN requires ICP filing (document the process precisely) |
| C3 | Sync SOAP mediation p95 latency < 300 ms in-region | Measure with k6; record real numbers |
| C4 | CDC freshness ≤ 15 min; T+1 batch complete by 06:00 Singapore time | Freshness monitor + batch window scheduler + evidence |
| C5 | Multi-currency (CAD/SGD/CNY), multi-timezone batch windows | Money as minor units + currency code; timezone-explicit scheduling |
| C6 | The ERP team will NOT modify their WSDLs | All impedance mismatch handled in the ESB layer (XSLT, canonical model) |
| C7 | Canada HQ subject to PIPEDA; cross-border transfer must be documented | PIPL Art. 38–40 transfer mechanism memo (CAC security assessment / standard contract / certification) |

---

## 3. ARCHITECTURE BLUEPRINT

**Dual-mode design** (recorded as ADR-0001):
- **Cloud mode** — deployed to Alibaba Cloud Singapore (pay-as-you-go, destroyed after evidence capture). China region designed in Terraform behind `var.enable_cn_region`, deployed only if an account is available (usually not — record honest ADR).
- **Sim mode** — identical interfaces via `docker-compose` on the laptop (MinIO for OSS, local Kafka/RocketMQ, MySQL for RDS). Sim mode is the default build/test target so the project always runs at zero spend and can be demoed offline.

**Repository layout (every session respects this tree):**

```
ALIBABA/
├── MASTER_PROMPT.md            # this file — the constitution
├── ROADMAP.md                  # phase status board
├── STATE.md                    # current phase, next actions, session log, war stories
├── decisions/                  # ADR-0001-*.md, ADR-0002-*.md ...
├── evidence/EVIDENCE.md        # claim → artifact → reproduce command → measured result
├── apps/
│   ├── legacy-erp/             # Spring Boot + Apache CXF SOAP 1.2, WSDL-first
│   ├── esb/                    # Apache Camel integration hub (the "ESB")
│   ├── modern-oms/             # REST order-management service + MySQL
│   ├── cdc/                    # Debezium connector configs + sink consumers
│   └── batch/                  # Spark bronze/silver/gold + reconciliation + DQ
├── infra/                      # Terraform (alicloud provider): network, security, data, compute, observability
├── compliance/                 # PII masking lib, residency tests, control matrix, ICP runbook, STRIDE
├── tests/                      # Karate contract (SOAP+REST), Testcontainers integration, k6 load, chaos
├── docs/                       # HLD, LLD, C4 diagrams, runbooks, delivery model, cost model, demo script
├── demos/                      # recorded runs, video outline
└── .github/workflows/          # CI: tests + terraform validate/plan
```

**Components (each is a build workstream):**

1. **`apps/legacy-erp/` — the legacy estate.** Spring Boot + Apache CXF, WSDL-first: `OrderService` (submitOrder, getOrderStatus), `InventoryService` (reserve, release, getStock), `PricingService` (priceForSku), SOAP 1.2 + WS-Security UsernameToken, typed faults (`InvalidOrderFault`, `OutOfStockFault`), seeded demo data (SKUs, stores, orders). Deliberately "legacy": verbose XML, NO changes allowed post-Phase 1 (C6).
2. **`apps/esb/` — the integration hub (ESB patterns on Apache Camel).** Versioned canonical `Order` JSON schema; REST façade → canonical → SOAP with saga orchestration (order → inventory reserve → pricing → confirm; compensation on failure); XSLT legacy↔canonical; content-based routing (CN vs SG store region); retry with exponential backoff + circuit breaker + DLQ (Kafka/RocketMQ dead-letter topic); idempotent consumer (Redis in sim); PII-masking route policy for any destination outside CN (C1).
3. **`apps/modern-oms/` + `apps/cdc/` + `apps/batch/` — the modern estate & data plane.** OMS consumes ESB events into MySQL. Debezium captures changes → Kafka → bronze (raw, immutable, region-tagged, MinIO/OSS) → Spark batch silver (cleansed/conformed) → gold star schema (`fact_orders`, `fact_order_lines`, `dim_sku`, `dim_store`, `dim_date`) with reconciliation totals (source vs gold) and DQ rules (completeness, uniqueness, referential) with quarantine table; freshness metric emitted every run (C4).
4. **`infra/` — AliCloud landing zone (Terraform).** Modules: network (VPC, vSwitches, NAT, security groups), security (RAM roles/policies least-privilege, STS, KMS keys), data (RDS MySQL, OSS buckets region-pinned + SSE-KMS), compute (SAE app or ACK spot nodepool for ESB), observability (SLS project/stores/dashboards/alerts, CloudMonitor), budget alarm, remote state. CN-region module with residency guards behind a flag.
5. **`compliance/` — the trust layer.** PII classification + masking library (wired into ESB egress), automated residency tests (CI-provable: CN-tagged PII never in non-CN sinks), PIPL/PDPA/PIPEDA control matrix with article-level citations (research with WebSearch — accuracy required, cite sources), cross-border transfer memo, ICP filing runbook, STRIDE threat model, audit-event schema (ActionTrail → SLS, queryable "who did what").
6. **`tests/` — proof of work.** Karate contract tests for SOAP (raw XML assertions against WSDL) and REST; Testcontainers integration tests; k6 load profile; chaos script (pod kill, toxiproxy latency/fault injection proving retry/DLQ/saga-compensation behavior).
7. **`docs/` + `demos/` + `evidence/` — the delivery layer.** HLD, LLD, C4 diagrams (Mermaid), ADR set, runbooks, delivery-model doc (RACI, agile cadence, dev→staging→prod promotion), cost model sheet from real pricing pages, demo script + 10-minute video outline. `evidence/EVIDENCE.md` is the spine — no claim without a row.

---

## 4. AGENT TEAM & SPAWN PROTOCOL

The session agent is **ORCH-LEAD**. It plans the slice, spawns subagents for parallelizable work, integrates, verifies, and records evidence.

| Agent | Role | Writes |
|---|---|---|
| ORCH-LEAD | state keeper, planner, integrator, verifier | STATE.md, ROADMAP.md, evidence/ |
| ARCH-1 | HLD, ADRs, trade-off analysis | docs/, decisions/ |
| BUILD-LEGACY | SOAP/WSDL services | apps/legacy-erp/ |
| BUILD-ESB | Camel routes, canonical model, resilience | apps/esb/ |
| BUILD-ETL | Debezium, Kafka sinks, Spark batch | apps/cdc/, apps/batch/ |
| BUILD-CLOUD | Terraform modules | infra/ |
| BUILD-QA | contract/integration/load/chaos tests | tests/ |
| SEC-1 | threat model, IAM review, compliance matrix, residency tests | compliance/, infra/ security review |
| DOC-1 | README, diagrams, runbooks, demo script | docs/ |
| **CRITIC** | red-team reviewer at every phase gate | **nothing — read-only, verdict only** |

**Spawn rules:**
1. Subagent prompts must be **self-contained**: include repo paths, the relevant MASTER_PROMPT section verbatim (scenario constraints especially), expected outputs, and the acceptance criteria being built toward. Subagents share no conversation context.
2. **Parallelize** independent workstreams (e.g., BUILD-LEGACY ∥ BUILD-CLOUD network module ∥ SEC-1 matrix). Never let two agents edit the same file.
3. **Verify before recording:** ORCH-LEAD runs/tests every subagent deliverable itself. A subagent's claim is not evidence — a passing run is.
4. **CRITIC is always a fresh agent** (no shared context = no confirmation bias) spawned with the §5 prompt.

---

## 5. CRITIC GATE PROTOCOL

At the end of every phase, spawn CRITIC. Fill `{PHASE}`, `{ARTIFACTS}`, and attach the current `STATE.md` + phase evidence rows.

> You are **CRITIC** — a principal Solution Architect and hiring interviewer with 15 years across Alibaba Cloud and enterprise integration (ESB, SOAP, ETL). You are skeptical, evidence-driven, and allergic to hand-waving. You are reviewing **Phase {PHASE}** of Project SILKROUTE, a self-directed reference build for a fictional Canadian retailer expanding into Singapore + China (scenario constraints in MASTER_PROMPT §2).
>
> **Inputs:** {ARTIFACTS — file list}, `STATE.md`, `evidence/EVIDENCE.md` rows for this phase.
> **You may run commands** (tests, builds, `docker compose`, `terraform validate`) to verify claims. **Assume any claim without a reproducible command is false.**
>
> **Score 0–10 each:**
> 1. Correctness — does it actually run, tests green?
> 2. Architectural soundness — decisions trace to scenario constraints C1–C7?
> 3. AliCloud authenticity — real service names, correct region semantics, correct RAM/KMS/SLS/OSS behavior?
> 4. Integration depth — real WSDL-first SOAP, real XSLT, real routing/resilience patterns — or a toy?
> 5. ETL correctness — DQ gates, reconciliation, freshness evidence?
> 6. Security & compliance depth — least privilege real, residency provable, citations accurate?
> 7. Test quality — contract + fault-path coverage, not happy-path only?
> 8. Documentation clarity — could a stranger deploy from the README?
> 9. Evidence & reproducibility — every claim re-runnable?
> 10. Interview-defensibility — could the owner explain every choice under hostile questioning?
>
> **Output format:**
> `## FINDINGS` — table [SEV crit/high/med/low | file:line | problem | exact fix]
> `## SCORES` — the 10 scores + weighted total
> `## VERDICT` — **PASS** only if weighted ≥ 8.5 AND zero unfixed critical/high findings
> `## BLOCKERS` — the must-fix list
>
> Do not praise. Do not fix. Judge.

**Gate rule:** PASS → mark phase done in ROADMAP.md and proceed. FAIL → fix blockers, re-run critic (max 3 cycles; if still failing, de-scope honestly and record the de-scope as an ADR).

---

## 6. PHASE PLAN & ACCEPTANCE CRITERIA

> **Fast path (MVCP):** Phases **0 → 1 → 2 → 4-lite → 8** produce a resume-ready v1 in ~5 sessions. Phases 3, 5, 6, 7 amplify depth. Do them all if time allows — the compliance phase (5) is what makes the "Asia rollout" story credible.

**Phase 0 — Foundation (½ session)**
Goal: scaffolding + state machinery + environment decision.
Tasks: `git init`; create the full repo tree (§3); write ROADMAP.md, STATE.md, evidence/EVIDENCE.md (empty ledger), ADR-0001 (dual-mode sim/cloud decision); scenario charter (§2 as a doc); docker-compose skeleton; decide AliCloud account path (international free-trial account + `aliyun` CLI, or sim-only for now — record either honestly).
AC: `docker compose up` starts the (stub) network; state files committed; repo clean.

**Phase 1 — Legacy ERP (SOAP, WSDL-first)**
Goal: the "untouchable legacy estate."
Tasks: author WSDLs + XSDs first (OrderService/InventoryService/PricingService, typed faults); implement with Spring Boot + CXF; WS-Security UsernameToken; seed data (50 SKUs, 8 stores, order generator); Karate SOAP contract tests.
AC: WSDLs validate (`xmllint`); contract tests green in CI; fault paths (invalid order, out of stock) tested; **freeze WSDLs** (C6 begins).

**Phase 2 — ESB core (the heart of the resume story)**
Goal: integration hub proving ESB/SOAP mastery.
Tasks: versioned canonical Order schema (JSON Schema); Camel routes: REST façade → canonical → SOAP saga with compensation; XSLT legacy↔canonical; content-based routing by store region; retry + exponential backoff + circuit breaker + DLQ; idempotent consumer; PII-masking egress policy; toxiproxy fault-injection tests proving retry/DLQ/compensation actually fire.
AC: end-to-end order flow green **under induced failures**; measured p95 recorded (C3); unit + Testcontainers integration tests green.

**Phase 3 — ETL pipelines**
Goal: CDC + batch with governed lake.
Tasks: Debezium on MySQL → Kafka → bronze (immutable, region-tagged); Spark silver (cleansed) + gold star schema; reconciliation report (source counts vs gold, must be 100%); DQ rules with quarantine for injected bad rows; freshness monitor (C4) and T+1 batch window (C5).
AC: seeded order day → gold tables with 100% reconciliation; DQ catches injected violations; freshness metric emitted and logged to evidence.

**Phase 4 — AliCloud IaC & cloud-mode deploy**
Goal: prove real AliCloud environment skill.
Tasks: Terraform modules (network/security/data/compute/observability), remote state, `terraform validate` + `plan` clean; deploy ESB to SAE (or ACK spot nodepool), RDS MySQL, region-pinned KMS-encrypted OSS buckets; SLS log ingest; RAM least-privilege (SEC-1 reviews every policy — zero wildcards); CI runs tests + plan. Capture plan/deploy output as evidence, then **destroy** (§8).
AC: one-command reproducibility (`make plan-sg` / `make deploy-sg` / `make destroy`); no wildcard IAM; budget alarm exists. If no cloud account: validated-plans mode with sim-runtime parity, labeled honestly in evidence as "validated IaC, sim runtime."

**Phase 5 — Compliance & security (the Asia-rollout credibility layer)**
Goal: provable residency + accurate regulatory mapping.
Tasks: PII masking wired into non-CN egress + CI residency tests (CN-tagged PII provably never in non-CN sinks); PIPL/PDPA/PIPEDA control matrix with article-level citations (WebSearch-verified, sources cited); cross-border transfer memo (PIPL Art. 38–40 mechanisms); ICP filing runbook (precise steps, timelines, what needs filing); STRIDE threat model + fixes applied; audit query demo (ActionTrail → SLS, e.g., "who rotated the KMS key").
AC: residency tests green in CI; SEC-1 sign-off; CRITIC drills 10 compliance questions and the docs survive.

**Phase 6 — Observability, load, chaos, cost**
Goal: production-grade operations evidence.
Tasks: SLS dashboards (order p95, DLQ depth, freshness), alert rules, SLO definitions; k6 load run — **record real measured numbers** (hardware noted); chaos: pod kill + dependency latency injection with documented steady-state; cost model sheet from real AliCloud pricing pages.
AC: SLO report with measured (not invented) numbers; chaos runbook executed; cost sheet cited.

**Phase 7 — Docs & demo**
Goal: stranger-testable delivery layer.
Tasks: top-tier README (architecture diagram, quickstart, decision log summary); HLD + LLD; complete ADR set; runbooks; delivery-model doc (how this rollout would be staffed and run: RACI, cadence, env promotion); demo video script + recording; portfolio one-pager.
AC: fresh DOC-1 subagent (or a friend) can deploy and demo using only the README.

**Phase 8 — Resume & interview pack (the payoff)**
Goal: convert the build into offer-generating material.
Tasks: resume bullets — every number traced to an EVIDENCE.md row; 40-question deep-drill Q&A (AliCloud services, ESB/SOAP internals, ETL, PIPL/PDPA/PIPEDA, delivery model, trade-offs, war stories); 25-minute whiteboard walkthrough script; "what I'd do differently" answers; honest-framing lines (§9).
AC / FINAL GATE: CRITIC runs a 3-round mock hostile interview. PASS = project complete.

---

## 7. STATE & FILE MECHANICS (exact formats)

**STATE.md template:**
```markdown
# SILKROUTE STATE
current_phase: 2          # phase in flight
next_actions:             # ordered, concrete
  1. Implement XSLT legacy→canonical for InventoryService
  2. Wire DLQ topic + consumer
open_risks: [...]
evidence_rows_added: [E-007, E-008]

## Session Log
### S3 — 2026-09-12
- slice: Phase 2.3 resilience patterns
- done: retry+circuit breaker on order route; toxiproxy latency test
- measured: p95 214ms @ 50 RPS (sim, i7 laptop) → E-007
- war stories: consumer double-fired after DLQ redrive; fixed with idempotency key (Redis SETNX + TTL)
- handoff: masking policy not yet wired — next session starts there
```

**EVIDENCE.md row format:** `| ID | claim | artifact | reproduce command | measured result |`
Example: `| E-007 | ESB order route p95 < 300ms under fault injection | tests/load/k6-order.js | make load-order | p95 214ms @ 50 RPS, sim mode, i7, 2026-09-12 |`

**ADR template:** title, status, context (tie to C1–C7), options considered, decision, consequences.

**War-story log:** every real failure + root cause + fix goes in the session log. These become your best interview answers — authentic detail that can't be faked.

**Commits:** `phase-N: <what>` — small, frequent.

---

## 8. COST & ACCOUNT GUARDRAILS

- Default to **sim mode** while building. Cloud mode only for Phase 4/6 evidence capture.
- International AliCloud account (Singapore region recommended): pay-as-you-go, free-trial credits if available; set a **budget alarm** (~$20) on day one.
- Prefer **SAE / Function Compute (pay-per-use)** over long-running ECS/ACK nodepools; use spot if ACK is required.
- **Destroy after evidence** (`make destroy`); never leave load balancers/nodepools running overnight; snapshot costs into the cost sheet.
- China mainland region: design-only (real-name registration + ICP complexities) — record as ADR; say exactly this in interviews ("I designed the CN partition and validated the IaC; running it requires a CN-registered account").

---

## 9. RESUME & INTEGRITY RULES (final pack constraints)

These rules are what make the project *survive* the hiring process. They are not optional.

1. **Placement:** listed under *Projects / Selected Work* as e.g. "SilkRoute — Multi-Region Alibaba Cloud Integration Platform (self-directed reference implementation, 2026)." Never under *Experience*. Never with a fake employer, fake client, or fake dates.
2. **Every number is measured.** No invented team sizes, users, or savings. Resume bullets pull IDs from EVIDENCE.md.
3. **The fictional client stays fictional** in all artifacts. In interviews, volunteer the framing with confidence: *"I built a reference implementation of exactly the problem your JD describes — a Canadian retailer's APAC expansion on AliCloud. Let me walk you through it."* Confidence + real depth beats faked employment and survives background checks.
4. **Do not claim** professional Asia-rollout delivery experience or real Canadian clients from this project. The project gives you the architecture, compliance, and integration knowledge — presented honestly, that directly answers what Madana is probing.
5. **Work authorization is a legal status, not a skill.** Never misstate it — it is verified at hire, and misrepresentation voids offers. Answer the recruiter truthfully and plainly.
6. Sample bullet shape (fill with *your measured* numbers):
   - "Built an ESB-pattern integration hub on Alibaba Cloud using Apache Camel: WSDL-first SOAP↔REST mediation with XSLT and a canonical data model, saga-based order orchestration with compensation, and retry/circuit-breaker/DLQ resilience — p95 {X}ms under fault injection ({evidence ID})."
   - "Designed multi-region IaC (Terraform, AliCloud provider) with Singapore hub + China data-residency partition, least-privilege RAM/KMS, and CI-provable PIPL/PIPEDA/PDPA residency controls."
   - "Implemented CDC + batch ETL (Debezium → Kafka → bronze/silver/gold lake) with data-quality gates and 100% automated reconciliation; freshness SLA {X} min."

---

*Build it real. Measure everything. Let the critic be cruel. That combination is what turns "no experience" into "the candidate who clearly understands the job."*
