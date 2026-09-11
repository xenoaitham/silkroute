# ADR-0004: ESB compute on SAE (not ACK spot); budget alarm via BSSOpenAPI script

- **Status:** Accepted (2026-09-11, S4)
- **Deciders:** ORCH-LEAD (Haitham)

## Context

Phase 4 requires a cloud compute target for the ESB (a long-running Spring Boot + Camel hub holding persistent Kafka consumers) and a budget alarm (~$20, MASTER_PROMPT §8/§6 Phase 4). MASTER_PROMPT §8 already states the preference: *"Prefer SAE / Function Compute (pay-per-use) over long-running ECS/ACK nodepools; use spot if ACK is required."* Validated-plans mode (ADR-0002) applies — nothing is deployed — but the decision still shapes the Terraform and the interview story.

Separately, ORCH-LEAD verified against the actual provider 1.285.0 schema (1161 resources, `terraform providers schema -json`) that **no budget/BSS/billing resource exists** in the provider. Any Terraform "budget alarm" resource would be fabrication.

## Options considered

1. **SAE (`alicloud_sae_application`)** — pay-per-use (replica-hours), deploys the existing fat jar from the artifacts bucket, namespaces give prod/CN isolation, built-in autoscaling rules, no cluster to run. Matches §8.
2. **ACK managed nodepool with spot instances** — deeper raw-Kubernetes surface, but an always-on nodepool costs money while idle (§8 violation risk), adds cluster ops burden no scenario constraint requires.
3. **Function Compute** — cheapest idle, but cold-starting a Camel hub with persistent Kafka consumers and a synchronous 300 ms saga budget (C3) is architecturally wrong.
4. **A Terraform resource for the budget alarm** — impossible honestly: no such resource exists in provider 1.285.0 (schema-verified; see Consequences).

## Decision

1. **Compute = SAE.** `infra/compute` models two SAE applications (`silkroute-esb`, `silkroute-erp`) in namespace `silkroute-sg`, plus the CN twins in `infra/cn-partition` behind `var.enable_cn_region`. Jar artifact path points at the `silkroute-sg-artifacts` OSS bucket; package upload is an out-of-band deploy step.
2. **Budget alarm = `scripts/budget-alarm.sh`** wrapping the BssOpenApi `CreateBudget` API (2023-09-30; doc-verified after critic cycle 1 caught the original draft targeting an uncitable `SetBudgets` action) — monthly cap $20, warn at 100% via `WarnConfs`. The script is dry-run by default (prints the exact request payload) and refuses to run against real credentials unless explicitly invoked — in validated-plans mode it is the documented, executable mechanism, and it becomes a real alarm the day an account exists. The provider gap is documented in `infra/observability/README.md`.

## Consequences

- (+) Pay-per-use only; no idle cluster cost; deploy path (jar → OSS → SAE) is one command away from real.
- (+) The budget-alarm mechanism exists as a falsifiable artifact without inventing provider resources.
- (−) Raw Kubernetes depth (PDBs, node pools, spot interrupts) is not demonstrated by this phase — acknowledged; ACK exposure can be a Phase 6/7 amplifier if time allows.
- (−) The SLS alert on DLQ depth uses the deprecated `notification_list` argument. CORRECTED per critic cycle 2: the modern `alicloud_sls_alert` resource DOES exist in provider 1.285.0 (schema-verified); migrating to it is a small follow-up, deferred — trade-off recorded in `infra/observability/README.md`.
