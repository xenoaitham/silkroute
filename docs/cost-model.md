# Cost model — SilkRoute SG hub (designed 24/7 footprint, priced from fetched rates)

Maple Retail Group is a **fictional** company; this sheet is a design artifact of a **self-directed reference implementation** (framing per MASTER_PROMPT §9). It prices the Phase-6 landing zone as designed in `infra/` — plan-validated (E-019: `Plan: 87 to add` SG), **never applied** (ADR-0002: no cloud account exists; sim-first).

**Honesty rule, stated once and applying to every row below:** every unit price cites its URL and its fetched-on date; **no price from memory**; prices drift — **the fetch date is part of the claim**. All prices were fetched **2026-09-13** and are recorded verbatim in `evidence/runs/E-025-cost-pricing-fetches.txt` (the single source of prices; each `Src` key maps to its URL in the legend). Currency is **USD throughout**; the last column is the same value in integer **USD minor (cents)** (value × 100, rounded) per the C5 money discipline. Every monthly figure is **derived** and shows its arithmetic inline; every assumption sits next to the number it affects.

## 1. Resource inventory (reconciles to E-019 `Plan: 87 to add`)

| # | Billable resource | Count | Configured spec (from `infra/`) | Priced in |
|---|---|---|---|---|
| 1 | SAE namespace + applications | 1 + 2 | 2 apps × 2 replicas × 0.5 vCPU / 1024 MB, FatJar, private vSwitch (ESB + legacy ERP) | L1 |
| 2 | ApsaraMQ for Kafka | 1 instance + 2 topics + 1 SASL user | deploy_type 4 (VPC), PostPaid, spec_type "normal", 300 GB disk, 12 + 6 partitions | L2 |
| 3 | RDS MySQL | 1 (+ database, account, privilege) | mysql.n2.small.v65, 20 GB cloud_essd, Postpaid, MySQL 8.0, TDE, VPC-only whitelist | L6 |
| 4 | OSS | 4 buckets | artifacts / bronze / silver / gold; SSE-KMS, versioning, public-access-block, bucket policies | L7 |
| 5 | KMS | 2 keys + 2 aliases | alias/silkroute-sg-oss, alias/silkroute-sg-rds (OSS SSE-KMS + RDS TDE) | L3 |
| 6 | NAT gateway | 1 (+ 2 SNAT entries) | Enhanced, spec Small, PayAsYouGo; SNAT covers both private vSwitches | L4 |
| 7 | EIP | 1 | PayByTraffic, 5 Mbps, associated to the NAT gateway | L5 |
| 8 | SLS | 1 project / 4 stores × 2 shards / 4 indexes / 4 alerts / 1 dashboard | TTL 30 d × 3 (esb-app, orders-events, pipeline-metrics) + 180 d audit; full-text + field indexes | L8 |
| 9 | ActionTrail | 1 trail | management events → `audit` store | L10 |
| 10 | CloudMonitor | 1 contact group + 2 alarms | `sae_cpu`, `rds_connections` | L9 |

**Free / non-billable (so the 87 reconciles):** VPC 1, vSwitches 3, security groups 3, SG rules 11, SNAT entries 2, EIP association 1 (network — 21); RAM 9 (3 policies, 2 roles, 3 attachments, 1 user); `random_password` × 2 (Terraform-local, not AliCloud resources); the SLS project/dashboard/alerts carry no separate item fee in the fetched rates — their cost is the usage priced in L8. Count check: network 23 (2 billable + 21 free) + security 13 (4 KMS + 9 RAM) + compute 8 + data 25 (RDS 5 + OSS 5 types × 4) + observability 18 = **87** = E-019 `Plan: 87 to add`.

## 2. Cost line items (monthly, 24/7 designed scale; Src keys → legend below)

