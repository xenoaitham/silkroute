# SEC-1 Review — Phase 5 compliance layer: sign-off review

- **Reviewer:** SEC-1 (security/compliance)
- **Date:** 2026-09-13
- **Repo:** /home/potato/Haitham/ALIBABA, branch `main`, HEAD under review `fc5fbaf` (compliance artifacts) + the fixes this review triggered
- **Mode:** READ-ONLY review; ORCH-LEAD applied the fixes. Validated-plans honesty (ADR-0002) applies throughout: no AliCloud account exists; nothing reviewed here was verified against a live cloud — runtime claims remain "verify at activation" by design.

## 1. Scope & method

Reviewed in full: the five new compliance artifacts (`compliance/control-matrix.md`, `compliance/cross-border-transfer-memo.md`, `docs/icp-filing-runbook.md`, `compliance/stride-threat-model.md`, `compliance/audit-trail-design.md`), `scripts/audit-demo.sh` + the `audit-demo` Makefile target + `evidence/runs/E-017-audit-demo.txt`, and — to catch misstatements of already-landed work — `scripts/residency-tests.sh`, `infra/` modules (cn-partition, providers, main, data, security, observability, network), `apps/esb` masking/client/XSLT sources, `compliance/iam-review-phase4.md` (the prior SEC-4 review), `compliance/citations-verification-phase8.md` (the verified citation set), the interview pack's compliance answers (qa Q25–Q30, whiteboard 18:30–21:00), and `evidence/EVIDENCE.md` E-001..E-016.

Independent verification performed (not trusted from prior claims):

