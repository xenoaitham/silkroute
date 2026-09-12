# Q&A deep drill — 40 questions

Maple Retail Group is a fictional company; SilkRoute is a self-directed reference implementation (2026).

Rehearsal material. Each answer = the crisp spoken version (say it out loud), then the anchor you can point to. Volunteer the honest framing before you are asked; never let the interviewer discover the framing.

## Coverage map

| Section | Questions |
|---|---|
| AliCloud | Q1–Q10 (landing zone, region strategy, provider pinning, IAM, KMS, SLS/CMS/ActionTrail, SAE trade-off, budget alarm, validated-plans, network) |
| ESB / SOAP internals | Q11–Q20 (order flow, contracts-first, freeze, WSS wire, SOAP faults, canonical/XSLT, saga, idempotency, breaker/retry/DLQ, latency) |
| ETL — DESIGN ONLY, not built | Q21–Q24 (CDC, lake layering, DQ gates, reconciliation + windows) |
| Compliance | Q25–Q30 (PIPL 38/39/40, PDPA s26, PIPEDA 4.1.3, ICP, residency enforcement, PII masking) |
| Delivery model & trade-offs | Q31–Q36 (sequencing, dual-mode, with-an-account, money, test strategy, real-team rollout) |
| War stories (failure → root cause → fix → lesson) | Q37–Q40 (smoke that couldn't fail, double-encoding + ambiguous timeout, plan-green/apply-impossible, WSS wire) |

Every number is measured and traces to `evidence/EVIDENCE.md` (E-001…E-015). The ETL answers (Q21–Q24) contain no numbers because nothing was built or measured there.

---

## AliCloud

### Q1. Walk me through your landing-zone module structure. Why split it that way?

Six modules: `network`, `security`, `data`, `compute`, `observability`, and a separate `cn-partition`. Network owns the VPC (10.60.0.0/16), two private plus one public vSwitch, the NAT gateway/EIP, and the security groups. Security owns two KMS keys with aliases, the ESB runtime role and policy, and the CI user-to-assume-role chain. Data owns RDS MySQL 8 and the OSS lake buckets (`silkroute-sg-{artifacts,bronze,silver,gold}`). Compute owns SAE applications and the ApsaraMQ Kafka instance and topics. Observability owns the SLS project, log stores, dashboard, CMS alarms, and ActionTrail shipping. CN sits alone because it must be a separately-applied, separately-accounted unit — residency (C1) is a blast-radius boundary, so the module boundary matches it. The split mirrors ADR-0001's interface mapping, so each module corresponds to the thing it replaces in sim.

Evidence/anchor: E-012; `infra/README.md` layout table; ADR-0001.

### Q2. Why a Singapore hub, and what exactly is the "CN partition"?

C2 makes Singapore the international hub — `ap-southeast-1` hosts the ESB, the Kafka backbone, and the lake, and needs no ICP filing for internal APIs. C1 (PIPL) requires Chinese-customer PII to stay in mainland China, so the CN partition is a mirror at small scale in `cn-beijing`: its own VPC (10.70.0.0/16), its own KMS key, its own OSS buckets with SSE-KMS, its own SAE namespace (`cn-beijing-*` visible in planned values), CN Kafka topics tagged for residency — and deliberately no NAT gateway or EIP, so the CN VPC has no egress path at all. It sits behind `var.enable_cn_region` and is plan-validated both ways: 79 resources for the SG hub alone, 110 with the flag on. It has never been applied — that needs a CN-registered account.

Evidence/anchor: E-012; ADR-0005; `infra/cn-partition/`.

### Q3. How do you actually force Terraform to place resources in cn-beijing?

At the provider graph, nowhere else. The CN module gets its own aliased provider block — `alicloud.cn`, `region = var.cn_region` — and the module block wires it with the `providers = { alicloud = alicloud.cn }` meta-argument. Every resource in the module then inherits that provider. This is the review's HIGH-finding fix: the original module had every `cn-beijing` string correct and the placement still Singapore, because there was one unaliased provider and tags don't place resources. Terraform's resource placement is provider-level; names and tags are descriptions, not constraints. The proof is in the plan values: `zone_id` and `namespace_id` show `cn-beijing-*`.

Evidence/anchor: E-012 (planned values `cn-beijing-*`); E-013 (HIGH finding — region pinning — fixed); ADR-0005; war story 15 in STATE.md.

### Q4. "Least privilege" — show me. What does zero-wildcard mean in your IAM?

Zero wildcard actions: no `"Action": "*"`, no `service:*`, no partial-action wildcards — a grep over every policy in the tree returns zero. The nuance worth stating: wildcards still exist in `Resource` segments where the ARN convention forces them (create-time resource IDs, the account segment in OSS ARNs), and `Principal: ["*"]` appears exactly once per bucket — inside an `Effect: "Deny"` statement conditioned on `acs:SecureTransport = "false"`, the canonical deny-insecure-transport pattern. A Deny with a wildcard principal narrows access; that's the opposite of an Allow-all. An independent review of the zone returned 1 HIGH + 3 MED + 6 LOW — the HIGH being the CN region pinning — and every finding was fixed and re-proven by green plans. The CI identity holds exactly `sts:AssumeRole` on the deploy role; nothing else.

Evidence/anchor: E-013 (interpretation verbatim in §1 of `compliance/iam-review-phase4.md`; wildcard grep = 0); `infra/security/main.tf`.

### Q5. How does SSE-KMS actually work on an OSS write, and why did your review flag GenerateDataKey?

Envelope semantics: on a PUT to an SSE-KMS bucket, the service calls KMS to generate a data key — that is the `kms:GenerateDataKey` permission — encrypts the object with the data key, and stores the wrapped key alongside. Reads need `kms:Decrypt` on the same key. My runtime role had Decrypt only, so the reviewer's point was that SSE-KMS uploads would fail at cloud runtime even though the IAM looked tidy. Fixed by adding `GenerateDataKey` scoped to the two named SG key ARNs. The same review added TDE with a `tde_encryption_key` on both RDS instances, so encryption at rest is keyed, not provider-default. Two KMS keys with aliases (`silkroute-sg-oss`, `silkroute-sg-rds`) keep the data plane and database key domains separate.

Evidence/anchor: E-013 (review finding: missing `kms:GenerateDataKey` for SSE-KMS writes, fixed); `infra/security/main.tf`; `infra/data/main.tf`.

### Q6. How do you split observability between SLS and CloudMonitor, and where does ActionTrail fit?

SLS is for logs and everything you want to query: the `silkroute-sg` project holds `esb-app` (30-day retention), `orders-events` (30 days), and `audit` (180 days); there's an overview dashboard and an SLS alert on DLQ depth. CloudMonitor (CMS) is for metric alarms — SAE CPU and RDS connections — with a contact group. ActionTrail is the control-plane audit log: every API call "who did what", shipped to the `audit` store, which is exactly the store an auditor asks about. The split rule I use: application and event logs to SLS where you query them, infrastructure metrics to CMS where you page on them, control-plane actions to ActionTrail where you investigate them. One honest note: the SLS alert resource uses a deprecated `notification_list` argument; the modern `alicloud_sls_alert` exists in provider 1.285.0 and migrating is a recorded follow-up.

Evidence/anchor: E-012; `infra/observability/`; ADR-0004 consequences (sls_alert correction).

### Q7. Why SAE and not ACK or Function Compute?

Three-way trade-off, recorded as ADR-0004. The workload is a long-running Spring Boot/Camel hub holding persistent Kafka consumers with a synchronous 300 ms budget (C3). Function Compute is cheapest at idle but cold-starting a Camel hub with persistent consumers behind a synchronous SLA is architecturally wrong — rejected. ACK gives the deepest Kubernetes surface, but an always-on nodepool costs money while idle and adds cluster ops no constraint in the scenario requires — rejected on cost discipline. SAE is pay-per-use replica-hours, deploys the existing fat jar straight from the OSS artifacts bucket, and namespaces give prod/CN isolation for free. Trade-off honestly stated: I don't demonstrate raw Kubernetes depth (PDBs, spot interrupts) in this project, and I say so.

Evidence/anchor: ADR-0004; E-012 (SAE apps in `infra/compute`, `cn-beijing` twins in `infra/cn-partition`).

### Q8. Where's your budget alarm in Terraform?

It isn't in Terraform, and that is the honest answer. Provider 1.285.0 ships 1161 resources and a case-insensitive grep over the schema finds zero budget/BSS/billing resources — a Terraform "budget alarm" resource would be fabrication. So the alarm is `scripts/budget-alarm.sh`, targeting the BssOpenApi `CreateBudget` API (version 2023-09-30 — doc-verified, URL cited in the script header), with BudgetType=CONSUME, Metric=REQUIRE_AMOUNT, monthly ~$20 quota, and WarnConfs alerting. It's dry-run by default: it prints the exact request payload and performs no API call; live mode needs LIVE=1 plus credentials. The billable targets are double-guarded — `make deploy-sg` and `make destroy` refuse (exit 2) without an explicit confirmation variable and credentials. My catch: I first wrote the API name from memory as `SetBudgets`, which doesn't exist; the doc-verification pass corrected it to `CreateBudget`.

Evidence/anchor: E-014; `scripts/budget-alarm.sh` header; ADR-0004; war stories 16/19 in STATE.md.

### Q9. What does a green `terraform plan` prove, and what does it NOT prove?

It proves the HCL is schema-correct against the real pinned provider — resource types, argument names, region semantics — and that the composition is resolvable: 79 resources for the SG hub, 110 with the CN flag. My plans perform no AliCloud API calls at all: placeholder credentials, empty local state, zero API-calling data sources. What it does not prove is apply. Two concrete catches made that vivid. First, ApsaraMQ for Kafka's CreateTopic rejects dots in topic names — the plan was green on topic names that could never survive an apply. Second, my snat_entries were plan-valid but racy at apply because CreateSnatEntry needs the EIP associated first — now encoded as `depends_on`. So I run an apply-validity lint in CI for known plan-invisible API rules, with a proven negative control, and every cloud evidence row is labeled "validated IaC, sim runtime". The only thing that proves a deployment is a deployment.

Evidence/anchor: E-012; ADR-0002; `scripts/tf-apply-validity.sh`; war stories 18/22 in STATE.md.

### Q10. How did you keep the ESB off the internet in the landing zone?

No 0.0.0.0/0 inbound anywhere — grep-verified; the string exists only in comments forbidding it. Ingress is security-group-to-security-group: ERP 18080 reachable from `sg-esb` only, MySQL 3306 from `sg-esb`/`sg-erp`, Redis 6379 from `sg-esb`, and admin ports 18081/18082 from a placeholder office CIDR. All single-port rules, priorities pinned, every rule described. The enhanced NAT gateway on the one public vSwitch is the single egress path. One honesty item I caught myself: basic AliCloud security groups are default-allow on egress, so my "egress denied by default" comment was prose, not enforcement — the code comments now say exactly that, and advanced security groups are deferred to activation. The CN security group goes further: rule-free by design, closed by default.

Evidence/anchor: E-012; `compliance/iam-review-phase4.md` §4 (network exposure: no findings); war story 20 in STATE.md; `infra/network/main.tf`.

---

## ESB / SOAP internals

### Q11. Walk me through one order, end to end.

POST to `/api/v1/orders` on the REST façade (loopback 18081). First the body is validated against the canonical JSON Schema — a violation is a 400 SCHEMA-VIOLATION. Then idempotency: Redis SETNX on the idempotency key; a replay gets 409 DUPLICATE carrying the original orderId. Then the saga orchestrator: submitOrder to the ERP, inventory reserve per line, priceForSku per line, getOrderStatus to confirm SUBMITTED. Every ERP call is CXF SOAP 1.2 with WS-Security UsernameToken, transformed by XSLT stylesheets on both legs — canonical to legacy request, legacy response to canonical. The response is 201 with an exact integer total (CAD 500 minor in the captured run), the per-step saga state, per-call attempt counts, and the route region. Success and DLQ events publish to Kafka with the CN customer reference pseudonymized first.

Evidence/anchor: E-008; `apps/esb/README.md` architecture diagram.

### Q12. Why WSDL-first? Why contracts-first at all?

Because the interesting party is the one you don't control. The scenario constraint C6 says the ERP team will not modify their WSDLs, so the contract has to exist and be frozen before any consumer logic is written, and the ESB client is generated from the frozen file — wsdl2java makes WSDL-first mechanical, not aspirational. I proved the discipline: the three WSDLs and four XSDs were authored and xmllint-validated, including instance validation positive and negative — an instance with USD money is rejected by the CAD/SGD/CNY enum — all before a single line of Java existed. The ESB's CXF client config points at the legacy-erp resource directory as the single source of truth, so there's one contract, not a copy that can drift.

Evidence/anchor: E-004 (7/7 files, USD negative exit 3); ADR-0003; `apps/legacy-erp/src/main/resources/{wsdl,xsd}/`.

### Q13. How did you enforce the contract freeze?

Three layers. The ADR records the freeze decision and moment; the git tag `contract-freeze-erp-v1` pins the exact commit; and each WSDL/XSD header carries a freeze comment. The enforcement is the Karate contract suite: 18 black-box scenarios over the wire asserting element names, namespaces, fault details, error codes, money shapes, and WS-Security behavior, running in CI on every push — any contract mutation that breaks a consumer fails the build by construction. Two recorded nuances: incompatible changes ship as v2 namespaces alongside v1, never as edits; and CXF re-serializes the served `?wsdl` (comments stripped, imports rewritten), so byte-diffing is not a valid integrity check — the tests are.

Evidence/anchor: ADR-0003; E-005 (contract 18/18); `tests/contract/`.

### Q14. What does WS-Security UsernameToken actually look like on the wire?

The outgoing interceptor sends action=UsernameToken with passwordType=PasswordText, a fresh Base64 nonce per request with EncodingType `...#Base64Binary`, and a UTC `wsu:Created`. The wire fact that cost me a debugging session: the Password `Type` must be the OASIS UsernameToken **profile URI** (`...oasis-200401-wss-username-token-profile-1.0#PasswordText`). Using the secext element namespace instead makes WSS4J classify it as an unknown custom password type and reject it — I confirmed against the shipped validator source: line 79 checks the required type, line 96 rejects custom types when handleCustomPasswordTypes is false. The nonce EncodingType requirement is BSP:R4220. After that session, WSS auth on the ESB client worked first try. Sim credentials are documented dummies (esb-client / erp-wss-pass-2026).

Evidence/anchor: E-005 (security scenarios incl. nonce replay); war story 7 in STATE.md; `apps/esb/README.md` "WS-Security wire facts".

### Q15. SOAP 1.2 document/literal wrapped with typed faults — and the Receiver/Sender nuance?

Three WSDLs — orders, inventory, pricing — in `urn:maple:erp:<domain>:v1`, document/literal wrapped, with typed faults declared on a shared `ErpFaultType` detail (InvalidOrderFault, OutOfStockFault carrying availableQuantity, UnknownSkuFault, UnknownReservationFault). The nuance: on the wire, CXF's default fault code for declared faults is `soap:Receiver`, even though these are semantically Sender-class errors — the caller sent the invalid SKU. The frozen WSDLs don't constrain fault codes, so the fix lives in the consumer: the ESB's error classifier ignores the wire fault code and reads the typed detail element to classify the error as BUSINESS (Sender-class), which is why business faults never trigger retries or the circuit breaker. That mapping was an explicit acceptance item, not an accident I discovered later.

Evidence/anchor: ADR-0003 consequences (Receiver-vs-Sender); E-005 (typed fault paths); `erp/ErpErrorClassifier` in `apps/esb/README.md`.

### Q16. How do you handle impedance mismatch — the canonical model and XSLT?

One canonical model, `urn:maple:canonical:order:v1`, enforced as JSON Schema (networknt, draft 2020-12, additionalProperties:false) and carried internally as both JAXB XML and Jackson JSON, because the modern side speaks JSON and the legacy side speaks XML. All impedance mismatch lives in four real XSLT 1.0 stylesheets — canonical-to-submit-request, submit-response-to-canonical, canonical-to-price-request, price-response-to-canonical — each unit-tested including validation of outputs against the frozen XSDs. That is the whole point of C6: the ERP cannot change, so the translation layer absorbs version skew, naming drift, and shape differences. When v2 contracts eventually arrive, they get new namespaces and new stylesheets alongside — the canonical model is the stability point, the XSLT legs are the sacrificial layer.

Evidence/anchor: E-008; E-004; `apps/esb/README.md` "What lives where"; C6 in `docs/scenario-charter.md`.

### Q17. Describe the saga. How does compensation actually work?

A hand-rolled orchestrator — deliberately not the camelSaga EIP, because I wanted exact step accounting: order → reserve (per line, collecting reservation IDs) → pricing (per line) → confirm. Failure after any reserve triggers compensation: release every collected reservation ID, best-effort, and return 503 SAGA-COMPENSATED with `compensated:true` and the released IDs. The subtle design point: the release calls ride a breaker-free route, so compensation still works while the circuit breaker is OPEN — a breaker that blocks your own compensation turns a partial saga into a stuck one. The proof is measured: under a fail-pricing injection the suite asserts the ERP stock went 19→19, meaning the hold was really released, not just reported. One documented consequence: a reserve-step OutOfStock returns 422 with no compensation, literally per the frozen contract — earlier-line holds expire in the ERP, and I say that out loud rather than hide it.

Evidence/anchor: E-009 (stock 19→19, compensated:true); `apps/esb/README.md` deviations; ADR-0003.

### Q18. Idempotent consumer — how is it built, and what happens when a saga fails?

The idempotency key is the client's Idempotency-Key header, else a derived key: sha256 of sourceSystem plus externalOrderRef. Claim via Redis SETNX with a 24h TTL; the final 201 response body is stored at a second key, so a same-key replay returns 409 DUPLICATE carrying the original orderId — captured live in the artifact. The interesting case is failure: a saga that FAILED must release its claim, or the client retrying the same key is 409-locked out for the TTL. That bug was found in review — my suite had not covered it — and the fix shipped with a regression scenario: a failed saga releases the claim, the same-key retry re-executes. Redis outage degrades to allow-through, which is the correct failure direction for idempotency backed by a cache.

Evidence/anchor: E-008 (201 + 409 verbatim); E-009 (release-on-failure scenario); `idem/*` classes in `apps/esb/README.md`.

### Q19. Retry, circuit breaker, DLQ — tuning and semantics?

Retry lives inside the gateway with exact attempt counting: 3 attempts, 200 ms initial, x2.0 backoff — on infra failures only (timeout/connect), never on business faults. The circuit breaker is resilience4j via the Camel EIP on the single ERP funnel: 50% failure rate, window 10, minimum 6 calls, open 2 s, 3 half-open probes, recording only ErpInfraException. One load-bearing flag: `throwExceptionWhenHalfOpenOrOpenState(true)` — the default silently passes calls through an OPEN breaker, which would defeat the fail-fast contract. Measured: hard-down ERP → 503 CIRCUIT-OPEN in 4 ms, well under the 100 ms fail-fast target, with the DLQ carrying the exhausted correlation IDs, and recovery to 201 after the proxy healed. DLQ events carry the compensation flag and the pseudonymized customer reference; publishes are synchronous with maxBlockMs=3000 and never change the HTTP outcome.

Evidence/anchor: E-009 (4 ms, DLQ, recovery); `config/ResilienceConfiguration` in `apps/esb/README.md`.

### Q20. How do you know you hit the 300 ms latency budget?

Measured, not asserted. k6 constant-arrival-rate profile against the real REST façade — 10 s ramp, 60 s hold at 20 RPS target: 1304 iterations over 70 s, p95 44.25 ms against the 300 ms budget (C3), medians 18.25 ms, 18.63 RPS achieved. Checks 98.61% — and I can explain the failures: all 18 non-201 responses are 422 INV-OUT-OF-STOCK, legitimate business outcomes from seeded 5–100-unit stock rows exhausted mid-run, zero infra failures. There are deliberately no thresholds in the k6 script — it records the real p95 into a JSON summary instead of passing/failing against a number, so the measurement can't be gamed. Honest context: this is sim mode, everything on localhost of a consumer desktop with other docker stacks sharing it; in-region cloud numbers would be different, but the measurement discipline is the same.

Evidence/anchor: E-010 (`tests/load/k6-orders.js`, results JSON, run transcript); C3 in `docs/scenario-charter.md`.

---

## ETL — design level only (the ETL phase is not built)

### Q21. How would the CDC pipeline work?

Straight answer first: the ETL phase is not built yet — what exists is the design and the constraint wiring, and I won't quote numbers for it. The design: Debezium captures row changes from the OMS MySQL (binlog, connector config in `apps/cdc/`) into ApsaraMQ for Kafka; a sink consumes change events into the bronze landing zone on OSS — raw, immutable, append-only, and region-tagged, so CN-origin records stay on CN buckets. Freshness is C4: CDC freshness ≤ 15 minutes, with a freshness monitor that measures ingest-to-bronze lag per run and emits the metric — the same measure-don't-assert discipline as the latency budget. The environment parity table already maps sim Kafka to managed Kafka and pins RDS to UTC, which is the timezone spine for C5, and topic names are already dot-free for ApsaraMQ's apply-time rules.

Evidence/anchor: C4/C5 in `docs/scenario-charter.md`; `docs/infra-env-parity.md`; MASTER_PROMPT §3 item 3 (intended architecture). Not built — no E-rows exist for ETL.

### Q22. What does the lake layering look like?

Design only — not built. Bronze is raw and immutable: change events landed as-is, region-tagged, no in-place edits, so it doubles as the replay source and the audit trail. Silver is cleansed and conformed: typed columns, money normalized to integer minor units plus currency code (C5 — never floats, never mixed currencies), store and SKU dimensions conformed, region column preserved on every row. Gold is the star schema: `fact_orders`, `fact_order_lines`, `dim_sku`, `dim_store`, `dim_date`. The gold grain decision matters for reconciliation — one row per order line, which lets totals be recomputed bottom-up against the source. All of this lives in the module plan (`apps/batch/`) and the constitution; there is no measured evidence for any of it yet, and I flag that before anyone has to ask.

Evidence/anchor: MASTER_PROMPT §3 (bronze/silver/gold, fact/dim tables); C5; `apps/batch/` placeholder in repo tree.

### Q23. Where would data-quality gates sit?

Design only — not built. Three rule families between the layers: completeness (required fields present per record type), uniqueness (no duplicate external order refs or change-event keys), and referential (every fact row's SKU/store/date keys resolve to dimension rows). Violations don't block the pipeline silently and don't pollute gold: they route to a quarantine table with the rule that failed, the offending payload, and the batch run ID, so quarantine is queryable and reprocessable. The other gate is residency: CN-tagged PII must never appear in a non-CN sink — the static residency checks for the IaC graph and the ESB egress wiring are already in CI, and the masking policy on shared-topic egress is built and proven. What's missing is the pipeline itself to run these gates against, which is exactly why I call them design.

Evidence/anchor: MASTER_PROMPT §3/§6 Phase 3; E-013 §5 (residency greps); E-009 (masking proven at the ESB egress layer).

### Q24. How would you prove correctness — reconciliation and the T+1 window?

Design only — not built, so no numbers. Reconciliation: after each batch, source counts and totals (OMS MySQL) are recomputed against gold — counts by order, sums by order-line minor units and currency — and the run must reconcile 100% or the run fails loudly; a reconciliation report is emitted per run with the freshness metric. The T+1 batch window is C4/C5: batch completes by 06:00 Singapore time, and the scheduling is timezone-explicit — Asia/Singapore on the compute, UTC in the database (`time_zone=+00:00` is already pinned in the RDS Terraform parameter), every schedule and report timestamp carrying its zone. Multi-timezone batch windows mean the "day" boundary is a business decision per region, encoded explicitly rather than assumed from server clock. The precedent I'd follow is the existing measure-and-record pattern from the latency and freshness monitoring.

Evidence/anchor: C4/C5; `docs/infra-env-parity.md` (RDS UTC, SAE timezones); MASTER_PROMPT §6 Phase 3 AC. Not built.

---

## Compliance

### Q25. What are the lawful cross-border transfer mechanisms out of China under PIPL?

Article 38 is the gate: a handler that "truly needs to provide personal information for a party outside the territory of the People's Republic of China" must meet one of the routes in Article 38 — in practice three compliance pathways: the CAC security assessment (Article 40 route), certification by a professional institution, or the CAC standard contract (the filing duty with the provincial cyberspace authority sits in the CAC's 2023 Standard Contract Measures, not in PIPL's own text) — Article 38 also recognises other conditions set by law or the CAC and treaty-based equivalence — and must "take necessary measures to ensure that the personal information handling activities of the overseas recipient … meet the personal information protection standards set forth in this Law". Article 39 adds the transparency duty: inform individuals of the overseas recipient's name and contact details, the purposes and means of handling, the categories of data, and how to exercise their rights against that recipient — plus separate consent. Article 40 mandates local storage for CII operators and any handler whose volume reaches the CAC threshold. My design answer: the CN partition exists so CN PII never triggers any of this — the transfer is avoided, not papered over.

Evidence/anchor: PIPL Art. 38 — "shall meet one of the following requirements", https://pipl.xllawconsulting.com/personal-information-protection-law-of-the-peoples-republic-of-china-pipl/chapter-iii-rules-on-provision-of-personal-information-across-the-border/article-38/ ; Art. 39 — "obtain individual's separate consent", https://pipl.xllawconsulting.com/personal-information-protection-law-of-the-peoples-republic-of-china-pipl/chapter-iii-rules-on-provision-of-personal-information-across-the-border/article-39/ ; Art. 40 — "shall store domestically the personal information collected and generated within the territory", https://pipl.xllawconsulting.com/personal-information-protection-law-of-the-peoples-republic-of-china-pipl/chapter-iii-rules-on-provision-of-personal-information-across-the-border/article-40/

### Q26. What does Singapore's PDPA require for data flowing out of Singapore?

Section 26, the Transfer Limitation Obligation: "An organisation must not transfer any personal data to a country or territory outside Singapore except in accordance with requirements prescribed under this Act to ensure that organisations provide a standard of protection to personal data so transferred that is comparable to the protection under this Act." The prescribed requirements live in the Personal Data Protection Regulations 2021, reg. 10: before transferring, "take appropriate steps to ascertain whether, and to ensure that, the recipient … is bound by legally enforceable obligations … to provide to the transferred personal data a standard of protection that is at least comparable to the protection under the Act." In practice that means binding the recipient — contractual clauses, intra-group agreements — to a comparable standard, and being able to show the steps taken. In my architecture the Singapore hub is the documented transfer point for non-restricted data; China-customer PII never transits it at all, because the CN partition has no egress path. So the PDPA obligation applies to the SG-hub-to-Canada/other flows, which would be governed by transfer terms under the memo — and that is design-level documentation in this project, not legal advice executed against a live population.

Evidence/anchor: PDPA 2012 s. 26(1) current text, Singapore Statutes Online https://sso.agc.gov.sg/Act/PDPA2012 ; operational steps in PDP Regulations 2021 reg. 10, https://sso.agc.gov.sg/SL/PDPA2012-S63-2021 (statute; corroborated by the PDPC Advisory Guidelines on Key Concepts, https://www.pdpc.gov.sg/-/media/files/pdpc/pdf-files/advisory-guidelines/ag-on-key-concepts/advisory-guidelines-on-key-concepts-in-the-pdpa-17-may-2022.pdf)

### Q27. And Canada — what does PIPEDA demand of the HQ?

Clause 4.1.3 of the Schedule 1 accountability principle: "An organization is responsible for personal information in its possession or custody", including information "that has been transferred to a third party for processing", and "The organization shall use contractual or other means to provide a comparable level of protection" while a third party processes it. Accountability does not transfer with the data — the Canadian HQ remains on the hook for APAC processing, which is what C7 is about: the cross-border transfer documentation. My deliverable maps the transfer edges (Canada↔Singapore, and the deliberate non-transfer of CN PII) to the PIPL Art. 38 mechanisms and the PDPA comparable-standard terms, so the HQ can demonstrate, per edge, who holds the data, under which instrument, with which protections. Design-level here — the memo and control matrix are the artifacts, not a live DPIA program.

Evidence/anchor: PIPEDA Schedule 1, cl. 4.1.3 (official statute), Justice Laws https://laws-lois.justice.gc.ca/eng/acts/p-8.6/page-7.html ; C7 in `docs/scenario-charter.md`.

### Q28. What needs an ICP filing, and what doesn't?

The dividing line is where the server sits, per Alibaba Cloud's own filing documentation: "ICP filing applies only to servers located in the Chinese mainland", and "domain names resolving to servers in the Chinese mainland must obtain an ICP filing before providing services" — non-commercial content needs the filing, commercial Internet Information Services need an ICP license. Without it, mainland access is blocked: "You cannot serve Internet Information Services without a license or ICP filing." What doesn't need it: anything hosted outside the mainland — which is exactly why C2 makes Singapore the hub. Internal APIs on the SG region need no filing; a public web presence in mainland CN does. Process-wise: a CN-registered entity does real-name verification, submits entity and website/app details through the cloud provider's filing service (Alibaba Cloud's Filing Service on the China site), passes the provider pre-review then the MIIT-side review, and the domain stays blocked until the filing number is issued. Our CN partition being compute-and-data-only, with no public endpoints, is a direct consequence.

Evidence/anchor: Alibaba Cloud, "What is ICP filing" — quotes above, https://www.alibabacloud.com/help/en/icp-filing/basic-icp-service/product-overview/what-is-an-icp-filing ; requirements overview https://help.aliyun.com/en/icp-filing/basic-icp-service/product-overview/icp-filing-requirements-for-a-regular-website ; process stages (provider pre-review, then communications-administration review) https://www.alibabacloud.com/help/en/icp-filing/basic-icp-service/user-guide/icp-filing-application-overview ; C2 in `docs/scenario-charter.md`; ADR-0005.

### Q29. How is data residency actually enforced in your build — and where are the gaps?

Three enforcement layers, and I name the gap honestly. One: placement — the CN module is pinned at the provider graph via the aliased `alicloud.cn` provider, with separate VPC/KMS/OSS/RDS, no cross-region replication resources anywhere (grep-verified), and no NAT/EIP in the CN VPC, so there is no egress path. Two: auditability — `residency=cn` and `data-classification=pipl-restricted` tags on every taggable CN resource (the SAE namespace has no tags attribute in this provider, so its residency is pinned by the region prefix in its namespace ID). Three: application egress — CN customer references are pseudonymized before any shared-topic publish. All three layers are now machine-checked in CI by a seven-check static residency suite — provider pinning, CN-string confinement, zero replication resources, tag coverage, bucket-family separation, no CN NAT/EIP, and a single Kafka publish chokepoint wired through the masking policy — and the suite ships a selftest that injects five violations and requires each check to catch its own. The remaining gap is runtime: no account exists, so an actual bucket rejecting a cross-region read is verify-at-activation. Statically CI-proven; runtime behavior honestly unverified.

Evidence/anchor: E-012 (cn-beijing planned values, no replication); E-013 (tag adjudication, SAE namespace exception); E-016 (static residency suite 7/7 + five-violation selftest, in CI); E-009 (masking proven); E-017 (sim-mode "who did what" audit-query demo); ADR-0005 consequences (no runtime CN evidence). The obligations-to-build mapping is written out in compliance/control-matrix.md (three-way honesty split per control) and the per-edge transfer analysis in compliance/cross-border-transfer-memo.md.

### Q30. How do you handle PII before it leaves the region?

At the application egress boundary, before every shared-topic publish. The PiiMaskingPolicy runs in the saga orchestrator and the response assembler, so both success events and DLQ events are covered — a dead-letter queue is exactly where people forget PII. For CN customer references the transform is keyed HMAC pseudonymization: `msk-` plus the first 12 hex characters of an HMAC-SHA256 over the reference with a configured secret — keyed, so it's not a naive hash that a rainbow table of known IDs reverses; the suite asserts the clear value is absent and the masked value present. The REST 201 body never carries customerRef at all — only the Kafka event copy does, masked. Honest limitations, stated: the HMAC secret is an env-var sim dummy pending a real secret store, and pseudonymized references are not anonymous — they're re-identifiable with the key, which is precisely why the key belongs in KMS, not an env default.

Evidence/anchor: E-009 (masked customerRef in DLQ, keyed HMAC); `events/PiiMaskingPolicy` in `apps/esb/README.md`; C1 in `docs/scenario-charter.md`.

---

## Delivery model & trade-offs

### Q31. How was the build sequenced, and why that order?

Built in slices: contracts first, then the hub, then the landing zone. Phase 1 authored, validated, and froze the ERP's SOAP contracts before any consumer code, because the ESB is only meaningfully demonstrable against an estate that cannot change — the freeze is what gives C6 its teeth. Phase 2 built the integration hub against that frozen contract, with the sim network (compose, smoke, fault injection) already standing from Phase 0, so every saga, retry, and compensation behavior was verifiable from day one. Phase 4 did the AliCloud IaC in validated-plans mode once the runtime behaviors it must host were real. The order is dependency-driven: you can't demonstrate mediation without a frozen contract, and you can't size a landing zone credibly before the workloads exist. The ETL phase is designed but deliberately not built — honest slice boundaries, recorded in the roadmap.

Evidence/anchor: ADR-0003 (freeze 2026-09-09, tag `contract-freeze-erp-v1`); E-004/E-005 (contracts before Java); E-012 (IaC); `STATE.md` session log S1→S4.

### Q32. Defend the sim/cloud dual-mode decision.

ADR-0001. The alternatives lose: cloud-only makes every build cycle billable and the project undemoable offline; sim-only leaves the AliCloud environment skill theoretical. Dual-mode: all application code speaks only to interfaces — JDBC, Kafka clients, S3 API — never to sim or cloud specifics, so the swap is configuration and Terraform, no code changes (that rule is verbatim in the parity doc). The sim is five pinned services — MySQL 8, Kafka 3.8 KRaft, Redis 7, MinIO, toxiproxy — converging in ~28 s from clean, with a falsifiable smoke; a sixth, a dedicated ERP fault-injection proxy, joined later and the smoke now asserts all six, re-proven at pack time. The honest cost: sim cannot prove managed-service behavior — actual SLS ingest, actual KMS envelopes, actual STS issuance — so those claims stay "designed, schema-validated" and I say so rather than blur them. The parity risk cuts both ways: image pinning includes registry pinning, which I learned when MinIO purged its Docker Hub tags mid-build.

Evidence/anchor: ADR-0001; E-001/E-002 (sim convergence and smoke), E-015 (re-proven 6-service smoke at pack time); `docs/infra-env-parity.md`; war story 23 in STATE.md.

### Q33. If I gave you an account tomorrow, what would you deploy differently?

There's an activation checklist, in order. Budget alarm first — `scripts/budget-alarm.sh` live mode, ~$20 cap. Then credentials: replace placeholders with environment auth, move RDS/Kafka passwords out of Terraform state into KMS-backed secrets with restricted state access, and tighten the alikafka ARN hedge to the real ARN form. Deploy is the guarded `make deploy-sg`, evidence captured, then `make destroy` — pay-as-you-go, nothing left overnight. At activation I verify the things plans cannot: SSE-KMS uploads with `kms:GenerateDataKey` actually succeeding, SLS ingest, the CI assume-role chain, TDE enablement, and STS. One gap I'll name before you find it: ApsaraDB for Redis is not modeled in the IaC at all — the idempotency backend's instance shape is region-availability-dependent, so it's chosen at activation with its endpoint flowing through `redis_host`/`redis_port` variables; the network path (`sg-data` 6379 rules) is already wired. Also queued: migrate the SLS alert to the modern resource, split the CI deploy role's governance powers, and wire the SAE app-to-role binding that the provider can't express. Nothing about the design would change — that was the point of keeping it interface-clean.

Evidence/anchor: `infra/README.md` activation checklist; E-014 (guards); ADR-0004 consequences; ADR-0002 (supersede path).

### Q34. How do you model money across three currencies?

Integer minor units plus a currency code, everywhere, never floats — enforced at the schema layer, not by convention. The shared `MoneyType` in the frozen XSD is an integer with a CAD/SGD/CNY enum, and I have a negative test proving it: an instance carrying USD is rejected by the schema (E-004, exit 3). The measured end-to-end proof is the saga response with an exact CAD 500 minor total, region-priced, through the full REST→canonical→XSLT→SOAP path (E-008). Why minor units: cents/fen as integers eliminate float rounding, make totals additive for reconciliation, and make cross-currency bugs a type error rather than a precision bug. The same discipline extends to time: RDS pinned to UTC, SAE apps timezone-explicit (Asia/Singapore / Asia/Shanghai), schedules carrying their zone — C5's multi-timezone rule is the temporal twin of the money rule.

Evidence/anchor: E-004 (USD rejected), E-008 (CAD 500 minor); ADR-0003 (MoneyType in common-v1); `docs/infra-env-parity.md` (RDS UTC); C5.

### Q35. Walk me through your test strategy. Why black-box wire-level tests?

Four layers, each with a reason. Unit: 33 ESB tests plus 17 ERP tests including wire-shape pins like xsi:nil marshalling. Contract: 18 Karate scenarios, black-box — the suite boots the jar itself and hard-fails if the jar is missing or the port stays dead; it asserts raw XML shapes and auth-failure paths against the frozen WSDLs. Fault injection: 8 integration scenarios that boot ERP and ESB and inject real latency, hard-down, and step failures through toxiproxy and a deterministic hook. Load: the k6 profile that measured p95 44.25 ms. Why wire-level black-box: because the nastiest bugs live at boundaries. My unit tests were 32-for-32 green while both the REST body and the success events were double-encoded JSON-in-JSON — only the integration suite over the real endpoint caught it. And every test is falsifiable: the smoke has a proven negative control, and the suite asserts honest endings for ambiguous timeouts rather than a single happy recovery.

Evidence/anchor: E-005, E-009, E-010, E-003; war stories 4/11/13 in STATE.md.

### Q36. How would you run this as a real team rollout?

Design-level, and I won't dress it up as delivery experience. Environment promotion: dev/staging/prod as separate Terraform root compositions with separate state backends and per-env variables — the CN partition stays flag-off everywhere except a deliberately created CN workspace, because "CN off" must be the default. Decision discipline: the five ADRs in this repo are the template — every consequential choice (dual-mode, account path, contract freeze, compute, region strategy) gets context, options, decision, consequences, and a supersede path. Runbooks for the operations that matter: activation, deploy/destroy, DLQ redrive, freshness breach. Cadence: the repo's own rhythm generalizes — small slices, each ending with green CI and updated evidence, decisions logged before code. Residency audits on a schedule: tag greps plus the CI residency tests in the environment, reported alongside the reconciliation and freshness metrics.

Evidence/anchor: `decisions/` (5 ADRs); `infra/README.md` (env/backend examples, activation checklist); MASTER_PROMPT §6 Phase 7 (delivery model intent). No team-size or delivery-experience claims.

---

## War stories (failure → root cause → fix → lesson)

### Q37. Tell me about a time your monitoring lied to you.

**Failure:** my first `make smoke` reported all green after the reviewer stopped Redis out from under it. **Root cause:** a shell-quirk bug — in a `set -e` script, a failing command that is a non-final member of an `&&` list is exempt from errexit, so my `check && echo OK` lines could never propagate failure. The check was structurally incapable of failing; it wasn't a flake, it was theater. **Fix:** rewrote it as `scripts/smoke.sh` with an explicit `|| fail` on every assertion, container-env credentials, host-forward TCP probes for every published port, and — the important part — committed a negative-control artifact: Redis stopped → `FAIL: redis host forward 127.0.0.1:16379 not reachable`, exit 2; restored → exit 0. That negative control has since earned its keep, catching a dead toxiproxy host-forward after a host restart. **Lesson:** a check you haven't seen fail is not a check. Falsifiability is proven with a negative control, not claimed.

Evidence/anchor: E-003 (negative control transcript); war stories 4/6 in STATE.md.

### Q38. Tell me about a bug your unit tests couldn't catch.

Two, caught by the same suite. **Failure one:** 32 unit tests green, but every integration response read as empty JSON. **Root cause:** double encoding — `writeValueAsString()` applied to an already-serialized JSON string, in two places: the saga response at the HTTP boundary and the success-event publish. Shape-of-the-payload is a black-box concern; unit tests on the producing code couldn't see it. **Fix:** fixed both serialization boundaries; the wire assertions in the fault-injection suite now pin the exact response shape. **Failure two:** under injected latency, submitOrder attempt 1 timed out — while the ERP actually committed the order — and attempt 2 hit the ERP's duplicate-ref guard. **Root cause:** timeout is not failure; it's ambiguity. **Fix:** the suite asserts both honest endings — clean 201 recovery, or 422 ORD-DUP-REF with attempts between 2 and 3 — proving retry fired and no duplicate order exists; true reconciliation needs a lookup-by-ref the frozen estate doesn't offer, which is a recorded v2 follow-up. **Lesson:** test the wire, at the boundary, including the ambiguous case.

Evidence/anchor: E-009 (both scenarios); war stories 11/13 in STATE.md; `apps/esb/README.md` deviations.

### Q39. Tell me about something that was plan-green but would have failed at apply.

**Failure:** two independent ones in the same landing zone. The Kafka topics planned green with dotted names (`silkroute.orders.events`) — but ApsaraMQ for Kafka's CreateTopic rejects dots outright. And the "CN" partition's placement was, at provider level, Singapore. **Root cause:** both are rules terraform plan cannot see — plan validates against the provider schema, but API-request-time constraints (naming rules) and resource placement (provider graph) live elsewhere. The CN case was worse: every name string and tag said cn-beijing, so the docs read right while the graph said ap-southeast-1. **Fix:** aliased `alicloud.cn` provider with the `providers` meta-argument (placement now enforced in the graph — the review's HIGH finding); dot-free cloud topic names selected via env indirection; and a CI apply-validity lint that rejects non-compliant topic literals, with a proven negative control; the snat entry now `depends_on` the EIP association. **Lesson:** plans prove schema. Placement, API rules, and apply ordering need their own enforcement layers — write the lint before the apply surprises you.

Evidence/anchor: E-012 (lint, CN planned values); E-013 (region-pinning finding); `scripts/tf-apply-validity.sh`; war stories 15/18/22 in STATE.md.

### Q40. Tell me about a time you had to read the library source instead of trusting the docs.

**Failure:** my independent WSS verification failed 13 of 16 with generic WSS4J "security error", while the implementer's own report said green — and I nearly made it worse by editing the service based on a misread. **Root cause:** two wire facts blog folklore gets wrong. The Password `Type` must be the OASIS UsernameToken profile URI — I had the secext element namespace, which WSS4J classifies as an unknown custom password type and rejects. And I conflated two similarly-named constants: `PW_TEXT` ("PasswordText", the short selector you configure) versus `PASSWORD_TEXT` (the profile URI required on the wire). **Fix:** mapped the exact validator line numbers — 79 checks the required type, 96 rejects custom types — against the shipped 3.0.3 sources, reverted my wrong edit, fixed my test tool instead. WSS on the ESB client then worked first try. **Lesson:** read the shipped library source, not memory; change one variable at a time; and trust independent wire probes over any green report, including your own.

Evidence/anchor: E-005 (auth paths incl. nonce replay); war story 7 in STATE.md; `apps/esb/README.md` "WS-Security wire facts".

---

## Citation verification log (for the independent check)

| Citation | Verified against | Verbatim anchor |
|---|---|---|
| PIPL Art. 38 | XL Law & Consulting PIPL translation (Art. 38 page), fetched 2026-09-12 | "truly needs to provide personal information for a party outside the territory of the People's Republic of China" → "shall meet one of the following requirements"; "(1) passing the security assessment organized by the national cyberspace department in accordance with Article 40"; "(2) obtaining personal information protection certification from the relevant specialized institution"; "(3) concluding a contract stipulating both parties' rights and obligations with the overseas recipient" |
| PIPL Art. 39 | Same site, Art. 39 page, fetched 2026-09-12 | "inform the individuals of the overseas recipient's name and contact information"; "the purposes and means of handling, the categories of personal information to be handled"; "the methods and procedures for the individuals to exercise their rights"; "obtain individual's separate consent" |
| PIPL Art. 40 | Same site, Art. 40 page, fetched 2026-09-12; full text also at chinacompliancesearch.com | CIIOs and handlers reaching the volume "prescribed by the national cyberspace department" "shall store domestically the personal information collected and generated within the territory"; if "truly necessary to provide the information for a party outside" China, "shall be subjected to security assessment organized by the national cyberspace department" |
| PDPA s. 26 | Singapore Statutes Online (statute of record; direct fetch blocked 403), current s. 26(1) as at 23 Jul 2026; operational language corroborated via the PDP Regulations 2021 reg. 10 and the PDPC Advisory Guidelines | Current s. 26(1): "An organisation must not transfer any personal data to a country or territory outside Singapore except in accordance with requirements prescribed under this Act to ensure that organisations provide a standard of protection to personal data so transferred that is comparable to the protection under this Act." Operational steps: PDP Regulations 2021 reg. 10(1) ("take appropriate steps … legally enforceable obligations … at least comparable"). Provenance note: s. 26 has used the "except in accordance with requirements prescribed" structure since 2012 (originally "shall"; "must" in the current Revised Edition); it never read "is satisfied" (that phrase is in ss. 22/29) and "has taken appropriate steps" is regulation language, not s. 26 text — an earlier draft of this answer misquoted it and the independent verification pass caught it |
| PIPEDA cl. 4.1.3 | Justice Laws (official), Schedule 1, fetched 2026-09-12 | "An organization is responsible for personal information in its possession or custody", including information "that has been transferred to a third party for processing"; "The organization shall use contractual or other means to provide a comparable level of protection" while processed by a third party |
| ICP filing | Alibaba Cloud filing documentation, fetched 2026-09-12 | "China requires ICP filing for non-commercial and ICP licensing for commercial Internet Information Services"; "domain names resolving to servers in the Chinese mainland must obtain an ICP filing before providing services"; "ICP filing applies only to servers located in the Chinese mainland"; "You cannot serve Internet Information Services without a license or ICP filing." |

No citation in this file is marked unverified. The only deliberately un-quoted figure is the ICP review duration (no day count asserted — not verified against a source).