| Id | Service | Configured | Unit price (exact quote) | Src | Monthly math (DERIVED, assumptions inline) | USD | USD minor (cents) |
|---|---|---|---|---|---|---|---|
| L1 | SAE | 4 replicas × (0.5 vCPU + 1 GB) | CU price Singapore "0.00001069 USD/CU"; coefficients vCPU 1 CU/vCPU-s, memory 0.25 CU/GB-s | r1 | Per-hour rates **computed-from-quoted-coefficients**: vCPU 1 × 0.00001069 × 3600 = 0.038484 USD/vCPU-h; memory 0.25 × 0.00001069 × 3600 = 0.009621 USD/GB-h. Math: 4 × 0.5 × 0.038484 × 720 = 55.42 + 4 × 1 × 0.009621 × 720 = 27.71. Disk 0: no persistent disk configured; "20 GiB of free temporary disk space by default" per instance | 83.13 | 8313 |
| L2 | ApsaraMQ for Kafka | 1 VPC PostPaid instance, 300 GB, 18 partitions | Standard "alikafka.hw.2xlarge = 0.335 USD/h"; SSD "0.03 USD/h per 100 GB"; 0.000444 USD/partition-h | r8 | **Assumptions recorded next to the numbers:** SG Region-group-1 membership assumed (not verified against buy flow); our IaC `spec_type = "normal"` is the docs' **Standard** tier (naming assumption), hw.2xlarge the matched RG1 VPC size. 0.335 × 720 = 241.20; 3 × 0.03 = 0.09 USD/h × 720 = 64.80; partitions 18 × 0.000444 × 720 = 5.75 (no included-partition quota captured — flagged as a possible over-count). "Billing stops immediately when you release the instance" | 311.75 | 31175 |
| L3 | KMS | 2 software keys + 2 aliases | Software Key Management Instance "USD 500/month" (1000-key quota, 1000 QPS, dual-zone); **no per-key or per-API-call pricing exists** in the current model | r7 | 1 × 500.00. **Material finding: the instance is the cost driver, not the 2 keys.** Alternative named: default keys for cloud-product server-side encryption are **free** — usable only where the design allows (SEC-4-08 currently mandates customer-managed keys for RDS TDE + OSS SSE-KMS) | 500.00 | 50000 |
| L4 | NAT gateway | 1 Enhanced Small + 2 SNAT entries | instance fee "0.0215 USD/hour" (Single-AZ-DR, international); CU fee "0.034 USD/CU" (tier 0–1,000,000 CUs) | r5 | 0.0215 × 720 = 15.48 fixed. CU fee is a **traffic-dependent assumption**: evidence-capture traffic ≈ ≤ 1 CU average → 0.034 × 720 = 24.48 at the generous end; → 0 when idle | 39.96 | 3996 |
| L5 | EIP | 1, PayByTraffic 5 Mbps | config "0.006 USD/hour/IP" (pay-by-data-transfer); outbound "0.081 USD/GB"; inbound free | r6 | 0.006 × 720 = 4.32. Egress **assumption**: low egress in evidence-capture mode, ~10 GB/month → 0.081 × 10 = 0.81 (grows linearly with real egress) | 5.13 | 513 |
| L6 | RDS MySQL † | 1 × mysql.n2.small.v65, 20 GB ESSD | **HONEST GAP:** configured class is console-priced only — **no fetchable public unit price**. Published sibling starter mysql.n2e.small.1 (1C2G): "From $5.52/Month" | r4 | **Labeled assumption, never our class's price:** 5.52 — a *smaller* class's "From" marketing floor; our v65 class may price higher. Storage: formulas fetched only (instance fee = unit price × duration; backup free quota = 200 % of storage) — no storage unit price fetched, so storage is **excluded** from the number. Real RDS cost ≥ 5.52 + unquantified storage | 5.52 | 552 |
| L7 | OSS | 4 buckets | Standard LRS "USD 0.0173 per GB-month" | r3 | **Assumption:** ~10 GB evidence data across the 4 buckets (working estimate, not an IaC quantity) → 10 × 0.0173 = 0.17. Request fees + external traffic are named billable items on the OSS pricing page — **named-not-fetched**; assumed negligible in evidence-capture mode, not priced | 0.17 | 17 |
| L8 | SLS | 1 project, 4 stores × 2 shards | index traffic "0.0875 USD/GB"; storage "0.002875 USD/GB-day"; read/write "0.045 USD/GB" (each: free 500 MB/month; ops free 1 M/month) | r2 | **Assumption:** ~1 GB/day ingest across stores, even split (0.25 GB/day each). Index: (30 − 0.5) × 0.0875 = 2.58. Read/write: (30 − 0.5) × 0.045 = 1.33. Storage (steady state): 30-day stores 0.25 × 30 = 7.5 GB × 3; the 180-day audit store 0.25 × 180 = 45 GB → (3 × 7.5 + 45) = 67.5 GB × 0.002875 × 30 = 5.82. Active shard lease listed as billable — no per-shard-day price captured: named, not priced. First month is lower while stores fill | 9.73 | 973 |
| L9 | CloudMonitor | 2 alarms + 1 contact group | basic monitoring/alerts **free** | r9 | 0.00. Caveat: alert SMS is a separate quota and basic-tier SMS serves +86 numbers only; email/webhook channels remain available | 0.00 | 0 |
| L10 | ActionTrail | 1 trail | **NO CLAIM** — page body did not render at fetch | r10 | Not priced. The trail's real cost surfaces as SLS ingest/index/storage on the `audit` store — already inside L8 | — | — |

† assumption-labeled row.

