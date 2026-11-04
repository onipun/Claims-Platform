#!/usr/bin/env bash
# End-to-end smoke test through the nginx gateway only. Rerunnable: unique idempotency keys, deltas not absolutes.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_env WEB_URL

RUN="$(date +%s)-$RANDOM"
# 3h ago, naive time: SG is UTC+8, so this is never "in the future" in the market zone.
INCIDENT="$(date -u -d @$(( $(date +%s) - 10800 )) +%Y-%m-%dT%H:%M:%S)"
# 1x1 PNG, base64: no extra tools needed
PNG_B64='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='
printf '%s' "$PNG_B64" | base64 -d > "$_TMP/evidence.png"
SHA_UP="$(sha256sum "$_TMP/evidence.png" | cut -d' ' -f1)"

section "Readiness"
wait_ready 120

section "Login"
login T_CLAIMANT claimant.sg@demo.test
login T_CLAIMANT2 claimant2.sg@demo.test
login T_OFFICER officer.sg@demo.test
login T_OFFICER_HK officer.hk@demo.test
login T_MANAGER manager.sg@demo.test
login T_APAC manager.apac@demo.test

# ---------- helpers ----------
# exposure_row -> "open provisional assessed paid" for SG/MOTOR/SGD as manager.apac (zeros if row absent)
exposure_row() {
  api "$T_APAC" GET /api/v1/insights/exposure || return 1
  [ "$STATUS" = 200 ] || return 1
  local row f
  row="$(printf '%s' "$BODY" | jq -r '[.rows[]|select(.market=="SG" and .line=="MOTOR" and .currency=="SGD")][0]
    | if . == null then "0 0 0 0" else "\(.openClaims) \(.provisional) \(.assessed) \(.paid)" end')" || return 1
  # a missing or non-numeric field must not become a baseline of "null" or ""
  local -a fields; read -r -a fields <<<"$row"
  [ "${#fields[@]}" -eq 4 ] || return 1
  for f in "${fields[@]}"; do is_num "$f" || return 1; done
  printf '%s' "$row"
}
# exposure_is dOpen dProv dAssess dPaid : true when current row == BASE + deltas
exposure_is() {
  local cur; cur="$(exposure_row)" || return 1
  local o p a d; read -r o p a d <<<"$cur"
  local bo bp ba bd; read -r bo bp ba bd <<<"$BASE"
  [ "$o" = "$((bo + $1))" ] && num_eq "$p" "$(awk -v b="$bp" -v d="$2" 'BEGIN{print b+d}')" \
    && num_eq "$a" "$(awk -v b="$ba" -v d="$3" 'BEGIN{print b+d}')" && num_eq "$d" "$(awk -v b="$bd" -v d="$4" 'BEGIN{print b+d}')"
}

# submit_motor TOKEN KEY ESTIMATE -> sets STATUS/BODY; claim fields in CID / CNUM on 200/201
submit_motor() {
  local body; body="$(jq -nc --arg pid "$POLICY_ID" --arg est "$3" --arg inc "$INCIDENT" '{
    policyId:$pid, line:"MOTOR", claimType:"OWN_DAMAGE", estimate:$est, incidentAtLocal:$inc,
    contactPhone:"+6591234567", description:"Smoke test collision",
    motor:{vehicleRegistration:"SMK1234X", driverName:"Alicia Tan", location:"Orchard Road junction"}}')"
  api "$1" POST /api/v1/claimant/claims "$body" -H "Idempotency-Key: $2"
  CID="$(jqb .id)"; CNUM="$(jqb .claimNumber)"
}

# staff_fetch TOKEN ID : GET staff detail, sets IFM (If-Match value from ETag, else quoted version)
staff_fetch() {
  api "$1" GET "/api/v1/staff/claims/$2"
  IFM="$(header etag)"; [ -n "$IFM" ] || IFM="\"$(jqb .version)\""
}
# staff_act TOKEN ID SUBPATH JSON [EXPECT_STATUS] : fresh ETag then POST. Asserts the status (default 200).
staff_act() {
  local want="${5:-200}"
  staff_fetch "$1" "$2"
  api "$1" POST "/api/v1/staff/claims/$2/$3" "${4:--}" -H "If-Match: $IFM"
  assert_status "$want" "staff $3"
}
claim_status() { # TOKEN ID -> staff status
  api "$1" GET "/api/v1/staff/claims/$2"; jqb .status
}
# in_queue ID : page through the unassigned queue as officer; return 0 if found
in_queue() {
  local page=0 pages=1
  while [ "$page" -lt "$pages" ]; do
    api "$T_OFFICER" GET "/api/v1/staff/claims?queue=unassigned&size=100&page=$page" || return 1
    [ "$STATUS" = 200 ] || return 1
    printf '%s' "$BODY" | jq -e --arg id "$1" '.items[]|select(.id==$id)' >/dev/null && return 0
    pages="$(jqb .totalPages)"; pages="${pages:-1}"; page=$((page + 1))
  done
  return 1
}

