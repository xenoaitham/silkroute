# Whiteboard walkthrough — 25:00 timed script

Maple Retail Group is a fictional company; SilkRoute is a self-directed reference implementation (2026).

Rehearse against a timer. SAY lines are first person, written to be spoken. Numbers are only from `evidence/EVIDENCE.md` (E-001…E-025) — if a number isn't in the ledger, don't say it. Say "validated IaC, sim runtime" every time cloud IaC appears; volunteer it, don't wait to be asked.

Legend: [BOARD] = what to draw; [SAY] = what to say; [CITE] = the measured number and its evidence ID.

---

## 00:00–02:30 — Scenario framing

[BOARD] Center-left: a box "Legacy ERP (on-prem, SOAP 1.2/WSDL)" with a padlock on it. Right of it: "300 stores Canada + e-commerce" flowing in. Two arrows out to new boxes: "Singapore — intl hub" and "Mainland China — marketplace + stores". Beneath: a strip listing C1–C7 as keywords only: `PIPL residency / SG hub no-ICP / p95<300ms / CDC≤15min T+1 06:00 SGT / CAD-SGD-CNY / WSDLs frozen / PIPEDA transfers`.

[SAY] "The scenario I built against: a Canadian retailer — fictional, and I'll keep labeling it that way — expanding into Singapore and mainland China. It has an on-prem ERP exposing SOAP 1.2 services that I must treat as untouchable. Seven hard constraints drive every decision: Chinese PII stays in China under scenario C1 (PIPL-driven residency); Singapore is the international hub; sync mediation p95 under 300 milliseconds; CDC fresh within 15 minutes and T+1 batch done by 06:00 Singapore time; three currencies as integer minor units; the ERP team will not modify their WSDLs; and Canada's PIPEDA accountability for cross-border flows. I built this as a reference implementation with real running code — let me show you the pieces."

[CITE] None yet — this section is framing. One sentence on honesty: "The code, tests, and measurements are real; the retailer is not."

## 02:30–05:00 — Dual-mode architecture

[BOARD] Two columns joined by a horizontal "same interfaces" bar. Left column labeled **sim (docker-compose)**: MySQL 8 → Kafka 3.8 KRaft → Redis 7 → MinIO → Toxiproxy. Right column labeled **AliCloud (Terraform)**: RDS MySQL → ApsaraMQ Kafka → ApsaraDB Redis → OSS (SSE-KMS) → SAE. Note under the bar: "swap = config only, no code changes (ADR-0001)".

[SAY] "It runs dual-mode by design. Locally, a docker-compose sim mirrors the managed services' interfaces — MySQL wire protocol, Kafka API, S3 API — five pinned services, later widened with a dedicated ERP fault-injection proxy, so every runtime behavior is reproducible offline at zero spend. The Terraform models the real AliCloud services. Application code speaks only to interfaces, so the swap is configuration, not code. The sim converges from a clean state in about 28 seconds and every session ends with a smoke check that's provably falsifiable — I'll show you that proof in a minute."

[CITE] "~28 s convergence (E-001); smoke asserts all six services, exit 0, re-proven at pack time (E-002 as captured, E-015 fresh)."

## 05:00–09:00 — The order saga end-to-end

[BOARD] Left to right pipeline of boxes: `POST /api/v1/orders (REST, loopback)` → `Schema validation (JSON Schema)` → `Idempotency (Redis SETNX+TTL)` → `Saga: order → reserve(per line) → pricing(per line) → confirm` → `XSLT ↔ canonical model` → `CXF SOAP 1.2 + WSS UsernameToken` → `ERP (frozen WSDLs)`. Fork below the saga: `success → Kafka orders.events` and `failure → compensate / DLQ`. Label the canonical model box "urn:maple:canonical:order:v1 — the only shared shape".

