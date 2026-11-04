#!/usr/bin/env bash
# Shared helpers for scripts/fault-*.sh (host side). Source it; do not execute it.
# Builds on lib.sh (api, login, pass/fail, wait_until, finish). Every wait has a timeout; every failure is named.
#
# State model: every fault script works INCREMENTALLY on the running stack (it adds claims, it does not wipe data).
# reconcile.sh compares the whole ledger with the whole projection, so incremental runs are valid. For a clean
# start use scripts/fault-all.sh with FAULT_CLEAN=1 (compose down -v, then up).

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 2
DC=(docker compose -f docker-compose.yml -f docker-compose.fault.yml)
export WEB_URL="${WEB_URL:-http://127.0.0.1:14200}"
CLAIMS_DIRECT="${CLAIMS_DIRECT:-http://127.0.0.1:18080}"
INSIGHTS_DIRECT="${INSIGHTS_DIRECT:-http://127.0.0.1:18081}"
TOXI="${TOXI:-http://127.0.0.1:18474}"
TOPIC=claims.events.v1
EVD="$REPO/docs/evidence/faults"
set -uo pipefail
# shellcheck source=lib.sh
source "$REPO/scripts/lib.sh"

now() { date +%s.%N; }
since() { awk -v a="$1" -v b="$(now)" 'BEGIN{printf "%.1f", b-a}'; }
utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# ---------- start / stop ----------
# fault_begin NAME : re-runs the calling script through tee into docs/evidence/faults/NAME.txt (exit code kept).
fault_begin() {
  FAULT_NAME="$1"
  mkdir -p "$EVD"
  if [ -z "${FAULT_TEE:-}" ]; then
    export FAULT_TEE=1
    bash "${BASH_SOURCE[1]}" 2>&1 | tee "$EVD/$FAULT_NAME.txt"
    exit "${PIPESTATUS[0]}"
  fi
  trap _fault_exit EXIT
  printf 'FAULT %s  started %s  (incremental on the running stack; compose files: docker-compose.yml + docker-compose.fault.yml)\n' "$FAULT_NAME" "$(utc)"
  _T_START=$SECONDS
  section "Preflight"
  command -v jq >/dev/null || die "jq missing on host"
  wait_until 240 "stack ready behind $WEB_URL" _web_ready || die "stack not ready: bring it up first (docker compose -f docker-compose.yml -f docker-compose.fault.yml up -d)"
  tox_reset || die "toxiproxy not reachable at $TOXI (is the fault override running?)"
  wl_init
}
summary() { printf 'SUMMARY: %s\n' "$*"; }
_fault_exit() {
  local rc=$?
  trap - EXIT
  section "Restore stack"
  if ! restore_stack; then printf 'RESTORE FAILED: stack is not fully back\n'; rc=1; fi
  printf 'duration %ss, exit %s\n' "$((SECONDS - ${_T_START:-$SECONDS}))" "$rc"
  rm -rf "$_TMP"
  exit "$rc"
}
restore_stack() {
  tox_reset >/dev/null 2>&1
  "${DC[@]}" up -d --no-build >/dev/null 2>&1
  local i
  for i in $(seq 1 240); do _web_ready && { printf 'stack ready (kafka, insights, claims, web up; toxics cleared)\n'; return 0; }; sleep 1; done
  return 1
}

# ---------- database / kafka ----------
sqlc() { "${DC[@]}" exec -T postgres psql -X -U postgres -d claims -tA -c "$1"; }
sqli() { "${DC[@]}" exec -T postgres psql -X -U postgres -d insights -tA -c "$1"; }
unpub() { sqlc "SELECT count(*) FROM outbox_event WHERE published_at IS NULL"; }
outbox_total() { sqlc "SELECT count(*) FROM outbox_event"; }
claims_sig() { sqlc "SELECT count(*)||'/'||coalesce(sum(version),0) FROM claim"; }
proj_sig() { sqli "SELECT count(*)||'/'||coalesce(sum(version),0) FROM claim_projection"; }
outbox_empty() { [ "$(unpub)" = 0 ]; }
is_synced() { [ "$(unpub)" = 0 ] && [ "$(claims_sig)" = "$(proj_sig)" ]; }
wait_synced() { wait_until "${1:-90}" "outbox drained and projection caught up" is_synced; }

