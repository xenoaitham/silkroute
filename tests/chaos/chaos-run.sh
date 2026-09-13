#!/usr/bin/env bash
# SILKROUTE Phase 6 (S7) — steady-state chaos harness for the ESB order-mediation path.
#
# Falsifiable by construction: a probe-loop instrument records a JSONL transcript
# of the world DURING the fault, and `analyze` FAILS (exit 2) on transcripts that
# show unbounded behavior — hangs, network-level failures outside a declared
# fault window, or a replay that commits a NEW order (idempotency breach).
# An analyzer that cannot fail is not an assertion (see --selftest).
#
# ALL toxiproxy manipulation goes through tests/chaos/esb-faults.sh (the stable
# Phase-2 wrapper used by CI) — this script never hand-rolls toxic HTTP.
# ERP/ESB lifecycle ONLY via the recorded PID files (/tmp/silkroute-esb-*.pid),
# never pkill. Sim stack services via docker compose, nothing else on this
# shared host is ever touched (busforge/helios run here too).
#
# Usage:
#   tests/chaos/chaos-run.sh precheck
#       Verify every dependency with a specific message on the first missing one:
#       k6 + jq, toxiproxy API (18474 /version), proxy "erp" (via esb-faults.sh
#       ensure), both jars, ERP health 18080, ESB health 18082, kafka 39092, redis.
#
#   tests/chaos/chaos-run.sh probe-loop <seconds> <interval_s> <outfile>
#       Measurement instrument (foreground). Every interval: one POST /api/v1/orders
#       probe (unique ref ESB-CHAOS-<ts>-<n>, qty 1, rotating over the 150 SG
#       sku/store combos, curl --max-time 10s) + every 5th probe is a REPLAY of
#       probe #1 (same externalOrderRef + same Idempotency-Key) to continuously
#       verify idempotency semantics. One JSON line per probe; curl failures are
#       recorded as {"status":0,"error":"curl:<exit>"} — errors are never discarded.
#
#   tests/chaos/chaos-run.sh analyze <outfile> [replay_outfile]
#           [--status0-window FROM TO] [--affected-window FROM TO]
#       Post-run analysis; EXITS 2 on any of:
#         - a probe with time_ms > 30000 ms (HANG_MS) — unbounded hang. NOTE: with
#           the default 10 s probe curl cap a hang surfaces as status 0 (below);
#           this rule bites when the cap is raised or for foreign transcripts.
#         - a probe with status 0 (curl-level failure: timeout/refused/reset)
#           OUTSIDE the declared --status0-window (deliberate kill-window status 0s
#           are counted, reported, and allowed; unexplained ones are violations)
#         - a REPLAY that returned 201 after its original probe returned 201
#           (a duplicate commit — replay must be 409 DUPLICATE carrying the
#           original orderId, or 422 ORD-DUP-REF when the Redis layer was down),
#           or a replay whose 409 carries a DIFFERENT orderId than the original,
#           or a replay with no original in the transcript.
#       A replay-201 after a FAILED original (status 0/4xx/5xx) is NOT a duplicate
#       commit — failed sagas release their idempotency claim by design (E-009),
#       so re-execution is legitimate; it is counted and reported, not failed.
#       Prints status histogram, error-code histogram, max/median durations,
#       and (with --affected-window FROM TO) per-window status splits plus the
#       recovery delta = TO (heal) -> first 201 probe after it.
#
#   tests/chaos/chaos-run.sh latency-under-load [RPS] [DURATION_s] [MS]   (10 120 800)
#       H1: sustained k6 load (tests/load/k6-orders.js) + probe-loop; after 15 s
#       steady state, inject MS ms latency on the ERP path via esb-faults.sh
#       latency, hold DURATION_s, remove the toxic, keep probing 30 s (recovery),
#       then analyze + per-window status split + heal->first-201 recovery.
#
#   tests/chaos/chaos-run.sh kill-erp [RPS] [DURATION_s]                  (10 120)
#       H2: sustained k6 load + probe-loop; after 20 s, record DLQ count, kill ERP
#       BY PID FILE (verified dead via kill -0), 20 s failure window (probes must
#       show fast explicit failures — CIRCUIT-OPEN / 5xx / status 0 — not hangs),
#       restart ERP exactly like make esb-run does, wait /actuator/health UP
#       (120 s max), probe until first 201 (60 s max), record DLQ delta, analyze.
#
#   tests/chaos/chaos-run.sh stop-redis [RPS] [DURATION_s]                (10 120)
#       H3: sustained k6 load + probe-loop; after 20 s, docker compose stop redis,
#       40 s outage, docker compose start redis + wait PONG (60 s), probe 20 s,
#       analyze. If probes HANG (unbounded Redis client timeout) analyze exits 2 —
#       that is a MEASURED FINDING about the ESB, recorded in the runbook.
#
#   tests/chaos/chaos-run.sh stop-all     # make esb-stop (PID files only); sim stack untouched
#   tests/chaos/chaos-run.sh --selftest   # negative control: analyze must FAIL a dirty
#                                         # synthetic transcript (exit 2) and PASS a clean one
#   tests/chaos/chaos-run.sh help
#
# Exit codes (consistent everywhere):
#   0 = success · 1 = usage error / missing dependency · 2 = analyze found
#   violations (a measured finding, never silent) · 3 = ERP/ESB not booted
#   ("run: make esb-run").
#
# Env overrides: TOXIPROXY_API / TOXIPROXY_PROXY (same defaults as esb-faults.sh),
# K6_BIN, ESB_BASEURL, ERP_PID_FILE, ESB_PID_FILE, KAFKA_CONTAINER, DLQ_TOPIC,
# REDIS_CONTAINER, PROBE_MAXTIME (10), HANG_MS (30000).
#
# HONESTY NOTE (applies to every recipe): sim mode — "pod kill" here means
# killing the ERP java process (its container/app-process stand-in); the ERP
# keeps orders in an IN-MEMORY ledger, so a restart re-seeds fresh state and
# idempotency across a restart is carried by the ESB's Redis done-keys, not the
# ERP ledger. Everything is loopback on a shared laptop (other stacks run
# concurrently) — absolute numbers include that contention.
set -uo pipefail

