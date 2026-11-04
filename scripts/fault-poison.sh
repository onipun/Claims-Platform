#!/usr/bin/env bash
# Fault 6 (threat T15): malformed records on the topic. Predicted: no retry, straight to claims.events.v1.DLT,
# and the projection keeps working for the valid events behind them.
source "$(dirname "${BASH_SOURCE[0]}")/fault-lib.sh"
fault_begin poison

section "Baseline"
run_workload 10 base
wait_synced 90 || die "baseline did not settle"
wait_until 60 "consumer lag 0" lag_zero || die "consumer not idle"
DLT0="$(topic_end "$TOPIC.DLT")" || die "cannot read the DLT end offsets"; FP0="$(projection_fp)" || die "cannot read the projection fingerprint"
topic_dump "$TOPIC" "$_TMP/rec.tsv"
[ -s "$_TMP/rec.tsv" ] || die "could not read any record back from $TOPIC (no event to build the unsupported-schema record from)"
REAL="$(cut -f2 "$_TMP/rec.tsv" | tail -1)"
# The unsupported-schema records are derived from REAL: if it is not a parseable event the third kind would be
# plain garbage and the test would still pass for the wrong reason.
printf '%s' "$REAL" | jq -e '.schemaVersion == 1 and (.eventId | type == "string") and (.claimId | type == "string")' >/dev/null \
  || die "REAL is not a valid v1 event (got: ${REAL:0:80}), refusing to derive the unsupported-schema record from it"
T_POISON="$(utc)"
say "  DLT end offsets sum before: $DLT0"

section "Publish poison records (one per partition-spread key, three kinds)"
{
  for k in a b c d e f; do printf 'poison-%s\tnot-json{{{ %s\n' "$k" "$k"; done                      # not JSON
  for k in g h i j; do printf 'poison-%s\t{"hello":"world","n":%s}\n' "$k" "1"; done               # JSON, no event fields
  for k in k l m; do printf 'poison-%s\t%s\n' "$k" "$(printf '%s' "$REAL" | jq -c '.schemaVersion=99')"; done  # unsupported schema major
} > "$_TMP/poison.tsv"
P="$(wc -l < "$_TMP/poison.tsv")"
KT kafka-console-producer.sh --bootstrap-server kafka:9092 --topic "$TOPIC" \
  --property parse.key=true --property $'key.separator=\t' < "$_TMP/poison.tsv" 2>/dev/null || die "could not publish poison"
say "  published $P poison records"

section "Valid events behind the poison"
run_workload 30 behind
wait_synced 120 || fail "projection stopped: valid events behind the poison were not applied"
wait_until 60 "consumer lag 0 (poison skipped, not stuck)" lag_zero || fail "consumer is stuck on a poison record"

section "Dead-letter topic"
dlt_ok() { [ "$(topic_end "$TOPIC.DLT")" -ge $((DLT0 + P)) ]; }
wait_until 60 "all $P poison records in the DLT" dlt_ok || true
DLT1="$(topic_end "$TOPIC.DLT")" || die "cannot read the DLT end offsets"
assert_eq "$((DLT1 - DLT0))" "$P" "DLT grew by exactly the number of poison records"
KT kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic "$TOPIC.DLT" --from-beginning --timeout-ms 8000 \
  --property print.key=true 2>/dev/null | tr -d '\r' | grep poison- | sort | head -13 | cut -c1-110 | sed 's/^/    DLT: /'
assert_eq "$(sqli "SELECT count(*) FROM claim_projection WHERE claim_number LIKE 'poison%' OR claim_id::text LIKE 'poison%'")" 0 "no poison record reached the projection"
curl -s -m 5 "$INSIGHTS_DIRECT/actuator/health" > "$_TMP/h"; assert_eq "$(jq -r .status "$_TMP/h")" UP "insights-service health UP after the poison"
# Every record that reached the DLT has its own EVENT_DEAD_LETTERED line (claim id = record key, no payload).
# All three kinds are checked by marker, with the exception class the consumer logged for each.
"${DC[@]}" logs --no-color --since "$T_POISON" insights-service 2>&1 > "$_TMP/insights.log"
say "  insights log lines about dead-lettering (payloads must not be logged):"
grep "EVENT_DEAD_LETTERED" "$_TMP/insights.log" | sed 's/^.*EVENT_DEAD_LETTERED/EVENT_DEAD_LETTERED/' | cut -c1-200 | sed 's/^/    /' | head -13
n_kind() { grep -c "EVENT_DEAD_LETTERED claimId=poison-[$1] .*reason=$2" "$_TMP/insights.log"; }
assert_eq "$(n_kind a-f PoisonEventException)" 6 "kind 1 (not JSON, 6 records): one EVENT_DEAD_LETTERED line each, reason PoisonEventException"
assert_eq "$(n_kind g-j PoisonEventException)" 4 "kind 2 (JSON without event fields, 4 records): one line each, reason PoisonEventException"
assert_eq "$(n_kind k-m UnsupportedSchemaException)" 3 "kind 3 (unsupported schema major, 3 records): one line each, reason UnsupportedSchemaException"
if grep -q "not-json{{{\|hello.*world" "$_TMP/insights.log"; then fail "insights logged a poison payload"; else pass "poison payload text is not in the insights log"; fi

run_reconcile
summary "poison: $P malformed records -> DLT +$((DLT1 - DLT0)); $WL_CMDS_N valid commands behind them projected; consumer not stuck"
finish