KT() { local tool="$1"; shift; "${DC[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka "/opt/kafka/bin/$tool" "$@"; }
# topic_dump TOPIC FILE : every record as "key<TAB>value"
topic_dump() { KT kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic "$1" --from-beginning --timeout-ms 8000 \
  --property print.key=true 2>/dev/null | tr -d '\r' | grep -P '\t' > "$2" || true; }
# topic_ends TOPIC : "partition offset" per line (log end offsets)
topic_ends() {
  local out
  out="$(KT kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic "$1" 2>/dev/null)" || return 1
  [ -n "$out" ] || return 1
  printf '%s\n' "$out" | awk -F: 'NF>=3 {print $2" "$3}'
}
# topic_dump_after TOPIC ENDSFILE OUTFILE : records appended after the offsets in ENDSFILE, as "key<TAB>value"
topic_dump_after() {
  : > "$3"
  local p o
  while read -r p o; do
    KT kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic "$1" --partition "$p" --offset "$o" --timeout-ms 6000 \
      --property print.key=true 2>/dev/null </dev/null | tr -d '\r' | grep -P '\t' >> "$3" || true
  done < "$2"
}
# topic_end TOPIC : sum of the log end offsets. Non-zero (and no output) when the broker command fails or says nothing:
# a failed read must not look like "0 records". Callers check it: X="$(topic_end T)" || die "...".
topic_end() {
  local out
  out="$(KT kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic "$1" 2>/dev/null)" || return 1
  [ -n "$out" ] || return 1
  printf '%s\n' "$out" | awk -F: 'NF>=3 {s+=$3; n++} END{ if (n == 0) exit 1; print s+0 }'
}
# group_describe : the consumer group's rows for $TOPIC ("partition current-offset lag"); non-zero if there are none.
group_describe() {
  local out
  out="$(KT kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group insights-projection 2>/dev/null)" || return 1
  printf '%s\n' "$out" | awk -v t="$TOPIC" '$2==t {print $3, $4, $6; n++} END{ if (n == 0) exit 1 }'
}
# group_lag : the total lag. A "-" in any partition (no committed offset yet, or no lag figure) is NOT zero: the
# partition is unread or unknown, so the answer is "unknown", which lag_zero rejects.
group_lag() {
  local rows
  rows="$(group_describe)" || { echo unknown; return 1; }
  printf '%s\n' "$rows" | awk '{ if ($3 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/) { s += $3; n++ } else bad = 1 }
    END { if (n > 0 && !bad) print s + 0; else print "unknown" }'
}
lag_zero() { [ "$(group_lag)" = 0 ]; }
# committed_offsets FILE : the group's committed offset per partition, "partition offset" lines ("-" when none).
committed_offsets() { group_describe | awk '{print $1, $2}' | sort -n > "$1"; [ -s "$1" ]; }
# offsets_not_back LABEL BEFORE AFTER : every partition's committed offset in AFTER is a number >= BEFORE's.
# Prints the comparison so the evidence shows the offsets, and sets OFFSETS_MOVED to how many partitions advanced.
OFFSETS_MOVED=0
offsets_not_back() {
  local label="$1" before="$2" after="$3" p b a bad=0
  OFFSETS_MOVED=0
  [ -s "$before" ] && [ -s "$after" ] || { fail "$label: committed offsets could not be read (before or after)"; return 1; }
  say "  committed offsets, $label (partition: before -> after)"
  while read -r p b; do
    a="$(awk -v p="$p" '$1 == p {print $2}' "$after")"
    if [[ "$b" =~ ^[0-9]+$ ]] && [[ "$a" =~ ^[0-9]+$ ]] && [ "$a" -ge "$b" ]; then
      printf '    partition %s: %s -> %s%s\n' "$p" "$b" "$a" "$([ "$a" -gt "$b" ] && echo '  (advanced)')"
      [ "$a" -gt "$b" ] && OFFSETS_MOVED=$((OFFSETS_MOVED + 1))
    elif [ "$b" = "-" ] && [[ "$a" =~ ^[0-9]+$ ]]; then
      printf '    partition %s: - -> %s  (first commit)\n' "$p" "$a"
    else
      printf '    partition %s: %s -> %s  BACKWARDS OR MISSING\n' "$p" "$b" "${a:-<none>}"; bad=1
    fi
  done < "$before"
  if [ "$bad" = 0 ]; then pass "$label: no committed offset went backwards ($OFFSETS_MOVED partitions advanced)"
  else fail "$label: a committed offset went backwards or disappeared"; fi
}
# projection_fp : count|md5 of the projection rows. Non-zero with no output if the query fails.
projection_fp() {
  local out
  out="$(sqli "SELECT count(*)||'|'||coalesce(md5(string_agg(claim_id||':'||version||':'||status||':'||reserve||':'||paid, ',' ORDER BY claim_id)),'-') FROM claim_projection")" || return 1
  [ -n "$out" ] || return 1
  printf '%s\n' "$out"
}

# ---------- toxiproxy ----------
tox_reset() {
  local names n
  names="$(curl -sf -m 5 "$TOXI/proxies/kafka/toxics" | jq -r '.[].name')" || return 1
  for n in $names; do curl -sf -m 5 -X DELETE "$TOXI/proxies/kafka/toxics/$n" >/dev/null; done
  curl -sf -m 5 -X POST "$TOXI/proxies/kafka" -d '{"enabled":true}' >/dev/null
}
tox_latency() { # MS : delay everything that flows broker -> claims-service (produce acks, metadata)
  curl -sf -m 5 -X POST "$TOXI/proxies/kafka/toxics" \
    -d "{\"name\":\"ack_delay\",\"type\":\"latency\",\"stream\":\"downstream\",\"toxicity\":1,\"attributes\":{\"latency\":$1,\"jitter\":0}}" >/dev/null
}

# ---------- reconcile ----------
run_reconcile() {
  section "reconcile (scripts/reconcile.sh in the compose test container)"
  local out rc
  out="$("${DC[@]}" --profile test run --rm -T reconcile 2>&1)"; rc=$?
  printf '%s\n' "$out" | grep -v '^ Container\|^ Network\|Creating\|Starting\|Waiting' | sed 's/^/  | /'
  if [ "$rc" = 0 ] && printf '%s' "$out" | grep -q 'RESULT: [0-9]* passed, 0 failed'; then
    pass "reconcile passes: ledger = projection = API, nothing lost, nothing double-counted (exit 0)"
  else
    fail "reconcile FAILED (exit $rc): lost or double-counted figures, see the table above"
  fi
}

# ---------- workload through the gateway ----------
wl_init() {
  login T_CLAIMANT claimant.sg@demo.test
  login T_OFFICER officer.sg@demo.test
  login T_MANAGER manager.sg@demo.test
  login T_APAC manager.apac@demo.test
  api "$T_CLAIMANT" GET /api/v1/claimant/policies
  POLICY_ID="$(printf '%s' "$BODY" | jq -r '[.[]|select(.marketCode=="SG" and .line=="MOTOR")][0].id // empty')"
  [ -n "$POLICY_ID" ] || die "no SG MOTOR policy for claimant.sg"
  RUN="$(date +%s)-$RANDOM"
  INCIDENT="$(date -u -d @$(( $(date +%s) - 10800 )) +%Y-%m-%dT%H:%M:%S)"
}
WL_CMDS=0; WL_BAD=0
wl_submit() { # KEY ESTIMATE -> CID, STATUS
  local body; body="$(jq -nc --arg pid "$POLICY_ID" --arg est "$2" --arg inc "$INCIDENT" '{
    policyId:$pid, line:"MOTOR", claimType:"OWN_DAMAGE", estimate:$est, incidentAtLocal:$inc,
    contactPhone:"+6591234567", description:"Fault run workload",
    motor:{vehicleRegistration:"FLT1234X", driverName:"Alicia Tan", location:"Orchard Road junction"}}')"
  api "$T_CLAIMANT" POST /api/v1/claimant/claims "$body" -H "Idempotency-Key: $1"
  CID="$(jqb .id)"
  WL_CMDS=$((WL_CMDS + 1))
  case "$STATUS" in 200|201) ;; *) WL_BAD=$((WL_BAD + 1)); say "  workload: submit -> HTTP $STATUS ${BODY:0:160}";; esac
}
wl_staff() { # TOKEN ID SUB JSON
  api "$1" GET "/api/v1/staff/claims/$2"
  local ifm; ifm="$(header etag)"; [ -n "$ifm" ] || ifm="\"$(jqb .version)\""
  api "$1" POST "/api/v1/staff/claims/$2/$3" "${4:--}" -H "If-Match: $ifm"
  WL_CMDS=$((WL_CMDS + 1))
  case "$STATUS" in 200|201) ;; *) WL_BAD=$((WL_BAD + 1)); say "  workload: $3 on $2 -> HTTP $STATUS ${BODY:0:160}";; esac
}
# workload N TAG : N claims, each taken a different distance through the lifecycle (submit only, +pick-up,
# +assessment, +approval+settlement). Runs in a subshell (so it can be backgrounded); result in $_TMP/TAG.res.
workload() {
  local n="$1" tag="$2" out="$_TMP"
  (
    trap - EXIT
    _TMP="$(mktemp -d -p "$out")"
    WL_CMDS=0; WL_BAD=0; local i claims=0
    : > "$out/$tag.ids"
    for i in $(seq 1 "$n"); do
      wl_submit "flt-$tag-$RUN-$i" "$((3000 + i * 7)).00"
      [ -n "$CID" ] || continue
      claims=$((claims + 1)); echo "$CID" >> "$out/$tag.ids"
      local id="$CID"
      case $((i % 4)) in
        1) wl_staff "$T_OFFICER" "$id" pick-up - ;;
        2) wl_staff "$T_OFFICER" "$id" pick-up -; wl_staff "$T_OFFICER" "$id" assessment '{"reserve":"1500.00"}' ;;
        3) wl_staff "$T_OFFICER" "$id" pick-up -; wl_staff "$T_OFFICER" "$id" assessment '{"reserve":"1500.00"}'
           wl_staff "$T_OFFICER" "$id" approval '{"amount":"1500.00"}'; wl_staff "$T_OFFICER" "$id" settlement '{"amount":"1500.00"}' ;;
      esac
    done
    echo "$claims $WL_CMDS $WL_BAD" > "$out/$tag.res"
  )
}
# wl_result TAG : read the workload's counters, assert every command succeeded. Sets WL_CLAIMS_N, WL_CMDS_N.
wl_result() {
  local f="$_TMP/$1.res"
  [ -s "$f" ] || { fail "workload $1 produced no result file (did it finish?)"; WL_CLAIMS_N=0; WL_CMDS_N=0; return 1; }
  local bad; read -r WL_CLAIMS_N WL_CMDS_N bad < "$f"
  if [ "$bad" = 0 ]; then pass "workload $1: $WL_CLAIMS_N claims, $WL_CMDS_N REST commands, all succeeded"
  else fail "workload $1: $bad of $WL_CMDS_N REST commands failed"; fi
}
run_workload() { workload "$1" "$2"; wl_result "$2"; }

# SG/MOTOR/SGD exposure row as manager.apac: "open provisional assessed paid"
exposure_row() {
  api "$T_APAC" GET /api/v1/insights/exposure || return 1
  [ "$STATUS" = 200 ] || return 1
  printf '%s' "$BODY" | jq -r '[.rows[]|select(.market=="SG" and .line=="MOTOR" and .currency=="SGD")][0]
    | if . == null then "0 0 0 0" else "\(.openClaims) \(.provisional) \(.assessed) \(.paid)" end'
}
dash_asof() { api "$T_APAC" GET /api/v1/insights/exposure; jqb .asOf; }
