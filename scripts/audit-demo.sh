#!/usr/bin/env bash
# Audit-trail demo — "who did what" query pattern, proven against sim-mode data.
#
# What this PROVES: the audit-event schema (compliance/audit-trail-design.md §1),
# the seed data, and the five cookbook queries behave as designed — including
# the two reads that matter most in practice: denied actions (who attempted
# what from where) and the region-anomaly canary.
#
# What this does NOT prove: anything about a live cloud. The production store
# is SLS fed by the designed ActionTrail (infra/observability/main.tf) — that
# trail has never run because no AliCloud account exists (ADR-0002,
# validated-plans mode). This demo runs against the SIM MySQL (silkroute_audit
# database inside sim-mysql) and seed rows are SIMULATED events: identities,
# IPs (RFC 5737 documentation ranges), and request payloads are illustrative.
# Event names use real AliCloud API vocabulary (UpdateRotationPolicy is the
# doc-verified KMS rotation API, Kms 2016-01-20) so the query shapes transfer.
#
# Idempotent: creates the schema if missing, seeds only when the table is
# empty, re-runs the queries and assertions every time.
#
# Exit 0 = all queries ran and all assertions held. Exit 2 = anything else.

set -u

fail() { echo "FAIL: $1"; exit 2; }

command -v docker >/dev/null || fail "docker not available"
docker compose ps mysql 2>/dev/null | grep -q sim-mysql || fail "sim-mysql not running (make up first)"

