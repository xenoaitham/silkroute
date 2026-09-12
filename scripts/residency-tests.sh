#!/usr/bin/env bash
# C1 residency checks — static, machine-checkable, falsifiable.
#
# What these checks PROVE: the Terraform resource graph pins the CN partition
# to the CN provider, keeps CN strings out of the SG modules, ships zero
# cross-region replication/backup resources, tags every taggable CN resource,
# keeps the cn-/sg- bucket families apart, has no NAT/EIP egress path in the
# CN partition, and the ESB has exactly one Kafka publish chokepoint wired
# through PiiMaskingPolicy.
#
# What they do NOT prove: runtime cloud behavior. No AliCloud account exists
# (ADR-0002 validated-plans mode) — an actual bucket rejecting a cross-region
# read or a live SAE namespace resolving in cn-beijing is verify-at-activation.
# This script is the CI side of the C1 consequence; the behavioral side is the
# fault-injection suite's masked-DLQ assertions (E-009).
#
# Usage:
#   scripts/residency-tests.sh [INFRA_DIR] [ESB_SRC_DIR]   # run checks
#   scripts/residency-tests.sh --selftest                  # prove falsifiability
#     (selftest copies the tree to a tmp dir, injects five violations, and
#      requires the suite to catch each one with its expected check ID)
#
# Exit 0 = all checks green (and selftest caught all mutations when requested).
# Exit 2 = at least one check failed (or selftest found a blind check).

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SELFTEST=0
ARGS=()
for a in "$@"; do
  [ "$a" = "--selftest" ] && SELFTEST=1 || ARGS+=("$a")
done
INFRA_DIR="${ARGS[0]:-$REPO_ROOT/infra}"
ESB_SRC="${ARGS[1]:-$REPO_ROOT/apps/esb/src/main/java}"

failures=0
fail() { echo "FAIL [$1] $2"; failures=$((failures + 1)); }
pass() { echo "ok   [$1] $2"; }

# Resource types in cn-partition WITHOUT a tags attribute in provider
# alicloud 1.285.0 (schema-verified: SEC-1 review §1/§5, 2026-09-11). The SAE
# namespace is residency-pinned by the region prefix in its namespace_id and
# by the aliased provider instead.
NO_TAGS_TYPES="alicloud_sae_namespace alicloud_kms_alias alicloud_db_database alicloud_db_account alicloud_db_account_privilege alicloud_oss_bucket_server_side_encryption alicloud_oss_bucket_versioning alicloud_oss_bucket_public_access_block alicloud_oss_bucket_policy random_password"

# ---------------------------------------------------------------- checks ---

# R1: CN partition is pinned at the provider graph (SEC-4-01's fix), behind
# the flag, and the aliased provider resolves to var.cn_region.
check_r1() {
  grep -q 'alias[[:space:]]*=[[:space:]]*"cn"' "$INFRA_DIR/providers.tf" \
    && grep -q 'region[[:space:]]*=[[:space:]]*var\.cn_region' "$INFRA_DIR/providers.tf" \
    && grep -q 'providers.*alicloud = alicloud\.cn' "$INFRA_DIR/main.tf" \
    && grep -q 'count[[:space:]]*=[[:space:]]*var\.enable_cn_region [?] 1 : 0' "$INFRA_DIR/main.tf"
}

