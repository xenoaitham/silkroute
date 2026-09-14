# ADR-0002: AliCloud account path - sim-first, validated-plans for landing zone IaC

- **Status:** Accepted (2026-09-08)
- **Deciders:** Haitham

## Context

The landing-zone workstream requires proving real AliCloud environment skill (Terraform alicloud modules, RAM least-privilege, KMS, SLS, OSS, RDS, SAE/ACK) and sets strict cost guardrails. The honest situation at the start of the build:

- No AliCloud account is currently available in this build environment.
- The landing-zone plan explicitly anticipates this: *"If no cloud account: validated-plans mode with sim-runtime parity, labeled honestly in evidence as 'validated IaC, sim runtime.'"*
- forbids invented metrics and requires every claim to map to a real artifact.

## Options considered

1. **Open an international AliCloud free-trial account now**: would enable real deploy evidence. Blocked in practice - real-name verification and payment setup are not available in this environment at this time; will be revisited if the owner completes account setup. Not assumed.
2. **Sim-only everything, no Terraform**: loses the entire AliCloud environment story. Rejected.
3. **Validated-plans mode**: full Terraform authorship against the real `alicloud` provider with `terraform validate` + `terraform plan` (plan against a real provider schema, no apply), plus sim-runtime parity for everything runtime-provable. All cloud claims labeled "validated IaC, sim runtime."

## Decision

Adopt **option 3 (validated-plans mode)** for landing zone unless an account materializes before then. Precisely:

- `infra/` modules are written and kept `terraform validate`-clean and `plan`-able (provider schema-exact: resource types, argument names, region semantics).
- `plan` runs with placeholder/variable values and its output is captured as evidence - proving the HCL is schema-correct - but is **explicitly not** proof of deployment.
- Every landing zone evidence row is prefixed `validated IaC, sim runtime` - no row may imply resources were actually created.
- The China-region partition (`var.enable_cn_region`) is designed in Terraform, validated the same way, and never applied (C1 residency design evidence; running it requires a CN-registered account per ).
- If an international account becomes available later: this ADR gets a superseding ADR, budget alarm first, deploy -> capture -> destroy.

## Consequences

- (+) AliCloud Terraform skill is still concretely demonstrated (real provider, real schema validation); zero spend; zero risk of orphaned billable resources.
- (+) Interview framing stays bulletproof: "I designed and validated the landing zone and ran the whole platform in sim; deploy evidence is validated-plans because no account was available."
- (−) No real SLS/RAM/KMS behavior evidence (e.g., actual STS token issuance, actual log ingest); those stay at "designed, schema-validated" depth unless the account path reopens.
- (−) Interview Q&A (delivery) must prepare honest answers distinguishing *validated* from *deployed* - this distinction is itself a defensibility asset, not a weakness.