SELF="${BASH_SOURCE[0]}"
ROOT="$(cd "$(dirname "$SELF")/../.." && pwd)"   # script lives at tests/chaos/ → repo root is two levels up
FAULTS="$ROOT/tests/chaos/esb-faults.sh"

K6_BIN="${K6_BIN:-$HOME/tools/k6/k6}"
API="${TOXIPROXY_API:-http://127.0.0.1:18474}"
PROXY="${TOXIPROXY_PROXY:-erp}"
ESB_BASE="${ESB_BASEURL:-http://127.0.0.1:18081}"
ERP_HEALTH="${ERP_HEALTH:-http://127.0.0.1:18080/actuator/health}"
ESB_HEALTH="${ESB_HEALTH:-http://127.0.0.1:18082/actuator/health}"
ERP_JAR="$ROOT/apps/legacy-erp/target/legacy-erp-1.0.0-SNAPSHOT.jar"
ESB_JAR="$ROOT/apps/esb/target/esb-1.0.0-SNAPSHOT.jar"
ERP_PID_FILE="${ERP_PID_FILE:-/tmp/silkroute-esb-erp.pid}"
ESB_PID_FILE="${ESB_PID_FILE:-/tmp/silkroute-esb-app.pid}"
K6_PID_FILE="${K6_PID_FILE:-/tmp/silkroute-chaos-k6.pid}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-sim-kafka}"
DLQ_TOPIC="${KAFKA_DLQ_TOPIC:-silkroute.esb.dlq}"
REDIS_CONTAINER="${REDIS_CONTAINER:-sim-redis}"
TRANS_DIR="$ROOT/tests/chaos/transcripts"
RESULTS_DIR="$ROOT/tests/load/results"
PROBE_MAXTIME="${PROBE_MAXTIME:-10}"
HANG_MS="${HANG_MS:-30000}"

CHAOS_ACTIVE=0
K6_PID=""

command -v jq >/dev/null || { echo "ERROR: jq is required"; exit 1; }
command -v curl >/dev/null || { echo "ERROR: curl is required"; exit 1; }

# ---------------------------------------------------------------- helpers ---

now_iso() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }

epoch_of_ts() {
  date -u -d "$1" +%s 2>/dev/null || date -u -d "${1%%.*}Z" +%s 2>/dev/null || echo 0
}

is_int() { case "${1:-}" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }

usage() { # usage <exit_code> — prints the Usage..Env-overrides block of this header
  awk '/^# Usage:/{f=1} /^# HONESTY NOTE/{f=0} f' "$SELF" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

# ------------------------------------------------------- probe instrument ---

probe_loop() { # <seconds> <interval_s> <outfile>
  local secs="$1" interval="$2" out="$3"
  local end_time=$(( $(date +%s) + secs ))
  local n=0 first_ref="" first_idx=0
  local ts kind idx ref key sku store payload body meta code tsec ms rc ec oid status err
  : > "$out"
  body=$(mktemp /tmp/silkroute-chaos-probe.XXXXXX) || return 1
  # NOTE: deliberately NO trap ... EXIT here — probe_loop may run in the
  # FOREGROUND (probe-loop subcommand), where an EXIT trap would clobber the
  # script-level on_exit safety net and see out-of-scope locals. The tmp file is
  # removed on the normal path and in the TERM/INT handler (body always in scope).
  trap 'rm -f "$body" 2>/dev/null; exit 0' TERM INT
  while [ "$(date +%s)" -lt "$end_time" ]; do
    n=$((n+1))
    ts=$(now_iso)
    if [ $(( n % 5 )) -eq 0 ] && [ -n "$first_ref" ]; then
      kind="replay"; idx="$first_idx"; ref="$first_ref"; key="$first_ref"
    else
      kind="probe"
      idx=$(( (n - 1) % 150 ))
      ref="ESB-CHAOS-$(date -u +%Y%m%dT%H%M%S)-${n}-$$"
      key="$ref"
      if [ -z "$first_ref" ]; then first_ref="$ref"; first_idx="$idx"; fi
    fi
    sku=$(printf 'SKU-%04d' $(( idx % 50 + 1 )))
    store=$(printf 'ST-SG-%02d' $(( idx / 50 + 1 )))
    payload=$(printf '{"externalOrderRef":"%s","sourceSystem":"ESB-CHAOS-HARNESS","storeId":"%s","channel":"WEB_STORE","customerRef":"cust-chaos","lines":[{"skuId":"%s","quantity":1}],"audit":{"sourceSystem":"ESB-CHAOS-HARNESS","receivedAt":"%s","correlationId":"%s"}}' "$ref" "$store" "$sku" "$ts" "$ref")
    meta=$(curl -s -o "$body" -w '%{http_code} %{time_total}' --max-time "$PROBE_MAXTIME" \
      -X POST "$ESB_BASE/api/v1/orders" \
      -H 'Content-Type: application/json' -H "Idempotency-Key: $key" \
      --data "$payload" 2>/dev/null)
    rc=$?
    code="${meta%% *}"; tsec="${meta#* }"
    status=0; err=""
    if [ "$rc" -eq 0 ] && [[ "$code" =~ ^[1-5][0-9][0-9]$ ]]; then
      status=$(( 10#$code ))
    else
      err="curl:$rc"                       # NEVER discard errors: record them
      if [ -n "$code" ] && [ "$code" != "000" ]; then err="curl:$rc(http_code:$code)"; fi
      tsec="${tsec:-0}"
    fi
    ms=$(awk -v t="$tsec" 'BEGIN{ printf "%.1f", t*1000 }' 2>/dev/null)
    [ -n "$ms" ] || ms="0"
    ec=$(jq -r '.error.code // empty' "$body" 2>/dev/null || true)
    oid=$(jq -r '.orderId // .error.orderId // empty' "$body" 2>/dev/null || true)
    jq -n -c \
      --arg ts "$ts" --argjson n "$n" --arg kind "$kind" --arg ref "$ref" \
      --argjson status "$status" --argjson ms "$ms" \
      --arg ec "$ec" --arg oid "$oid" --arg err "$err" \
      '{ts:$ts, n:$n, kind:$kind, ref:$ref, status:$status, time_ms:$ms,
        error_code:(if $ec=="" then null else $ec end),
        order_id:(if $oid=="" then null else $oid end)}
        + (if $err=="" then {} else {error:$err} end)' >> "$out" \
      || printf '{"ts":"%s","n":%s,"kind":"%s","ref":"%s","status":0,"time_ms":%s,"error":"record-serialization-failed"}\n' "$ts" "$n" "$kind" "$ref" "$ms" >> "$out"
    sleep "$interval"
  done
  rm -f "$body"
  return 0
}

probe_loop_cmd() { # foreground subcommand form
  local secs="${1:-}" interval="${2:-}" out="${3:-}"
  if [ -z "$secs" ] || [ -z "$interval" ] || [ -z "$out" ]; then
    echo "usage: $SELF probe-loop <seconds> <interval_s> <outfile>"; return 1
  fi
  is_int "$secs" && is_int "$interval" || { echo "ERROR: seconds and interval must be integers"; return 1; }
  is_int "$PROBE_MAXTIME" || { echo "ERROR: PROBE_MAXTIME must be an integer (got '$PROBE_MAXTIME')"; return 1; }
  echo "[probe-loop] ${secs}s @ ${interval}s interval -> $out (every 5th probe = replay of probe #1; curl --max-time ${PROBE_MAXTIME}s)"
  probe_loop "$secs" "$interval" "$out"
  echo "[probe-loop] done: $(wc -l < "$out" 2>/dev/null || echo 0) records -> $out"
}

stop_probe_loop() { # <pid>
  local pid="$1"
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    echo "[chaos-run] probe-loop stopped (pid $pid)"
  else
    wait "$pid" 2>/dev/null || true
    echo "[chaos-run] probe-loop finished"
  fi
}

# ---------------------------------------------------------------- precheck ---

precheck() {
  [ -x "$K6_BIN" ] || { echo "ERROR: k6 binary not found/executable at $K6_BIN (override with K6_BIN=...)"; exit 1; }
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$API/version" || true)
  [ "$code" = "200" ] || { echo "ERROR: toxiproxy API not reachable at $API (HTTP $code) — run: make up"; exit 1; }
  bash "$FAULTS" ensure || exit 1                       # proxy "erp" must exist
  [ -f "$ERP_JAR" ] || { echo "ERROR: missing $ERP_JAR — run: ./mvnw -B -f apps/legacy-erp/pom.xml package -DskipTests"; exit 1; }
  [ -f "$ESB_JAR" ] || { echo "ERROR: missing $ESB_JAR — run: ./mvnw -B -f apps/esb/pom.xml package -DskipTests"; exit 1; }
  if ! curl -sf --max-time 5 "$ERP_HEALTH" 2>/dev/null | grep -q UP; then
    echo "ERROR: ERP not healthy on 127.0.0.1:18080"
    echo "run: make esb-run"
    exit 3
  fi
  if ! curl -sf --max-time 5 "$ESB_HEALTH" 2>/dev/null | grep -q UP; then
    echo "ERROR: ESB not healthy on 127.0.0.1:18082"
    echo "run: make esb-run"
    exit 3
  fi
  timeout 3 bash -c '</dev/tcp/127.0.0.1/39092' 2>/dev/null \
    || { echo "ERROR: kafka not reachable on 127.0.0.1:39092 — run: make up"; exit 1; }
  [ "$(docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null || true)" = "PONG" ] \
    || { echo "ERROR: redis ($REDIS_CONTAINER) did not answer PING→PONG — run: make up"; exit 1; }
  is_int "$PROBE_MAXTIME" || { echo "ERROR: PROBE_MAXTIME must be an integer (got '$PROBE_MAXTIME')"; exit 1; }
  is_int "$HANG_MS" || { echo "ERROR: HANG_MS must be an integer (got '$HANG_MS')"; exit 1; }
  echo "[chaos-run] precheck OK: k6, toxiproxy($API, proxy $PROXY), jars, ERP 18080, ESB 18081/18082, kafka 39092, redis PONG"
}

# ----------------------------------------------------------------- analyze ---

analyze() ( # subshell: self-contained exit codes + tmpdir trap
  local file="" replay_file="" s0_from="" s0_to="" aff_from="" aff_to=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --replay)          replay_file="${2:-}"; shift 2 ;;
      --status0-window)  s0_from="${2:-}"; s0_to="${3:-}"; shift 3 ;;
      --affected-window) aff_from="${2:-}"; aff_to="${3:-}"; shift 3 ;;
      -h|--help) echo "usage: $SELF analyze <outfile> [replay_outfile] [--status0-window FROM TO] [--affected-window FROM TO]"; return 0 ;;
      --) shift ;;
      *) if [ -z "$file" ]; then file="$1"
         elif [ -z "$replay_file" ]; then replay_file="$1"
         else echo "ERROR: unexpected analyze argument: $1"; return 1; fi
         shift ;;
    esac
  done
  [ -n "$file" ] || { echo "usage: $SELF analyze <outfile> [replay_outfile] [--status0-window FROM TO] [--affected-window FROM TO]"; return 1; }
  [ -f "$file" ] || { echo "ERROR: transcript not found: $file"; return 1; }
  [ -s "$file" ] || { echo "ERROR: transcript is empty: $file"; return 1; }
  if [ -n "$replay_file" ] && [ ! -f "$replay_file" ]; then
    echo "ERROR: replay transcript not found: $replay_file"; return 1
  fi
  is_int "$HANG_MS" || { echo "ERROR: HANG_MS must be an integer (got '$HANG_MS')"; return 1; }

  local tmp; tmp=$(mktemp -d) || return 1
  trap 'rm -rf "$tmp"' EXIT

  local V=0
  violation() { echo "VIOLATION: $*"; V=$((V+1)); }

  local files=("$file")
  [ -n "$replay_file" ] && files+=("$replay_file")

  echo "=== analyze: $file${replay_file:+ (+ replay $replay_file)} ==="

  # transcript integrity: every line must parse as JSON
  local f total parsed mal=0
  for f in "${files[@]}"; do
    total=$(wc -l < "$f"); parsed=$(jq -c '.' "$f" 2>/dev/null | wc -l)
    if [ "$parsed" -ne "$total" ]; then
      violation "$(( total - parsed )) malformed/unparseable line(s) in $f (truncated or corrupt transcript)"
      mal=1
    fi
  done

  # (1) hangs: any probe over HANG_MS
  #     NOTE: with the default 10 s probe curl cap, a real hang surfaces as a
  #     status-0 record (~10000 ms) caught by rule (2); this rule bites when the
  #     cap is raised (PROBE_MAXTIME) or for foreign/synthetic transcripts.
  while IFS= read -r hang; do
    [ -n "$hang" ] || continue
    violation "HANG: request took $(jq -r '.time_ms' <<<"$hang")ms > ${HANG_MS}ms ($(jq -r '.ts + " " + .kind + " " + .ref' <<<"$hang"))"
  done < <(jq -c --argjson h "$HANG_MS" 'select(.time_ms > $h)' "${files[@]}" 2>/dev/null)

  # (2) network-level failures (status 0): allowed ONLY inside a declared window
  local s0_total s0_allowed=0 s0_out
  s0_total=$(jq -c 'select(.status==0)' "${files[@]}" 2>/dev/null | wc -l)
  if [ "$s0_total" -gt 0 ] && [ -n "$s0_from" ] && [ -n "$s0_to" ]; then
    s0_allowed=$(jq -c --arg f "$s0_from" --arg t "$s0_to" 'select(.status==0 and .ts >= $f and .ts <= $t)' "${files[@]}" 2>/dev/null | wc -l)
  fi
  s0_out=$(( s0_total - s0_allowed ))
  echo "network-level failures (status 0): total=$s0_total, allowed-in-declared-fault-window=$s0_allowed"
  if [ "$s0_out" -gt 0 ]; then
    while IFS= read -r bad; do
      [ -n "$bad" ] || continue
      violation "status 0 outside any declared fault window ($(jq -r '.ts + " " + (.error // "curl:?") + " " + .kind + " " + .ref' <<<"$bad")) — unbounded network failure"
    done < <(jq -c --arg f "${s0_from:-}" --arg t "${s0_to:-}" \
      'select(.status==0) | select(($f=="" or .ts < $f) or ($t=="" or .ts > $t))' "${files[@]}" 2>/dev/null)
  fi

  # (3) idempotency: every replay must honor the original probe's outcome
  jq -r 'select(.kind=="probe")  | [.ref, (.status|tostring), (.order_id // "-"), .ts] | @tsv' "${files[@]}" 2>/dev/null > "$tmp/orig.tsv"
  jq -r 'select(.kind=="replay") | [.ref, (.status|tostring), (.error_code // "-"), (.order_id // "-"), .ts] | @tsv' "${files[@]}" 2>/dev/null > "$tmp/replay.tsv"
  local n_replay=0 idem_409=0 idem_422=0 idem_reexec=0 idem_other=0
  local r_ref r_status r_ec r_oid r_ts orig o_status o_oid
  while IFS=$'\t' read -r r_ref r_status r_ec r_oid r_ts; do
    [ -n "$r_ref" ] || continue
    n_replay=$((n_replay+1))
    orig=$(awk -F'\t' -v r="$r_ref" '$1==r{print $2 "\t" $3; exit}' "$tmp/orig.tsv")
    if [ -z "$orig" ]; then
      violation "unpaired replay: no original probe with ref $r_ref in transcript (at $r_ts) — idempotency unverifiable"
      continue
    fi
    o_status="${orig%%$'\t'*}"; o_oid="${orig#*$'\t'}"
    case "$r_status" in
      201)
        if [ "$o_status" = "201" ]; then
          violation "DUPLICATE COMMIT: replay of committed order $r_ref returned 201 with orderId ${r_oid} (original committed orderId $o_oid) — expected 409 DUPLICATE"
        else
          idem_reexec=$((idem_reexec+1))  # failed/ambiguous original -> claim released -> re-execution is by-design (E-009)
        fi
        ;;
      409)
        if [ "$r_ec" = "DUPLICATE" ] && [ "$o_status" = "201" ] && [ "$r_oid" != "-" ] && [ "$o_oid" != "-" ] && [ "$r_oid" != "$o_oid" ]; then
          violation "replay of $r_ref returned 409 DUPLICATE carrying orderId $r_oid but the original committed orderId was $o_oid — evidence of a second commit"
        else
          idem_409=$((idem_409+1))
        fi
        ;;
      422) idem_422=$((idem_422+1)) ;;   # ORD-DUP-REF: ERP-side guard held (e.g. Redis degraded) — acceptable
      *)   idem_other=$((idem_other+1)) ;; # e.g. replay hit CIRCUIT-OPEN during a fault — recorded, not an idempotency breach
    esac
  done < "$tmp/replay.tsv"

  # histograms + duration stats
  local n_all
  n_all=$(cat "${files[@]}" 2>/dev/null | wc -l)
  echo "-- status histogram ($n_all records: probes + replays) --"
  jq -r '.status' "${files[@]}" 2>/dev/null | sort | uniq -c | awk '{printf "  %7s x status %s\n", $1, $2}'
  echo "-- error-code histogram --"
  local echist
  echist=$(jq -r '.error_code // empty' "${files[@]}" 2>/dev/null | sort | uniq -c | awk '{printf "  %7s x %s\n", $1, $2}')
  [ -n "$echist" ] && printf '%s\n' "$echist" || echo "  (no error bodies recorded)"
  dstat() { # <label> <files...> — reads durations from stdin
    sort -n | awk -v lbl="$1" '{a[NR]=$1} END{
      if (NR==0) { printf "  %-28s (no samples)\n", lbl; exit }
      med = (NR%2==1) ? a[(NR+1)/2] : (a[NR/2]+a[NR/2+1])/2
      printf "  %-28s n=%d max=%.1fms median=%.1fms\n", lbl, NR, a[NR], med }'
  }
  echo "-- probe durations --"
  jq -r '.time_ms' "${files[@]}" 2>/dev/null | dstat "all requests"
  jq -r 'select(.status==201) | .time_ms' "${files[@]}" 2>/dev/null | dstat "201-only (C3 view)"
  echo "-- idempotency replay outcomes: n=$n_replay, 409-DUPLICATE(ok)=$idem_409, 422-guard(ok)=$idem_422, re-exec-after-failed-original(by design)=$idem_reexec, other=$idem_other --"

  # (4) affected-window split + recovery
  if [ -n "$aff_from" ] && [ -n "$aff_to" ]; then
    local seg
    for seg in before affected after; do
      local segf
      case "$seg" in
        before)   segf=$(jq -c --arg f "$aff_from" 'select(.ts < $f)'  "$file" 2>/dev/null) ;;
        affected) segf=$(jq -c --arg f "$aff_from" --arg t "$aff_to" 'select(.ts >= $f and .ts <= $t)' "$file" 2>/dev/null) ;;
        after)    segf=$(jq -c --arg t "$aff_to"  'select(.ts > $t)'  "$file" 2>/dev/null) ;;
      esac
      local cnt
      cnt=$(printf '%s' "$segf" | grep -c . || true)
      echo "-- window '$seg' (${cnt} records) status histogram --"
      if [ "$cnt" -gt 0 ]; then
        printf '%s\n' "$segf" | jq -r '.status' | sort | uniq -c | awk '{printf "  %7s x status %s\n", $1, $2}'
      else
        echo "  (empty)"
      fi
    done
    local first201 rec="-"
    first201=$(jq -r --arg t "$aff_to" 'select(.kind=="probe" and .status==201 and .ts > $t) | .ts' "$file" 2>/dev/null | head -n1)
    if [ -n "$first201" ]; then
      rec=$(( $(epoch_of_ts "$first201") - $(epoch_of_ts "$aff_to") ))
      echo "recovery: heal($aff_to) -> first 201 probe at $first201 = ${rec}s"
    else
      echo "recovery: NO 201 probe after heal ($aff_to) in transcript"
    fi
  fi

  if [ "$V" -gt 0 ]; then
    echo "analyze: FAIL — $V violation(s) (see VIOLATION lines above)"
    exit 2
  fi
  echo "analyze: OK — 0 violations"
  exit 0
)

