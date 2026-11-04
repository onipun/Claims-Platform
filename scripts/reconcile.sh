#!/usr/bin/env bash
# Independent reconciliation. Shares no code with the services: figures come from the ledger (financial_entry) and are
# computed in SQL, then compared with the insights projection and the /exposure API. All comparisons are numeric, in SQL.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_env WEB_URL CLAIMS_DB_URL INSIGHTS_DB_URL

PSQL=(psql -X -v ON_ERROR_STOP=1 -tA -F '|')
# q DB_URL SQL : run SQL, print rows. A psql failure returns non-zero with the error on stderr. It is called inside
# $(...), where fail/die would only change a subshell's counters, so the CALLER must check: X="$(q ...)" || die "...".
q() {
  local out err
  err="$(mktemp -p "$_TMP")"
  if ! out="$("${PSQL[@]}" "$1" -c "$2" 2>"$err")"; then
    printf 'psql failed: %s\n' "$(head -c 400 "$err")" >&2
    return 1
  fi
  printf '%s' "$out"
}

OPEN="status NOT IN ('SETTLED','REJECTED')"

# Per market/line/currency, from the ledger. Status/market/line/currency/reserve_status come from the claim table only.
LEDGER_SQL="
WITH lr AS (SELECT DISTINCT ON (claim_id) claim_id, amount FROM financial_entry WHERE entry_type='RESERVE_SET' ORDER BY claim_id, id DESC),
     pay AS (SELECT claim_id, sum(amount) AS amount FROM financial_entry WHERE entry_type='PAYMENT' GROUP BY claim_id),
     c AS (SELECT cl.market_code AS market, cl.line, cl.currency, cl.status, cl.reserve_status,
                  coalesce(lr.amount,0) AS r, coalesce(pay.amount,0) AS p
           FROM claim cl LEFT JOIN lr ON lr.claim_id=cl.id LEFT JOIN pay ON pay.claim_id=cl.id),
     g AS (SELECT market, line, currency,
                  count(*) FILTER (WHERE $OPEN) AS open_claims,
                  coalesce(sum(greatest(r-p,0)) FILTER (WHERE $OPEN AND reserve_status='PROVISIONAL'),0) AS provisional,
                  coalesce(sum(greatest(r-p,0)) FILTER (WHERE $OPEN AND reserve_status<>'PROVISIONAL'),0) AS assessed,
                  coalesce(sum(p),0) AS paid
           FROM c GROUP BY market, line, currency)
SELECT market, line, currency, open_claims, provisional, assessed, provisional+assessed, paid, provisional+assessed+paid FROM g ORDER BY 1,2,3"

# Same shape from the projection (its own status/reserve_status/reserve/paid columns).
PROJ_SQL="
WITH c AS (SELECT market, line, currency, status, reserve_status, reserve AS r, paid AS p FROM claim_projection),
     g AS (SELECT market, line, currency,
                  count(*) FILTER (WHERE $OPEN) AS open_claims,
                  coalesce(sum(greatest(r-p,0)) FILTER (WHERE $OPEN AND reserve_status='PROVISIONAL'),0) AS provisional,
                  coalesce(sum(greatest(r-p,0)) FILTER (WHERE $OPEN AND reserve_status<>'PROVISIONAL'),0) AS assessed,
                  coalesce(sum(p),0) AS paid
           FROM c GROUP BY market, line, currency)
SELECT market, line, currency, open_claims, provisional, assessed, provisional+assessed, paid, provisional+assessed+paid FROM g ORDER BY 1,2,3"

# ---------- 1. wait for outbox drain ----------
section "Wait: outbox drained"
outbox_empty() { local n; n="$(q "$CLAIMS_DB_URL" "SELECT count(*) FROM outbox_event WHERE published_at IS NULL")" || return 1; [ "$n" = 0 ]; }
wait_ready 120
wait_until 60 "claims outbox_event unpublished = 0" outbox_empty || true

