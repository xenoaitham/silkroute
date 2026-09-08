# ADR-0002: AliCloud account path — sim-first, validated-plans for Phase 4 IaC

- **Status:** Accepted (2026-09-08)
- **Deciders:** ORCH-LEAD (Haitham)

## Context

MASTER_PROMPT Phase 4 requires proving real AliCloud environment skill (Terraform alicloud modules, RAM least-privilege, KMS, SLS, OSS, RDS, SAE/ACK) and §8 sets strict cost guardrails. The honest situation as of Session 1:

- No AliCloud account is currently available in this build environment.
- MASTER_PROMPT Phase 4 explicitly anticipates this: *"If no cloud account: validated-plans mode with sim-runtime parity, labeled honestly in evidence as 'validated IaC, sim runtime.'"*
- §9 forbids invented metrics and requires every claim to map to a real artifact.

## Options considered

1. **Open an international AliCloud free-trial account now**: would enable real deploy evidence. Blocked in practice — real-name verification and payment setup are not available in this environment at this time; will be revisited if the owner completes account setup. Not assumed.
2. **Sim-only everything, no Terraform**: loses the entire AliCloud environment story. Rejected.
3. **Validated-plans mode**: full Terraform authorship against the real `alicloud` provider with `terraform validate` + `terraform plan` (plan against a real provider schema, no apply), plus sim-runtime parity for everything runtime-provable. All cloud claims labeled "validated IaC, sim runtime."

## Decision

Adopt **option 3 (validated-plans mode)** for Phase 4 unless an account materializes before then. Precisely:

- `infra/` modules are written and kept `terraform validate`-clean and `plan`-able (provider schema-exact: resource types, argument names, region semantics).
- `plan` runs with placeholder/variable values and its output is captured as evidence — proving the HCL is schema-correct — but is **explicitly not** proof of deployment.
- Every Phase 4 evidence row is prefixed `validated IaC, sim runtime` — no row may imply resources were actually created.
- The China-region partition (`var.enable_cn_region`) is designed in Terraform, validated the same way, and never applied (C1 residency design evidence; running it requires a CN-registered account per §8).
- If an international account becomes available later: this ADR gets a superseding ADR, budget alarm first, deploy → capture → destroy.

## Consequences

- (+) AliCloud Terraform skill is still concretely demonstrated (real provider, real schema validation); zero spend; zero risk of orphaned billable resources.
- (+) Interview framing stays bulletproof: "I designed and validated the landing zone and ran the whole platform in sim; deploy evidence is validated-plans because no account was available."
- (−) No real SLS/RAM/KMS behavior evidence (e.g., actual STS token issuance, actual log ingest); those stay at "designed, schema-validated" depth unless the account path reopens.
- (−) Interview Q&A (Phase 8) must prepare honest answers distinguishing *validated* from *deployed* — this distinction is itself a defensibility asset, not a weakness.