# ---------------------------------------------------------------- selftest ---

selftest() {
  local tmp rc
  tmp=$(mktemp -d) || { echo "ERROR: mktemp failed"; return 1; }
  # Dirty transcript: (a) a 45000 ms probe (hang) and (b) a replay that returns 201
  # with a NEW orderId after its original committed — analyze MUST exit 2.
  cat > "$tmp/dirty.jsonl" <<'EOF'
{"ts":"2026-01-01T00:00:00.000Z","n":1,"kind":"probe","ref":"R-1","status":201,"time_ms":42.0,"error_code":null,"order_id":"ORD-A"}
{"ts":"2026-01-01T00:00:02.000Z","n":2,"kind":"probe","ref":"R-2","status":201,"time_ms":45000.0,"error_code":null,"order_id":"ORD-B"}
{"ts":"2026-01-01T00:00:04.000Z","n":3,"kind":"replay","ref":"R-1","status":201,"time_ms":40.0,"error_code":null,"order_id":"ORD-ZZZ"}
EOF
  # Clean transcript: 201s, a 409-DUPLICATE replay carrying the ORIGINAL orderId,
  # and a 422 ORD-DUP-REF replay (the Redis-degraded path) — analyze MUST exit 0.
  cat > "$tmp/clean.jsonl" <<'EOF'
{"ts":"2026-01-01T00:00:00.000Z","n":1,"kind":"probe","ref":"R-1","status":201,"time_ms":42.0,"error_code":null,"order_id":"ORD-A"}
{"ts":"2026-01-01T00:00:01.000Z","n":2,"kind":"probe","ref":"R-2","status":201,"time_ms":88.5,"error_code":null,"order_id":"ORD-B"}
{"ts":"2026-01-01T00:00:02.000Z","n":3,"kind":"probe","ref":"R-3","status":201,"time_ms":120.1,"error_code":null,"order_id":"ORD-C"}
{"ts":"2026-01-01T00:00:03.000Z","n":4,"kind":"replay","ref":"R-1","status":409,"time_ms":5.0,"error_code":"DUPLICATE","order_id":"ORD-A"}
{"ts":"2026-01-01T00:00:04.000Z","n":5,"kind":"replay","ref":"R-2","status":422,"time_ms":8.0,"error_code":"ORD-DUP-REF","order_id":null}
EOF
  echo "selftest: no services required — pure analyzer negative control"
  bash "$SELF" analyze "$tmp/dirty.jsonl" >/dev/null 2>&1; rc=$?
  if [ "$rc" -eq 2 ]; then
    echo "ok   [selftest] dirty transcript (45000ms hang + replay-201-new-orderId) -> analyze exited 2 (violation caught)"
  else
    echo "FAIL [selftest] dirty transcript -> analyze exited $rc, REQUIRED 2 (analyzer is blind — not an assertion)"
    rm -rf "$tmp"; return 1
  fi
  bash "$SELF" analyze "$tmp/clean.jsonl" >/dev/null 2>&1; rc=$?
  if [ "$rc" -eq 0 ]; then
    echo "ok   [selftest] clean transcript (409-with-original-orderId + 422-ORD-DUP-REF replays) -> analyze exited 0"
  else
    echo "FAIL [selftest] clean transcript -> analyze exited $rc, REQUIRED 0 (analyzer has false positives)"
    rm -rf "$tmp"; return 1
  fi
  rm -rf "$tmp"
  echo "selftest OK: the analyzer is falsifiable (fails the dirty world, passes the clean one)"
  return 0
}