# ---------- 2. per-claim snapshot from claims DB (ledger-derived) ----------
snapshot() {
  q "$CLAIMS_DB_URL" "
WITH lr AS (SELECT DISTINCT ON (claim_id) claim_id, amount FROM financial_entry WHERE entry_type='RESERVE_SET' ORDER BY claim_id, id DESC),
     pay AS (SELECT claim_id, sum(amount) AS amount FROM financial_entry WHERE entry_type='PAYMENT' GROUP BY claim_id)
SELECT cl.id, cl.claim_number, cl.version, cl.status, cl.reserve_status, coalesce(lr.amount,0), coalesce(pay.amount,0), cl.reserve, cl.paid
FROM claim cl LEFT JOIN lr ON lr.claim_id=cl.id LEFT JOIN pay ON pay.claim_id=cl.id ORDER BY cl.claim_number"
}
# claim_values : VALUES rows for embedding in a query against the insights DB
claim_values() {
  snapshot | awk -F'|' 'NF==9 {printf "%s('"'"'%s'"'"'::uuid,'"'"'%s'"'"',%s::bigint,'"'"'%s'"'"','"'"'%s'"'"',%s::numeric,%s::numeric)", (n++?",":""), $1,$2,$3,$4,$5,$6,$7}'
}
# per-claim differences (empty output = none). Columns: claim_number|what|claims-side|projection-side
claim_diffs() {
  local vals; vals="$(claim_values)" || return 1   # pipefail: a failed snapshot query fails here
  [ -n "$vals" ] || { echo "no claims found in claims DB"; return 0; }
  q "$INSIGHTS_DB_URL" "
WITH s(id, claim_number, version, status, reserve_status, l_reserve, l_paid) AS (VALUES $vals),
d AS (
  SELECT s.claim_number, 'MISSING IN PROJECTION' AS what, 'present' AS c_side, 'absent' AS p_side FROM s LEFT JOIN claim_projection p ON p.claim_id=s.id WHERE p.claim_id IS NULL
  UNION ALL SELECT p.claim_number, 'EXTRA IN PROJECTION', 'absent', 'present' FROM claim_projection p LEFT JOIN s ON s.id=p.claim_id WHERE s.id IS NULL
  UNION ALL SELECT s.claim_number, 'version', s.version::text, p.version::text FROM s JOIN claim_projection p ON p.claim_id=s.id WHERE s.version<>p.version
  UNION ALL SELECT s.claim_number, 'status', s.status, p.status FROM s JOIN claim_projection p ON p.claim_id=s.id WHERE s.status<>p.status
  UNION ALL SELECT s.claim_number, 'reserve_status', s.reserve_status, p.reserve_status FROM s JOIN claim_projection p ON p.claim_id=s.id WHERE s.reserve_status<>p.reserve_status
  UNION ALL SELECT s.claim_number, 'reserve', s.l_reserve::text, p.reserve::text FROM s JOIN claim_projection p ON p.claim_id=s.id WHERE s.l_reserve<>p.reserve
  UNION ALL SELECT s.claim_number, 'paid', s.l_paid::text, p.paid::text FROM s JOIN claim_projection p ON p.claim_id=s.id WHERE s.l_paid<>p.paid)
SELECT * FROM d ORDER BY 1,2"
}
no_claim_diffs() { local d; d="$(claim_diffs)" || return 1; [ -z "$d" ]; }

section "Wait: projection caught up (version per claim)"
wait_until 60 "claim.version = claim_projection.version for every claim" no_claim_diffs || true

# ---------- 3. ledger integrity: claim row vs ledger-derived ----------
section "Ledger integrity (claim row vs ledger)"
N_CLAIMS="$(q "$CLAIMS_DB_URL" "SELECT count(*) FROM claim")" || die "cannot count claims"
SQLBAD="$(q "$CLAIMS_DB_URL" "
WITH lr AS (SELECT DISTINCT ON (claim_id) claim_id, amount FROM financial_entry WHERE entry_type='RESERVE_SET' ORDER BY claim_id, id DESC),
     pay AS (SELECT claim_id, sum(amount) AS amount FROM financial_entry WHERE entry_type='PAYMENT' GROUP BY claim_id)
SELECT cl.claim_number, 'claim.reserve='||cl.reserve||' ledger.reserve='||coalesce(lr.amount,0)||' claim.paid='||cl.paid||' ledger.paid='||coalesce(pay.amount,0)
FROM claim cl LEFT JOIN lr ON lr.claim_id=cl.id LEFT JOIN pay ON pay.claim_id=cl.id
WHERE cl.reserve<>coalesce(lr.amount,0) OR cl.paid<>coalesce(pay.amount,0) ORDER BY 1")" || die "ledger integrity query failed"
if [ -z "$SQLBAD" ]; then pass "all $N_CLAIMS claims: claim.reserve and claim.paid equal ledger-derived values"
else fail "ledger integrity violated" ; printf '%s\n' "$SQLBAD" | sed 's/^/        /'; fi

# ---------- 4. three-way comparison ----------
section "Three-way comparison: ledger (claims DB) vs projection (insights DB) vs API /exposure"
login T_APAC manager.apac@demo.test
api "$T_APAC" GET /api/v1/insights/exposure
assert_status 200 "GET /exposure as manager.apac"
API_ROWS="$(printf '%s' "$BODY" | jq -r '.rows[]|[.market,.line,.currency,.openClaims,.provisional,.assessed,.caseOutstanding,.paid,.incurred]|join("|")')"

