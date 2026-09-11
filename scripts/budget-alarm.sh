#!/usr/bin/env bash
# Budget alarm for the SilkRoute AliCloud account (~$20/month, MASTER_PROMPT §8).
#
# WHY A SCRIPT, NOT TERRAFORM: provider aliyun/alicloud 1.285.0 ships no
# budget/BSS resource type (verified by case-insensitive grep over all 1161
# resource names in `terraform providers schema -json`, S4). The alarm is
# therefore an out-of-band BssOpenApi call, documented here and executed only
# against a real account.
#
# API CONTRACT (corrected per critic cycle 1 — the original draft targeted a
# SetBudgets action that is not findable in the docs): CreateBudget, BssOpenApi
# version 2023-09-30. Key parameters verified against
# https://help.aliyun.com/en/user-center/developer-reference/api-bssopenapi-2023-09-30-createbudget :
#   BudgetName, BudgetType=CONSUME, Metric=REQUIRE_AMOUNT, CycleType=MONTHLY,
#   CycleStartPeriod/CycleEndPeriod (e.g. 2026-01..2026-12), QuotaType=SPECIFY
#   with CycleQuota[].Quota, and WarnConfs[] for alerts
#   (WarnTarget=ACTUAL, ThresholdType=PERCENTAGE, ThresholdValue, MscChannels).
# Residual honesty: parameter SPELLING is doc-verified; the CALL itself stays
# unexercised until an account exists (ADR-0002 validated-plans mode).
set -euo pipefail

BUDGET_AMOUNT="${BUDGET_AMOUNT:-20}"
BUDGET_NAME="${BUDGET_NAME:-silkroute-monthly}"
ALERT_THRESHOLD="${ALERT_THRESHOLD:-100}"
YEAR="${YEAR:-$(date +%Y)}"

payload=$(cat <<JSON
{
  "BudgetName": "${BUDGET_NAME}",
  "BudgetType": "CONSUME",
  "Metric": "REQUIRE_AMOUNT",
  "CycleType": "MONTHLY",
  "CycleStartPeriod": "${YEAR}-01",
  "CycleEndPeriod": "${YEAR}-12",
  "QuotaType": "SPECIFY",
  "CycleQuota": [
    {"CyclePeriod": "${YEAR}-01", "Quota": "${BUDGET_AMOUNT}"},
    {"CyclePeriod": "${YEAR}-02", "Quota": "${BUDGET_AMOUNT}"},
    {"CyclePeriod": "${YEAR}-03", "Quota": "${BUDGET_AMOUNT}"},
    {"CyclePeriod": "${YEAR}-04", "Quota": "${BUDGET_AMOUNT}"},
    {"CyclePeriod": "${YEAR}-05", "Quota": "${BUDGET_AMOUNT}"},
    {"CyclePeriod": "${YEAR}-06", "Quota": "${BUDGET_AMOUNT}"},
    {"CyclePeriod": "${YEAR}-07", "Quota": "${BUDGET_AMOUNT}"},
    {"CyclePeriod": "${YEAR}-08", "Quota": "${BUDGET_AMOUNT}"},
    {"CyclePeriod": "${YEAR}-09", "Quota": "${BUDGET_AMOUNT}"},
    {"CyclePeriod": "${YEAR}-10", "Quota": "${BUDGET_AMOUNT}"},
    {"CyclePeriod": "${YEAR}-11", "Quota": "${BUDGET_AMOUNT}"},
    {"CyclePeriod": "${YEAR}-12", "Quota": "${BUDGET_AMOUNT}"}
  ],
  "WarnConfs": [
    {
      "Name": "silkroute-monthly-cap",
      "WarnTarget": "ACTUAL",
      "ThresholdType": "PERCENTAGE",
      "ThresholdValue": ${ALERT_THRESHOLD},
      "MscChannels": ["EMAIL"]
    }
  ]
}
JSON
)

if [[ "${LIVE:-0}" != "1" ]]; then
  echo "DRY RUN (default) — no API call performed. To execute against a real account:"
  echo "  LIVE=1 ALICLOUD_ACCESS_KEY_ID=... ALICLOUD_ACCESS_KEY_SECRET=... bash $0"
  echo
  echo "BssOpenApi CreateBudget (2023-09-30) payload: monthly cap \$${BUDGET_AMOUNT}, warn at ${ALERT_THRESHOLD}%:"
  echo "$payload"
  exit 0
fi

command -v aliyun >/dev/null || { echo "ERROR: LIVE=1 needs the 'aliyun' CLI (see aliyun-cli install docs)."; exit 1; }
: "${ALICLOUD_ACCESS_KEY_ID:?LIVE=1 needs ALICLOUD_ACCESS_KEY_ID}"
: "${ALICLOUD_ACCESS_KEY_SECRET:?LIVE=1 needs ALICLOUD_ACCESS_KEY_SECRET}"

# RPC-style call with explicit flags (JSON arrays quoted); if the CLI version
# differs on array-flag conventions at activation, adjust there and record it.
aliyun bssopenapi CreateBudget \
  --BudgetName "$BUDGET_NAME" \
  --BudgetType CONSUME \
  --Metric REQUIRE_AMOUNT \
  --CycleType MONTHLY \
  --CycleStartPeriod "${YEAR}-01" \
  --CycleEndPeriod "${YEAR}-12" \
  --QuotaType SPECIFY \
  --"CycleQuota=[{\"CyclePeriod\":\"${YEAR}-01\",\"Quota\":\"${BUDGET_AMOUNT}\"}]" \
  --"WarnConfs=[{\"Name\":\"silkroute-monthly-cap\",\"WarnTarget\":\"ACTUAL\",\"ThresholdType\":\"PERCENTAGE\",\"ThresholdValue\":${ALERT_THRESHOLD},\"MscChannels\":[\"EMAIL\"]}]"
echo "CreateBudget submitted: name=${BUDGET_NAME} monthly cap=\$${BUDGET_AMOUNT} warn@${ALERT_THRESHOLD}%"
