# Observability module (SG hub)

Logs, audit trail, dashboard, indexes, and alerts for the Singapore landing zone.

## Pieces

- **Log project `silkroute-sg`** - single SG project. C1 note baked into the
  resource description: the CN partition is designed to keep its own logs in a
  CN project that lands with CN activation (no CN observability exists yet);
  nothing is ever shipped cross-region.
- **Log stores**: `esb-app` (SAE application logs, 30-day TTL),
  `audit` (ActionTrail target, 180-day TTL - the "who did what" groundwork for
  Phase 5), `orders-events` (order event traces, 30-day TTL),
  `pipeline-metrics` (30-day TTL - the DESIGNED home for the Phase-3 CDC/batch
  freshness metric; the store ships ahead of its producer and is empty until
  Phase 3 lands, so the freshness dashboard panel draws nothing yet and says
  so in its title).
- **Store indexes** (`alicloud_log_store_index` × 4): full-text on every
  store plus the exact fields the dashboard/alert SQL references
  (`request_time` on esb-app; `event` JSON on audit; `metric`/`value` on
  pipeline-metrics). Without an index an SLS query returns nothing, so
  shipping query-facing stores without indexes would be a design gap. Token
  and index tuning details are verify-at-activation.
- **ActionTrail `silkroute-sg-trail`** - management events (Read+Write) written
  into `audit` via the ActionTrail service-linked role (referenced by ARN
  placeholder; the role is created out-of-band on first trail activation).
- **Dashboard `silkroute-overview`** - three charts: ESB p95 latency on
  `esb-app`, DLQ depth (`silkroute-esb-dlq`) on `orders-events`, and CDC
  freshness on `pipeline-metrics` (DESIGN-ONLY, awaiting its Phase 3 producer
  — the panel title says exactly that; C4 has no producer until the CDC/batch
  pipeline exists).
- **CMS contact group `silkroute-ops`** + alarms:
  - SAE CPU > 80% averaged over 5 minutes, per app (`acs_sae` / `CPU`)
  - RDS connection usage > 80 on the OMS instance (`acs_rds` / `ConnectionUsage`)
- **SLS alerts (modern `alicloud_sls_alert` form, migrated 2026-09-13 from the
  deprecated `alicloud_log_alert` `notification_list` shape — the ADR-0004
  deferred follow-up):**
  - `dlq-depth-alert` - 5-minute fixed-rate query on `orders-events` filtered
    to `topic: silkroute-esb-dlq`, fires Critical (severity 8) when the count
    exceeds 100, scheduled in an explicit timezone (`Asia/Singapore`, C5).
  - `esb-p95-budget-breach` - p95 on `esb-app` over the trailing 5 minutes
    exceeding the 300 ms C3 budget. The query assumes the latency metric is
    emitted in MILLISECONDS; the producer's unit convention is confirmed at
    activation and the threshold adjusted once, if needed.
  - `audit-denied-action-burst` - more than 10 denied management actions
    (non-empty `errorCode`) in 5 minutes on `audit`; the burst signal from
    `compliance/audit-trail-design.md` §5. Who/where refinement is the analyst
    cookbook (§3 Q2).
  - `audit-trail-tamper-tripwire` - ANY `StopLogging`/`DeleteTrail` event, zero
    tolerance (`>= 1`); the automated tripwire for STRIDE finding F-05.
- All four SLS alerts route notifications through the SLS **action policy**
  referenced by `var.sls_action_policy_id`. Provider 1.285.0 ships NO
  action-policy resource (schema-verified), so the policy is console-managed at
  activation — the same out-of-band pattern as the ActionTrail write role ARN.
  Condition-expression SEMANTICS are verified against the live alert editor at
  activation: a green plan proves schema, not SLS expression behavior (one
  vocabulary divergence was already caught live by the plan validator during
  the migration — `group_configuration.type` takes `no_group`/`custom`/
  `labels_auto`, not the legacy `no_condition`).

## Known trade-offs (documented honestly)

1. ~~`notification_list` deprecation~~ **RESOLVED 2026-09-13 (S7):** all SLS
   alerts now use the modern `alicloud_sls_alert` resource (schema-verified in
   1.285.0); the deprecated `alicloud_log_alert` form is gone from the module.
   Landing-zone plan moved 79 → 87 (SG) / 110 → 118 (CN flag on) — delta +8 =
   −1 legacy alert, +4 alerts, +4 store indexes, +1 `pipeline-metrics` store
   (evidence E-019).
2. **Budget alarm is NOT in Terraform - provider gap.** The ~\$20 budget alarm
   is implemented OUTSIDE Terraform via the BssOpenApi `CreateBudget` API
   (2023-09-30; script: `scripts/budget-alarm.sh`), because provider
   1.285.0 ships **no** budget/BSS resource type at all (verified by
   case-insensitive grep across all 1161 resource names in
   `terraform providers schema -json` - nothing matching budget/bss/billing).
   Console path if preferred: **BSS console -> Budgets -> Create budget**
   (amount ~\$20, alerts at 80%/100%). We did not invent a resource or fake the
   capability.
3. **SLS alerts cannot bind a CMS contact group directly.** The CMS contact
   group covers the two CMS alarms; the SLS alerts notify the same on-call
   channel through the action policy's DingTalk webhook (see the
   `sls_action_policy_id` variable note).
4. **Latency/freshness telemetry has no runtime producer in this repo's sim
   mode.** The dashboard/alert queries describe what SAE/log shipper output is
   expected to look like at activation; the MEASURED latency numbers in
   `docs/slo-report.md` come from k6 against the sim ESB (E-010 lineage), not
   from SLS. Nothing here claims a runtime SLS datapoint.
