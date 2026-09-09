# ADR-0003: WSDL/XSD contract freeze for the legacy ERP (enforcement of C6)

- **Status:** Accepted (freeze effective 2026-09-09, commit following Phase 1 contract-test PASS)
- **Deciders:** ORCH-LEAD (Haitham)

## Context

Scenario constraint **C6** states: *"The ERP team will NOT modify their WSDLs — all impedance mismatch must live in the ESB layer (XSLT, canonical model)."* MASTER_PROMPT Phase 1 makes the freeze an acceptance criterion: contracts are authored first, validated, implemented against, and **frozen the moment contract tests pass**.

Phase 1 produced three SOAP 1.2 WSDLs (document/literal wrapped) and four XSDs (`apps/legacy-erp/src/main/resources/{wsdl,xsd}/`):

- `OrderService-v1` (submitOrder, getOrderStatus; fault `InvalidOrderFault`) — ns `urn:maple:erp:orders:v1`
- `InventoryService-v1` (reserve, release, getStock; faults `OutOfStockFault`, `UnknownReservationFault`) — ns `urn:maple:erp:inventory:v1`
- `PricingService-v1` (priceForSku; fault `UnknownSkuFault`) — ns `urn:maple:erp:pricing:v1`
- shared `common-v1` (MoneyType = integer minor units + CAD/SGD/CNY enum per C5; StoreRegionCodeType CA/SG/CN — load-bearing for Phase 2 content-based routing; LegacyAuditType; ErpFaultType base)

Validation before any Java existed: `xmllint` well-formedness on all 7 files + instance-document validation positive **and negative** (a USD currency — outside the CAD/SGD/CNY enum — is rejected by the schema; evidence E-004). Implementation is generated *from* the WSDLs via `cxf-codegen-plugin` wsdl2java (WSDL-first is mechanical, not aspirational), and the running services serve the frozen contracts at `GET /ws/*/v1?wsdl`.

The enforcement mechanism for the freeze is the Karate contract-test suite (`tests/contract/`): 16 black-box scenarios asserting raw-XML shapes, typed fault detail elements, errorCodes, and WS-Security behavior (incl. auth-failure paths) — green at freeze time (evidence E-005). Any future contract mutation that breaks a consumer shows up as a contract-test failure, by construction.

## Options considered

1. **No formal freeze** (keep editing WSDLs when convenient): destroys the C6 discipline that Phase 2's impedance-mismatch work (XSLT, canonical model) is supposed to demonstrate. Rejected.
2. **Freeze by convention** (comment in each file, no ADR): weaker — nothing prevents "one small tweak" drift; the freeze moment is unrecorded and untagged. Rejected.
3. **Freeze by ADR + git tag + contract tests as the tripwire** (chosen).

## Decision

1. The 7 contract files are **frozen as of 2026-09-09**. The freeze commit is tagged `contract-freeze-erp-v1`.
2. No file under `apps/legacy-erp/src/main/resources/{wsdl,xsd}/` may change after the freeze commit — for the remainder of the project. Comments declaring the freeze live in each file's header.
3. Incompatible changes ship as NEW versions with NEW namespaces (`urn:maple:erp:<domain>:v2`), added alongside v1 — mirroring how a real untouchable estate co-exists with its consumers.
4. `tests/contract/` is the freeze tripwire: it pins the wire behavior (element names, namespaces, fault details, errorCodes, money shapes) and runs in CI on every push. Green tests = contract intact.
5. Known CXF behavior, recorded so nobody mistakes it for contract drift: CXF re-serializes the served WSDL (`?wsdl`) — comments stripped, whitespace normalized, schema imports rewritten to `?xsd=` URLs, `soap:address` rewritten to the live endpoint. Content (target namespaces, messages, portTypes, bindings, faults) is unchanged. XML namespace *prefixes* on the wire (`soap:`, `ns2:`) are throwaway and are NOT part of the contract; the contract-test suite deliberately asserts on local names, not prefixes.

## Consequences

- (+) Phase 2 can now freely demonstrate XSLT/canonical-model impedance handling against a contract that provably cannot move — the entire point of C6.
- (+) The freeze is auditable: ADR + tag + git history + a CI-enforced tripwire.
- (+) Money/region discipline (C5, Phase 2 routing input) is schema-enforced, not just documented.
- (−) Any Phase 2+ discovery that the contract is *inconvenient* must be absorbed by the ESB layer (that is the exercise) or handled by versioning, never by editing v1.
- (−) CXF's WSDL re-serialization means byte-diffing served WSDL against the frozen file is not a valid integrity check; the contract tests are the integrity check.