# ------------------------------------------------------- recipe machinery ---

start_k6() { # <rps> <duration_seconds> <summary_rel_to_root> <log_abs>
  local rps="$1" durs="$2" summary="$3" log="$4"
  [ -x "$K6_BIN" ] || { echo "ERROR: k6 binary not found/executable at $K6_BIN"; return 1; }
  mkdir -p "$RESULTS_DIR"
  (
    cd "$ROOT" || exit 1
    RPS="$rps" DURATION="${durs}s" nohup "$K6_BIN" run tests/load/k6-orders.js \
      --summary-export="$summary" > "$log" 2>&1 &
    echo $! > "$K6_PID_FILE"
  )
  K6_PID=$(cat "$K6_PID_FILE" 2>/dev/null || true)
  echo "[recipe] k6 background load started pid=${K6_PID:-?} (tests/load/k6-orders.js RPS=$rps DURATION=${durs}s; log $log)"
}

wait_k6() { # <summary_rel_to_root> <duration_seconds> <log_abs>
  local summary="$1" durs="$2" log="$3"
  [ -n "${K6_PID:-}" ] || return 0
  local budget=$(( durs + 10 + 60 )) waited=0
  while kill -0 "$K6_PID" 2>/dev/null && [ "$waited" -lt "$budget" ]; do
    sleep 2; waited=$((waited+2))
  done
  if kill -0 "$K6_PID" 2>/dev/null; then
    echo "[recipe] WARNING: k6 still running after ${budget}s — killing by recorded pid $K6_PID"
    kill "$K6_PID" 2>/dev/null || true
  fi
  wait "$K6_PID" 2>/dev/null || true
  if [ ! -f "$ROOT/$summary" ]; then
    echo "[recipe] WARNING: k6 summary $ROOT/$summary missing — tail of k6 log ($log):"
    tail -n 5 "$log" 2>/dev/null || true
  fi
}

