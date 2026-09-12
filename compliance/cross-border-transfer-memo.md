# Cross-border transfer memo — per-edge analysis (C7)

Maple Retail Group is a **fictional** Canadian retailer; this memo is a design artifact of a **self-directed reference implementation**, not legal advice and not a client deliverable. Honesty frame: per ADR-0002 (validated-plans mode) no AliCloud account exists and nothing is deployed — the transfer posture described here is **design-level**: it describes what the architecture is built to make true, and what would have to be decided before any real data ever crossed a border. Nothing in this memo claims a live transfer, a filed contract, or regulator interaction.

Method note: citations reuse the project's primary-source-verified citation set (`compliance/citations-verification-phase8.md`). Every statutory quote below is from that set; no new statutory quotations are introduced.

## 1. Transfer-edge inventory

| Edge | Data | Direction | Posture |
|---|---|---|---|
| A1 | Canada HQ ↔ Singapore hub (orders, inventory, pricing, events for **non-China** customers) | both | Planned flow for non-restricted data; instrument required before go-live (§3) |
| A2 | China partition ↔ anywhere | none exists | **Design position: no transfer.** The CN partition has no egress path; the Art. 38 analysis in §4 exists only for the day someone proposes to change that |

The inventory is the whole memo: one real edge to paper correctly (A1), and one edge whose entire compliance strategy is *not existing* (A2).

## 2. Edge A1 — Canada HQ ↔ Singapore hub

