#!/usr/bin/env bash
# Fault 3 (threat T16): insights-service stopped while claims are worked.
# Predicted (plan 6.5): all claim work continues, events keep flowing to Kafka, dashboards 502, and
# insights resumes from its committed offset on restart; reconcile passes.
source "$(dirname "${BASH_SOURCE[0]}")/fault-lib.sh"
fault_begin insights-down

section "Baseline"
run_workload 5 base
wait_synced 90 || die "baseline did not settle"
EXP0="$(exposure_row)"; END0="$(topic_end "$TOPIC")" || die "cannot read the topic end offsets"
committed_offsets "$_TMP/off.before_stop" || die "cannot read the group's committed offsets before the stop"
say "  committed offsets before the stop: $(tr '\n' ';' < "$_TMP/off.before_stop")"
say "  dashboard SG/MOTOR (open prov assessed paid): $EXP0; topic end offsets sum: $END0"

section "Stop insights-service"
"${DC[@]}" stop insights-service >/dev/null 2>&1 || die "could not stop insights-service"
# nginx caches the upstream's DNS answer for 5s (resolver valid=5s), so a probe straight after the stop may be sent
# to a dead address: that is the case a user hits first, and it must still fail fast.
sleep 1
T_P="$(now)"
CODE1="$(curl -s -o "$_TMP/p1" -m 20 -w '%{http_code}' -H "Authorization: Bearer $T_APAC" "$WEB_URL/api/v1/insights/exposure")"
D1="$(since "$T_P")"
# 504 is expected inside the DNS cache window: proxy_connect_timeout 2s gives up on the dead address. Must answer within 4s.
case "$CODE1" in
  502|503|504)
    if awk -v d="$D1" 'BEGIN { exit !(d + 0 < 4) }'; then pass "dashboard 1s after the stop: HTTP $CODE1 in ${D1}s (fails fast)"
    else fail "dashboard 1s after the stop: HTTP $CODE1 but took ${D1}s, limit 4s (see proxy_connect_timeout in web/nginx/default.conf)"; fi;;
  *) fail "dashboard 1s after the stop did NOT fail fast: HTTP $CODE1 after ${D1}s (expected 502/503/504 within 4s; see proxy_connect_timeout in web/nginx/default.conf)";; esac
sleep 7
T_P="$(now)"; api "$T_APAC" GET /api/v1/insights/exposure; D2="$(since "$T_P")"
case "$STATUS" in 502|503) pass "dashboard 8s after the stop: HTTP $STATUS in ${D2}s (as predicted 502/503)";; *) fail "dashboard returned HTTP $STATUS with insights stopped; expected 502 or 503";; esac
say "  body: $(printf '%s' "$BODY" | tr -d '\n' | cut -c1-120)"

section "Claims work while insights is down"
run_workload 30 down
api "$T_CLAIMANT" GET /api/v1/claimant/claims; assert_status 200 "claimant list still works"
api "$T_OFFICER" GET "/api/v1/staff/claims?queue=all"; assert_status 200 "staff list still works"
wait_until 60 "outbox drained to Kafka while insights is down" outbox_empty || die "claims side is not publishing"
END1="$(topic_end "$TOPIC")" || die "cannot read the topic end offsets"
if [ "$END1" -gt "$END0" ]; then pass "events keep flowing to Kafka: topic end offsets $END0 -> $END1 (+$((END1 - END0)))"; else fail "no new records on the topic during the outage"; fi
api "$T_APAC" GET /api/v1/insights/workload
case "$STATUS" in 502|503) pass "second dashboard endpoint also unavailable (HTTP $STATUS)";; *) fail "workload dashboard returned HTTP $STATUS";; esac
say "  claims DB signature (claims/sum of versions): $(claims_sig); projection (frozen): $(proj_sig); consumer lag: $(group_lag)"

section "Restart insights-service: catch-up"
LAG_BEFORE="$(group_lag)"
committed_offsets "$_TMP/off.down" || die "cannot read the group's committed offsets while insights is down"
offsets_not_back "while insights was down (nothing may move them)" "$_TMP/off.before_stop" "$_TMP/off.down"
T0="$(now)"
"${DC[@]}" up -d --no-build insights-service >/dev/null 2>&1
wait_until 180 "insights serves the dashboard again" bash -c "[ \"\$(curl -s -o /dev/null -m 5 -w '%{http_code}' $WEB_URL/api/v1/insights/exposure)\" = 401 ]"
T_UP="$(since "$T0")"
wait_synced 180 || die "insights did not catch up"
T_SYNC="$(since "$T0")"
say "  consumer lag before restart: $LAG_BEFORE records; insights answered after ${T_UP}s, fully caught up after ${T_SYNC}s"
api "$T_APAC" GET /api/v1/insights/exposure; assert_status 200 "dashboard back (HTTP 200)"
say "  asOf: $(jqb .asOf)"
wait_until 60 "consumer lag 0 (a partition showing '-' does not count)" lag_zero || fail "consumer lag did not reach a numeric 0 on every partition"
committed_offsets "$_TMP/off.restarted" || die "cannot read the group's committed offsets after the restart"
offsets_not_back "after the restart, against the offsets while it was down (resumes from its committed offset)" "$_TMP/off.down" "$_TMP/off.restarted"
if [ "$OFFSETS_MOVED" -gt 0 ]; then pass "insights consumed from its committed offsets after the restart ($OFFSETS_MOVED partitions advanced)"; else fail "no partition advanced after the restart although events were pending"; fi

run_reconcile
summary "insights-down: first probe HTTP $CODE1 after ${D1}s, later probe HTTP 502/503 in ${D2}s; claims unaffected ($WL_CMDS_N commands ok); topic +$((END1 - END0)) records during outage; lag $LAG_BEFORE; up ${T_UP}s, caught up ${T_SYNC}s after start"
finish