dlq_count() {
  local n
  n=$(docker exec "$KAFKA_CONTAINER" sh -c "/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic '$DLQ_TOPIC' --from-beginning --timeout-ms 3000 2>/dev/null | wc -l" 2>/dev/null | tr -d '[:space:]')
  if [ -n "$n" ]; then printf '%s' "$n"; else echo "WARNING: DLQ count failed (is $KAFKA_CONTAINER up?)" >&2; printf '0'; fi
}

wait_pong() { # <max_wait_s>
  local maxw="${1:-60}" w=0 p=""
  while [ "$w" -lt "$maxw" ]; do
    p=$(docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null || true)
    [ "$p" = "PONG" ] && return 0
    sleep 2; w=$((w+2))
  done
  return 1
}

wait_first_201_after() { # <outfile> <since_iso> <max_wait_s> -> prints ts ("" if none)
  local out="$1" since="$2" maxw="$3" waited=0 found=""
  while [ "$waited" -lt "$maxw" ]; do
    found=$(jq -r --arg t "$since" 'select(.kind=="probe" and .status==201 and .ts > $t) | .ts' "$out" 2>/dev/null | head -n1)
    [ -n "$found" ] && { printf '%s' "$found"; return 0; }
    sleep 1; waited=$((waited+1))
  done
  return 1
}

boot_erp() { # the make esb-run ERP command, verbatim semantics
  (
    cd "$ROOT" || exit 1
    SERVER_PORT=18080 ERP_DEMO_GENERATE_ORDERS=0 nohup java -jar "$ERP_JAR" > /tmp/silkroute-esb-erp.log 2>&1 &
    echo $! > "$ERP_PID_FILE"
  )
}

boot_esb() { # the make esb-run ESB command, verbatim semantics
  (
    cd "$ROOT" || exit 1
    ESB_FAULT_INJECTION=false nohup java -jar "$ESB_JAR" > /tmp/silkroute-esb-app.log 2>&1 &
    echo $! > "$ESB_PID_FILE"
  )
}

wait_health_up() { # <health_url> <max_wait_s>
  local url="$1" maxw="$2" w=0
  while [ "$w" -lt "$maxw" ]; do
    curl -sf --max-time 3 "$url" 2>/dev/null | grep -q UP && return 0
    sleep 2; w=$((w+2))
  done
  return 1
}

ensure_erp_esb_up() { # best-effort restore (used by finish / EXIT trap)
  if ! curl -sf --max-time 3 "$ERP_HEALTH" 2>/dev/null | grep -q UP; then
    echo "[chaos-run] ERP down — restoring via standard boot (SERVER_PORT=18080 ERP_DEMO_GENERATE_ORDERS=0)"
    boot_erp
    wait_health_up "$ERP_HEALTH" 60 || echo "[chaos-run] WARNING: ERP still not healthy after restore attempt"
  fi
  if ! curl -sf --max-time 3 "$ESB_HEALTH" 2>/dev/null | grep -q UP; then
    echo "[chaos-run] ESB down — restoring via standard boot (ESB_FAULT_INJECTION=false)"
    boot_esb
    wait_health_up "$ESB_HEALTH" 60 || echo "[chaos-run] WARNING: ESB still not healthy after restore attempt"
  fi
}

print_stack_state() {
  local erp="DOWN" esb="DOWN" pong="FAIL" tox="(unreachable)"
  curl -sf --max-time 3 "$ERP_HEALTH" 2>/dev/null | grep -q UP && erp="UP"
  curl -sf --max-time 3 "$ESB_HEALTH" 2>/dev/null | grep -q UP && esb="UP"
  [ "$(docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null || true)" = "PONG" ] && pong="PONG"
  tox=$(curl -s --max-time 3 "$API/proxies/$PROXY" 2>/dev/null \
    | jq -r 'if .toxics and (.toxics|length)>0 then ([.toxics[].name]|join(",")) else "(none)" end' 2>/dev/null) || tox="(unreachable)"
  [ -n "$tox" ] || tox="(unreachable)"
  local erp_pid="(none)" esb_pid="(none)"
  [ -f "$ERP_PID_FILE" ] && erp_pid=$(cat "$ERP_PID_FILE" 2>/dev/null)
  [ -f "$ESB_PID_FILE" ] && esb_pid=$(cat "$ESB_PID_FILE" 2>/dev/null)
  echo "CHAOS RUN COMPLETE — stack state: ERP(18080)=$erp pid=$erp_pid · ESB(18081/18082)=$esb pid=$esb_pid · toxiproxy($PROXY) toxics=$tox · redis=$pong"
}

finish() { # restore the world; every recipe ends here
  echo "[chaos-run] restoring world: esb-faults clean · ERP/ESB running with valid PID files · redis running"
  bash "$FAULTS" clean || true
  if [ "$(docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null || true)" != "PONG" ]; then
    ( cd "$ROOT" && docker compose start redis >/dev/null 2>&1 ) || true
    wait_pong 30 || echo "[chaos-run] WARNING: redis not answering PONG after restore attempt"
  fi
  ensure_erp_esb_up
  print_stack_state
  CHAOS_ACTIVE=0
}