**Legend (fetched-on stamp per row):** every unit price above is "URL — fetched 2026-09-13".
r1 SAE billing — https://www.alibabacloud.com/help/en/sae/product-overview/billing-new — fetched 2026-09-13 ·
r2 SLS pricing + pay-as-you-go — https://www.alibabacloud.com/en/product/log-service/pricing and https://www.alibabacloud.com/help/en/sls/pay-as-you-go — fetched 2026-09-13 (page caveat: "prices are for reference only" — buy page prevails; "consistent across all regions", SA/BR/NL excepted) ·
r3 OSS storage fees — https://www.alibabacloud.com/help/en/oss/storage-fees — fetched 2026-09-13 ·
r4 RDS billable items + product page — https://www.alibabacloud.com/help/en/rds/apsaradb-rds-for-mysql/billable-items-billing-methods-and-pricing and https://www.alibabacloud.com/en/product/apsaradb-for-rds — fetched 2026-09-13 ·
r5 NAT billing — https://www.alibabacloud.com/help/en/nat-gateway/nat-gateway-billing — fetched 2026-09-13 ·
r6 EIP pay-as-you-go — https://www.alibabacloud.com/help/en/eip/pay-as-you-go/ — fetched 2026-09-13 ·
r7 KMS billing — https://www.alibabacloud.com/help/en/kms/key-management-service/product-overview/kms-billing — fetched 2026-09-13 ·
r8 ApsaraMQ for Kafka pay-as-you-go — https://www.alibabacloud.com/help/en/apsaramq-for-kafka/cloud-message-queue-for-kafka/product-overview/pay-as-you-go-billing-rules — fetched 2026-09-13 ·
r9 CloudMonitor free quota — https://www.alibabacloud.com/help/en/cms/cloudmonitor-1.0/product-overview/free-quota — fetched 2026-09-13 ·
r10 ActionTrail billing — https://www.alibabacloud.com/help/en/actiontrail/product-overview/billing — fetched 2026-09-13 (body did not render; hence no claim).

## 3. Monthly total at designed scale 24/7 — DERIVED

Mid-assumption sum: 83.13 + 311.75 + 500.00 + 39.96 + 5.13 + 5.52 + 0.17 + 9.73 + 0.00 = **955.39 USD/month** = **95539 USD minor (cents)**.

Range given the assumptions: **≈ 930–955 USD/month** — the only traffic-driven lines are the NAT CU fee (0–24.48) and EIP egress (0.81 at the 10 GB assumption, linear beyond); SLS moves a few USD with ingest around the 1 GB/day assumption. The unfetched items (RDS storage unit price, SLS shard lease) sit **outside** the computed number, so the true figure skews slightly **higher**, never lower.

**Honest conclusion:** the dominant lines are the **KMS instance (500.00 USD/mo — 52 %)** and the **Kafka instance (311.75 USD/mo — 33 %)**: two fixed instance fees carry ~85 % of the bill, while all four SAE replicas cost less than the Kafka disk alone. At 24/7 the designed landing zone costs **≈ 930–955 USD/month** — the ~$850–950 order-of-magnitude band, at its upper edge (computed, not asserted). That is exactly why the project operates **sim-first (ADR-0002)** with **destroy-after-evidence (MASTER_PROMPT §8)** and why no cloud evidence exists yet: this sheet is the quantified argument for that operating model. Budget-alarm context: `scripts/budget-alarm.sh` caps the account at **20 USD/month** — under a 24/7 designed-scale deploy that alarm would fire immediately; it is sized for evidence-capture bursts, not steady state.

## 4. CN partition (designed, never applied)

- **Scope:** with `-var enable_cn_region=true` the plan is `Plan: 118 to add` = the 87 SG resources + **31 CN-partition resources** (cn-partition module: 1 VPC, 2 vSwitches, 1 SG, SAE namespace + 1 app, Kafka instance + 2 topics, RDS + database/account/privilege, 5 OSS resource types × 3 buckets, 1 KMS key + alias). Counts per ADR-0005; E-012 (`79/110` when first proven) and E-019 (`87/118` current). **Designed and plan-validated only — never applied.**
- **Prerequisites per ADR-0005:** a **CN-registered account with real-name verification**, and **ICP filing for any public endpoint** (runbook: `docs/icp-filing-runbook.md`). The designed partition has no NAT/EIP and no public endpoint — internal-only by design.
- **No CN monthly estimate is claimed as runnable.** CN region prices were **NOT fetched** (E-025 covers the international/SG pages only), so no CN-specific price appears here. On the same unit-price basis the partition "would cost the same shape, plus its own NAT/KMS instance if replicated" — the SG rows above give the *shape* only, not CN prices; any CN estimate requires its own fetch record before it can exist.

## 5. Re-verification stamp

Every price row above carries "URL — fetched 2026-09-13" (legend, §2). Prices drift; **re-fetch before any real activation** and amend E-025 with the new date — a stale fetched date is treated like an unfetched price.