[SAY] "One order, end to end. The REST façade validates the body against a versioned canonical JSON Schema, then claims idempotency in Redis — a replay gets a 409 carrying the original orderId. The saga makes four typed SOAP calls: submit the order, reserve inventory per line, price per line, confirm status. Every SOAP leg goes through XSLT into and out of the canonical model — that's where all impedance mismatch lives, because the ERP's WSDLs are frozen. Auth is WS-Security UsernameToken on the wire. The measured happy path returns 201 with an exact integer total — CAD 500 minor — plus per-step saga state and per-call attempt counts. Success and failure both publish to Kafka, with Chinese customer references pseudonymized before any shared topic."

[CITE] "201 + CAD 500 minor captured verbatim; same-key replay → 409 DUPLICATE with original orderId (E-008)." "18/18 black-box contract scenarios against the frozen WSDLs, including a schema negative test that rejects USD money (E-004, E-005)."

## 09:00–12:30 — Resilience under fault injection

[BOARD] Draw toxiproxy as a small box between ESB and ERP labeled "fault injection: latency / hard-down / step-fail". Then four rows, each a mini-timeline: (1) `2500ms latency → attempts=3 → 201`; (2) `timeout ambiguity → attempts=2, ERP dup-ref guard holds — no double order`; (3) `ERP hard-down → OPEN breaker → fail-fast 4ms → DLQ(correlationIds) → heal → 201`; (4) `pricing fails → 503 SAGA-COMPENSATED → holds released: stock 19→19 → DLQ(compensated:true, msk-****)`.

[SAY] "Everything here is proven under real fault injection, not claimed. Persistent latency: the retry ladder records three attempts and recovers. Ambiguous timeout — the interesting one: attempt one times out while the ERP may have committed; the frozen ERP's duplicate-ref guard means no double order, and my suite asserts both honest endings instead of pretending there's only one. Hard-down ERP: the circuit breaker opens and fail-fast returns in 4 milliseconds, with the dead-letter queue carrying the exhausted correlation IDs; when the proxy heals, we're back to 201. Pricing failure mid-saga: 503, saga compensated, and the proof the compensation is real — the ERP stock row reads 19 before and 19 after, the hold genuinely released. Releases ride a breaker-free route so compensation works even while the breaker is open."

[CITE] "attempts=3; attempts=2 with no duplicate; CIRCUIT-OPEN 4 ms; stock 19→19; DLQ masked — 8/8 scenarios, zero failures (~83 s) (E-009)."

## 12:30–14:00 — Measured latency

[BOARD] Write large: `p95 44.25 ms` over `budget 300 ms`. Under it: `20 RPS target → 18.63 achieved · 1304 iters / 70 s · median 18.25 ms · checks 98.61%`.

[SAY] "Constraint C3 says sync mediation p95 under 300 milliseconds, so I measured it rather than asserted it. k6 constant-arrival-rate against the real REST façade: 1304 iterations over 70 seconds, p95 44 and a quarter milliseconds — roughly one-seventh of the budget. Checks are 98.61 percent, and I can account for every failure: all 18 non-201s are out-of-stock business outcomes from seeded stock rows legitimately exhausted mid-run — zero infra failures. The k6 script deliberately has no thresholds; it records the measured p95 into a JSON summary so the number can't be gamed into a pass. Honest context: sim mode, everything localhost, a consumer desktop — in-region cloud numbers will differ, but the measurement discipline is identical."

[CITE] "p95 44.2480541 ms, median 18.25 ms, 18.63 RPS, 1304 iterations, 98.61% checks (E-010)."

## 14:00–18:30 — The AliCloud landing zone

[BOARD] Two dashed regions. **SG hub (ap-southeast-1)**: VPC 10.60.0.0/16 with 2 private + 1 public vSwitch, NAT/EIP, SGs (allowlist arrows ESB→ERP→data, admin CIDR), then stacked module boxes: KMS×2, RAM roles, RDS MySQL 8 (SSE/TDE), OSS lake ×4 buckets, SAE apps, ApsaraMQ, SLS + ActionTrail + CMS alarms. **CN partition (cn-beijing)**: mirror with its own VPC 10.70.0.0/16, CN-only KMS/OSS/RDS, tags `residency=cn`, and a big X over "NAT/egress" — "no egress path". Between them: `alicloud.cn alias → providers meta-argument` on an arrow into the CN region. Header line: `var.enable_cn_region ? 118 : 87 resources`.

