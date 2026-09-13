# Resume bullets — SilkRoute

Maple Retail Group is a fictional company; SilkRoute is a self-directed reference implementation (2026).

## Projects / Selected Work

**SilkRoute — Multi-Region Alibaba Cloud Integration Platform** (self-directed reference implementation, 2026). A scenario company ("Maple Retail Group") is fictional by design; every number below is measured and traceable to the repo's evidence ledger (`evidence/EVIDENCE.md`), with a reproduce command for each row. No ETL/CDC claims are included — that phase is designed, not built.

- Built an ESB-pattern integration hub on Apache Camel 4.10 + Spring Boot 3.4: WSDL-first SOAP↔REST mediation with XSLT and a canonical data model, saga-based order orchestration with compensation, and retry/circuit-breaker/DLQ resilience — p95 44.25 ms against a 300 ms budget at ~19 RPS under k6, with fault-injection proof of the 3-attempt retry ladder, 4 ms circuit-open fail-fast, and real inventory-hold compensation (E-009, E-010).

- Authored and froze SOAP 1.2/WSDL contracts for a simulated untouchable legacy ERP before writing any Java — 7/7 schema-validated files including a negative test rejecting an out-of-enum currency — then proved them with an 18-scenario black-box Karate suite covering typed fault paths and WS-Security auth-failure/nonce-replay (E-004, E-005).

- Proved resilience under real fault injection (toxiproxy latency/down recipes plus a deterministic fault hook): retry-after-ambiguous-timeout recorded honestly with the ERP's duplicate-ref guard holding (no double order), hard-down ERP → CIRCUIT-OPEN in 4 ms with DLQ carrying exhausted correlation IDs, failed sagas really releasing inventory holds (ERP stock 19→19) and Redis idempotency claims (E-009).

- Designed multi-region IaC (Terraform 1.16, alicloud provider 1.285.0) with a Singapore hub (87 planned resources after the Phase-6 SLS alert layer; 79 at the Phase-4 gate) and a China data-residency partition (118 with the CN flag on; 110 at the gate) — residency enforced at the provider graph via an aliased `alicloud.cn` provider, with least-privilege RAM, KMS/SSE-KMS on OSS and RDS, SAE compute, and no cross-region replication anywhere (E-012).

- Stood behind an honest cloud posture ("validated IaC, sim runtime"): fmt/validate/plan clean with plans that perform zero AliCloud API calls, an apply-validity CI lint guarding plan-invisible API rules (ApsaraMQ topic naming), and `deploy`/`destroy` targets that refuse to run without explicit confirmation plus credentials (E-012, E-014).

- Fixed every finding from an independent least-privilege review (1 HIGH / 3 MED / 6 LOW, re-proven by green plans): zero wildcard IAM actions, runtime OSS grants split to least privilege, `kms:GenerateDataKey` added for SSE-KMS writes, CN partition re-pinned at the provider level, TDE on both RDS instances (E-013).

- Ran a 4-job GitHub Actions pipeline green on every push — sim compose+smoke, SOAP contract suite, the fault-injection saga suite booting ERP+ESB on a hosted ubuntu-latest runner (~186 s), and Terraform fmt/validate/plan — anchored by a falsifiable smoke test proven to exit non-zero when a service is down (E-011, E-012, E-003).

- Designed PIPL/PDPA/PIPEDA-aware residency controls into the platform: China-pinned storage with `residency=cn` / `data-classification=pipl-restricted` tags on every taggable CN resource, masked pseudonymized PII egress from shared Kafka topics (keyed HMAC), a seven-check static residency suite in CI with an injected-violation selftest, and a PIPL Art. 38–40 cross-border transfer mechanism map at design level (E-009, E-012, E-016).

- Built a dual-mode runtime: a docker-compose sim (MySQL 8, Kafka 3.8 KRaft, Redis 7, MinIO, plus two toxiproxy fault-injection proxies) converging in ~28 s from a clean start with authenticated falsifiable smoke assertions — re-proven exit 0 at six services at pack time (E-001, E-002, E-015), mapped service-for-service to the managed AliCloud stack so the cloud swap is configuration-only.

- Implemented the ~$20 budget alarm as an executable, honestly-labeled mechanism outside Terraform after schema-proving the provider ships zero budget resources — targeting the doc-verified BssOpenApi `CreateBudget` API (2023-09-30), dry-run by default, live mode gated on credentials (E-014).

- Debugged WS-Security UsernameToken to the wire (OASIS profile-URI Password Type, BSP:R4220 nonce EncodingType) by reading the shipped WSS4J validator sources, and re-proved auth-failure and identical-envelope nonce-replay rejection against the live server-side cache (E-005).

