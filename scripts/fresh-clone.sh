#!/usr/bin/env bash
# Starts the stack from a clean copy of the working tree and follows the README:
#   down -v, up --build -d --wait, smoke, reconcile, Playwright, down -v.
# There is no git history to clone, so the copy is made with tar (build output and tooling folders excluded).
# The compose project name is fixed (`name: chubb-claims` in docker-compose.yml), so the copy uses the same
# project, volumes and host ports as the original. Stop any running stack of this project first: step 1 runs
# `down -v` and deletes its data.
# Prints PASS or FAIL per step, removes the temp directory, exits non-zero if any step failed. Needs Docker.
set -uo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/chubb-fresh-clone.XXXXXX")"
DC=(docker compose)
RESULTS=()
FAILED=0
UP_DONE=0

cleanup() {
  local rc=$?
  # Always remove containers and volumes of this project if the stack was started, then the copy.
  if [ "$UP_DONE" = 1 ] && [ -d "$WORK" ]; then
    (cd "$WORK" && "${DC[@]}" --profile test down -v --remove-orphans >/dev/null 2>&1) || true
  fi
  rm -rf "$WORK"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# step NAME CMD... : runs CMD in the copy, records PASS or FAIL, never hides the exit code.
step() {
  local name="$1"; shift
  printf '\n== %s\n' "$name"
  local rc=0
  (cd "$WORK" && "$@") || rc=$?
  if [ "$rc" -eq 0 ]; then
    RESULTS+=("PASS  $name")
    printf '  PASS  %s\n' "$name"
  else
    RESULTS+=("FAIL  $name (exit $rc)")
    FAILED=$((FAILED + 1))
    printf '  FAIL  %s (exit %s)\n' "$name" "$rc"
  fi
  return "$rc"
}

summary() {
  printf '\n----------------------------------------\n'
  printf '%s\n' "${RESULTS[@]}"
  if [ "$FAILED" -ne 0 ]; then printf 'RESULT: FAIL (%d step(s) failed)\n' "$FAILED"; else printf 'RESULT: PASS\n'; fi
}

command -v docker >/dev/null 2>&1 || { echo "FAIL  docker not found" >&2; exit 2; }
docker compose version >/dev/null 2>&1 || { echo "FAIL  docker compose v2 not found" >&2; exit 2; }

printf 'Copying %s to %s\n' "$SRC" "$WORK"
step "copy working tree" bash -c "
  set -o pipefail
  cd '$SRC' && tar -cf - \
    --exclude='target' --exclude='node_modules' --exclude='dist' --exclude='.angular' --exclude='.claude' \
    --exclude='test-results' --exclude='playwright-report' . | tar -xf - -C '$WORK'
" || { summary; exit 1; }

# The copy is created from the tree; the README steps follow. Later steps run only if the stack came up,
# because smoke and the rest are meaningless otherwise. Down still runs, through the trap.
step "docker compose down -v (clean slate)" "${DC[@]}" --profile test down -v --remove-orphans
UP_DONE=1
if step "docker compose up --build -d --wait" "${DC[@]}" up --build -d --wait; then
  step "smoke" "${DC[@]}" --profile test run --rm smoke
  step "reconcile" "${DC[@]}" --profile test run --rm reconcile
  step "playwright" "${DC[@]}" --profile test run --rm playwright
else
  RESULTS+=("SKIP  smoke, reconcile, playwright (stack did not start)")
fi

if step "docker compose down -v (teardown)" "${DC[@]}" --profile test down -v --remove-orphans; then UP_DONE=0; fi

summary
[ "$FAILED" -eq 0 ]