on_exit() { # safety net if a recipe dies mid-flight (set -e is deliberately NOT used)
  [ "${CHAOS_ACTIVE:-0}" = "1" ] || return 0
  echo "[chaos-run] non-clean exit detected — restoring world (best effort)" >&2
  bash "$FAULTS" clean >/dev/null 2>&1 || true
  ( cd "$ROOT" && docker compose start redis >/dev/null 2>&1 ) || true
  ensure_erp_esb_up >/dev/null 2>&1 || true
  print_stack_state >&2
  CHAOS_ACTIVE=0
}
trap on_exit EXIT

# ----------------------------------------------------------------- recipes ---

recipe_latency() { # [RPS] [DURATION_s] [MS]  — defaults 10 120 800
  local rps="${1:-10}" dur="${2:-120}" ms="${3:-800}"
  is_int "$rps" && is_int "$dur" && is_int "$ms" \
    || { echo "ERROR: RPS, DURATION and MS must be integers"; return 1; }
  CHAOS_ACTIVE=1
  precheck
  local stamp out summary log total probe_pid inject_ts heal_ts arc
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  mkdir -p "$TRANS_DIR" "$RESULTS_DIR"
  out="$TRANS_DIR/latency-${ms}ms-${stamp}.jsonl"
  summary="tests/load/results/chaos-latency-${ms}ms-summary.json"
  log="/tmp/silkroute-chaos-k6-latency-${ms}ms.log"
  total=$(( 15 + dur + 30 + 10 ))
  echo "[recipe latency-under-load] RPS=$rps DURATION=${dur}s INJECTION=${ms}ms — hypothesis H1 (runbook §2)"
  start_k6 "$rps" "$dur" "$summary" "$log" || { finish; return 1; }
  probe_loop "$total" 1 "$out" &
  probe_pid=$!
  echo "[recipe] probe-loop pid=$probe_pid total=${total}s interval=1s -> $out"
  sleep 15                                            # steady state (baseline window)
  inject_ts=$(now_iso)
  bash "$FAULTS" latency "$ms" || { echo "ERROR: latency injection failed"; stop_probe_loop "$probe_pid"; finish; return 1; }
  echo "[recipe] INJECTED latency=${ms}ms at $inject_ts — fault window ${dur}s"
  sleep "$dur"
  heal_ts=$(now_iso)
  bash "$FAULTS" remove lat || true
  echo "[recipe] TOXIC REMOVED (healed) at $heal_ts — recovery window 30s"
  sleep 30
  stop_probe_loop "$probe_pid"
  wait_k6 "$summary" "$dur" "$log"
  echo "[recipe] transcript: $out · k6 summary: $ROOT/$summary"
  analyze "$out" --affected-window "$inject_ts" "$heal_ts"
  arc=$?
  echo "[recipe] reading the result: 422 INV-OUT-OF-STOCK counts are business rejections, NEVER saturation; recovery printed above = heal -> first 201"
  finish
  return "$arc"
}

recipe_killerp() { # [RPS] [DURATION_s] — defaults 10 120
  local rps="${1:-10}" dur="${2:-120}"
  is_int "$rps" && is_int "$dur" || { echo "ERROR: RPS and DURATION must be integers"; return 1; }
  CHAOS_ACTIVE=1
  precheck
  [ -f "$ERP_PID_FILE" ] || { echo "ERROR: ERP PID file $ERP_PID_FILE missing — boot via: make esb-run"; finish; return 1; }
  echo "[recipe kill-erp] HONEST NOTE: the sim ERP keeps orders in an IN-MEMORY ledger, so the restart re-seeds fresh state; idempotency across the restart is carried by the ESB's Redis done-keys, not the ERP ledger."
  local stamp out summary log total probe_pid arc
  local pre_dlq kill_ts erp_pid restart_ts health_ts post_dlq first201 rec cob
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  mkdir -p "$TRANS_DIR" "$RESULTS_DIR"
  out="$TRANS_DIR/kill-erp-${stamp}.jsonl"
  summary="tests/load/results/chaos-killerp-summary.json"
  log="/tmp/silkroute-chaos-k6-killerp.log"
  total=$(( 20 + 20 + 125 + 65 ))   # steady + failure + max health wait + max recovery watch + margin
  echo "[recipe kill-erp] RPS=$rps DURATION=${dur}s — hypothesis H2 (runbook §2)"
  start_k6 "$rps" "$dur" "$summary" "$log" || { finish; return 1; }
  probe_loop "$total" 1 "$out" &
  probe_pid=$!
  echo "[recipe] probe-loop pid=$probe_pid total=${total}s interval=1s -> $out"
  sleep 20                                            # steady state (baseline window)
  pre_dlq=$(dlq_count)
  echo "[recipe] pre-kill DLQ count: $pre_dlq"
  kill_ts=$(now_iso)
  erp_pid=$(cat "$ERP_PID_FILE")
  echo "[recipe] KILL ERP pid $erp_pid at $kill_ts (by PID file — never pkill)"
  kill "$erp_pid" 2>/dev/null || true
  local w=0
  while kill -0 "$erp_pid" 2>/dev/null && [ "$w" -lt 10 ]; do sleep 1; w=$((w+1)); done
  if kill -0 "$erp_pid" 2>/dev/null; then
    echo "[recipe] ERP pid still alive after 10s TERM — escalating to kill -9 (same recorded pid)"
    kill -9 "$erp_pid" 2>/dev/null || true
    sleep 1
  fi
  if kill -0 "$erp_pid" 2>/dev/null; then
    echo "ERROR: ERP pid $erp_pid still alive after kill -9 — aborting (world will be restored)"
    stop_probe_loop "$probe_pid"; finish; return 1
  fi
  echo "[recipe] ERP process gone (verified kill -0) — failure window 20s (probes must show FAST explicit failures: CIRCUIT-OPEN / 5xx / status 0 — NOT 30s hangs)"
  sleep 20
  restart_ts=$(now_iso)
  boot_erp
  echo "[recipe] ERP restarting pid=$(cat "$ERP_PID_FILE") at $restart_ts — waiting /actuator/health UP (timeout 120s)"
  if ! wait_health_up "$ERP_HEALTH" 120; then
    echo "ERROR: ERP did not become healthy within 120s — MEASURED FINDING (recovery failed)"
    stop_probe_loop "$probe_pid"; finish; return 1
  fi
  health_ts=$(now_iso)
  echo "[recipe] ERP healthy at $health_ts — probing until first 201 (max 60s)"
  first201=$(wait_first_201_after "$out" "$health_ts" 60 || true)
  if [ -n "$first201" ]; then
    rec=$(( $(epoch_of_ts "$first201") - $(epoch_of_ts "$health_ts") ))
    echo "[recipe] RECOVERY: first 201 at $first201 = ${rec}s after health"
  else
    echo "[recipe] RECOVERY: no 201 within 60s after health — MEASURED FINDING"
  fi
  post_dlq=$(dlq_count)
  echo "[recipe] post-recovery DLQ count: $post_dlq (pre-kill: $pre_dlq; DLQ DELTA during fault: $(( post_dlq - pre_dlq )) exhausted-work messages)"
  stop_probe_loop "$probe_pid"
  wait_k6 "$summary" "$dur" "$log"
  echo "[recipe] transcript: $out · k6 summary: $ROOT/$summary"
  analyze "$out" --status0-window "$kill_ts" "$health_ts" --affected-window "$kill_ts" "$health_ts"
  arc=$?
  cob=$(jq -r 'select(.error_code=="CIRCUIT-OPEN") | .ts' "$out" 2>/dev/null | head -n1)
  if [ -n "$cob" ]; then
    echo "[recipe] breaker behavior VISIBLE: first CIRCUIT-OPEN at $cob"
  else
    echo "[recipe] breaker behavior: NO CIRCUIT-OPEN recorded in transcript (check the kill-window status histogram above)"
  fi
  finish
  return "$arc"
}