[SAY] "The landing zone, in validated IaC, sim runtime mode — meaning fmt, validate, and plan are proven against the real pinned provider, plans perform zero API calls, and nothing was ever applied, because no account exists. Singapore hub: 87 planned resources across network, security, data, compute on SAE, and observability — SLS for logs, CloudMonitor for metrics, ActionTrail shipped to an audit store for who-did-what, and four SLS alert rules on the modern alicloud_sls_alert resource: DLQ depth, the 300-millisecond p95 budget breach, a denied-action burst on the audit store, and a zero-tolerance trail-tamper tripwire. With the CN flag on, 118: the China partition is pinned at the provider graph via an aliased alicloud.cn provider — residency lives in the provider meta-argument, not in tags — with its own VPC, KMS, buckets, and deliberately no NAT, so there is no egress path at all. IAM is zero-wildcard on actions; the only wildcard Principal sits inside Deny statements on insecure transport, where it narrows access. An independent least-privilege review returned one high, three medium, six low — all fixed and re-proven by green plans."

[CITE] "`Plan: 87 to add` SG / `Plan: 118 to add` with `enable_cn_region=true` (79/110 at the Phase-4 gate; +8 = alert-layer migration, store indexes, pipeline-metrics store — E-019); zone_id/namespace_id `cn-beijing-*` in planned values; wildcard-action grep = 0; review 1 HIGH + 3 MED + 6 LOW all fixed (E-012, E-013)."

## 18:30–21:00 — Residency & compliance posture

[BOARD] Small table, three rows: `PIPL (CN)` → `Art. 38: security assessment / standard contract / certification (+ other conditions by law/CAC) · Art. 39: notify + separate consent · Art. 40: local storage for CII + volume threshold` ; `PDPA (SG)` → `s.26 transfer limitation: comparable standard` ; `PIPEDA (CA)` → `cl. 4.1.3 accountability: contractual means`. Beside it: `ICP: mainland-hosted services = filing required; SG-hosted internal APIs = no filing`. Under the table: `enforced in build: provider pinning · tags · no replication · masked egress · residency checks in CI (static + selftest)  |  next: runtime proof at activation`.

[SAY] "The compliance posture, honestly stated. The design avoids the hard problem: CN PII never leaves the CN partition, so no PIPL cross-border mechanism is triggered — Article 38's routes exist if a transfer ever became necessary, Article 39 adds notification and separate consent, Article 40 mandates local storage for critical infrastructure and CAC-threshold volumes. Singapore's transfer-limitation obligation and Canada's accountability clause are both about guarantees following the data — contractual means, comparable protection — which is what the transfer memo maps per edge. ICP filing applies only to services hosted on servers in the Chinese mainland; our Singapore-hosted internal APIs need none. What's built and measured: region pinning, tags, no cross-region replication, masked egress — and a static residency suite in CI on every push, with a selftest that injects five violations and requires the checks to catch them. The written layer backs this up: a control matrix mapping PIPL/PDPA/PIPEDA obligations to what's enforced versus designed, a per-edge cross-border transfer memo, an ICP filing runbook, a STRIDE model of this exact architecture, and a sim-mode audit-query demo — "who did what" against a seeded audit-event schema. The honest gap: those checks are static — runtime proof, like a bucket actually rejecting a cross-region read, waits for an account."

[CITE] "Masked CN egress: `msk-`+HMAC, clear value absent in DLQ events (E-009); no cross-region replication resources — grep-verified (E-012); residency suite 7/7 + five-violation selftest in CI (E-016); audit-query demo with negative control (E-017)."

## 21:00–23:00 — War-story beat

