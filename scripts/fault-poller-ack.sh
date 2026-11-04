#!/usr/bin/env bash
# Fault 1: the poller is killed after the broker has the record but before the outbox row is marked published.
# Toxiproxy (claims-service -> kafka) holds the acks back with a latency toxic; docker kill lands inside that window.
# Predicted (plan 6.3, OutboxPoller): a duplicate on the topic, never a loss; the read model counts the claim once.
source "$(dirname "${BASH_SOURCE[0]}")/fault-lib.sh"
fault_begin poller-ack

LATENCY_MS=2500
DELAYS=(1.2 0.8 1.6 1.0 1.4 0.7)   # seconds between the claim commit and the kill, one per attempt
FORCED=0

section "Baseline workload"
run_workload 10 base
wait_synced 90 || die "baseline did not settle"

for attempt in 1 2 3 4 5 6; do
  [ "$FORCED" = 1 ] && break
  d="${DELAYS[$((attempt - 1))]}"
  section "Attempt $attempt: ack delay ${LATENCY_MS}ms, kill ${d}s after the claim commits"
  BASE="$(exposure_row)" || die "cannot read the dashboard"
  tox_latency "$LATENCY_MS" || die "could not add the latency toxic"
  wl_submit "flt-ack-$RUN-$attempt" "4000.00"
  [ "$STATUS" = 201 ] || die "claim submit failed: HTTP $STATUS"
  CLAIM="$CID"
  EID="$(sqlc "SELECT event_id FROM outbox_event WHERE claim_id='$CLAIM' AND version=0")"
  [ -n "$EID" ] || die "no outbox row for the claim"
  sleep "$d"
  PUB_AT_KILL="$(sqlc "SELECT coalesce(published_at::text,'NULL') FROM outbox_event WHERE event_id='$EID'")"
  T_KILL="$(utc)"
  docker kill "$("${DC[@]}" ps -q claims-service)" >/dev/null && tox_reset
  say "  killed claims-service at $T_KILL; outbox row published_at at kill time (read just before the kill) = $PUB_AT_KILL"
  "${DC[@]}" up -d --no-build claims-service >/dev/null 2>&1
  wait_until 150 "claims-service back" _web_ready || die "claims-service did not come back"
  wait_until 60 "outbox drained after restart" outbox_empty || die "outbox did not drain"
  topic_dump "$TOPIC" "$_TMP/topic.tsv"
  COUNT="$(cut -f2 "$_TMP/topic.tsv" | jq -r .eventId | grep -c "^$EID$")"
  say "  event $EID (claim $CLAIM v0): seen $COUNT time(s) on $TOPIC"
  if [ "$COUNT" -ge 2 ]; then FORCED=1; pass "duplicate forced on attempt $attempt: same eventId $COUNT times on the topic"
  else say "  not duplicated this time (published_at at kill = $PUB_AT_KILL): kill landed outside the send/ack window, adjusting the delay"; fi
done

if [ "$FORCED" != 1 ]; then
  fail "COULD NOT FORCE A DUPLICATE in 6 attempts: the send-before-mark window was not hit; the predicted duplicate is untested"
  finish
fi

section "Evidence: the duplicate on the topic"
printf '  records for event %s:\n' "$EID"
grep -F "$EID" "$_TMP/topic.tsv" | awk -F'\t' '{print "    key=" $1 "  " substr($2,1,150) "..."}'
TOTAL="$(wc -l < "$_TMP/topic.tsv")"; DISTINCT="$(cut -f2 "$_TMP/topic.tsv" | jq -r .eventId | sort -u | wc -l)"
say "  topic records: $TOTAL, distinct eventIds: $DISTINCT, duplicated eventIds: $(cut -f2 "$_TMP/topic.tsv" | jq -r .eventId | sort | uniq -d | wc -l)"
# Sanity only: event_id is UNIQUE and the outbox has already drained, so this restates "drained" and cannot fail on its own.
assert_eq "$(sqlc "SELECT count(*) FROM outbox_event WHERE event_id='$EID' AND published_at IS NOT NULL")" 1 "sanity (restates the drain; event_id is UNIQUE): the duplicated event's outbox row is marked published"

section "Evidence: the read model counted it once"
wait_synced 60 || die "projection did not catch up"
# Sanity only: claim_id is the projection's primary key, so one row is guaranteed once the projection has caught up.
# The check that can fail is the dashboard delta below (+1 open claim and +reserve, not +2).
assert_eq "$(sqli "SELECT count(*)||'/v'||max(version) FROM claim_projection WHERE claim_id='$CLAIM'")" "1/v0" "sanity (primary key; cannot fail alone): one projection row, version 0, for the duplicated claim"
RESERVE="$(sqlc "SELECT reserve FROM claim WHERE id='$CLAIM'")"
AFTER="$(exposure_row)"
read -r bo bp _ _ <<<"$BASE"; read -r ao ap _ _ <<<"$AFTER"
assert_eq "$ao" "$((bo + 1))" "dashboard open claims rose by exactly 1 (not 2)"
assert_num "$ap" "$(awk -v b="$bp" -v r="$RESERVE" 'BEGIN{print b+r}')" "dashboard provisional rose by exactly the claim's reserve $RESERVE (not twice)"

section "More work after the fault"
run_workload 20 post
wait_synced 90 || die "projection did not catch up after more work"
run_reconcile
summary "poller-ack: duplicate eventId seen ${COUNT}x on topic (attempt $attempt, ack delay ${LATENCY_MS}ms, kill ${d}s after commit); projection rows 1, dashboard +1 open"
finish
