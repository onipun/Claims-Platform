#!/usr/bin/env bash
# Fault 5: recorded events are published to the topic again, duplicated and out of order, straight to Kafka.
# Predicted (D4): insights replaces a row only when event.claimVersion > stored.version, so the projection is unchanged.
source "$(dirname "${BASH_SOURCE[0]}")/fault-lib.sh"
fault_begin duplicate-reorder

section "Baseline"
run_workload 30 base
wait_synced 90 || die "baseline did not settle"
wait_until 60 "consumer lag 0" lag_zero || die "consumer not idle"

section "Record real events from the topic"
topic_dump "$TOPIC" "$_TMP/recorded.tsv"
N="$(wc -l < "$_TMP/recorded.tsv")"
[ "$N" -ge 30 ] || die "only $N recorded events"
FP0="$(projection_fp)" || die "cannot read the projection fingerprint"; END0="$(topic_end "$TOPIC")" || die "cannot read the topic end"; DLT0="$(topic_end "$TOPIC.DLT")" || die "cannot read the DLT end"
say "  recorded $N real events; topic end offsets sum $END0; projection fingerprint $FP0"
# how many of them are STALE (older than what the projection already holds): the ones that would corrupt it if applied
sqli "SELECT claim_id||'|'||version FROM claim_projection" > "$_TMP/proj.txt"
STALE="$(cut -f2 "$_TMP/recorded.tsv" | jq -r '[.claimId,.claimVersion]|join("|")' | awk -F'|' 'NR==FNR{v[$1]=$2; next} ($1 in v) && ($2 < v[$1]) {n++} END{print n+0}' "$_TMP/proj.txt" -)"
say "  of those, $STALE carry a version older than the projection's current version for their claim"
[ "$STALE" -gt 0 ] || die "no stale versions among the recorded events: the replay would prove nothing"

section "Replay: every event twice more, newest first, then shuffled"
{ tac "$_TMP/recorded.tsv"; shuf "$_TMP/recorded.tsv"; } > "$_TMP/replay.tsv"
R="$(wc -l < "$_TMP/replay.tsv")"
KT kafka-console-producer.sh --bootstrap-server kafka:9092 --topic "$TOPIC" \
  --property parse.key=true --property $'key.separator=\t' < "$_TMP/replay.tsv" 2>/dev/null || die "replay publish failed"
END1="$(topic_end "$TOPIC")" || die "cannot read the topic end"
assert_eq "$((END1 - END0))" "$R" "all $R replayed records landed on the topic ($END0 -> $END1)"
wait_until 120 "insights consumed the replay (lag 0)" lag_zero || die "consumer did not finish the replay"
sleep 2

section "Projection unchanged"
FP1="$(projection_fp)" || die "cannot read the projection fingerprint"
assert_eq "$FP1" "$FP0" "projection fingerprint (rows, versions, status, reserve, paid) identical before and after"
DLT1="$(topic_end "$TOPIC.DLT")" || die "cannot read the DLT end"
assert_eq "$DLT1" "$DLT0" "nothing went to the DLT (replayed events are valid, just not newer)"
wait_synced 60 || die "not in sync"
run_workload 6 after
wait_synced 60 || die "projection stopped working after the replay"
run_reconcile
summary "duplicate-reorder: replayed $R real records ($N recorded x2, reversed + shuffled; $STALE of the recorded were older than the projection); projection fingerprint unchanged, DLT unchanged"
finish
