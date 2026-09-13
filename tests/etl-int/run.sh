#!/usr/bin/env bash
# SILKROUTE Phase-3 ETL integration suite (tests/etl-int) — BUILD-QA.
#
# One command:   bash tests/etl-int/run.sh
#
# Drives the REAL data plane end to end (no synthetic SQL for seeding — the
# only direct SQL is the recon negative control, which injects a phantom row
# on purpose and deletes it). Six scenarios, each printing "PASS <name>";
# the suite exits non-zero on the FIRST failed assertion ("FAIL: ..." line).
#
# House style follows scripts/smoke.sh: `set -uo pipefail` WITHOUT -e and
# every assertion written as an explicit `|| fail` / [ ... ] || fail — never
# an &&-list that can silently pass, never a command substitution that
# swallows a failure exit code.
#
# Hygiene:
#   - processes are ONLY ever started/stopped via the make PID-file targets
#     (esb-run/oms-run/cdc-run, esb-stop/oms-stop/cdc-stop); NEVER pkill;
#   - a trap stops esb+oms+cdc on ANY exit path (success, failure, Ctrl-C);
#   - on SUCCESS the suite finishes with `make etl-reset` so ORCH-LEAD and
#     the critic start from a known slate;
#   - never touches busforge/helios or anything outside the sim compose stack;
#   - the full transcript is tee'd to /tmp/s9-etl-int-run.txt (NOT committed;
#     ORCH-LEAD copies what it needs into evidence/).
set -uo pipefail

cd "$(dirname "$0")/../.." || { echo "cannot find repo root"; exit 1; }

TRANSCRIPT=/tmp/s9-etl-int-run.txt
SCRATCH=/tmp/s9-etl-int-scratch
TODAY="$(date -u +%F)"   # bronze dt partition = UTC date of the seed (C5)
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-silkroute}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-127.0.0.1:39092}"   # same default etl-reset.sh uses inside sim-kafka
OMS_GROUP="${OMS_CONSUMER_GROUP:-silkroute-oms}"
ORDERS_TOPIC="${KAFKA_ORDERS_TOPIC:-silkroute.orders.events}"
OMS_LOG=/tmp/silkroute-oms.log
CDC_ENGINE_LOG=/tmp/silkroute-cdc-engine.log
RECON_REPORT=/tmp/silkroute-recon-report.json

