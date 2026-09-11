# ADR-0005: Region strategy — Singapore hub; China partition designed and validated, never applied

- **Status:** Accepted (2026-09-11, S4)
- **Deciders:** ORCH-LEAD (Haitham)
- **Supersedes:** nothing; implements the CN clause of ADR-0002 and MASTER_PROMPT §8 ("China mainland region: design-only").

## Context

C1 (PIPL: Chinese-customer PII stays in the China region) and C2 (Singapore is the international hub; mainland-CN public presence needs ICP filing) require a two-partition design. §8 and ADR-0002 add the operating reality: this build has no AliCloud account at all, and a **mainland-CN partition additionally requires a CN-registered account (real-name verification)** plus ICP filing for any public endpoint — not just the missing international account. The partition design must still be reviewable and machine-checkable, not prose.

## Options considered

1. **Singapore-only** — simplest, but deletes the residency story (C1) and half the Asia-rollout credibility of the project.
2. **Apply the CN partition** — blocked twice over: no international account (ADR-0002) and no CN-registered account; applying is not available at any spend level in this environment.
3. **Design + validate the CN partition in Terraform behind `var.enable_cn_region`, never apply** — the partition becomes concrete, plan-able HCL (`Plan: 110 to add` with the flag on, vs `79` off — evidence E-012; counts move as resources land, the artifact is authoritative), with residency enforced by construction: separate region (`cn-beijing` default), separate VPC (10.70.0.0/16), CN-only KMS keys, CN-only OSS buckets with SSE-KMS, no cross-region replication resources anywhere, and `residency=cn` + `data-classification=pipl-restricted` tags on every CN resource for machine-checkable audits (CI residency tests are Phase 5 work).

## Decision

Option 3. `infra/cn-partition/` is a real module, wired into the root as `module "cn_partition" { count = var.enable_cn_region ? 1 : 0 }`, plan-validated both ways (E-012), never applied. The Singapore hub (`ap-southeast-1`) is the international partition and the only one the SG modules model. Per MASTER_PROMPT §8, the interview line is: *"I designed the CN partition and validated the IaC; running it requires a CN-registered account."*

## Consequences

- (+) C1 residency is a reviewable resource graph, not a slide; the flag makes "CN off" the default in every environment.
- (+) Zero spend, zero orphaned-resource risk, honest evidence.
- (−) No runtime CN evidence (no actual PIPL-enforced bucket rejection, no CN KMS envelope demo) — those claims stay "designed, schema-validated" unless the account path reopens.
- (−) Phase 5 must carry the documentation weight (ICP runbook, PIPL Art. 38–40 transfer memo) that a live CN region would partially self-demonstrate.