# to_values SRC : rows from stdin -> VALUES tuples tagged with SRC (numeric cast happens in SQL)
to_values() {
  awk -F'|' -v src="$1" 'NF==9 {printf "%s('"'"'%s'"'"','"'"'%s'"'"','"'"'%s'"'"','"'"'%s'"'"',%s::numeric,%s::numeric,%s::numeric,%s::numeric,%s::numeric,%s::numeric)", (n++?",":""), src,$1,$2,$3,$4,$5,$6,$7,$8,$9}'
}
LEDGER_ROWS="$(q "$CLAIMS_DB_URL" "$LEDGER_SQL")" || die "ledger query failed"
PROJ_ROWS="$(q "$INSIGHTS_DB_URL" "$PROJ_SQL")" || die "projection query failed"
ALL_VALUES=""
for pair in "ledger:$LEDGER_ROWS" "projection:$PROJ_ROWS" "api:$API_ROWS"; do
  v="$(printf '%s\n' "${pair#*:}" | to_values "${pair%%:*}")"
  [ -n "$v" ] && ALL_VALUES="${ALL_VALUES:+$ALL_VALUES,}$v"
done
[ -n "$ALL_VALUES" ] || die "no exposure rows from any source"

THREEWAY="
WITH v(src, market, line, currency, open_claims, provisional, assessed, outstanding, paid, incurred) AS (VALUES $ALL_VALUES),
u AS (SELECT src, market, line, currency, m.metric, m.val
      FROM v CROSS JOIN LATERAL (VALUES ('open',open_claims),('provisional',provisional),('assessed',assessed),
                                        ('outstanding',outstanding),('paid',paid),('incurred',incurred)) AS m(metric,val)),
k AS (SELECT DISTINCT market, line, currency, metric FROM u)
SELECT k.market, k.line, k.currency, k.metric,
       coalesce(l.val,0) AS ledger, coalesce(p.val,0) AS projection, coalesce(a.val,0) AS api,
       CASE WHEN coalesce(l.val,0)=coalesce(p.val,0) AND coalesce(l.val,0)=coalesce(a.val,0) THEN 'ok' ELSE 'DIFF' END AS verdict
FROM k LEFT JOIN u l ON (l.src,l.market,l.line,l.currency,l.metric)=('ledger',k.market,k.line,k.currency,k.metric)
       LEFT JOIN u p ON (p.src,p.market,p.line,p.currency,p.metric)=('projection',k.market,k.line,k.currency,k.metric)
       LEFT JOIN u a ON (a.src,a.market,a.line,a.currency,a.metric)=('api',k.market,k.line,k.currency,k.metric)
ORDER BY k.market, k.line, k.currency, array_position(ARRAY['open','provisional','assessed','outstanding','paid','incurred'], k.metric)"

TABLE="$(q "$CLAIMS_DB_URL" "$THREEWAY")" || die "three-way comparison query failed"
printf '\n  %-3s %-9s %-4s %-12s %16s %16s %16s  %s\n' MKT LINE CCY METRIC LEDGER PROJECTION API VERDICT
printf '%s\n' "$TABLE" | awk -F'|' 'NF==8 {printf "  %-3s %-9s %-4s %-12s %16.2f %16.2f %16.2f  %s\n",$1,$2,$3,$4,$5,$6,$7,$8}'
NROWS="$(printf '%s\n' "$TABLE" | awk -F'|' 'NF==8' | wc -l)"
DIFFS="$(printf '%s\n' "$TABLE" | awk -F'|' '$8=="DIFF" {printf "%s/%s/%s %s: ledger=%s projection=%s api=%s\n",$1,$2,$3,$4,$5,$6,$7}')"
if [ "$NROWS" -gt 0 ] && [ -z "$DIFFS" ]; then pass "three-way: $NROWS figures identical across ledger, projection and API"
else fail "three-way figures differ"; printf '%s\n' "$DIFFS" | sed 's/^/        /'; [ "$NROWS" -gt 0 ] || fail "three-way table is empty"; fi

# ---------- 5. per-claim projection vs claims (names the row) ----------
section "Per-claim: claims DB vs projection"
CD="$(claim_diffs)" || die "per-claim comparison query failed"
if [ -z "$CD" ]; then pass "all $N_CLAIMS claims present in projection with equal version, status, reserve_status, reserve and paid"
else
  fail "per-claim differences between claims DB and projection"
  printf '%s\n' "$CD" | awk -F'|' '{printf "        claim %s: %s claims-db=%s projection=%s\n",$1,$2,$3,$4}'
fi
OB="$(q "$CLAIMS_DB_URL" "SELECT count(*) FROM outbox_event WHERE published_at IS NULL")" || die "outbox count query failed"
assert_eq "$OB" 0 "outbox_event unpublished count"

finish
