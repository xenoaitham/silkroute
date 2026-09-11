# cn-partition (DESIGNED, NEVER APPLIED - ADR-0005)

The China-mainland partition for Maple Retail Group: the same shape as the
Singapore hub at small scale, pinned to `cn-beijing` (AZs `cn-beijing-g` /
`cn-beijing-h`), VPC `10.70.0.0/16`. Region placement is enforced **at the
provider level**: the root composes this module with
`providers = { alicloud = alicloud.cn }` where the `cn` alias sets
`region = var.cn_region` (SEC-4-01 fix — without the alias, every "CN"
resource would silently inherit ap-southeast-1). Every taggable resource is
tagged:

- `residency=cn`
- `data-classification=pipl-restricted`

so the PIPL constraint (C1: Chinese-customer PII stays in the China region) is
machine-checkable from tags alone. (The SAE namespace carries no tags —
provider 1.285.0 gives `alicloud_sae_namespace` no tags attribute; its
residency is pinned by the region prefix in `namespace_id` plus the provider
alias.)

## Contents

- VPC + 2 private vSwitches (no NAT/EIP - no egress design, no public endpoints)
- `sg-cn-esb` security group, intentionally rule-free until activation
- KMS key + `alias/silkroute-cn-data` (CN key material never leaves cn-beijing)
- RDS MySQL 8.0 with **TDE enabled** (`tde_status = "Enabled"`), utf8mb4, UTC
- OSS `silkroute-cn-{bronze,silver,gold}`: SSE-KMS with the CN key, versioning,
  public-access block, SecureTransport deny. **No cross-region replication.**
- SAE namespace `cn-beijing:silkroute-cn` + `silkroute-esb-cn`; every env var
  (`KAFKA_BOOTSTRAP`, `REDIS_HOST`, `ERP_BASEURL`, ...) resolves inside the CN
  partition - no SG cross-border calls
- AliCloud Kafka `silkroute-cn-kafka` + topics `silkroute.orders.events` /
  `silkroute.esb.dlq` (sim-identical names, separate brokers, no replication to SG)

## Why it is validated-plan-only here

Applying this module requires:

1. **A CN-registered AliCloud account** - mainland-region accounts need
   real-name verification (business license for a corporate account), which a
   non-CN entity cannot complete overnight and this project does not have.
2. **ICP filing for any public endpoint (C2)** - a public web presence or
   public API in mainland China requires an ICP beian (via the CN account,
   typically 2-4 weeks; commercial services need an ICP license, not just a
   filing). Internal/VPC-only endpoints like everything in this module do not
   need ICP - the design deliberately publishes nothing.
3. **A PIPL Art. 38-40 cross-border transfer assessment (C7)** - runs before
   any SG<->CN data flow design; until then there is no cross-border path,
   which is why the env vars point only at CN endpoints.

Per ADR-0002/ADR-0005 the module is therefore composed at root with
`count = var.enable_cn_region ? 1 : 0` (default false), kept
`terraform validate`-clean, and proven plan-able:

```bash
terraform plan -var enable_cn_region=true   # "Plan: 104 to add, ..." - evidence only
```

Root outputs use the plan-safe `one(module.cn_partition[*].attr)` form so the
flag-false plan completes with CN outputs evaluating to null.
