#!/usr/bin/env bash
# Fault 7: JWKS. Open question from review. With claims-service stopped and insights freshly restarted, insights
# cannot fetch keys. Plan 6.5 predicts dashboards return 401, recovering by themselves. Questions:
#   a) does it recover without restarting insights?   b) does the insights log say why, or is the 401 silent?
source "$(dirname "${BASH_SOURCE[0]}")/fault-lib.sh"
fault_begin jwks

dash_code() { curl -s -o "$_TMP/dbody" -D "$_TMP/dhdr" -m 8 -w '%{http_code}' -H "Authorization: Bearer $1" "$WEB_URL/api/v1/insights/exposure"; }

section "Baseline: a token, and a warm key cache in insights"
TOK="$T_APAC"
assert_eq "$(dash_code "$TOK")" 200 "dashboard with the token (insights fetches and caches the keys)"
wait_synced 60 || die "not in sync"

section "claims-service stopped, insights NOT restarted (plan: cached keys keep dashboards working)"
T_STOP="$(utc)"
"${DC[@]}" stop claims-service >/dev/null 2>&1 || die "could not stop claims-service"
sleep 8   # past nginx's 5s upstream DNS cache: the fail-fast-on-stop behaviour is fault-insights-down's subject, not this one
assert_eq "$(dash_code "$TOK")" 200 "dashboard still works with the cached keys"
api - POST /api/v1/auth/login '{"email":"claimant.sg@demo.test","password":"Password1!"}'
case "$STATUS" in 502|503|000) pass "login is down (HTTP $STATUS)";; *) fail "login returned HTTP $STATUS with claims-service stopped";; esac
api "$T_CLAIMANT" GET /api/v1/claimant/claims
case "$STATUS" in 502|503) pass "claim work is down (HTTP $STATUS)";; *) fail "claimant API returned HTTP $STATUS with claims-service stopped";; esac

section "insights-service restarted while claims-service is down (key cache empty)"
T_RESTART="$(utc)"
"${DC[@]}" restart insights-service >/dev/null 2>&1 || die "could not restart insights"
wait_until 120 "insights process healthy" bash -c "[ \"\$(curl -s -m 3 $INSIGHTS_DIRECT/actuator/health | jq -r .status)\" = UP ]" || die "insights not healthy"
# nginx may hold insights' old address for up to 5s after the restart, and answers a fast 504 meanwhile (see
# proxy_connect_timeout in web/nginx/default.conf). Only 504 is waited out, for 8s at most. The final answer must be 401.
CODE="$(dash_code "$TOK")"
for _ in 1 2 3 4; do
  [ "$CODE" = 504 ] || break
  sleep 2
  CODE="$(dash_code "$TOK")"
done
assert_eq "$CODE" 401 "dashboard answers 401 (as predicted) with a perfectly valid token"
say "  response headers:"; tr -d '\r' < "$_TMP/dhdr" | grep -i "www-authenticate\|^HTTP" | sed 's/^/    /'
say "  response body: $(head -c 300 "$_TMP/dbody")"
sleep 3; dash_code "$TOK" >/dev/null
say "  insights log since the restart:"
"${DC[@]}" logs --no-color --since "$T_RESTART" insights-service > "$_TMP/ilog" 2>&1
grep -v "sasl.oauthbearer" "$_TMP/ilog" > "$_TMP/ilog2"
grep -i -B1 -m1 "AuthenticationServiceException\|attempting to decode the Jwt\|JWK set\|jwks.json" "$_TMP/ilog2" | cut -c1-330 | sed 's/^/    /'
EXPLAIN="$(grep -ci "attempting to decode the Jwt.*jwks.json\|Couldn't retrieve remote JWK set\|jwks.json" "$_TMP/ilog2")"
if [ "$EXPLAIN" -gt 0 ]; then
  pass "insights log names the cause: $(grep -i -m1 'I/O error on GET request' "$_TMP/ilog2" | grep -o 'I/O error on GET request.*' | cut -c1-160)"
else
  fail "SILENT 401: insights logged no line naming the key fetch failure while answering 401 to a valid token (log lines since restart: $(wc -l < "$_TMP/ilog"))"
fi

section "claims-service started again: does insights recover by itself?"
T0="$(now)"
"${DC[@]}" up -d --no-build claims-service >/dev/null 2>&1
REC=0
for _ in $(seq 1 150); do
  if [ "$(dash_code "$TOK")" = 200 ]; then REC=1; break; fi
  sleep 1
done
if [ "$REC" = 1 ]; then T_REC="$(since "$T0")"; pass "dashboard returned 200 with the same token, insights NOT restarted (${T_REC}s after claims-service start)"
else T_REC="never"; fail "NO SELF-RECOVERY: still not 200 after 150s without restarting insights"; fi
TOK2=""; login TOK2 manager.apac@demo.test
assert_eq "$(dash_code "$TOK2")" 200 "a freshly issued token also works"

run_reconcile
summary "jwks: restart-with-claims-down -> 401 ($(grep -c . "$_TMP/dhdr") header lines); log explains cause: $([ "$EXPLAIN" -gt 0 ] && echo yes || echo NO); self-recovery ${T_REC}s after claims-service start"
finish