[BOARD] Write: `plan: ✓ · apply: ✗` then `topic: silkroute.orders.events` crossed out → `silkroute-orders-events`. Arrow: `lint (tf-apply-validity.sh) in CI`.

[SAY] "One war story that shaped the whole IaC discipline. Every plan was green while the Kafka topic names contained dots — and ApsaraMQ for Kafka's CreateTopic rejects dots at apply time. Plan-green, apply-impossible. The same class of blind spot had already bitten placement: the CN module said cn-beijing in every string and was still going to land in Singapore, because Terraform places resources at the provider graph. So three fixes: dot-free cloud topic names selected by environment variables — the sim keeps its defaults, swap stays configuration-only — an apply-validity lint in CI for known plan-invisible API rules with a proven negative control, and region enforced by the aliased provider. The lesson I took: a plan proves schema; placement, API rules, and apply ordering need their own enforcement."

[CITE] "Provider 1.285.0 pinned; lint negative control proven; CN planned values `cn-beijing-*` (E-012)."

## 23:00–25:00 — Trade-offs and what's next

[BOARD] Two columns. **Trade-offs I made**: `validated-plans, no apply` / `SAE not ACK (pay-per-use)` / `budget alarm as script (provider has none)` / `hand-rolled saga for exact accounting`. **Next**: `residency runtime proof at activation (static checks already in CI)` / `ETL: Debezium→Kafka→bronze/silver/gold (designed, NOT built)` / `activation checklist: budget first, secrets to KMS, deploy→capture→destroy`.

[SAY] "The trade-offs I stand behind, stated plainly. Cloud side is validated-plans — a plan proves schema, not deployment, and I say that every time. Compute is SAE over ACK because pay-per-use beats an idle nodepool, and over Function Compute because a Camel hub with persistent Kafka consumers can't cold-start under a 300 ms budget. The budget alarm is an executable script against the BSS OpenAPI because the provider genuinely has no budget resource — schema-proven — and faking one would be fabrication. The saga is hand-rolled for exact step accounting. What's next, in order: runtime residency proof once an account exists — the static residency checks already gate every push; then the ETL phase — Debezium into Kafka, bronze immutable and region-tagged, silver conformed, gold star schema with fact_orders and fact_order_lines — that is designed, not built, and I'll quote no numbers for it. And with an account: budget alarm first, secrets to KMS, deploy, capture evidence, destroy. Happy to go a level deeper on any box on this board."

[CITE] "Budget: schema grep = 0 budget resources; dry-run payload printed; deploy/destroy refuse without confirmation (E-014). CI green at HEAD, 4/4 jobs incl. the fault-injection suite on a hosted runner (~186 s) (E-011, E-012)."

---

## If cut to 10 minutes — collapse note

Keep the same board, drop the detail:

| Time | Keep | Cut |
|---|---|---|
| 00:00–01:30 | Scenario + C1–C7 one-liner (02:30 section, compressed) | Constraint-by-constraint read |
| 01:30–03:00 | Dual-mode, one sentence + sim convergence 28 s (E-001/E-002) | Service-by-service mapping |
| 03:00–05:30 | Order saga board + CAD 500 minor + 409 (E-008) | XSLT stylesheet enumeration |
| 05:30–07:30 | Resilience rows 3 and 4 only — 4 ms circuit-open, stock 19→19 (E-009) | Retry-ladder and ambiguous-timeout detail |
| 07:30–08:30 | Latency: p95 44.25 ms vs 300 ms (E-010) | RPS/iterations detail |
| 08:30–09:30 | Landing zone headline: 87/118, provider-level pinning, "validated IaC, sim runtime" (E-012 gate-era 79/110, E-019) | IAM and observability detail |
| 09:30–10:00 | One honest closer: residency designed + validated, CI tests next; ETL designed, not built | War story and trade-off columns |

Non-negotiables even at 10 minutes: the fictional-company label, "validated IaC, sim runtime" whenever the board shows cloud IaC, and "ETL: designed, not built."