recipe_stopredis() { # [RPS] [DURATION_s] — defaults 10 120
  local rps="${1:-10}" dur="${2:-120}"
  is_int "$rps" && is_int "$dur" || { echo "ERROR: RPS and DURATION must be integers"; return 1; }
  CHAOS_ACTIVE=1
  precheck
  local stamp out summary log total probe_pid arc
  local stop_ts heal_ts
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  mkdir -p "$TRANS_DIR" "$RESULTS_DIR"
  out="$TRANS_DIR/stop-redis-${stamp}.jsonl"
  summary="tests/load/results/chaos-stopredis-summary.json"
  log="/tmp/silkroute-chaos-k6-stopredis.log"
  total=$(( 20 + 40 + 20 + 10 ))   # steady + outage + post-heal + margin
  echo "[recipe stop-redis] RPS=$rps DURATION=${dur}s — hypothesis H3 (runbook §2)"
  echo "[recipe stop-redis] hypothesis under test: with Redis down requests DEGRADE but stay BOUNDED (claim() allow-through + ERP ORD-DUP-REF backstop); replays during the outage must NOT produce fresh 201s; done-key replays return 409 again after heal. If probes HANG (unbounded Redis client timeout) that is a MEASURED FINDING — analyze exits 2 and the runbook records it."
  start_k6 "$rps" "$dur" "$summary" "$log" || { finish; return 1; }
  probe_loop "$total" 1 "$out" &
  probe_pid=$!
  echo "[recipe] probe-loop pid=$probe_pid total=${total}s interval=1s -> $out"
  sleep 20                                            # steady state (baseline window)
  stop_ts=$(now_iso)
  echo "[recipe] STOP redis ($REDIS_CONTAINER) at $stop_ts — 40s outage window"
  ( cd "$ROOT" && docker compose stop redis ) \
    || { echo "ERROR: docker compose stop redis failed"; stop_probe_loop "$probe_pid"; finish; return 1; }
  sleep 40
  ( cd "$ROOT" && docker compose start redis ) || echo "[recipe] WARNING: docker compose start redis failed — finish will retry"
  if ! wait_pong 60; then
    echo "ERROR: redis did not answer PONG within 60s of start — MEASURED FINDING (heal failed)"
    stop_probe_loop "$probe_pid"; finish; return 1
  fi
  heal_ts=$(now_iso)
  echo "[recipe] redis PONG at $heal_ts — probing 20s post-heal (claims/done-keys resume)"
  sleep 20
  stop_probe_loop "$probe_pid"
  wait_k6 "$summary" "$dur" "$log"
  echo "[recipe] transcript: $out · k6 summary: $ROOT/$summary"
  # NO status0 allowance here on purpose: a status 0 during a Redis outage means
  # the ESB blocked past the 10s probe cap — exactly the unbounded-timeout finding.
  analyze "$out" --affected-window "$stop_ts" "$heal_ts"
  arc=$?
  if [ "$arc" -eq 2 ]; then
    echo "[recipe verdict] MEASURED FINDING — analyze found violations (see VIOLATION lines above): a HANG/status-0 means the Redis degradation did NOT stay bounded (unbounded client timeout); a replay-201 means a duplicate commit. Record which in the runbook OBSERVED section."
  else
    echo "[recipe verdict] degradation stayed BOUNDED (no hang, no network-level failure, no duplicate commit) — H3 held on this transcript"
  fi
  finish
  return "$arc"
}

stop_all() {
  echo "[chaos-run] stop-all: make esb-stop (kills ONLY by the recorded PID files; sim stack untouched)"
  ( cd "$ROOT" && make esb-stop ) || true
  print_stack_state
}

# ---------------------------------------------------------------- dispatch ---

case "${1:-}" in
  precheck)          precheck ;;
  probe-loop)        shift; probe_loop_cmd "$@" ;;
  analyze)           shift; analyze "$@" ;;
  --selftest|selftest) selftest ;;
  latency-under-load) shift; recipe_latency "$@" ;;
  kill-erp)          shift; recipe_killerp "$@" ;;
  stop-redis)        shift; recipe_stopredis "$@" ;;
  stop-all)          stop_all ;;
  help|-h|--help)    usage 0 ;;
  "")                usage 1 ;;
  *) echo "ERROR: unknown subcommand: $1" >&2; usage 1 ;;
esac
