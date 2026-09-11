# SilkRoute landing zone - AliCloud (Phase 4, validated-plans mode)

Terraform landing zone for Maple Retail Group's Alibaba Cloud footprint: a
Singapore hub (`ap-southeast-1`, C2 international hub) plus a designed but
never-applied mainland-China partition (`cn-partition/`, C1 PIPL residency).

**Mode (ADR-0002):** everything here is schema-correct and `terraform plan`-able
with placeholder credentials, but nothing is ever applied. Every Phase 4
evidence row is `validated IaC, sim runtime` - plans prove the HCL, not a
deployment. The CN partition (`var.enable_cn_region`) is plan-validated only
(ADR-0005).

## Layout

| Path        | Contents |
|-------------|----------|
| `network/`       | VPC 10.60.0.0/16, 2 private + 1 public vSwitch, enhanced NAT + EIP, `sg-esb`/`sg-erp`/`sg-data` with allowlist rules (no 0.0.0.0/0 inbound) |
| `security/`      | 2 KMS keys + aliases (`silkroute-sg-oss`, `silkroute-sg-rds`), ESB runtime role (SAE trust, least-privilege policy), `silkroute-ci` user + `silkroute-ci-deploy` assume-role |
| `data/`          | RDS MySQL 8.0 (Postpaid, utf8mb4, UTC) + `silkroute_oms` DB/account, OSS lake `silkroute-sg-{artifacts,bronze,silver,gold}` with SSE-KMS, versioning, public-access block, SecureTransport-deny bucket policies |
| `compute/`       | SAE namespace + `silkroute-esb` / `silkroute-erp` apps (ADR-0004), AliCloud Kafka instance + dot-free topics `silkroute-orders-events` / `silkroute-esb-dlq` (ApsaraMQ forbids dots in topic names; the ESB selects them via `KAFKA_ORDERS_TOPIC`/`KAFKA_DLQ_TOPIC`) + SASL user |
| `observability/` | Log project `silkroute-sg` (stores: `esb-app` 30d, `audit` 180d, `orders-events` 30d), ActionTrail -> audit store, overview dashboard, CMS contact group + SAE-CPU / RDS-connection alarms, SLS DLQ-depth alert |
| `cn-partition/`  | Mirror of the SG hub at small scale in `cn-beijing`, every taggable resource tagged `residency=cn`, `data-classification=pipl-restricted`, region pinned via the aliased `alicloud.cn` provider - **design only, see its README** |

## Commands

```bash
export PATH="$HOME/tools/terraform:$PATH"

terraform fmt -check -recursive            # must be clean
terraform init                             # downloads aliyun/alicloud 1.285.0
terraform validate                         # schema check
terraform plan                             # flag false: "Plan: 79 to add..."
terraform plan -var enable_cn_region=true  # CN design: "Plan: 110 to add..."
```

Plans perform **no AliCloud API calls** (placeholder credentials, local empty
state, zero API-calling data sources - AZs are pinned variables). Plan success
is schema evidence only, never proof of deployment.

## State and credentials

- Default backend: local (no `backend "oss"` block). `backend-remote.tf.example`
  documents the OSS + TableStore-locking backend to rename/activate when an
  account exists.
- `providers.tf` consumes `var.access_key` / `var.secret_key` with neutral
  placeholders (`tf-placeholder-*`) purely so plan runs credential-free. In a
  real account these variables are removed and the provider reads
  `ALICLOUD_ACCESS_KEY` / `ALICLOUD_SECRET_KEY` from the environment. No real
  key material, password, or SASL secret is committed; generated secrets
  (`random_password`) exist only to avoid literals and would move to a secret
  manager in cloud mode.
- `terraform.tfvars.example` is fully commented and safe to copy.

## Budget alarm (provider gap - honest note)

The ~\$20 budget alarm is implemented **outside Terraform** via the BSSOpenAPI
`CreateBudget` API of BssOpenApi 2023-09-30 (script: `scripts/budget-alarm.sh`),
because provider 1.285.0 ships **no** budget/BSS resource type (verified by
case-insensitive grep over all 1161 resource names in `terraform providers
schema -json`). Console path if you prefer clicking: BSS console -> Budgets ->
Create budget (amount ~\$20, alert at 80%/100%).

## Compliance hooks (machine-checkable)

- **C1 (PIPL):** CN partition is region-pinned **at the provider level** — the
  module gets its own `providers = { alicloud = alicloud.cn }` alias
  (`region = var.cn_region`), so placement is enforced by the provider graph,
  not by tags or name strings (fixed per SEC-4-01); no cross-region
  replication anywhere; CN log isolation is designed (zero cross-region
  shipping resources exist) but the CN SLS project itself lands with CN
  activation — observability is SG-only today; `residency=cn` +
  `data-classification=pipl-restricted` tags on every **taggable** CN resource
  (the SAE namespace has no tags attribute in provider 1.285.0 and is pinned
  by the region prefix in its `namespace_id` instead).
- **C2 (SG hub):** no public endpoints in this landing zone; mainland public
  web presence would need an ICP filing (documented in `cn-partition/README.md`).
- **C5 (multi-currency/multi-timezone):** RDS stores UTC (`time_zone=+00:00`
  parameter); SAE apps run `timezone=Asia/Singapore` (SG) / `Asia/Shanghai`
  (CN); the SLS alert schedule is timezone-explicit.
- **C7 (PIPEDA cross-border):** the SG hub is the documented transfer point for
  non-restricted data; China-customer PII never leaves the CN partition, which
  is exactly why the CN module exists as a separate, separately-applied unit
  (transfer-mechanism memo lives in `docs/`, Phase 5 wiring).

## Trust boundaries (stated, not hidden)

- The `silkroute-ci-deploy` role **is the IAM trust boundary**: it can author
  and attach `silkroute-*` policies, so compromise of `silkroute-ci` equals
  compromise of the landing zone. Accepted for a single-pipeline landing zone;
  at activation this can be split into a separately-assumed governance role.
- `actiontrail:StopLogging/DeleteTrail` sit in the CI deploy policy for
  lifecycle symmetry; at activation, move them to a break-glass role so a
  compromised pipeline cannot silence the audit trail.
- AliCloud RAM cannot pin an SAE service trust to one application, and
  provider 1.285.0 exposes no RAM-role attribute on `alicloud_sae_application`
  — the app→`silkroute-esb-runtime` credential binding is an activation-time,
  out-of-band step; record it in evidence when it happens.

## Activation checklist (when a real account exists)

1. Budget alarm first: `scripts/budget-alarm.sh` (BssOpenApi `CreateBudget`, ~$20).
2. Replace placeholder credentials/account-id with env/profile auth + real
   account id; move RDS/Kafka passwords from `random_password` state into KMS
   secrets, and restrict state access.
3. `make deploy-sg` (guarded), capture plan/apply, then `make destroy` — §8.
4. Confirm the exact alikafka instance ARN form (`*instances/*` in the CI
   policy is a deliberate hedge pending a live ARN) and tighten it.
5. Verify at runtime: SSE-KMS upload with `kms:GenerateDataKey`, SLS ingest,
   STS assume-role from CI, TDE enablement on both RDS instances.
