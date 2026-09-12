# Audit trail design — "who did what" (ActionTrail → SLS)

Maple Retail Group is a **fictional** company; this is a design artifact of a **self-directed reference implementation**. Status, stated first: the audit trail is **designed, schema-validated** — `infra/observability/main.tf` models `alicloud_actiontrail.sg` (`trail_name = "silkroute-sg-trail"`, `event_rw = "All"`, write role = the service-linked role wired out-of-band) shipping management events into the SLS project `silkroute-sg`, log store `audit` with **180-day retention** (`infra/observability/main.tf:1-9,28-34`; the store map fixes `audit = 180`). No AliCloud account exists (ADR-0002, validated-plans mode): the trail has never run, and nothing in this document is runtime cloud evidence — the landing-zone proof is plan/schema-level (E-012). What this document adds to the HCL is the query design and a sim-mode demonstration of the query patterns.

## 1. The audit-event schema

The canonical row shape for the sim-mode demonstration (and for anything downstream that wants to treat the trail as queryable rows):

```sql
CREATE TABLE IF NOT EXISTS audit_event (
  event_id        VARCHAR(64)  NOT NULL,
  event_time      DATETIME(3)  NOT NULL,
  event_source    VARCHAR(64)  NOT NULL,
  event_name      VARCHAR(64)  NOT NULL,
  resource_type   VARCHAR(64)  NOT NULL,
  resource_name   VARCHAR(128) NOT NULL,
  region          VARCHAR(32)  NOT NULL,
  identity_type   VARCHAR(16)  NOT NULL,
  identity_name   VARCHAR(64)  NOT NULL,
  source_ip       VARCHAR(45)  NOT NULL,
  user_agent      VARCHAR(128) NOT NULL,
  error_code      VARCHAR(64)  NULL,
  error_message   VARCHAR(255) NULL,
  request_id      VARCHAR(64)  NOT NULL,
  request_params  JSON         NULL,
  PRIMARY KEY (event_id, event_time)
);
```

## 2. Column → ActionTrail field mapping

Field names follow ActionTrail's documented event structure; the mapping is stated at field-name level, verified against two Alibaba Cloud documentation pages (the SLS delivery field reference, https://www.alibabacloud.com/help/en/sls/actiontrail — "Understand ActionTrail Operation Log Fields", and the management-event structure reference, https://www.alibabacloud.com/help/en/cloud-config/latest/actiontrail-event-log-reference — "Management event structure"). Exact values and nesting inside delivered SLS records are **verified at activation**.