# SQL on stdin -> MySQL in the sim container (password from container env,
# never hardcoded — same pattern as scripts/smoke.sh). sqlddl connects WITHOUT
# a default database (the database may not exist yet); sql/sqln default to
# silkroute_audit, which the DDL step guarantees exists.
sqlddl() { docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --table'; }
sql()    { docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --table silkroute_audit'; }
sqln()   { docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N silkroute_audit'; }

echo "== audit-trail demo: schema + seed + \"who did what\" cookbook (sim-mode, per audit-trail-design.md) =="

# 1. Schema — the DDL is the one committed in compliance/audit-trail-design.md §1.
sqlddl <<'SQL' >/dev/null || fail "schema DDL failed"
CREATE DATABASE IF NOT EXISTS silkroute_audit;
USE silkroute_audit;
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
SQL

# 2. Seed only when empty (8 simulated management events, 2026-09-13).
count="$(echo "SELECT COUNT(*) FROM silkroute_audit.audit_event;" | sqln 2>/dev/null | tail -1)"
[ "$count" = "0" ] || { echo "seed: table already holds $count rows — skipping seed"; }
if [ "$count" = "0" ]; then
sqlddl <<'SQL' >/dev/null || fail "seed insert failed"
USE silkroute_audit;
INSERT INTO audit_event
  (event_id, event_time, event_source, event_name, resource_type, resource_name,
   region, identity_type, identity_name, source_ip, user_agent,
   error_code, error_message, request_id, request_params) VALUES
 -- Q1 target: KMS key-management by a human identity (rotation policy change)
 ('seed-001','2026-09-13 09:14:03.221','kms.aliyuncs.com','UpdateRotationPolicy','ALIAS','alias/silkroute-sg-oss',
  'ap-southeast-1','ram-user','mrg-secops','203.0.113.17','aliyun-cli/3.0.214',
  NULL,NULL,'req-7f3a91','{"KeyName":"alias/silkroute-sg-oss","EnableKeyRotation":true,"RotationInterval":"365"}'),
 ('seed-002','2026-09-13 09:15:47.808','kms.aliyuncs.com','CreateKey','ALIAS','alias/silkroute-sg-rds',
  'ap-southeast-1','ram-user','mrg-secops','203.0.113.17','aliyun-cli/3.0.214',
  NULL,NULL,'req-7f3a92','{"KeyAlias":"alias/silkroute-sg-rds","KeyUsage":"ENCRYPT/DECRYPT"}'),
 -- Q3 target: bucket-policy change by the CI deploy role (trust-boundary visibility, STRIDE F-04)
 ('seed-003','2026-09-13 10:02:12.519','oss.aliyuncs.com','PutBucketPolicy','OSSBUCKET','silkroute-sg-bronze',
  'ap-southeast-1','assumed-role','silkroute-ci','198.51.100.42','Terraform/1.16.2',
  NULL,NULL,'req-8c10d2','{"BucketName":"silkroute-sg-bronze","PolicyDigest":"sha256:9f2c…"}'),
 -- Q2 target #1: the CI identity attempting to silence the audit trail (STRIDE F-05 signal)
 ('seed-004','2026-09-13 10:04:55.002','actiontrail.aliyuncs.com','StopLogging','TRAIL','silkroute-sg-trail',
  'ap-southeast-1','assumed-role','silkroute-ci','198.51.100.42','Terraform/1.16.2',
  'SubUserForbiddenError','The user is not authorized to perform this operation','req-8c10d3','{"TrailName":"silkroute-sg-trail"}'),
 -- Q2 target #2: trail deletion attempt, also denied
 ('seed-005','2026-09-13 10:05:01.334','actiontrail.aliyuncs.com','DeleteTrail','TRAIL','silkroute-sg-trail',
  'ap-southeast-1','assumed-role','silkroute-ci','198.51.100.42','Terraform/1.16.2',
  'SubUserForbiddenError','The user is not authorized to perform this operation','req-8c10d4','{"TrailName":"silkroute-sg-trail"}'),
 -- Q4 target: RAM self-modification by the CI role (visible in the per-identity read)
 ('seed-006','2026-09-13 10:06:29.610','ram.aliyuncs.com','CreatePolicy','RAMPOLICY','silkroute-ci-assume',
  'ap-southeast-1','assumed-role','silkroute-ci','198.51.100.42','Terraform/1.16.2',
  NULL,NULL,'req-8c10d5','{"PolicyName":"silkroute-ci-assume"}'),
 -- Q5 target: a region-anomaly event — an action OUTSIDE the hub region
 ('seed-007','2026-09-13 11:41:18.947','oss.aliyuncs.com','CreateBucket','OSSBUCKET','silkroute-cn-bronze',
  'cn-beijing','ram-user','mrg-secops','203.0.113.17','aliyun-cli/3.0.214',
  NULL,NULL,'req-9d44e7','{"BucketName":"silkroute-cn-bronze"}'),
 -- Baseline: routine KMS data-plane decrypt by the runtime role (background row)
 ('seed-008','2026-09-13 11:59:02.763','kms.aliyuncs.com','Decrypt','KEY','key/silkroute-sg-oss',
  'ap-southeast-1','assumed-role','silkroute-esb-runtime','10.60.1.42','sae/silkroute-esb',
  NULL,NULL,'req-9d44e8','{"KeyId":"alias/silkroute-sg-oss"}');
SQL
echo "seed: 8 simulated management events inserted (2026-09-13)"
fi

# 3. The cookbook queries (compliance/audit-trail-design.md §3).
echo
echo "-- Q1: who touched KMS keys in a window --"
echo "SELECT event_time, identity_type, identity_name, event_name, resource_name, source_ip
FROM audit_event
WHERE event_source LIKE '%kms%'
  AND event_time BETWEEN '2026-09-13 00:00:00' AND '2026-09-13 23:59:59'
ORDER BY event_time;" | sql || fail "Q1 failed"

echo
echo "-- Q2: all DENIED actions (who attempted what from where) --"
echo "SELECT event_time, identity_name, event_name, source_ip, user_agent, error_code
FROM audit_event
WHERE error_code IS NOT NULL
ORDER BY event_time DESC;" | sql || fail "Q2 failed"

echo
echo "-- Q3: bucket-policy changes --"
echo "SELECT event_time, identity_name, event_name, resource_name, request_params
FROM audit_event
WHERE event_name LIKE '%BucketPolicy%'
ORDER BY event_time DESC;" | sql || fail "Q3 failed"

echo
echo "-- Q4: everything a specific identity did --"
echo "SELECT event_time, event_name, resource_type, resource_name, source_ip, error_code
FROM audit_event
WHERE identity_name = 'silkroute-ci'
ORDER BY event_time DESC;" | sql || fail "Q4 failed"

echo
echo "-- Q5: region-anomaly canary (outside the hub region) --"
echo "SELECT event_time, identity_name, event_name, region, source_ip
FROM audit_event
WHERE region <> 'ap-southeast-1'
ORDER BY event_time DESC;" | sql || fail "Q5 failed"

# 4. Assertions — the demo must be able to fail if the pattern breaks.
deny_count="$(echo "SELECT COUNT(*) FROM silkroute_audit.audit_event WHERE error_code IS NOT NULL;" | sqln | tail -1)"
[ "$deny_count" = "2" ] || fail "expected 2 denied actions in seed, got: '$deny_count'"
stop_denied="$(echo "SELECT COUNT(*) FROM silkroute_audit.audit_event WHERE event_name='StopLogging' AND error_code IS NOT NULL;" | sqln | tail -1)"
[ "$stop_denied" = "1" ] || fail "the StopLogging denial is not visible to Q2's read"
anomaly="$(echo "SELECT COUNT(*) FROM silkroute_audit.audit_event WHERE region <> 'ap-southeast-1';" | sqln | tail -1)"
[ "$anomaly" = "1" ] || fail "expected exactly 1 region-anomaly row, got: '$anomaly'"
rotation="$(echo "SELECT COUNT(*) FROM silkroute_audit.audit_event WHERE event_name='UpdateRotationPolicy' AND identity_name='mrg-secops';" | sqln | tail -1)"
[ "$rotation" = "1" ] || fail "the KMS rotation event is not attributable via Q1's read"

echo
echo "audit-demo OK: schema + seed + 5 cookbook queries; assertions held (2 denials visible, 1 region anomaly, rotation attributable)"
echo "honest label: sim-mode query-pattern demonstration — the production store is SLS via the DESIGNED trail (ADR-0002; verify at activation)"
exit 0
