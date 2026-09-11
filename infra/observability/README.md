# Observability module (SG hub)

Logs, audit trail, dashboard, and alarms for the Singapore landing zone.

## Pieces

- **Log project `silkroute-sg`** - single SG project. C1 note baked into the
  resource description: the CN partition keeps its own logs in a CN project;
  nothing is ever shipped cross-region.
- **Log stores**: `esb-app` (SAE application logs, 30-day TTL),
  `audit` (ActionTrail target, 180-day TTL - the "who did what" groundwork for
  Phase 5), `orders-events` (order event traces, 30-day TTL).
- **ActionTrail `silkroute-sg-trail`** - management events (Read+Write) written
  into `audit` via the ActionTrail service-linked role (referenced by ARN
  placeholder; the role is created out-of-band on first trail activation).
- **Dashboard `silkroute-overview`** - two charts: ESB p95 latency on `esb-app`,
  DLQ depth (`silkroute.esb.dlq`) on `orders-events`.
- **CMS contact group `silkroute-ops`** + alarms:
  - SAE CPU > 80% averaged over 5 minutes, per app (`acs_sae` / `CPU`)
  - RDS connection usage > 80 on the OMS instance (`acs_rds` / `ConnectionUsage`)
- **SLS alert `dlq-depth-alert`** - 5-minute fixed-rate query on `orders-events`
  filtered to `topic: silkroute.esb.dlq`, fires Critical (severity 8) when the
  count exceeds 100, scheduled in an explicit timezone (`Asia/Singapore`, C5).

## Known trade-offs (documented honestly)

1. **`notification_list` deprecation.** Provider 1.285.0 deprecates
   `notification_list` in favour of `policy_configuration`, but the modern path
   requires a pre-existing SLS **action policy** resource that this landing
   zone's confirmed resource set does not include (there is no
   `alicloud_log_action_policy` in provider 1.285.0). We keep
   `notification_list` (type `DingTalk`, routed to the ops webhook) so the
   alert is self-contained; when an account exists, create an action policy and
   switch to `policy_configuration`.
2. **Budget alarm is NOT in Terraform - provider gap.** The ~\$20 budget alarm
   is implemented OUTSIDE Terraform via the BSSOpenAPI `SetBudgets` API
   (script: `scripts/budget-alarm.sh`, owned by ORCH-LEAD), because provider
   1.285.0 ships **no** budget/BSS resource type at all (verified by
   case-insensitive grep across all 1161 resource names in
   `terraform providers schema -json` - nothing matching budget/bss/billing).
   Console path if preferred: **BSS console -> Budgets -> Create budget**
   (amount ~\$20, alerts at 80%/100%). We did not invent a resource or fake the
   capability.
3. **SLS alerts cannot bind a CMS contact group directly.** The CMS contact
   group covers the two CMS alarms; the SLS DLQ alert notifies the same on-call
   channel via DingTalk webhook. Routing both through one SLS action policy is
   the Phase-5 cleanup.