section "Policies"
api "$T_CLAIMANT" GET /api/v1/claimant/policies
assert_status 200 "claimant lists policies"
POLICY_ID="$(printf '%s' "$BODY" | jq -r '[.[]|select(.marketCode=="SG" and .line=="MOTOR")][0].id // empty')"
[ -n "$POLICY_ID" ] || die "no SG MOTOR policy for claimant.sg"
assert_eq "$(printf '%s' "$BODY" | jq -r '[.[]|select(.id=="'"$POLICY_ID"'")][0].currency')" SGD "policy currency"

section "Dashboard baseline"
BASE="$(exposure_row)" || die "cannot read /insights/exposure as manager.apac (no response, a non-200 status, or a non-numeric field)"
say "  baseline (open provisional assessed paid) SG/MOTOR/SGD = $BASE"

# =====================================================================
section "Happy journey"
submit_motor "$T_CLAIMANT" "smoke-A-$RUN" 8000.00
assert_status 201 "submit claim A"
A="$CID"; A_NUM="$CNUM"
[ -n "$A" ] || die "claim A has no id"
case "$A_NUM" in CLM-SG-*) pass "claim number format ($A_NUM)";; *) fail "claim number format: $A_NUM";; esac
assert_eq "$(jqb .status)" SUBMITTED "A status after submit"

submit_motor "$T_CLAIMANT" "smoke-A-$RUN" 8000.00
assert_status 200 "replay with same Idempotency-Key"
assert_eq "$CID" "$A" "replay returns same claim id"
assert_eq "$CNUM" "$A_NUM" "replay returns same claim number"

# upload + download, sha256 compared
api "$T_CLAIMANT" POST "/api/v1/claimant/claims/$A/documents?documentType=VEHICLE_REGISTRATION_CARD" - \
  -F "file=@$_TMP/evidence.png;type=image/png;filename=evidence.png"
assert_status 201 "upload PNG"
DOC="$(jqb .id)"
[ -n "$DOC" ] || die "upload returned no document id"
assert_eq "$(jqb .contentType)" image/png "stored content type"
curl -sS -m 30 -o "$_TMP/down.bin" -w '%{http_code}' -H "Authorization: Bearer $T_CLAIMANT" \
  "${WEB_URL%/}/api/v1/claimant/claims/$A/documents/$DOC/content" > "$_TMP/dl_status"
assert_eq "$(cat "$_TMP/dl_status")" 200 "download document HTTP status"
assert_eq "$(sha256sum "$_TMP/down.bin" | cut -d' ' -f1)" "$SHA_UP" "downloaded sha256 equals uploaded"

# upload size through the gateway: nginx (11 MB) must let the API's own 10 MB limit decide
MAX=$((10 * 1024 * 1024))
make_pdf() { { printf '%%PDF-1.7\n'; head -c "$(($1 - 9))" /dev/zero; } > "$2"; }
make_pdf $((MAX - 1)) "$_TMP/just-under.pdf"
make_pdf $((MAX + 1)) "$_TMP/just-over.pdf"
assert_eq "$(stat -c %s "$_TMP/just-under.pdf")" $((MAX - 1)) "test file just under 10 MB has the intended size"
assert_eq "$(stat -c %s "$_TMP/just-over.pdf")" $((MAX + 1)) "test file of 10 MB + 1 byte has the intended size"
api "$T_CLAIMANT" POST "/api/v1/claimant/claims/$A/documents?documentType=OTHER" - \
  -F "file=@$_TMP/just-under.pdf;type=application/pdf;filename=just-under.pdf"
assert_status 201 "upload just under 10 MB through the gateway"
api "$T_CLAIMANT" POST "/api/v1/claimant/claims/$A/documents?documentType=OTHER" - \
  -F "file=@$_TMP/just-over.pdf;type=application/pdf;filename=just-over.pdf"