| Column | ActionTrail event field | Notes |
|---|---|---|
| `event_id` | `eventId` | Unique event ID (SLS doc: "The unique ID of the event") |
| `event_time` | `eventTime` | UTC, per the management-event reference; `DATETIME(3)` leaves room for sub-second precision |
| `event_source` | `eventSource` | Service endpoint the call targeted |
| `event_name` | `eventName` | API operation / console operation name |
| `resource_type` / `resource_name` | `resourceType` / `resourceName` | Both documented in the management-event reference (added to event logs per ActionTrail's 2020 field announcement); multi-value fields are semicolon-separated — split on ingest or store as delivered |
| `region` | `acsRegion` | Region where the event occurred |
| `identity_type` | `userIdentity.type` | Documented values include `root-account`, `ram-user`, `assumed-role` |
| `identity_name` | `userIdentity.userName` | `root` for the account itself; assumed-role sessions carry `principalId` (keep `principalId` in `request_params` if session attribution matters) |
| `source_ip` | `sourceIpAddress` | Note the spelling: Alibaba's documented field is `sourceIpAddress`, **not** AWS CloudTrail's `sourceIPAddress` — verified on both doc pages above |
| `user_agent` | `userAgent` | Client that initiated the event |
| `error_code` / `error_message` | `errorCode` / `errorMessage` | Present when the call failed — the DENIED-action signal |
| `request_id` | `requestId` | Correlates with the service-side request log |
| `request_params` | `requestParameters` (delivered to SLS also as `requestParameterJson`) | Stored as JSON; treat as sensitive-adjacent — it can embed ARNs and names |

## 3. Query cookbook ("who did what")

Each query is given in MySQL (runs against the sim `audit_event` table) and in SLS SQL form (design-level; SLS executes SQL after the pipe over the store's `event` JSON field — exact syntax validated against a live project at activation). Hub region assumed `ap-southeast-1`.

**Q1 — who touched KMS keys in a window**

```sql
SELECT event_time, identity_type, identity_name, event_name, resource_name, source_ip
FROM   audit_event
WHERE  event_source LIKE '%kms%'
  AND  event_time BETWEEN '2026-09-13 00:00:00' AND '2026-09-13 23:59:59'
ORDER BY event_time;
```

SLS: `* | SELECT from_unixtime(__time__) AS t, json_extract_scalar(event, '$.userIdentity.userName') AS actor, json_extract_scalar(event, '$.eventName') AS op, json_extract_scalar(event, '$.resourceName') AS res FROM log WHERE json_extract_scalar(event, '$.serviceName') = 'Kms' ORDER BY t DESC LIMIT 100`

**Q2 — all DENIED actions (who attempted what from where)** — failed calls carry a non-empty `errorCode`; access-denied codes vary per service, so this casts the net and the analyst refines by code:

```sql
SELECT event_time, identity_name, event_name, source_ip, user_agent, error_code
FROM   audit_event
WHERE  error_code IS NOT NULL
ORDER BY event_time DESC;
```

SLS: `* | SELECT from_unixtime(__time__) AS t, json_extract_scalar(event, '$.userIdentity.userName') AS actor, json_extract_scalar(event, '$.eventName') AS op, json_extract_scalar(event, '$.sourceIpAddress') AS src, json_extract_scalar(event, '$.errorCode') AS err FROM log WHERE json_extract_scalar(event, '$.errorCode') IS NOT NULL ORDER BY t DESC LIMIT 100`

**Q3 — bucket-policy changes** (the control that gates public/plaintext access):

```sql
SELECT event_time, identity_name, event_name, resource_name, request_params
FROM   audit_event
WHERE  event_name LIKE '%BucketPolicy%'
ORDER BY event_time DESC;
```

SLS: `* | SELECT from_unixtime(__time__) AS t, json_extract_scalar(event, '$.userIdentity.userName') AS actor, json_extract_scalar(event, '$.eventName') AS op, json_extract_scalar(event, '$.resourceName') AS bucket FROM log WHERE json_extract_scalar(event, '$.eventName') LIKE '%BucketPolicy%' ORDER BY t DESC LIMIT 100`

**Q4 — everything a specific identity did:**

```sql
SELECT event_time, event_name, resource_type, resource_name, source_ip, error_code
FROM   audit_event
WHERE  identity_name = 'silkroute-ci'
ORDER BY event_time DESC;
```

SLS: `* | SELECT from_unixtime(__time__) AS t, json_extract_scalar(event, '$.eventName') AS op, json_extract_scalar(event, '$.resourceName') AS res, json_extract_scalar(event, '$.sourceIpAddress') AS src, json_extract_scalar(event, '$.errorCode') AS err FROM log WHERE json_extract_scalar(event, '$.userIdentity.userName') = 'silkroute-ci' ORDER BY t DESC LIMIT 100`

**Q5 — region-anomaly canary** (any event outside the hub region is a question, not an event):

```sql
SELECT event_time, identity_name, event_name, region, source_ip
FROM   audit_event
WHERE  region <> 'ap-southeast-1'
ORDER BY event_time DESC;
```

SLS: `* | SELECT from_unixtime(__time__) AS t, json_extract_scalar(event, '$.acsRegion') AS reg, json_extract_scalar(event, '$.userIdentity.userName') AS actor, json_extract_scalar(event, '$.eventName') AS op FROM log WHERE json_extract_scalar(event, '$.acsRegion') <> 'ap-southeast-1' ORDER BY t DESC LIMIT 100`

## 4. Sim-mode demonstration (`make audit-demo`)

**Label, exactly: this is a sim-mode query-pattern demonstration.** The production store is SLS fed by the designed trail; no cloud runtime evidence exists (ADR-0002). The demonstration proves that the schema, the seed data, and the cookbook queries behave as designed — nothing more.

`make audit-demo` (orchestrator-owned target) seeds a schema-conformant `audit_event` table into the **sim MySQL** (`sim-mysql`, docker-compose) inside a dedicated `silkroute_audit` database, with a small realistic event set:

1. **KMS key rotation by a human identity** — `identity_type='ram-user'`, `event_name='UpdateRotationPolicy'` (the doc-verified KMS rotation API, Kms 2016-01-20: https://help.aliyun.com/en/kms/key-management-service/developer-reference/api-kms-2016-01-20-overview), `error_code IS NULL`.
2. **Bucket-policy change by the CI role** — `identity_type='assumed-role'`, `identity_name='silkroute-ci'` — ties to the STRIDE trust-boundary finding F-04: the pipeline *can* change the bucket-policy control surface, and the trail is what makes that visible.
3. **A DENIED `StopLogging` attempt by the CI identity** — `error_code` set, `error_message` populated — ties directly to STRIDE finding F-05 (the CI role currently holds `StopLogging`; this seeded event demonstrates exactly the audit signal a break-glass split would produce if the attempt were denied).
4. **A region-anomaly event** — `region <> 'ap-southeast-1'` — the row Q5 exists to catch.

It then runs the §3 cookbook queries against the seeded table and prints the results, each annotated with the SLS equivalent it mirrors. The value of the demo is narrow and honest: the query patterns, the schema round-trip, and the denied-action/region-anomaly reads behave as designed before anyone spends a cloud cent.

## 5. Retention and alerting notes

- **Retention**: the `audit` store is pinned at **180 days** in IaC (`infra/observability/main.tf` `log_stores` map); app logs (`esb-app`) and event traces (`orders-events`) run 30 days by contrast — the audit tier is deliberately the long-memory store.
- **Alerting (design-level)**: alert on **denied-action bursts** (e.g., > N failed calls sharing an identity or source IP in a window) and on **`StopLogging`/`DeleteTrail` events of any count** — the second is the automated tripwire for STRIDE finding F-05. The modern `alicloud_sls_alert` resource exists in provider 1.285.0 (per the corrected note in ADR-0004; the observability module currently ships the older `alicloud_log_alert` form for DLQ depth, with the migration recorded as a small deferred follow-up). Alert rules land with the trail at activation and are verified then — no alerting claim is made beyond design.