1. **Citation re-derivation (the two-verifier rule).** Every legal citation re-derived from primary/authoritative sources: PIPL Arts. 38/39/40 (XL Law re-fetched; matches the phase-8 verified set), PDPA s. 26(1) + reg. 10(1) (SSO, verbatim), PDPA ss. 13/24 paraphrases (SSO), PIPEDA cl. 4.1.3 and the newly used cl. 4.7 (Justice Laws, verbatim), CAC Standard Contract Measures Art. 7 "10 working days" (official cac.gov.cn Chinese text — a statutory filing deadline, NOT an ICP review duration, so the no-day-count rule is not breached), all four Alibaba ICP scope fragments (verbatim on the `what-is-an-icp-filing` page), the blog/597263 footer-display quote, ActionTrail field names incl. the `sourceIpAddress` (not CloudTrail's `sourceIPAddress`) spelling, `userIdentity.type` values, the resourceType/resourceName 2020 field announcement, `UpdateRotationPolicy` (KMS 2016-01-20), and `alicloud_sls_alert` in pinned provider 1.285.0 (schema-verified locally).
2. **Live re-runs:** `make residency` → 7/7 + all five selftest mutations caught, exit 0; `make audit-demo` ×3 → clean bootstrap after DROP DATABASE (exit 0), idempotent re-run (seed skipped, exit 0), and a TAMPER TEST (deleted the seeded StopLogging denial → `FAIL: expected 2 denied actions in seed, got: '1'`, exit 2 — the assertions bite); `make smoke` → 6/6 exit 0; `make deploy-sg`/`make destroy` → REFUSING, exit 2.
3. **Provenance greps:** ICP duration sweep (no day counts in user-facing artifacts at review time), run-ID provenance, F-01..F-05 mapping checks, every file:line cite spot-checked.

## 2. FINDINGS (as returned to ORCH-LEAD)

| ID | SEV | file:line | Problem | Exact fix | Status after same-session fixes |
|---|---|---|---|---|---|
| SEC-5-01 | **HIGH** | docs/icp-filing-runbook.md:30; infra/cn-partition/README.md:47-48 | "The provider's documentation states no fixed duration" is one-fetch-falsifiable: the cited process-overview page states provider pre-review "1–2 business days" and communications-administration review "usually takes 1 to 20 business days … cannot be expedited". The no-day-count rule bans *quoting* a duration; it does not license a false absence-claim about the source. | Acknowledge a published typical (non-binding) window exists and the review cannot be expedited, without quoting numbers; gate the launch on the filing number issuing. | **FIXED** both sites (ORCH-LEAD independently re-fetched the page and confirmed the stated windows before rewording) |
| SEC-5-02 | MED | docs/icp-filing-runbook.md:10 | Three verbatim fragments attributed to the `icp-filing-requirements-for-a-regular-website` page live on the `what-is-an-icp-filing` page; the requirements page's real sentences are "Websites hosted on servers in the Chinese mainland require an ICP filing" and "do not make the website publicly accessible until your ICP filing is approved". | Re-attribute fragments to the correct URLs; quote the requirements page's actual sentences. | **FIXED** |
| SEC-5-03 | MED | evidence ledger + E-017 artifact | The audit-demo proof was floating: run file untracked, no E-017 row, no E-017 cite in audit-trail-design.md §4 ("no claim without a row" violation). | Add the E-017 row, track the artifact, cite E-017 in §4. | **FIXED** (row added; artifact committed; §4 cites E-017 with the falsifiability summary) |
| SEC-5-04 | MED | compliance/stride-threat-model.md:47 | Wrong evidence cite: run 34642622532 appears only in the docker-compose.yml comment; E-012's artifact records run 34640392548 for the purge-era red CI runs. | Cite each run ID with its actual home. | **FIXED** |
| SEC-5-05 | MED | compliance/stride-threat-model.md:58 | "F-01–F-05 map to already-recorded SEC-4 review findings" is false for F-01 (masking secret absent from iam-review-phase4.md; recorded in apps/esb/README.md instead). | Re-scope the sentence: F-02–F-05 → SEC-4 review; F-01 → apps/esb/README.md. | **FIXED** |
| SEC-5-06 | LOW | stride threat table | Completeness: no row for the ESB→ERP cleartext credential leg (UsernameToken PasswordText over plaintext HTTP; sim loopback = accepted risk; cloud VPC leg = designed gap) nor the ESB Kafka client's missing security.protocol/SASL wiring. | Add threat rows + finding F-07. | **FIXED** (rows 22–23 + F-07 added, honestly deferred to activation) |
| SEC-5-07 | LOW | compliance/cross-border-transfer-memo.md §4/§5 | Art. 38's fourth catch-all item + treaty-equivalence paragraph missing (the gate-reviewed pack carries the nuance); §5 checklist lacked the CAC Measures Art. 4 applicability conditions and the Art. 7 PIA-report attachment. | Add the nuance + checklist item. | **FIXED** (Art. 4 thresholds — non-CIIO, <1M processed, <100k provided abroad, <10k sensitive provided abroad, no volume-splitting — verified by SEC-1 AND independently by ORCH-LEAD against the official CAC text before recording) |
| SEC-5-08 | LOW | compliance/audit-trail-design.md:40 | "multi-value fields are semicolon-separated" imprecise (same-type names are comma-separated; different types semicolon-separated). | Reword. | **FIXED** |
| SEC-5-09 | LOW | docs/icp-filing-runbook.md:32 | The footer-display quote's source sentence addresses the ICP **license** number; runbook used it generally. | Make the license context explicit; keep the provider-notice hedge. | **FIXED** |
| SEC-5-10 | LOW | docs/icp-filing-runbook.md:31 | "providers enforce it by blocking serving on un-filed domains" was uncited. | Softened to the operator-side duty supported by the (now correctly quoted) requirements-page sentence. | **FIXED** |
| SEC-5-11 | LOW | stride table rows 1/4 | Two cites pointed at Javadoc/comment lines instead of the enforced code lines. | Cited the code lines. | **FIXED** |

## 3. Checked and clean (non-findings)

- All verbatim legal quotes match the verified citation set or were re-verified against primary sources (including the new cl. 4.7 quote and the CAC Art. 7 deadline).
- Every honesty-split status survives attack: every "Enforced in build" has a runnable proof (residency suite, masking assertions incl. success AND DLQ paths at `tests/esb-int/.../EsbIntegrationTest.java`, guards, demo); every "Designed, schema-validated" maps to real HCL (E-012) and never claims runtime; every "Verify at activation" is named.
- Pack consistency: no contradiction between the new artifacts and gate-reviewed Q25–Q30 / whiteboard claims.
- `infra/cn-partition/README.md` "2-4 weeks" removal: clean (and superseded by the SEC-5-01 fix wording).
- Under-claim note (orchestrator's call, applied): the pack predated the matrix/memo/runbook/STRIDE/demo — Q29's anchor and the whiteboard's 18:30–21:00 section now cite them additively (E-017 + doc pointers).

## 4. Verdict

**FIX-FIRST (1 HIGH, 4 MED, 6 LOW at review time) → all eleven findings fixed the same session; re-verified by ORCH-LEAD (demo re-run green, residency green).**

**SEC-1 SIGN-OFF: the Phase 5 compliance layer is approved as fixed** — with the standing honest boundary: everything cloud-runtime remains verify-at-activation per ADR-0002, and the no-day-count posture (acknowledge published windows, quote no numbers) is now applied without false absence-claims.