assert_status 413 "upload of 10 MB + 1 byte through the gateway"
assert_code PAYLOAD_TOO_LARGE "10 MB + 1 byte upload (the API's problem, not nginx's HTML page)"
case "$(header content-type)" in application/problem+json*) pass "413 body is problem+json";; *) fail "413 body is not problem+json" "content-type: $(header content-type)";; esac

# dashboard: A is open with provisional 8000 - 500 deductible = 7500
wait_until 30 "exposure shows A (open +1, provisional +7500)" exposure_is 1 7500 0 0

# officer finds in unassigned queue
if in_queue "$A"; then pass "A in officer's unassigned queue"; else fail "A not found in unassigned queue"; fi

staff_act "$T_OFFICER" "$A" pick-up - 200
assert_eq "$(jqb .status)" UNDER_REVIEW "A status after pick-up"
assert_eq "$(jqb .ledger.reserve)" 7500.00 "A provisional reserve = estimate - deductible"
assert_eq "$(jqb .ledger.reserveStatus)" PROVISIONAL "A reserve status"

NOTE_TEXT="INTERNAL-ONLY-NOTE-$RUN"
staff_act "$T_OFFICER" "$A" notes "{\"text\":\"$NOTE_TEXT\"}" 200
staff_act "$T_OFFICER" "$A" info-requests '{"question":"Please confirm the workshop name."}' 200
assert_eq "$(jqb .status)" AWAITING_INFO "A status after info request"

api "$T_CLAIMANT" GET "/api/v1/claimant/claims/$A"
assert_status 200 "claimant reads A"
assert_eq "$(jqb .actionRequired)" true "claimant actionRequired"
assert_eq "$(jqb .openQuestion.question)" "Please confirm the workshop name." "claimant sees the question"
assert_eq "$(printf '%s' "$BODY" | jq '[..|objects|keys[]|select(test("reserve|ledger|outstanding|incurred";"i"))]|length')" 0 "claimant view has no reserve/ledger fields"
if printf '%s' "$BODY" | grep -q "$NOTE_TEXT"; then fail "claimant view leaks internal note text"; else pass "claimant view has no internal note text"; fi
api "$T_CLAIMANT" GET /api/v1/claimant/claims
assert_status 200 "claimant lists claims"
if printf '%s' "$BODY" | grep -q "$NOTE_TEXT"; then fail "claimant list leaks internal note"; else pass "claimant list has no internal note text"; fi
api "$T_CLAIMANT" GET /api/v1/claimant/updates
assert_status 200 "claimant reads updates"
if printf '%s' "$BODY" | grep -q "$NOTE_TEXT"; then fail "claimant updates leak internal note"; else pass "claimant updates have no internal note text"; fi

api "$T_CLAIMANT" GET "/api/v1/claimant/claims/$A"
CIFM="$(header etag)"; [ -n "$CIFM" ] || CIFM="\"$(jqb .version)\""
api "$T_CLAIMANT" POST "/api/v1/claimant/claims/$A/info-responses" '{"answer":"Ah Seng Motors."}' -H "If-Match: $CIFM"
assert_status 200 "claimant answers info request"
assert_eq "$(claim_status "$T_OFFICER" "$A")" UNDER_REVIEW "A status after answer"
api "$T_CLAIMANT" GET "/api/v1/claimant/claims/$A"
assert_eq "$(jqb .actionRequired)" false "actionRequired cleared"

staff_act "$T_OFFICER" "$A" assessment '{"reserve":"5000.00"}' 200
assert_eq "$(jqb .status)" ASSESSED "A status after assessment"
assert_num "$(jqb .ledger.reserve)" 5000 "A reserve after assessment"
assert_eq "$(jqb .ledger.reserveStatus)" ASSESSED "A reserve status after assessment"
staff_act "$T_OFFICER" "$A" approval '{"amount":"5000.00"}' 200
assert_eq "$(jqb .status)" APPROVED "A status after approval"
assert_num "$(jqb .ledger.approvedAmount)" 5000 "A approved amount"
staff_act "$T_OFFICER" "$A" settlement '{"amount":"5000.00"}' 200
assert_eq "$(jqb .status)" SETTLED "A status after settlement"
assert_num "$(jqb .ledger.paid)" 5000 "A paid"
assert_num "$(jqb .ledger.outstanding)" 0 "A outstanding"
assert_num "$(jqb .ledger.incurred)" 5000 "A incurred"
api "$T_CLAIMANT" GET "/api/v1/claimant/claims/$A"
assert_eq "$(jqb .status)" SETTLED "claimant sees SETTLED"
assert_num "$(jqb .decision.amount)" 5000 "claimant sees decision amount"

