# Scenario Charter — Maple Retail Group (fictional)

> **Honesty banner:** Maple Retail Group is a **fictional** company created as the scenario for a self-directed reference implementation. No real client, employer, or engagement is implied. Every artifact in this repo is bound by MASTER_PROMPT §9 (integrity rules).

## 1. The company and the expansion

Maple Retail Group operates **300 stores across Canada** plus an e-commerce platform. It is entering two new markets:

- **Singapore** — regional operations hub for Southeast Asia (international services).
- **Mainland China** — marketplace presence + physical stores (data-residency partition).

## 2. Starting estate

| Estate | Shape | Integration stance |
|---|---|---|
| Legacy ERP (on-prem) | SOAP 1.2 / WSDL services — orders, inventory, pricing | **Untouchable.** Must be integrated as-is; ERP team will not modify WSDLs (C6) |
| Digital stack | REST microservices + event streaming | Modern; consumes ESB events |
| Analytics | Governed lake, region-aware data | CDC + batch, DQ gates, reconciliation |

## 3. Hard constraints (every design decision must trace to one)

| # | Constraint | Consequence |
|---|---|---|
| C1 | Chinese-customer PII must remain in the China region (PIPL) | Region-pinned storage; masked egress for analytics; residency tests in CI |
| C2 | Singapore region is the international hub | No ICP needed for internal APIs; public web presence in mainland CN requires ICP filing (documented precisely) |
| C3 | Sync SOAP mediation p95 < 300 ms in-region | Measured with k6; real numbers recorded in evidence |
| C4 | CDC freshness ≤ 15 min; T+1 batch complete by 06:00 Singapore time | Freshness monitor + batch window scheduler + evidence |
| C5 | Multi-currency (CAD/SGD/CNY), multi-timezone batch windows | Money as minor units + currency code; timezone-explicit scheduling |
| C6 | The ERP team will NOT modify their WSDLs | All impedance mismatch handled in the ESB layer (XSLT, canonical model) |
| C7 | Canada HQ subject to PIPEDA; cross-border transfer must be documented | PIPL Art. 38–40 transfer mechanism memo (CAC security assessment / standard contract / certification) |

## 4. How this charter is used

- The **Critic Gate** (MASTER_PROMPT §5) scores architectural soundness by whether decisions trace to C1–C7. A design choice that traces to no constraint is suspect.
- Subagent prompts must restate the relevant constraints verbatim.
- Any scenario change requires an ADR.
