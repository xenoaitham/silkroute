# SilkRoute — portfolio one-pager

**SilkRoute — Multi-Region Alibaba Cloud Integration Platform** (self-directed reference implementation, 2026). Maple Retail Group is a **fictional** Canadian retailer (300 stores + e-commerce) expanding into Singapore (international hub) and mainland China (PIPL data-residency partition). The retailer is fictional; the code, the tests, and the numbers are not — every number below is a measured evidence row in [evidence/EVIDENCE.md](../evidence/EVIDENCE.md) with a re-runnable command.

**Honest framing (verbatim from the project constitution, MASTER_PROMPT §9):**
- *Placement:* listed under *Projects / Selected Work* — never under *Experience*. No fake employer, client, or dates.
- *The interview line, volunteered:* "I built a reference implementation of exactly the problem your JD describes — a Canadian retailer's APAC expansion on AliCloud. Let me walk you through it."
- *The CN line:* "I designed the CN partition and validated the IaC; running it requires a CN-registered account."

## What it demonstrates (each number = its row)

| Area | Headline (measured) | Row |
|---|---|---|
| ESB mediation (C3) | p95 **131.6 ms** / p99 236.2 ms @ 19.49 RPS sustained (budget 300 ms — 44%/79% consumed); historical baseline p95 44.25 ms | E-020, E-010 |
| Breaking point | budget holds through **90 RPS**; warm-110 p95 266 ms at 78.9 sagas/s; cold-110 breaches (658 ms); stock-pool ceiling named | E-021 |
| Saga + compensation | failed pricing step releases real ERP holds (stock 19→19); retry ladder attempts=3 under 2500 ms latency; ambiguous-timeout replays held by the ERP duplicate-ref guard | E-009 |
| Circuit breaker / DLQ | hard-down ERP → CIRCUIT-OPEN fail-fast **4 ms**; kill-erp → 1.9 ms fail-fast, DLQ 0→59 with complete replayable envelopes, first 201 **2 s** after health | E-009, E-023 |
| Idempotency | same-key replay → 409 DUPLICATE with the original orderId; Redis outage degrades to allow-through at **0.9 s** with the ERP guard backstopping — zero duplicate commits across all chaos runs | E-008, E-024 |
| Chaos honesty | two hypotheses (bounded executor, Redis client timeout) were **first REFUTED by the harness**, root-caused, fixed (pool 32/64/200; 500 ms), re-proved — the refutations are recorded, not rewritten | E-022, E-024 |
| AliCloud IaC | Terraform alicloud 1.285.0: SG hub **Plan: 87 to add**, CN residency partition **118** with the flag on — plan-proven, **never applied**; CI lint guards plan-invisible API rules | E-019 |
| Residency (C1) | CN pinned at the provider graph (aliased `alicloud.cn`), no egress path, zero replication resources; 7-check CI suite + 5-mutation selftest; masked keyed-HMAC PII egress from shared topics | E-016, E-009 |
| Compliance paper layer | PIPL/PDPA/PIPEDA control matrix with article-level citations, cross-border transfer memo (Art. 38–40), ICP filing runbook, STRIDE, audit-query demo | E-017 (audit-query demo only) |
| Cost model | designed 24/7 SG footprint **949.64 USD/month** (KMS instance ~53% + Kafka ~32%), priced only from fetched pricing pages — the quantified argument for sim-first | E-025 |
| CI | 4-job pipeline green at the Phase-7 HEAD (run 34754300083, sha ae4364f): sim smoke, SOAP contract suite, fault-injection saga suite, terraform plan + residency checks | E-028 |

## Honest modes — what is proven where

| Mode | Meaning |
|---|---|
| **Sim runtime** | Everything here actually runs: docker-compose (MySQL/Kafka/Redis/MinIO/toxiproxy) + the real Camel ESB and frozen-ERP SOAP services; every runtime number above was measured on it (shared consumer desktop, contention noted per row) |
| **Validated IaC** | All `infra/` Terraform is schema- and plan-proven against the real provider, performs zero API calls, and has **never been applied** — no account exists (plans create nothing) |
| **Not run** | SLS runtime behavior, real cloud costs, the CN partition, runtime residency behavior, CDC freshness — designed, honestly unclaimed until an account exists ("verify at activation") |

The ETL data plane (Debezium CDC → Kafka → bronze/silver/gold) is **designed, not built** — no numbers are claimed for it.

## Go deeper

- [README.md](../README.md) — architecture + a quickstart every command of which has been run verbatim from a clean clone (E-027)
- [docs/slo-report.md](slo-report.md) — SLOs with measured columns and error budgets
- [docs/cost-model.md](cost-model.md) — the priced sheet (URL + fetch date per row)
- [tests/chaos/README.md](../tests/chaos/README.md) — the chaos runbook (hypotheses before results)
- [docs/runbooks.md](runbooks.md) · [docs/delivery-model.md](delivery-model.md) · [docs/hld.md](hld.md) · [docs/lld.md](lld.md)
- Interview pack: [docs/interview/](interview/) — resume bullets, 40-question deep drill, whiteboard walkthrough