mkdir -p "$SCRATCH"
rm -f "$SCRATCH"/*.txt "$SCRATCH"/*.json
: > "$TRANSCRIPT"
exec > >(tee "$TRANSCRIPT") 2>&1

fail() { echo "FAIL: $1"; exit 1; }
step() { echo; echo "=== $1 ==="; }

cleanup() {
  # PID-file stops ONLY (same order as etl-reset: oms, cdc, esb). Never pkill.
  make -s oms-stop || true
  make -s cdc-stop || true
  make -s esb-stop || true
}
trap cleanup EXIT

echo "SILKROUTE ETL-INT suite start $(date -u +%FT%TZ) head=$(git rev-parse --short HEAD) business-date(UTC)=$TODAY"
command -v jq >/dev/null || fail "jq is required by the etl-int suite"

# ----------------------------------------------------------------- helpers --

mysql_scalar() { # echo scalar result of one SQL statement
  docker exec sim-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e "$1" 2>/dev/null
}

oms_orders() { mysql_scalar "SELECT COUNT(*) FROM silkroute_oms.oms_order"; }
oms_lines()  { mysql_scalar "SELECT COUNT(*) FROM silkroute_oms.oms_order_line"; }

assert_oms_rows() { # $1 expected orders, $2 expected lines, $3 label
  local want_o="$1" want_l="$2" label="$3" got_o got_l
  got_o="$(oms_orders)"; got_l="$(oms_lines)"
  [ "$got_o" = "$want_o" ] || fail "$label: oms_order rows expected $want_o, got $got_o"
  [ "$got_l" = "$want_l" ] || fail "$label: oms_order_line rows expected $want_l, got $got_l"
  echo "$label: OMS rows orders=$got_o lines=$got_l (as expected)"
}

log_count() { # echo count of pattern occurrences in a log (0 if log absent)
  # grep -c prints 0 AND exits 1 on no match, so capture stdout and default it
  local n
  n=$(grep -c "$1" "$2" 2>/dev/null)
  echo "${n:-0}"
}

# Wait until the CDC engine log shows no NEW CDC-CAPTURE lines for ~10s.
await_quiescence() { # $1 = minimum capture lines expected, $2 = label
  local want="$1" label="$2" waited=0 n1 n2
  while :; do
    n1="$(log_count CDC-CAPTURE "$CDC_ENGINE_LOG")"
    sleep 10
    n2="$(log_count CDC-CAPTURE "$CDC_ENGINE_LOG")"
    if [ "$n2" -ge "$want" ] && [ "$n2" = "$n1" ]; then
      echo "QUIESCENT $label: captures=$n2 (>= $want) stable for 10s after ${waited}s"
      return 0
    fi
    waited=$((waited + 10))
    if [ "$waited" -ge 240 ]; then
      fail "quiescence timeout ($label): captures=$n2 want>=$want — see $CDC_ENGINE_LOG"
    fi
  done
}

# After cdc-stop, SIGTERM shutdown is not instantaneous — wait until the
# engine log is static so the "CDC is DOWN" window is deterministic (a capture
# line appearing after this point would be a real violation, not shutdown lag).
await_engine_dead() { # $1 = label
  local label="$1" waited=0 n1 n2
  while :; do
    n1="$(log_count CDC-CAPTURE "$CDC_ENGINE_LOG")"
    sleep 3
    n2="$(log_count CDC-CAPTURE "$CDC_ENGINE_LOG")"
    if [ "$n1" = "$n2" ]; then
      echo "ENGINE-DOWN $label: log static at $n2 capture(s)"
      return 0
    fi
    waited=$((waited + 3))
    if [ "$waited" -ge 60 ]; then
      fail "engine log still moving 60s after cdc-stop ($label) — engine did not die"
    fi
  done
}

# Wait until the OMS log contains >= n REPLAY-SKIP lines (post offset-reset re-run).
await_replay_skips() { # $1 = min REPLAY-SKIP lines, $2 = label
  local want="$1" label="$2" waited=0 n
  while :; do
    n="$(log_count REPLAY-SKIP "$OMS_LOG")"
    if [ "$n" -ge "$want" ]; then
      echo "REPLAYED $label: replay-skips=$n (>= $want)"
      return 0
    fi
    sleep 5; waited=$((waited + 5))
    if [ "$waited" -ge 120 ]; then
      fail "replay wait timeout ($label): REPLAY-SKIP=$n want>=$want — see $OMS_LOG"
    fi
  done
}

# Wait until an OMS row count reaches the expected value (consumer catch-up).
await_oms_orders() { # $1 = expected count, $2 = label
  local want="$1" label="$2" waited=0 n
  while :; do
    n="$(oms_orders)"
    if [ "$n" = "$want" ]; then
      echo "OMS-CAUGHT-UP $label: orders=$n"
      return 0
    fi
    sleep 5; waited=$((waited + 5))
    if [ "$waited" -ge 120 ]; then
      fail "OMS catch-up timeout ($label): orders=$n want=$want — see $OMS_LOG"
    fi
  done
}

bronze_keys() { # echo all bronze object paths (one per line)
  docker exec sim-minio mc find local/silkroute-sg-bronze --name '*.jsonl' 2>/dev/null
}

bronze_keys_for_table() { # $1 = table name
  bronze_keys | grep "/table=$1/"
}

# Count raw JSONL records across every bronze object of one table (host-side
# counting only — NO grep/cat INSIDE the minio container, pipe to the host).
bronze_table_records() { # $1 = table name -> echoes total record count
  local table="$1" total=0 key n
  while IFS= read -r key; do
    [ -n "$key" ] || continue
    n=$(docker exec sim-minio mc cat "$key" 2>/dev/null | wc -l)
    total=$((total + n))
  done < <(bronze_keys_for_table "$table")
  echo "$total"
}

seed_and_check() { # $1 = N orders, $2 = label — runs make seed-day, asserts N/N 201s
  local n="$1" label="$2"
  make seed-day N="$n" 2>&1 | tee "$SCRATCH/seed-$label.txt" || fail "$label: make seed-day N=$n failed"
  grep -q "posted=$n ok201=$n non201=0" "$SCRATCH/seed-$label.txt" \
    || fail "$label: seed summary did not show posted=$n ok201=$n non201=0 — see $SCRATCH/seed-$label.txt"
  grep -q "^SEED-DAY OK" "$SCRATCH/seed-$label.txt" || fail "$label: seed-day did not report OK"
  echo "$label: seeded $n/$n orders (201) through the live ESB"
}

assert_recon_report() { # $1 = expected orders, $2 = expected lines, $3 = label
  local want_o="$1" want_l="$2" label="$3"
  [ -f "$RECON_REPORT" ] || fail "$label: recon report missing at $RECON_REPORT"
  jq -e --argjson o "$want_o" --argjson l "$want_l" \
      '.orders.source==$o and .orders.gold==$o and .lines.source==$l and .lines.gold==$l and .allMatch==true' \
      "$RECON_REPORT" >/dev/null \
    || fail "$label: recon report did not show orders=$o/$o lines=$l/$l allMatch=true — see $RECON_REPORT"
  echo "$label: recon report orders=$want_o/$want_o lines=$want_l/$want_l allMatch=true"
}

# =========================================================== SCENARIO 1 =====
step "scenario 1: clean-slate pipeline (reset -> boot -> seed 12 -> batch full -> 100% recon)"

make etl-reset 2>&1 | tee "$SCRATCH/reset-s1.txt" || fail "s1: make etl-reset failed"
grep -q "RESET OK" "$SCRATCH/reset-s1.txt" || fail "s1: etl-reset did not reach its OK banner"

make esb-run || fail "s1: make esb-run failed"
make oms-run || fail "s1: make oms-run failed"
make cdc-run || fail "s1: make cdc-run failed"

seed_and_check 12 s1

# every 201 order -> one oms_order insert + one oms_order_line insert = 2 captures
await_quiescence 24 s1
assert_oms_rows 12 12 "s1 after quiescence"

make cdc-stop || fail "s1: make cdc-stop failed"

make batch-run ARGS="full --business-date $TODAY" 2>&1 | tee "$SCRATCH/batch-s1.txt" \
  || fail "s1: make batch-run full exited non-zero — see $SCRATCH/batch-s1.txt"
grep -q "BATCH-DONE" "$SCRATCH/batch-s1.txt" || fail "s1: batch full produced no BATCH-DONE line"
assert_recon_report 12 12 s1

echo "PASS 1-clean-slate-pipeline"

# =========================================================== SCENARIO 2 =====
step "scenario 2: replay safety (OMS re-consumes the same envelopes -> no-op, no duplicates)"

# state carried over from scenario 1 (12/12 in OMS) — do NOT reset here.
# A consumer-group offset reset requires the group to be INACTIVE -> stop OMS first.
make oms-stop || fail "s2: make oms-stop failed"

reset_ok=""
for _ in $(seq 1 12); do
  if docker exec sim-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
       --bootstrap-server "$KAFKA_BOOTSTRAP" --group "$OMS_GROUP" \
       --topic "$ORDERS_TOPIC" --reset-offsets --to-earliest --execute \
       > "$SCRATCH/s2-reset.txt" 2>&1; then reset_ok=yes; break; fi
  sleep 5   # group can take a few seconds to become Empty after the kill
done
[ -n "$reset_ok" ] || { cat "$SCRATCH/s2-reset.txt"; fail "s2: consumer-group offset reset failed (group active?)"; }
grep -q "NEW-OFFSET" "$SCRATCH/s2-reset.txt" \
  || fail "s2: offset reset produced no NEW-OFFSET lines — see $SCRATCH/s2-reset.txt"

make oms-run || fail "s2: make oms-run failed (restart after offset reset)"

# oms-run truncated the OMS log: everything it now ingests is the replay.
await_replay_skips 12 s2

stored_after="$(log_count OMS-ORDER-STORED "$OMS_LOG")"
[ "$stored_after" = "0" ] \
  || fail "s2: replay inserted NEW rows ($stored_after OMS-ORDER-STORED lines) — replay guard broken"
assert_oms_rows 12 12 "s2 after replay"
echo "s2 evidence: $(grep -m2 REPLAY-SKIP "$OMS_LOG" | head -2)"

echo "PASS 2-replay-safety"

# =========================================================== SCENARIO 3 =====
step "scenario 3: CDC kill mid-stream (chaos-lite) — catch-up on restart, no loss, no dup"

make etl-reset 2>&1 | tee "$SCRATCH/reset-s3.txt" || fail "s3: make etl-reset failed"
grep -q "RESET OK" "$SCRATCH/reset-s3.txt" || fail "s3: etl-reset did not reach its OK banner"

make esb-run || fail "s3: make esb-run failed"
make oms-run || fail "s3: make oms-run failed"
make cdc-run || fail "s3: make cdc-run failed"

seed_and_check 6 s3a
await_quiescence 12 s3a
make cdc-stop || fail "s3: make cdc-stop failed (first stop)"
await_engine_dead s3-first-stop

bronze_keys > "$SCRATCH/s3-bronze-before.txt"
echo "s3: bronze inventory while CDC is DOWN: $(wc -l < "$SCRATCH/s3-bronze-before.txt") object(s)"

# seed 6 MORE orders while CDC is DOWN: they reach the OMS but are NOT captured
seed_and_check 6 s3b
await_oms_orders 12 s3b
assert_oms_rows 12 12 "s3 with CDC down"
caps_down="$(log_count CDC-CAPTURE "$CDC_ENGINE_LOG")"
[ "$caps_down" = "12" ] || fail "s3: CDC was supposed to be DOWN but engine log moved ($caps_down captures)"

# fresh engine: offset file was kept by cdc-stop -> engine resumes from its
# saved binlog position and MUST capture the 6 missed inserts
make cdc-run || fail "s3: make cdc-run failed (restart)"
await_quiescence 12 s3-restart

catchup_order="$(grep -c "CDC-CAPTURE op=c table=oms_order " "$CDC_ENGINE_LOG" 2>/dev/null)"; catchup_order="${catchup_order:-0}"
catchup_line="$(grep -c "CDC-CAPTURE op=c table=oms_order_line" "$CDC_ENGINE_LOG" 2>/dev/null)"; catchup_line="${catchup_line:-0}"
[ "$catchup_order" -ge 6 ] || fail "s3: expected >=6 catch-up CDC-CAPTURE for oms_order after restart, got $catchup_order"
[ "$catchup_line" -ge 6 ] || fail "s3: expected >=6 catch-up CDC-CAPTURE for oms_order_line after restart, got $catchup_line"
echo "s3: catch-up captures after restart: oms_order=$catchup_order oms_order_line=$catchup_line"

# wait until the bronze writer has landed everything (records per table == 12)
waited=0
while :; do
  rec_o="$(bronze_table_records oms_order)"
  rec_l="$(bronze_table_records oms_order_line)"
  if [ "$rec_o" -ge 12 ] && [ "$rec_l" -ge 12 ]; then break; fi
  sleep 5; waited=$((waited + 5))
  if [ "$waited" -ge 120 ]; then
    fail "s3: bronze landing wait timeout: oms_order records=$rec_o oms_order_line records=$rec_l"
  fi
done

rec_o="$(bronze_table_records oms_order)"
rec_l="$(bronze_table_records oms_order_line)"
[ "$rec_o" = "12" ] || fail "s3: bronze oms_order record count expected 12 (no loss/dup), got $rec_o"
[ "$rec_l" = "12" ] || fail "s3: bronze oms_order_line record count expected 12 (no loss/dup), got $rec_l"
echo "s3: bronze record counts: oms_order=$rec_o oms_order_line=$rec_l (== OMS rows, no dup)"

# NO duplicate topic-partition-offset, parsed from the BRONZE-LAND object names
bronze_keys > "$SCRATCH/s3-bronze-after.txt"
[ "$(wc -l < "$SCRATCH/s3-bronze-after.txt")" -gt 0 ] || fail "s3: no bronze objects landed at all"
bad_keys=$(grep -cvE '^local/silkroute-sg-bronze/region=[A-Z][A-Z]/table=oms_order(_line)?/dt=[0-9]{8}/silkroute\.cdc\.oms-0-[0-9]+-[0-9a-f-]{36}\.jsonl$' "$SCRATCH/s3-bronze-after.txt" 2>/dev/null); bad_keys="${bad_keys:-0}"
[ "$bad_keys" = "0" ] || fail "s3: $bad_keys bronze object name(s) do not match the region/table/dt/topic-partition-offset-uuid contract"
grep -oE 'silkroute\.cdc\.oms-0-[0-9]+-' "$SCRATCH/s3-bronze-after.txt" | sed -E 's/silkroute\.cdc\.oms-0-([0-9]+)-/\1/' > "$SCRATCH/s3-offsets.txt"
dup_offsets=$(sort -n "$SCRATCH/s3-offsets.txt" | uniq -d | wc -l)
[ "$dup_offsets" = "0" ] || { sort -n "$SCRATCH/s3-offsets.txt" | uniq -d; fail "s3: duplicate topic offsets across bronze objects (a landing was replayed)"; }
echo "s3: $(wc -l < "$SCRATCH/s3-offsets.txt") bronze objects, all topic-partition-offsets unique"

make batch-run ARGS="full --business-date $TODAY" 2>&1 | tee "$SCRATCH/batch-s3.txt" \
  || fail "s3: make batch-run full after chaos exited non-zero — see $SCRATCH/batch-s3.txt"
assert_recon_report 12 12 s3

echo "PASS 3-cdc-kill-mid-stream"

# =========================================================== SCENARIO 4 =====
step "scenario 4: freshness is MEASURED, not a constant (>=2 CDC-METRIC lines, >=2 distinct values, exact contract shape)"

# engine still running from scenario 3; heartbeats fire every 30s
waited=0
while :; do
  metric_lines="$(log_count CDC-METRIC "$CDC_ENGINE_LOG")"
  distinct="$(grep 'CDC-METRIC' "$CDC_ENGINE_LOG" 2>/dev/null | grep -oE '"value":[0-9]+(\.[0-9]+)?' | sort -u | wc -l)"
  if [ "$metric_lines" -ge 2 ] && [ "$distinct" -ge 2 ]; then break; fi
  sleep 5; waited=$((waited + 5))
  if [ "$waited" -ge 150 ]; then
    fail "s4: waited 150s but freshness did not produce >=2 CDC-METRIC lines with >=2 distinct values (lines=$metric_lines distinct=$distinct)"
  fi
done

grep 'CDC-METRIC' "$CDC_ENGINE_LOG" > "$SCRATCH/s4-metrics.txt"
[ "$(wc -l < "$SCRATCH/s4-metrics.txt")" -ge 2 ] || fail "s4: fewer than 2 CDC-METRIC lines"
# EXACT wire contract, after stripping the log prefix (the JSON object must be the whole remainder)
while IFS= read -r line; do
  json="$(printf '%s' "$line" | sed -nE 's/.*CDC-METRIC[[:space:]]+trigger=[a-z]+[[:space:]]+(.*)$/\1/p')"
  [ -n "$json" ] || fail "s4: cannot extract JSON object from CDC-METRIC line: $line"
  printf '%s' "$json" | grep -qE '^\{"metric":"cdc_freshness_seconds","value":[0-9]+(\.[0-9]+)?,"pipeline":"cdc"\}$' \
    || fail "s4: CDC-METRIC payload violates the contract regex: $json"
done < "$SCRATCH/s4-metrics.txt"
echo "s4: CDC-METRIC lines ($(wc -l < "$SCRATCH/s4-metrics.txt") total), all match the exact contract shape"
echo "s4: distinct freshness values: $(grep -oE '"value":[0-9]+(\.[0-9]+)?' "$SCRATCH/s4-metrics.txt" | sort -u | tr '\n' ' ')"
[ "$(grep -oE '"value":[0-9]+(\.[0-9]+)?' "$SCRATCH/s4-metrics.txt" | sort -u | wc -l)" -ge 2 ] \
  || fail "s4: freshness values are all identical — looks like a constant, not a measurement"

echo "PASS 4-freshness-metric-contract"

# =========================================================== SCENARIO 5 =====
step "scenario 5: DQ selftest + recon falsifiability (negative control flips recon red, then green)"

make batch-run ARGS=selftest 2>&1 | tee "$SCRATCH/selftest.txt" \
  || fail "s5: make batch-run selftest exited non-zero — see $SCRATCH/selftest.txt"
grep -q "DQ-SELFTEST-OK" "$SCRATCH/selftest.txt" \
  || fail "s5: DQ-SELFTEST-OK not printed — DQ rules did not all catch their fixture violations"

# --- negative control: inject a phantom SOURCE row into oms_order.
# Same instrument as evidence/runs/E-030 (its delta there: CAD +9999 minor);
# E-030 does not carry the literal INSERT, so it is reconstructed here against
# the oms_order DDL (apps/modern-oms SchemaInitializer). CDC is running, so the
# phantom is captured like any row — recon-only must go red (exit 2).
NEG_ID="s9-negctl-$(date +%s)"
NEG_TS="$(date -u '+%Y-%m-%d %H:%M:%S.000')"
docker exec sim-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  INSERT INTO silkroute_oms.oms_order
    (order_id, external_order_ref, source_system, store_id, region, customer_ref,
     channel, status, total_amount_minor, currency, correlation_id, ingested_at)
  VALUES
    ('$NEG_ID', 'WEB-$NEG_ID', 'NEG-CONTROL', 'ST-CA-01', 'CA', 'msk-s9negctl',
     'WEB_STORE', 'CONFIRMED', 9999, 'CAD', 's9-negctl', '$NEG_TS');" 2>/dev/null \
  || fail "s5: phantom-row INSERT failed"
ph="$(mysql_scalar "SELECT COUNT(*) FROM silkroute_oms.oms_order WHERE order_id='$NEG_ID'")"
[ "$ph" = "1" ] || fail "s5: phantom row not present after INSERT (count=$ph)"

rc=0
make batch-run ARGS="recon-only --business-date $TODAY" > "$SCRATCH/recon-nc.txt" 2>&1 || rc=$?
[ "$rc" = "2" ] || { tail -5 "$SCRATCH/recon-nc.txt"; fail "s5: recon-only with phantom row expected exit 2, got $rc — recon is NOT falsifiable"; }
jq -e '.allMatch==false' "$RECON_REPORT" >/dev/null || fail "s5: recon report after phantom row is not allMatch=false"
echo "s5: negative control — phantom row made recon-only exit 2 (allMatch=false)"

docker exec sim-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
  "DELETE FROM silkroute_oms.oms_order WHERE order_id='$NEG_ID';" 2>/dev/null \
  || fail "s5: phantom-row DELETE failed"
after="$(mysql_scalar "SELECT COUNT(*) FROM silkroute_oms.oms_order WHERE order_id='$NEG_ID'")"
[ "$after" = "0" ] || fail "s5: phantom row still present after DELETE"
assert_oms_rows 12 12 "s5 after delete"

rc=0
make batch-run ARGS="recon-only --business-date $TODAY" > "$SCRATCH/recon-restore.txt" 2>&1 || rc=$?
[ "$rc" = "0" ] || { tail -5 "$SCRATCH/recon-restore.txt"; fail "s5: recon-only after cleanup expected exit 0, got $rc"; }
assert_recon_report 12 12 s5

echo "PASS 5-dq-selftest-and-recon-falsifiability"

# =========================================================== SCENARIO 6 =====
step "scenario 6: C1 spot-check — CN customerRefs in bronze are msk-*, never plaintext"

# from scenario 3's run (12 seeded orders include 3 CN orders)
cn_objects="$(bronze_keys | grep -c '^local/silkroute-sg-bronze/region=CN/' 2>/dev/null)"; cn_objects="${cn_objects:-0}"
[ "$cn_objects" -ge 1 ] || fail "s6: no region=CN bronze objects found — nothing to spot-check"
echo "s6: region=CN bronze objects: $cn_objects"

plain_total=0
while IFS= read -r key; do
  [ -n "$key" ] || continue
  n=$(docker exec sim-minio mc cat "$key" 2>/dev/null | grep -c 'cust-cn-')
  n="${n:-0}"
  plain_total=$((plain_total + n))
done < <(bronze_keys)
[ "$plain_total" = "0" ] \
  || fail "s6: C1 VIOLATION — $plain_total plaintext 'cust-cn-' occurrence(s) in bronze"

msk_total=0
while IFS= read -r key; do
  [ -n "$key" ] || continue
  n=$(docker exec sim-minio mc cat "$key" 2>/dev/null | grep -c '"customer_ref":"msk-')
  n="${n:-0}"
  msk_total=$((msk_total + n))
done < <(bronze_keys | grep '^local/silkroute-sg-bronze/region=CN/')
[ "$msk_total" -ge 1 ] \
  || fail "s6: no '\"customer_ref\":\"msk-\" found in region=CN bronze objects — masking not observable"
echo "s6: C1 OK — plaintext cust-cn- occurrences across ALL bronze: 0; '\"customer_ref\":\"msk-\"' in region=CN objects: $msk_total"

echo "PASS 6-c1-cn-masking-bronze"

# ================================================================ WRAP-UP ===
make etl-reset 2>&1 | tee "$SCRATCH/reset-final.txt" || fail "final: make etl-reset failed"
grep -q "RESET OK" "$SCRATCH/reset-final.txt" || fail "final: etl-reset did not reach its OK banner"

echo
echo "SUITE-OK all 6 etl-int scenarios passed in ${SECONDS}s; transcript: $TRANSCRIPT"
exit 0