# =====================================================================
section "Authority path"
submit_motor "$T_CLAIMANT" "smoke-B-$RUN" 15000.00
assert_status 201 "submit claim B"
B="$CID"; [ -n "$B" ] || die "claim B has no id"
staff_act "$T_OFFICER" "$B" pick-up - 200
staff_act "$T_OFFICER" "$B" assessment '{"reserve":"12000.00"}' 200
assert_num "$(jqb .ledger.reserve)" 12000 "B reserve 12000"
staff_act "$T_OFFICER" "$B" approval '{"amount":"12000.00"}' 403
assert_code AUTHORITY_EXCEEDED "officer approval over limit"
assert_eq "$(claim_status "$T_OFFICER" "$B")" ASSESSED "B still ASSESSED after refusal"
staff_act "$T_MANAGER" "$B" approval '{"amount":"12000.00"}' 200
assert_eq "$(jqb .status)" APPROVED "B approved by manager"

# =====================================================================
section "Reject path"
submit_motor "$T_CLAIMANT" "smoke-C-$RUN" 2000.00
assert_status 201 "submit claim C"
C="$CID"; [ -n "$C" ] || die "claim C has no id"
staff_act "$T_OFFICER" "$C" pick-up - 200
EXPL="Smoke test: the damage is not covered by this policy."
staff_act "$T_OFFICER" "$C" rejection "{\"reason\":\"NOT_COVERED\",\"explanation\":\"$EXPL\"}" 200
assert_eq "$(jqb .status)" REJECTED "C status after rejection"
assert_num "$(jqb .ledger.reserve)" 0 "C reserve is 0 after rejection"
api "$T_CLAIMANT" GET "/api/v1/claimant/claims/$C"
assert_eq "$(jqb .status)" REJECTED "claimant sees REJECTED"
assert_eq "$(jqb .decision.reasonCode)" NOT_COVERED "claimant sees reason code"
assert_eq "$(jqb .decision.explanation)" "$EXPL" "claimant sees explanation"
api "$T_OFFICER" GET "/api/v1/staff/claims/$C/financials"
assert_status 200 "C financials"
assert_num "$(jqb .reserve)" 0 "C financials reserve"

# =====================================================================
section "Negative checks"
submit_motor "$T_CLAIMANT" "smoke-D-$RUN" 3000.00
assert_status 201 "submit claim D (left SUBMITTED)"
D="$CID"; [ -n "$D" ] || die "claim D has no id"
staff_fetch "$T_OFFICER" "$D"
api "$T_OFFICER" POST "/api/v1/staff/claims/$D/pick-up" - -H 'If-Match: "9999"'
assert_status 409 "stale If-Match"
assert_code STALE_VERSION "stale If-Match"
api "$T_OFFICER" POST "/api/v1/staff/claims/$D/pick-up" -
assert_status 428 "missing If-Match"
assert_code PRECONDITION_REQUIRED "missing If-Match"
assert_eq "$(claim_status "$T_OFFICER" "$D")" SUBMITTED "D unchanged by rejected commands"
api "$T_CLAIMANT2" GET "/api/v1/claimant/claims/$D"
assert_status 404 "another claimant's claim"
api "$T_OFFICER_HK" GET "/api/v1/staff/claims/$D"
assert_status 404 "HK-only staff cannot see SG claim"
api "$T_CLAIMANT" GET "/api/v1/staff/claims?queue=all"
assert_status 403 "claimant on staff endpoint"
api - GET "/api/v1/claimant/claims"
assert_status 401 "no token on claimant endpoint"
api - GET "/api/v1/staff/claims/$D"
assert_status 401 "no token on staff endpoint"

# =====================================================================
section "Dashboard eventual consistency"
# Net effect of this run vs baseline:
#   A settled: paid +5000; B approved 12000: open +1, assessed +12000; C rejected: nothing; D submitted: open +1, provisional +2500
wait_until 30 "exposure = baseline + (open +2, provisional +2500, assessed +12000, paid +5000)" exposure_is 2 2500 12000 5000
say "  final (open provisional assessed paid) = $(exposure_row)"

finish
