#!/usr/bin/env bash
# Fault 4: docker kill insights-service in the middle of a burst of events; restart; reconcile.
# Predicted: the offset is acknowledged only after the DB commit, so the consumer resumes from its committed offset;
# redelivered records are absorbed by the version rule. Nothing lost, nothing double-counted.
source "$(dirname "${BASH_SOURCE[0]}")/fault-lib.sh"
fault_begin consumer-crash

section "Baseline"
run_workload 5 base
wait_synced 90 || die "baseline did not settle"
TOTAL0="$(outbox_total)"

section "Burst, and kill insights in the middle of it"
workload 40 burst &
WL_PID=$!
for _ in $(seq 1 100); do
  [ "$(( $(outbox_total) - TOTAL0 ))" -ge 25 ] && break
  kill -0 "$WL_PID" 2>/dev/null || break
  sleep 0.1
done
EVENTS_AT_KILL=$(( $(outbox_total) - TOTAL0 ))
if kill -0 "$WL_PID" 2>/dev/null; then pass "burst still running at the kill ($EVENTS_AT_KILL events created so far)"; else fail "the burst had already finished before the kill: not mid-burst"; fi
PROJ_AT_KILL="$(proj_sig)"; CLAIMS_AT_KILL="$(claims_sig)"
committed_offsets "$_TMP/off.before_kill" || die "cannot read the group's committed offsets before the kill"
say "  committed offsets just before the kill: $(tr '\n' ';' < "$_TMP/off.before_kill")"
docker kill "$("${DC[@]}" ps -q insights-service)" >/dev/null || die "could not kill insights-service"
say "  killed insights-service at $(utc); claims signature $CLAIMS_AT_KILL, projection signature $PROJ_AT_KILL"
wait "$WL_PID"
wl_result burst
wait_until 60 "outbox drained" outbox_empty || die "outbox did not drain"
say "  after the burst, insights still down: claims $(claims_sig) vs projection $(proj_sig)"
if [ "$(claims_sig)" != "$(proj_sig)" ]; then pass "projection was behind when the consumer came back (events pending on the topic)"; else fail "projection was not behind: the crash did not interrupt anything"; fi

committed_offsets "$_TMP/off.after_kill" || die "cannot read the group's committed offsets after the kill"
offsets_not_back "across the kill (committed offsets must survive a hard kill)" "$_TMP/off.before_kill" "$_TMP/off.after_kill"

section "Restart"
T0="$(now)"
"${DC[@]}" up -d --no-build insights-service >/dev/null 2>&1
wait_synced 180 || die "insights did not catch up after the crash"
T_SYNC="$(since "$T0")"; say "  caught up ${T_SYNC}s after restart"
wait_until 60 "consumer lag 0 (a partition showing '-' does not count)" lag_zero || fail "consumer lag did not reach a numeric 0 on every partition"
committed_offsets "$_TMP/off.after_restart" || die "cannot read the group's committed offsets after the restart"
offsets_not_back "after the restart, against the offsets at the kill (resumes from, never before, its committed offset)" "$_TMP/off.after_kill" "$_TMP/off.after_restart"
if [ "$OFFSETS_MOVED" -gt 0 ]; then pass "the restarted consumer made progress from its committed offsets ($OFFSETS_MOVED partitions advanced)"; else fail "no partition advanced after the restart although events were pending"; fi
run_reconcile
summary "consumer-crash: killed mid-burst at $EVENTS_AT_KILL events; burst $WL_CMDS_N commands; caught up ${T_SYNC}s after restart, reconciled"
finish
