#!/usr/bin/env bash
# Fault 2: Kafka stopped. Predicted (plan 6.5): claim work continues, outbox rows build up, health stays UP,
# dashboards readable but stale (asOf), poller drains the outbox in order on recovery.
source "$(dirname "${BASH_SOURCE[0]}")/fault-lib.sh"
fault_begin kafka-down

section "Baseline"
run_workload 5 base
wait_synced 90 || die "baseline did not settle"
ASOF0="$(dash_asof)"; BASE_UNPUB="$(unpub)"; ID_MAX0="$(sqlc "SELECT coalesce(max(id),0) FROM outbox_event")"
say "  asOf before the fault: $ASOF0; unpublished outbox rows: $BASE_UNPUB"

section "Stop Kafka"
topic_ends "$TOPIC" > "$_TMP/ends.before" || die "cannot read the topic end offsets"; say "  topic end offsets before the outage (partition offset): $(tr '\n' ';' < "$_TMP/ends.before")"
T_STOP="$(utc)"
"${DC[@]}" stop kafka >/dev/null 2>&1 || die "could not stop kafka"
assert_eq "$("${DC[@]}" ps --status running -q kafka | wc -l | tr -d ' ')" 0 "kafka container is stopped"
sleep 3

section "Claim commands over REST while Kafka is down"
run_workload 30 down
BACKLOG="$(unpub)"
say "  outbox backlog (unpublished rows): $BACKLOG   (rows created during the outage: $(( $(sqlc "SELECT coalesce(max(id),0) FROM outbox_event") - ID_MAX0 )))"
if [ "$BACKLOG" -ge "$WL_CMDS_N" ]; then pass "outbox grew: $BACKLOG unpublished rows after $WL_CMDS_N commands"; else fail "outbox did not grow as expected: backlog $BACKLOG, commands $WL_CMDS_N"; fi
sleep 5
curl -s -m 5 -o "$_TMP/h" -w '%{http_code}' "$CLAIMS_DIRECT/actuator/health" > "$_TMP/hs"
assert_eq "$(cat "$_TMP/hs")" 200 "claims-service /actuator/health HTTP status during the outage"
assert_eq "$(jq -r .status "$_TMP/h")" UP "claims-service /actuator/health status during the outage"
say "  health body: $(cat "$_TMP/h")"
curl -s -m 5 -o "$_TMP/h2" -w '%{http_code}' "$CLAIMS_DIRECT/actuator/health/liveness" > "$_TMP/hs2"
assert_eq "$(cat "$_TMP/hs2")" 200 "liveness probe during the outage"
assert_eq "$("${DC[@]}" ps --status running -q claims-service | wc -l | tr -d ' ')" 1 "claims-service is still running (not restarted by anything)"
say "  poller log lines during the outage:"
"${DC[@]}" logs --no-color --since "$T_STOP" claims-service 2>&1 | grep -i outbox | head -5 | sed 's/^/    /'

section "Dashboards: readable but stale"
ASOF1="$(dash_asof)"
assert_status 200 "dashboard still readable through nginx"
assert_eq "$ASOF1" "$ASOF0" "asOf did not move while claims changed (stale marker)"
LATEST_CLAIMS="$(sqlc "SELECT to_char(max(created_at) AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS') FROM outbox_event")"
say "  asOf on the dashboard: $ASOF1; newest change in the claims database: $LATEST_CLAIMS UTC"

section "Restart Kafka: drain"
sqlc "SELECT event_id FROM outbox_event WHERE published_at IS NULL ORDER BY id" > "$_TMP/backlog.ids"
ORDER_FROM="$(sqlc "SELECT coalesce(min(id),0) FROM outbox_event WHERE published_at IS NULL")"
T0="$(now)"
"${DC[@]}" up -d --no-build kafka >/dev/null 2>&1
kafka_healthy() { [ "$(docker inspect -f '{{.State.Health.Status}}' "$("${DC[@]}" ps -q kafka)" 2>/dev/null)" = healthy ]; }
wait_until 180 "kafka healthy" kafka_healthy || die "kafka did not become healthy"
T_KAFKA_UP="$(since "$T0")"
wait_until 180 "outbox drained (backlog $BACKLOG -> 0)" outbox_empty || die "backlog did not drain"
T_DRAIN="$(since "$T0")"
say "  kafka healthy after ${T_KAFKA_UP}s; backlog of $BACKLOG rows drained ${T_DRAIN}s after the start command"
"${DC[@]}" logs --no-color --since "$T_STOP" claims-service 2>&1 | grep -i "recovered" | head -2 | sed 's/^/    /'
wait_synced 120 || die "projection did not catch up"
T_SYNC="$(since "$T0")"

section "Order"
assert_eq "$(sqlc "SELECT count(*) FROM outbox_event a JOIN outbox_event b ON b.id > a.id AND b.published_at < a.published_at WHERE a.id >= $ORDER_FROM")" 0 "no later outbox row was marked published before an earlier one (backlog rows ids >= $ORDER_FROM)"
topic_dump_after "$TOPIC" "$_TMP/ends.before" "$_TMP/topic.tsv"
# per claim, in topic order, claimVersion must never go backwards
BAD="$(cut -f2 "$_TMP/topic.tsv" | jq -r '[.claimId,.claimVersion]|@tsv' | awk -F'\t' '{ if (($1 in last) && $2 < last[$1]) bad++; last[$1]=$2 } END{print bad+0}')"
assert_eq "$BAD" 0 "per claim, versions never go backwards on the topic (no reordering) in the records published after the outage"
cut -f2 "$_TMP/topic.tsv" | jq -r .eventId | sort | uniq -c | awk '{print $2" "$1}' > "$_TMP/evcount"
NB="$(wc -l < "$_TMP/backlog.ids")"
SEEN1="$(awk 'NR==FNR{c[$1]=$2; next} c[$1]==1 {n++} END{print n+0}' "$_TMP/evcount" "$_TMP/backlog.ids")"
assert_eq "$SEEN1" "$NB" "each of the $NB backlog events is on the topic exactly once (none lost, none duplicated by the drain)"
assert_eq "$(wc -l < "$_TMP/topic.tsv" | tr -d ' ')" "$NB" "the drain published exactly the backlog: $NB records, no extra, none missing"
ASOF2="$(dash_asof)"
if [ "$ASOF2" != "$ASOF0" ]; then pass "asOf advanced after recovery ($ASOF0 -> $ASOF2)"; else fail "asOf did not advance after recovery"; fi

run_reconcile
summary "kafka-down: backlog $BACKLOG outbox rows from $WL_CMDS_N commands; kafka healthy ${T_KAFKA_UP}s, drained ${T_DRAIN}s, projection synced ${T_SYNC}s after start; health UP throughout; asOf stale then advanced"
finish
