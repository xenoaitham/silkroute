# SilkRoute — Delivery Model (DESIGNED operating model)

> **Explicit label:** this document describes the **DESIGNED** operating model for the fictional Maple Retail Group rollout — how the platform **would** be staffed and run. Nothing here is a claim about real operations: no cloud account exists (ADR-0002), the cloud side is validated IaC that has never been applied, and Maple Retail Group is fictional (self-directed reference implementation, 2026; MASTER_PROMPT §9 framing). Verbs are deliberately conditional ("would", "is designed to").

## 1. RACI — roles mapped to what the build actually produced

The roles below are the ones this repo's artifacts imply; the artifact column points at what each role would own. The evidence ledger is the contract between build and operations: no claim without a row, every row with a re-runnable command.

| Activity | Platform lead | Integration engineer | Data engineer | Security / compliance | SRE / on-call |
|---|---|---|---|---|---|
| Terraform landing zone ([infra/](../infra/)) | **A/R** | C | C | C (IAM review) | I |
| ESB module ([apps/esb/](../apps/esb/)) — routes, saga, resilience, masking | C | **A/R** | I | C (PII egress) | I |
| Legacy ERP estate ([apps/legacy-erp/](../apps/legacy-erp/), frozen WSDLs) | I | **A/R** | I | C | I |
| Data plane (CDC + batch — designed, not built) | C | C | **A/R** | C (residency) | I |
| Compliance pack ([compliance/](../compliance/): control matrix, transfer memo, STRIDE, audit design) | C | C | I | **A/R** | I |
| Residency tests + selftest ([scripts/residency-tests.sh](../scripts/residency-tests.sh)) | C | C | C | **A/R** | I |
| Observability/alerts ([infra/observability/](../infra/observability/)) | C | I | I | C (audit alerts) | **A/R** |
| Chaos harness + load profiles ([tests/chaos/](../tests/chaos/), [tests/load/](../tests/load/)) | C | C | I | I | **A/R** |
| Cost model + budget guardrails ([docs/cost-model.md](cost-model.md), [scripts/budget-alarm.sh](../scripts/budget-alarm.sh)) | **A/R** | I | I | I | C |
| Evidence ledger ([evidence/EVIDENCE.md](../evidence/EVIDENCE.md)) | **A/R** | R | R | R | R |
| Runbooks ([docs/runbooks.md](runbooks.md)) | C | C | C | C | **A/R** |

(R = responsible, A = accountable, C = consulted, I = informed. One line each, by design: this is a small-platform model, not a program org chart.)

## 2. Cadence — the rhythm the repo itself models

The build process is the cadence spec: **small slices, each ending green.** Concretely, each session/slice:

1. Hypotheses and SLO definitions are committed **before** results (the SLO report shipped with pending measured columns first — auditable in git history).
2. The slice ends with CI green and its results recorded as evidence rows — never silent passes; a refuted hypothesis is recorded as a finding, not rewritten (E-022/E-024 are refuted-then-fixed rows).
3. Project state (STATE.md) and post-mortem notes are written down at slice end; the next slice starts from the recorded state, not from memory.

A Maple rollout team would run the same rhythm: every merge lands with green CI + evidence; hypotheses (resilience targets, SLO budgets) are written before measurement.

## 3. Environment promotion — dev → staging → prod

The promotion design is built into the repo's guardrails:

- **Separate Terraform roots + state backends.** The default backend is local; [infra/backend-remote.tf.example](../infra/backend-remote.tf.example) documents the OSS + Tablestore-locking remote backend to rename/activate per environment when an account exists. Each environment gets its own root/state — never a shared state.
- **CN flag off by default, everywhere.** `var.enable_cn_region` defaults to `false` in every environment ([infra/variables.tf](../infra/variables.tf)); the CN partition is a separately-applied unit on a CN-registered account (ADR-0005), promoted independently of the SG hub.
- **Guarded deploy/destroy.** `make deploy-sg` / `make destroy` **refuse to run** (exit 2) unless both `SILKROUTE_CLOUD_CONFIRM=YES` **and** AliCloud credentials are present — the negative paths are proven live (E-014). The refusal is a double guard: environment confirmation AND credentials, either one missing stops the target.
- **Capture-then-destroy discipline.** Per the cost guardrails, cloud mode exists for evidence capture only: deploy → capture → `make destroy`; billable resources are never left running (MASTER_PROMPT §8). The activation runbook in [docs/runbooks.md](runbooks.md) operationalizes this.

## 4. Environment matrix

The authoritative sim↔cloud service-by-service matrix is [docs/infra-env-parity.md](infra-env-parity.md) — one summary sentence per row here, nothing more: MySQL (sim 8.0 ↔ RDS MySQL 8.0, UTC pinned), Kafka (sim dotted topics ↔ ApsaraMQ dot-free via topic env vars), Redis (sim 16379 ↔ ApsaraDB for Redis — unmodeled IaC gap, chosen at activation), object storage (MinIO ↔ 4 SSE-KMS OSS buckets, with the known S3-compat gap), the SOAP ERP (local jar ↔ SAE app, fault injection moves from toxiproxy to the `ESB_FAULT_INJECTION` flag), and WSS credentials (sim dummies ↔ KMS-injected secrets).

## 5. On-call & alert routing (designed)

- The four SLS alerts (DLQ depth, p95 budget breach, denied-action burst, trail-tamper tripwire) and their thresholds are defined in [infra/observability/README.md](../infra/observability/README.md) — the alert authority; not restated here.
- The two CloudMonitor alarms (SAE CPU > 80%, RDS connection count > 80) route to the `silkroute-ops` CMS contact group.
- SLS alert notifications route through an SLS **action policy** that has no provider resource in 1.285.0 — it is console-managed at activation (`var.sls_action_policy_id` placeholder). Condition-expression semantics are likewise verified against the live editor at activation: a green plan proves schema, not SLS behavior.
- Runtime behavior of the whole alert layer is **verify at activation** — it has never ingested anything.

## 6. Cost guardrails

- The budget alarm is sized for **evidence-capture bursts**: `scripts/budget-alarm.sh` (BssOpenApi `CreateBudget`, provider 1.285.0 ships no budget resource — schema-proven) caps the account at **~20 USD/month**. At the designed 24/7 scale of **949.64 USD/month** (E-025), that alarm would fire immediately — the sheet is the quantified argument for why the operating model is sim-first with deploy→capture→destroy, not steady-state cloud.
- **Destroy-after-evidence:** the guarded `make destroy` plus the capture-then-destroy discipline above; nothing billable runs overnight.
- The KMS-instance decision dominates the bill (500 USD/mo, ~53%): see the activation runbook — customer-managed vs default keys is a pre-apply decision, not a cleanup.

## 7. Documentation discipline

- **ADRs** ([decisions/](../decisions/)) for any decision that shapes the architecture — five exist; scenario changes require one.
- **Runbooks** ([docs/runbooks.md](runbooks.md)) for the operations a real team would perform: activation, deploy/destroy, DLQ redrive, dependency-degradation triage, freshness-breach response.
- **The evidence ledger as the build↔operations contract:** every operational claim (a threshold, a recovery time, a cost line) traces to a row with a reproduce command; operations inherits proof, not promises.