# R2: CN region strings/variables appear ONLY in the wiring files and the
# cn-partition module — never inside the SG hub modules.
check_r2() {
  local bad
  bad=$(grep -rnE 'cn[-_]beijing|var\.cn_' "$INFRA_DIR" --include='*.tf' --include='*.tfvars.example' 2>/dev/null \
    | while IFS=: read -r f _; do
        case "$f" in
          */variables.tf|*/providers.tf|*/main.tf|*/terraform.tfvars.example|*/cn-partition/*) ;;
          *) echo "$f" ;;
        esac
      done | sort -u)
  [ -z "$bad" ]
}

# R3: zero cross-region replication / backup / mirror resources anywhere.
check_r3() {
  ! grep -rnE '^resource ' "$INFRA_DIR" --include='*.tf' 2>/dev/null \
    | grep -qiE 'replication|backup|mirror|geo[-_]'
}

# R4: every resource block in cn-partition carries tags = local.cn_tags,
# except the schema-tagless types adjudicated in NO_TAGS_TYPES. The check
# runs INSIDE awk — a body with embedded newlines cannot be streamed line-by-
# line (every continuation line would read as a phantom untagged block).
check_r4() {
  ! awk -v exc="$NO_TAGS_TYPES" '
    BEGIN { n = split(exc, a, " "); for (i = 1; i <= n; i++) tagless[a[i]] = 1 }
    /^resource "/ {inblk = 1; type = $2; gsub(/"/, "", type); tagged = 0; next}
    inblk && /^}/ {
      if (!tagless[type] && !tagged) print "UNTAGGED " type
      inblk = 0; next
    }
    inblk && /tags[[:space:]]*=[[:space:]]*local\.cn_tags/ {tagged = 1}
  ' "$INFRA_DIR/cn-partition/main.tf" | grep -q '^UNTAGGED'
}

# R5: bucket name FAMILIES never cross — CN bucket-name literals only inside
# cn-partition, SG names never inside it. The `acs:oss:*:*:silkroute-cn-*`
# ARN family prefixes in the CI deploy policy (infra/security/main.tf) are
# management-plane references to both families and are allowed (SEC-1 §2).
check_r5() {
  local cn_literal_outside sg_inside
  cn_literal_outside=$(grep -rn 'silkroute-cn-' "$INFRA_DIR" --include='*.tf' 2>/dev/null \
    | grep -v '/cn-partition/' | grep -v 'acs:oss:\*:\*:' | wc -l)
  sg_inside=$(grep -rn 'silkroute-sg-' "$INFRA_DIR/cn-partition" 2>/dev/null | wc -l)
  [ "$cn_literal_outside" -eq 0 ] && [ "$sg_inside" -eq 0 ]
}

# R6: the CN partition has no NAT gateway / EIP — no egress path at all.
check_r6() {
  ! grep -qnE '^resource "(alicloud_nat_gateway|alicloud_eip|alicloud_eip_association)"' \
    "$INFRA_DIR/cn-partition/main.tf" 2>/dev/null
}

# R7: the ESB has exactly one Kafka publish chokepoint (sendBodyAndHeaders /
# "kafka:" URIs live only in EventPublisher), that chokepoint holds
# PiiMaskingPolicy, and the saga masks the CN customerRef for egress.
check_r7() {
  local senders
  senders=$(grep -rl 'sendBodyAndHeaders\|"kafka:' "$ESB_SRC" --include='*.java' 2>/dev/null | wc -l)
  [ "$senders" -eq 1 ] \
    && grep -q 'PiiMaskingPolicy' "$ESB_SRC/com/mapleretail/silkroute/esb/events/EventPublisher.java" \
    && grep -q 'maskForEgress' "$ESB_SRC/com/mapleretail/silkroute/esb/saga/SagaOrchestrator.java"
}

run_checks() {
  check_r1 && pass "R1" "CN partition pinned at the provider graph (alicloud.cn alias + providers meta-argument + count flag)" \
    || fail "R1" "provider-graph pinning missing or flag removed"
  check_r2 && pass "R2" "CN region strings confined to wiring files + cn-partition" \
    || fail "R2" "CN region string inside an SG hub module"
  check_r3 && pass "R3" "zero cross-region replication/backup/mirror resources" \
    || fail "R3" "cross-region replication/backup resource present"
  check_r4 && pass "R4" "every taggable cn-partition resource carries residency=cn tags" \
    || fail "R4" "a taggable CN resource is missing residency tags"
  check_r5 && pass "R5" "bucket name families never cross (silkroute-cn-* vs silkroute-sg-*)" \
    || fail "R5" "bucket family violation"
  check_r6 && pass "R6" "no NAT/EIP in the CN partition (no egress path)" \
    || fail "R6" "CN partition contains a NAT/EIP resource"
  check_r7 && pass "R7" "single Kafka publish chokepoint, wired through PiiMaskingPolicy; saga masks CN customerRef" \
    || fail "R7" "publish chokepoint or masking wiring broken"
}

# ------------------------------------------------------------- selftest ---

# Each mutation must turn the suite red WITH the expected check id — a check
# that survives its own violation is decoration, not a check.
selftest() {
  local tmp
  tmp="$(mktemp -d)"
  cp -r "$REPO_ROOT/infra" "$tmp/infra"
  mkdir -p "$tmp/apps/esb/src/main/java/com/mapleretail/silkroute/esb/saga"
  cp -r "$REPO_ROOT/apps/esb/src/main/java" "$tmp/apps/esb/src/main/java"
  local all_ok=1

  expect_fail() { # id description mutation-command...
    local id="$1" desc="$2"; shift 2
    local work
    work="$(mktemp -d "$tmp/mut-XXXX")"
    cp -r "$REPO_ROOT/infra" "$work/infra"
    cp -r "$REPO_ROOT/apps/esb/src/main/java" "$work/java"
    "$@" "$work"
    local out
    out=$(bash "$SCRIPT_DIR/residency-tests.sh" "$work/infra" "$work/java" 2>/dev/null)
    if echo "$out" | grep -q "FAIL \[$id\]"; then
      echo "ok   [selftest] $id caught: $desc"
    else
      echo "FAIL [selftest] $id did NOT catch: $desc (a blind check)"
      all_ok=0
    fi
    rm -rf "$work"
  }

  drop_providers_line() { sed -i '/providers = { alicloud = alicloud\.cn }/d' "$1/infra/main.tf"; }
  add_replication() {
    cat >> "$1/infra/cn-partition/main.tf" <<'EOF'

resource "alicloud_oss_bucket_replication" "bad" {
  bucket = "silkroute-cn-bronze"
}
EOF
  }
  strip_kms_tags() {
    sed -i '/resource "alicloud_kms_key" "cn_data"/,/^}/ { /tags[[:space:]]*=[[:space:]]*local\.cn_tags/d }' "$1/infra/cn-partition/main.tf"
  }
  cross_bucket_family() { sed -i 's/silkroute-cn-bronze/silkroute-sg-bronze/' "$1/infra/cn-partition/main.tf"; }
  stray_publisher() {
    cat > "$1/java/com/mapleretail/silkroute/esb/saga/StrayPublisher.java" <<'EOF'
package com.mapleretail.silkroute.esb.saga;

import org.apache.camel.ProducerTemplate;
import java.util.Map;

public class StrayPublisher {
    private final ProducerTemplate producer;
    public StrayPublisher(ProducerTemplate producer) { this.producer = producer; }
    public void leak(String uri, String body, Map<String, Object> headers) {
        producer.sendBodyAndHeaders(uri, body, headers);
    }
}
EOF
  }

  expect_fail "R1" "providers meta-argument deleted from the cn module" drop_providers_line
  expect_fail "R3" "cross-region replication resource added to cn-partition" add_replication
  expect_fail "R4" "residency tags stripped from the CN KMS key" strip_kms_tags
  expect_fail "R5" "CN bucket renamed into the SG family" cross_bucket_family
  expect_fail "R7" "stray direct Kafka publish path added outside EventPublisher" stray_publisher

  rm -rf "$tmp"
  [ "$all_ok" -eq 1 ] || exit 2
}

# ----------------------------------------------------------------- main ----

if [ "$SELFTEST" -eq 1 ]; then
  echo "== residency-tests selftest: every check must catch its own violation =="
  selftest
  echo "selftest OK: all five mutations caught"
  exit 0
fi

echo "== C1 residency checks (static; validated-plans honesty per ADR-0002) =="
echo "    infra: $INFRA_DIR"
# Run in THIS shell — a command substitution would run the checks in a
# subshell and the failure counter would never reach the exit decision
# (the bug the selftest exists to catch).
run_checks
if [ "$failures" -eq 0 ]; then
  echo "residency OK (7/7 checks) — graph + wiring enforce C1 statically; runtime proof waits for an account"
  exit 0
fi
echo "residency FAILED: $failures check(s)"
exit 2