## Verbatim-ready honest-framing lines

Rehearse these until they are reflexes. Volunteer the framing before the interviewer asks.

- **The opener (use first, every time):** "I built a reference implementation of exactly the problem your JD describes — a Canadian retailer's APAC expansion on AliCloud. Let me walk you through it."

- **The CN region line:** "I designed the CN partition and validated the IaC; running it requires a CN-registered account."

- **Sim/cloud dual-mode rationale:** "I ran it dual-mode by design. A docker-compose sim — five managed stand-ins, later six with a dedicated ERP fault-injection proxy — mirrors the managed services' interfaces — MySQL wire protocol, Kafka API, S3 API — so every runtime behavior is reproducible offline at zero spend, and the Terraform is real alicloud-provider work kept plan-clean against the pinned provider. Same code, same tests, swap is configuration only."

- **"Validated ≠ deployed":** "The cloud-side claims are labeled 'validated IaC, sim runtime' and I mean that precisely: a green `terraform plan` proves the HCL is schema-correct against the real provider — it does not prove a deployment. The plans perform no API calls, and I added a CI lint for constraints plans can't see, like ApsaraMQ's topic-naming rules. Plans prove schema, not apply."

- **The fictional-company label:** "Maple Retail Group is fictional — a scenario I wrote to force real constraints: PIPL residency for Chinese PII, a 300-millisecond mediation budget, an ERP whose WSDLs can't change. The code, the tests, and the numbers are real; the retailer is not. That's deliberate — I'd rather show measured depth under an honest label than fake employment."

## Traceability table

| Bullet (lead phrase) | Evidence row(s) | Exact measured value |
|---|---|---|
| ESB-pattern hub…p95 | E-010, E-009 | p95 44.2480541 ms (44.25 ms) < 300 ms budget; 18.63 RPS achieved of 20 target; 1304 iterations/70 s; medians 18.25 ms; 98.61% checks (18 non-201s are 422 business outcomes, 0 infra failures); 8/8 fault-injection scenarios; attempts=3; CIRCUIT-OPEN 4 ms |
| Authored and froze contracts | E-004, E-005 | 7/7 xmllint OK; USD instance rejected by schema enum (exit 3); unit 17/17; Karate contract 18/18 (pricing 4, orders 6, inventory 4, security 4) |
| Proved resilience under fault injection | E-009 | 8 scenarios, 0 failures (~83 s); ambiguous-timeout attempts=2 with ORD-DUP-REF guard holding; stock 19→19 on compensation; DLQ carries compensated:true + pseudonymized customerRef; idempotency claim released |
| Multi-region IaC | E-012 (gate), E-019 (post-alert-layer) | `Plan: 87 to add` (SG), `Plan: 118 to add` (CN flag on) after the S7 SLS-alert migration — 79/110 at the Phase-4 gate; provider 1.285.0, terraform 1.16.2; zone_id/namespace_id `cn-beijing-*` in planned values; 6/6 modules validate green |
| Validated IaC, sim runtime | E-012, E-014 | plans perform NO AliCloud API calls; tf-apply-validity lint OK (negative control proven); deploy-sg exit 2 REFUSING; destroy exit 2 REFUSING; plan-sg exit 0 |
| Independent least-privilege review | E-013 | 1 HIGH + 3 MED + 6 LOW, all fixed; wildcard-action grep = 0; post-fix plans green (75/106 → 79/110) |
| 4-job CI pipeline | E-011, E-012, E-003 | run 34550280537 success (contract ~87 s, sim ~53 s, esb suite ~186 s); run 34643255075 green at sha 0e5609f, 4/4 jobs; latest run 34645192560 at e294948; redis stopped → smoke exit 2, restored → exit 0 |
| Residency controls | E-009, E-012, E-016 | `msk-`+HMAC-prefix customerRef on CN egress (clear value absent); `residency=cn` + `data-classification=pipl-restricted` tags; zero cross-region replication resources (grep-verified); residency suite 7/7 checks + 5/5 selftest mutations caught, in CI |
| Dual-mode sim runtime | E-001, E-002, E-015 | converged ~28 s, mysql/kafka/redis/minio (healthy); `smoke OK (all 5 services) in 2s` as captured, re-proven `smoke OK (all 6 services…) exit 0` at pack time (E-015); kafka host listener 127.0.0.1:39092 |
| Budget alarm | E-014 | schema grep = 0 budget resources across 1161 provider resources; dry-run CreateBudget payload printed; BssOpenApi 2023-09-30 doc-verified |
| WS-Security wire depth | E-005 | security scenarios 4/4 incl. missing-header on both endpoints, wrong password, identical-envelope nonce replay rejected by live server-side cache |