**Canadian side (PIPEDA).** The accountability principle in Schedule 1, cl. 4.1.3 makes the HQ responsible for personal information "in its possession or custody, including information that has been transferred to a third party for processing", and requires it to "use contractual or other means to provide a comparable level of protection" while a third party processes the information (https://laws-lois.justice.gc.ca/eng/acts/p-8.6/page-7.html). Accountability does not travel with the data: for APAC processing, the Canadian HQ stays on the hook. In the group structure the Singapore hub is the processing party, and the comparable-protection duty is discharged by the instrument below.

**Singapore side (PDPA).** Section 26, the Transfer Limitation Obligation: "An organisation must not transfer any personal data to a country or territory outside Singapore except in accordance with requirements prescribed under this Act to ensure that organisations provide a standard of protection to personal data so transferred that is comparable to the protection under this Act." (https://sso.agc.gov.sg/Act/PDPA2012?ProvIds=pr26-). The prescribed operational steps live in the Personal Data Protection Regulations 2021, reg. 10(1): before transferring, "take appropriate steps to ascertain whether, and to ensure that, the recipient of the personal data is bound by legally enforceable obligations ... to provide to the transferred personal data a standard of protection that is at least comparable to the protection under the Act" (https://sso.agc.gov.sg/SL/PDPA2012-S63-2021?ProvIds=pr10-).

**Instrument (design-level).** One instrument serves both directions because both statutes converge on the same shape — legally enforceable obligations binding the recipient to a comparable standard:

- An **intra-group data-transfer agreement** (or contractual clauses incorporated into the group services agreement) between the Canadian HQ and the Singapore entity, covering both directions of Edge A1: recipient obligations for security safeguards, purpose limitation, access/retention, breach handling, and individual-rights assistance; audit and sub-processor terms; and an explicit statement of the protection standard each side must provide.
- The memo records the **ability to show the steps taken** (reg. 10): the executed instrument, the transfer inventory (this document), and the safeguards description are the evidence pack.

Status: designed. No live data population exists (sim mode), so there is no counterparty signature to obtain and no executed contract to cite — the instrument is specified, not signed.

## 3. Edge A2 — CN partition → anywhere: the transfer that does not happen

**The design position.** Chinese-customer PII stays inside the mainland partition end-to-end in the target design: the CN ESB (`silkroute-esb-cn`) publishes to the CN Kafka brokers, and CN data lands in CN storage — every environment variable of the CN app resolves inside the CN VPC (`infra/cn-partition/main.tf:194-211`: `KAFKA_BOOTSTRAP`, `REDIS_HOST`, `ERP_BASEURL` all CN). The partition has:

- **No egress path**: no NAT gateway, no EIP, no egress design (check R6, `scripts/residency-tests.sh`);
- **No replication path**: zero cross-region replication/backup/mirror resources anywhere in the IaC (check R3);
- **Family separation**: `silkroute-cn-*` buckets exist only in the CN module (check R5);
- **Provider-graph pinning**: the aliased `alicloud.cn` provider, not tags or name strings (check R1) — the SEC-4-01 fix.

All seven checks plus a five-violation selftest run in CI on every push (`make residency`, E-016; graph validated by plan, E-012).

**Consequence under PIPL.** Because no provision outside China occurs, **no PIPL Art. 38 mechanism is triggered today**. Art. 38 governs a handler "that truly needs to provide personal information for a party outside the territory of the People's Republic of China" — the architecture is designed so that this predicate is never met. The transfer is avoided, not papered over.

**The masking nuance, stated honestly.** The ESB's `PiiMaskingPolicy` pseudonymizes CN `customerRef` values (`msk-` + keyed HMAC-SHA256 prefix) before any publish to a shared destination (`apps/esb/src/main/java/com/mapleretail/silkroute/esb/events/PiiMaskingPolicy.java`). Two limits must be named:

1. **Pseudonymized data is not automatically non-personal under PIPL.** A keyed transform is reversible by the secret-holder, so masked references cannot be assumed to exit PIPL's scope. The masking is **defense-in-depth** — it shrinks what an event could leak if one ever reached a shared destination — not a residency mechanism, and the code's own doc comment says so.
2. **The primary control is structural**, not cryptographic: CN order flow stays inside the CN partition end-to-end in the target design. In sim mode today, the shared sim Kafka topics are the only publish targets; the fault-injection suite (E-009) proves the **policy that would govern any event that ever reaches a shared destination** — masked `customerRef` present, clear value absent — so the secondary control is demonstrated, not merely claimed.

## 4. If a transfer ever became necessary — the Art. 38 mechanism map

Nothing below is an endorsement of transferring; it is the decision map so that the first conversation about a real CN transfer starts from the right obligations. Article 38 requires a handler to "meet one of the following requirements" (https://pipl.xllawconsulting.com/personal-information-protection-law-of-the-peoples-republic-of-china-pipl/chapter-iii-rules-on-provision-of-personal-information-across-the-border/article-38/):

1. **CAC security assessment** — the Art. 40 route. Art. 40 requires local storage for critical information infrastructure operators and for handlers whose volume reaches the threshold prescribed by the national cyberspace department, and subjects any truly necessary cross-border provision by them to the security assessment (…/article-40/). For Maple as a non-CIIO retailer, this route becomes mandatory only if Maple is designated a CIIO or crosses the CAC volume threshold — at that point the assessment is not optional.
2. **Certification** by a relevant specialized institution (Art. 38 route 2).
3. **Standard contract** concluded with the overseas recipient in accordance with the standard contract formulated by the national cyberspace department (Art. 38 route 3). **For a non-CIIO retailer below the volume threshold, this is the realistic default route** — the CAC 2023 Measures gate this route on the Art. 4 conditions (non-CIIO; <1,000,000 individuals processed; <100,000 provided abroad; <10,000 sensitive provided abroad, cumulative since the prior January 1; no volume-splitting), verified against the official text (https://www.cac.gov.cn/2023-02/24/c_1678884830036813.htm). The provincial filing duty sits in the CAC's 2023 Standard Contract Measures, not in PIPL's own text: the handler files the standard contract with its provincial cyberspace authority "within 10 working days of the standard contract taking effect" (个人信息出境标准合同办法 Art. 7; https://www.cac.gov.cn/2023-02/24/c_1678884830036813.htm).

Art. 38 also requires "necessary measures to ensure that the personal information handling activities of the overseas recipient meet the personal information protection standards set forth in this Law" — i.e., a route is necessary but not sufficient; the recipient-side safeguards duty rides on top of whichever route is chosen. And the three routes above are not the statute's whole list: Article 38 also recognizes a fourth, catch-all item — "meeting other conditions set forth by laws and administrative regulations and by the national cyberspace department" — plus a closing treaty-equivalence paragraph; the three named routes are the practical pathways, not an exhaustive enumeration. (Wording per the verified rendering in `compliance/citations-verification-phase8.md`, fix 3.)

**Art. 39 accompanies any route**: individuals must be informed of "the overseas recipient's name and contact information", "the purposes and means of handling", "the categories of personal information", and "the methods and procedures for the individuals to exercise their rights", and the handler "shall obtain individual's separate consent" (…/article-39/).

## 5. Decision required before any transfer

- [ ] **Justify the transfer**: can the processing stay in the CN partition? (The design default is yes.) Confirm the requester has exhausted in-region alternatives.
- [ ] **Select the Art. 38 mechanism**: confirm non-CIIO status and volume below the CAC threshold (else security assessment per Art. 40); realistically, standard contract + provincial filing via the CAC 2023 Measures. Check the Measures' own applicability conditions (Art. 4, verified against the official text: the standard-contract route requires ALL of — not a CIIO; fewer than 1,000,000 individuals' personal information processed; fewer than 100,000 individuals' PI cumulatively provided abroad since the prior January 1; fewer than 10,000 individuals' **sensitive** PI cumulatively provided abroad since the prior January 1 — and forbids splitting volumes to dodge the security assessment) and prepare the personal-information-protection-impact-assessment report the filing requires (Art. 7: filed together with the contract).
- [ ] **Art. 39 notification + separate consent**: update privacy notices with the overseas recipient's name/contact, purposes/means, categories, and rights-exercise methods; design and capture **separate consent** records (not bundled consent) before the first transfer.
- [ ] **Execute the standard contract with the overseas recipient** and complete the **provincial cyberspace authority filing within 10 working days of it taking effect** (CAC Measures Art. 7); keep the filed copy in the evidence pack.
- [ ] **Recipient-side safeguards** per Art. 38's "necessary measures" duty: comparable-protection terms, sub-processor controls, audit rights — reuse and extend the Edge A1 intra-group instrument.
- [ ] **Update the other instruments**: the Canada↔Singapore intra-group agreement and any PIPEDA-facing documentation must reflect the new flow (accountability cl. 4.1.3 does not transfer with the data); re-run this memo's inventory.
- [ ] **Machine-check the new edge**: any new cross-border path must be reflected in the residency suite expectations (a new replication or egress resource today turns `make residency` red — E-016 — so the suite must be consciously amended, never silently bypassed).
- [ ] **Verify at activation**: runtime behavior of every residency control named above remains verify-at-activation until a CN-registered account exists (ADR-0005).
