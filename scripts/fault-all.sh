#!/usr/bin/env bash
# Runs every fault script in order, prints a results table, exits non-zero if any fault failed.
# FAULT_CLEAN=1  : compose down -v, then bring the stack up from empty volumes first (known clean state).
# FAULT_BUILD=1  : build images when bringing the stack up (default: use existing images).
# Otherwise the faults run incrementally on whatever stack is running (and start it if it is not).
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 2
DC=(docker compose -f docker-compose.yml -f docker-compose.fault.yml)
EVD="$REPO/docs/evidence/faults"; mkdir -p "$EVD"
FAULTS=(poller-ack kafka-down insights-down consumer-crash duplicate-reorder poison jwks)
unset FAULT_TEE
{
  echo "fault-all started $(date -u +%Y-%m-%dT%H:%M:%SZ)  mode: $([ "${FAULT_CLEAN:-0}" = 1 ] && echo 'clean (down -v, fresh volumes)' || echo incremental)"
  if [ "${FAULT_CLEAN:-0}" = 1 ]; then
    echo "-- compose down -v"; "${DC[@]}" --profile test down -v --remove-orphans 2>&1 | tail -3
  fi
  echo "-- compose up"
  BUILD=--no-build; [ "${FAULT_BUILD:-0}" = 1 ] && BUILD=--build
  "${DC[@]}" up -d $BUILD 2>&1 | tail -3
  T_UP=$SECONDS
  for i in $(seq 1 300); do
    [ "$(curl -s -o /dev/null -m 5 -w '%{http_code}' http://127.0.0.1:14200/api/v1/.well-known/jwks.json)" = 200 ] &&
    [ "$(curl -s -o /dev/null -m 5 -w '%{http_code}' http://127.0.0.1:14200/api/v1/insights/exposure)" = 401 ] && break
    sleep 1
  done
  echo "-- stack ready after $((SECONDS - T_UP))s"
  declare -A RC DUR
  for f in "${FAULTS[@]}"; do
    echo; echo "################ fault-$f ################"
    s=$SECONDS
    timeout 1200 bash "$REPO/scripts/fault-$f.sh" > "$EVD/.last-$f.out" 2>&1; RC[$f]=$?
    DUR[$f]=$((SECONDS - s))
    grep -E "^  FAIL|^FATAL|^SUMMARY|^RESULT" "$EVD/$f.txt" 2>/dev/null | sed 's/^/    /'
    echo "    -> exit ${RC[$f]} in ${DUR[$f]}s"
  done
  echo; echo "==================== RESULTS ===================="
  printf '%-18s %-6s %6s  %s\n' FAULT RESULT SECS SUMMARY
  bad=0
  for f in "${FAULTS[@]}"; do
    r=PASS; [ "${RC[$f]}" = 0 ] || { r=FAIL; bad=$((bad + 1)); }
    printf '%-18s %-6s %6s  %s\n' "$f" "$r" "${DUR[$f]}" "$(grep -m1 '^SUMMARY: ' "$EVD/$f.txt" 2>/dev/null | cut -c10-)"
  done
  echo "$bad of ${#FAULTS[@]} faults failed"
  [ "$bad" = 0 ]
} 2>&1 | tee "$EVD/ALL.txt"
exit "${PIPESTATUS[0]}"
