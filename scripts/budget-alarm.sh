#!/usr/bin/env bash
# Budget alarm for the SilkRoute AliCloud account (~$20/month, MASTER_PROMPT §8).
#
# WHY A SCRIPT, NOT TERRAFORM: provider aliyun/alicloud 1.285.0 ships no
# budget/BSS resource type (verified by case-insensitive grep over all 1161
# resource names in `terraform providers schema -json`, S4). The alarm is
# therefore an out-of-band BSSOpenAPI SetBudgets call, documented here and
# executed only against a real account.
#
# Modes:
#   bash scripts/budget-alarm.sh            # DRY RUN (default): prints the exact
#                                           # SetBudgets payload, performs nothing
#   LIVE=1 bash scripts/budget-alarm.sh     # live call via the `aliyun` CLI;
#                                           # requires ALICLOUD_ACCESS_KEY_ID /
#                                           # ALICLOUD_ACCESS_KEY_SECRET in env
#
# Honesty note: parameter names below follow the published BSSOpenAPI
# SetBudgets contract; the call itself is UNVERIFIED until an account exists
# (ADR-0002 validated-plans mode). Verify against
# https://api.aliyun.com/document/bssopenapi at activation and adjust there —
# do not trust this file's parameter spelling blindly.
set -euo pipefail

BUDGET_AMOUNT="${BUDGET_AMOUNT:-20}"
BUDGET_NAME="${BUDGET_NAME:-silkroute-monthly}"
ALERT_THRESHOLD="${ALERT_THRESHOLD:-100}"

payload=$(cat <<JSON
{
  "BudgetName": "${BUDGET_NAME}",
  "BudgetType": "MONTHLY",
  "BudgetAmount": "${BUDGET_AMOUNT}",
  "BudgetLimitType": "Cost",
  "AlertThresholdList": [
    {"BudgetLimitType": "Cost", "AlertThreshold": ${ALERT_THRESHOLD}}
  ],
  "AlertTypeList": ["EmailAndSms"]
}
JSON
)

if [[ "${LIVE:-0}" != "1" ]]; then
  echo "DRY RUN (default) — no API call performed. To execute against a real account:"
  echo "  LIVE=1 ALICLOUD_ACCESS_KEY_ID=... ALICLOUD_ACCESS_KEY_SECRET=... bash $0"
  echo
  echo "BSSOpenAPI SetBudgets payload (amount \$${BUDGET_AMOUNT}, alert at ${ALERT_THRESHOLD}%):"
  echo "$payload"
  exit 0
fi

command -v aliyun >/dev/null || { echo "ERROR: LIVE=1 needs the 'aliyun' CLI (pacman/brew install aliyun-cli)."; exit 1; }
: "${ALICLOUD_ACCESS_KEY_ID:?LIVE=1 needs ALICLOUD_ACCESS_KEY_ID}"
: "${ALICLOUD_ACCESS_KEY_SECRET:?LIVE=1 needs ALICLOUD_ACCESS_KEY_SECRET}"

aliyun bssopenapi SetBudgets \
  --BudgetName "$BUDGET_NAME" \
  --BudgetType MONTHLY \
  --BudgetAmount "$BUDGET_AMOUNT" \
  --BudgetLimitType Cost \
  --AlertThresholdList "[{\"BudgetLimitType\":\"Cost\",\"AlertThreshold\":${ALERT_THRESHOLD}}]" \
  --AlertTypeList EmailAndSms
echo "SetBudgets submitted: name=${BUDGET_NAME} amount=\$${BUDGET_AMOUNT} alert@${ALERT_THRESHOLD}%"
