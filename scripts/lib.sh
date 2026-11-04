#!/usr/bin/env bash
# Shared helpers for smoke.sh and reconcile.sh. Source it; do not execute it.
# Nothing here swallows a failure: every check records PASS or FAIL, and finish() exits non-zero if any FAIL.

PASS=0
FAIL=0
FAILED_NAMES=()
STATUS=""      # HTTP status of the last api call
BODY=""        # response body of the last api call
HDRS=""        # response headers of the last api call
PASSWORD="${PASSWORD:-Password1!}"
_TMP="$(mktemp -d)"
trap 'rm -rf "$_TMP"' EXIT

say()  { printf '%s\n' "$*"; }
pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); FAILED_NAMES+=("$1"); printf '  FAIL  %s\n' "$1"; [ $# -gt 1 ] && printf '        %s\n' "${@:2}"; return 0; }
section() { printf '\n== %s\n' "$*"; }

# Abort immediately (used when later steps cannot make sense, e.g. no claim id).
die() { printf 'FATAL: %s\n' "$*" >&2; FAIL=$((FAIL + 1)); FAILED_NAMES+=("FATAL: $*"); finish; }

finish() {
  printf '\n----------------------------------------\n'
  printf 'RESULT: %d passed, %d failed\n' "$PASS" "$FAIL"
  if [ "$FAIL" -ne 0 ]; then
    printf 'Failed checks:\n'
    # the guard keeps an empty array from tripping `set -u` on bash older than 4.4
    if [ "${#FAILED_NAMES[@]}" -gt 0 ]; then printf '  - %s\n' "${FAILED_NAMES[@]}"; fi
    exit 1
  fi
  exit 0
}

# api TOKEN METHOD PATH [JSON_BODY|-] [extra curl args...]
# TOKEN "-" means no Authorization header. Sets STATUS, BODY, HDRS. Never exits the script.
api() {
  local token="$1" method="$2" path="$3" data="${4:--}"; shift 4 2>/dev/null || shift $#
  local args=(-sS -m 30 -X "$method" -o "$_TMP/body" -D "$_TMP/hdrs" -w '%{http_code}')
  [ "$token" != "-" ] && args+=(-H "Authorization: Bearer $token")
  if [ "$data" != "-" ]; then args+=(-H 'Content-Type: application/json' --data "$data"); fi
  STATUS="$(curl "${args[@]}" "$@" "${WEB_URL%/}$path" 2>"$_TMP/err")" || { STATUS="000"; BODY="$(cat "$_TMP/err")"; HDRS=""; return 0; }
  BODY="$(cat "$_TMP/body")"
  HDRS="$(cat "$_TMP/hdrs")"
}

# header NAME -> value of that response header from the last call (case-insensitive)
header() { printf '%s' "$HDRS" | tr -d '\r' | awk -F': ' -v h="$(printf '%s' "$1" | tr A-Z a-z)" 'tolower($1)==h {sub(/^[^:]*: /,""); print; exit}'; }

# jqb FILTER -> evaluate on last BODY (raw output). Empty string if null/invalid.
jqb() { printf '%s' "$BODY" | jq -r "($1) | if . == null then empty else . end" 2>/dev/null; }

assert_status() { # EXPECTED NAME
  if [ "$STATUS" = "$1" ]; then pass "$2 (HTTP $STATUS)"; else fail "$2: expected HTTP $1, got $STATUS" "body: ${BODY:0:300}"; fi
}
assert_eq() { # ACTUAL EXPECTED NAME
  if [ "$1" = "$2" ]; then pass "$3 ($1)"; else fail "$3: expected '$2', got '$1'"; fi
}
# numeric equality: 0 == 0.00, within half a cent
num_eq() { awk -v a="${1:-0}" -v b="${2:-0}" 'BEGIN{d=a-b; if(d<0)d=-d; exit !(d<0.005)}'; }
# is_num VALUE : true only for a plain decimal number (optional minus, optional fraction)
is_num() { [[ "${1:-}" =~ ^-?[0-9]+(\.[0-9]+)?$ ]]; }
# A missing field comes back from jqb as "", and num_eq treats "" as 0, so an absent value used to pass
# as zero. Both sides must be numbers before they are compared.
assert_num() { # ACTUAL EXPECTED NAME
  if ! is_num "${1:-}"; then fail "$3: expected $2, got a non-number '${1:-}'"
  elif ! is_num "${2:-}"; then fail "$3: the expected value '${2:-}' is not a number"
  elif num_eq "$1" "$2"; then pass "$3 ($1)"
  else fail "$3: expected $2, got $1"; fi
}
assert_code() { # EXPECTED_PROBLEM_CODE NAME  (checks the stable `code` field of the last body)
  assert_eq "$(jqb .code)" "$1" "$2 problem code"
}
assert_true() { # CONDITION_EXIT NAME  -> use: assert_true "$?" ...  (rarely used)
  if [ "$1" = 0 ]; then pass "$2"; else fail "$2"; fi
}
assert_absent() { # JQ_PATH_EXPR NAME : fail if the filter finds a non-null value in BODY
  if printf '%s' "$BODY" | jq -e "$1" >/dev/null 2>&1; then fail "$2: unexpectedly present"; else pass "$2"; fi
}

# login VARNAME EMAIL : stores the access token in VARNAME. Failure is recorded and fatal.
login() {
  api - POST /api/v1/auth/login "{\"email\":\"$2\",\"password\":\"$PASSWORD\"}"
  local tok; tok="$(jqb .accessToken)"
  if [ "$STATUS" = 200 ] && [ -n "$tok" ]; then pass "login $2"; printf -v "$1" '%s' "$tok"
  else fail "login $2: HTTP $STATUS" "${BODY:0:200}"; die "cannot continue without token for $2"; fi
}

# wait_until TIMEOUT_SECS DESCRIPTION CMD... : poll CMD (every 1s) until it exits 0. Clear failure on timeout.
wait_until() {
  local limit="$1" desc="$2"; shift 2
  local start=$SECONDS
  while ! "$@" >/dev/null 2>&1; do
    if [ $((SECONDS - start)) -ge "$limit" ]; then
      fail "timeout after ${limit}s waiting for: $desc"; return 1
    fi
    sleep 1
  done
  pass "ready: $desc (after $((SECONDS - start))s)"
}

# Readiness: the gateway serves the JWKS (claims-service up) and insights answers 401 without a token (up and secured).
_web_ready() {
  [ "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "${WEB_URL%/}/api/v1/.well-known/jwks.json")" = 200 ] &&
  [ "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "${WEB_URL%/}/api/v1/insights/exposure")" = 401 ]
}
wait_ready() { wait_until "${1:-120}" "stack ready behind $WEB_URL" _web_ready || finish; }

require_env() {
  local v missing=0
  for v in "$@"; do [ -n "${!v:-}" ] || { printf 'FATAL: env %s not set\n' "$v" >&2; missing=1; }; done
  [ "$missing" = 0 ] || exit 2
}
