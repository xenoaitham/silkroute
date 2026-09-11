#!/usr/bin/env bash
# Apply-validity lint for validated-plans mode (ADR-0002).
#
# `terraform plan` cannot catch constraints the AliCloud APIs enforce at APPLY
# time. This script guards the known plan-invisible rules so a plan-only phase
# does not certify an unappliable design. Born from critic cycle 1: the landing
# zone planned green with Kafka topic names containing dots — which ApsaraMQ
# for Kafka CreateTopic rejects (only letters, digits, "_" and "-", 3-64 chars,
# ref: alikafka 2019-09-16 CreateTopic).
set -euo pipefail
cd "$(dirname "$0")/../infra"

violations=$(awk '
  /^resource[[:space:]]+"alicloud_alikafka_topic"/ { inblock = 1; next }
  inblock && /^[}]/                                { inblock = 0; next }
  inblock && /^[[:space:]]*topic[[:space:]]*=/ {
    if (match($0, /"[^"]+"/)) {
      v = substr($0, RSTART + 1, RLENGTH - 2)
      ok = (v ~ /^[A-Za-z0-9_-]+$/) && length(v) >= 3 && length(v) <= 64
      if (!ok) print "FAIL: " FILENAME ":" FNR " alikafka topic [" v "] violates the ApsaraMQ naming rule (letters/digits/_/- , 3-64 chars) — CreateTopic rejects it at apply."
    }
  }
' $(find . -name '*.tf' -not -path './.terraform/*'))

if [ -n "$violations" ]; then
  echo "$violations"
  exit 1
fi
echo "tf-apply-validity: OK — no known apply-time violations in infra/."
