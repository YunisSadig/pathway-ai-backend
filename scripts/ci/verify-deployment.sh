#!/usr/bin/env bash
# Waits until the deployed API reports EXPECTED_SHA, then smoke-tests it. Reads API_BASE_URL, EXPECTED_SHA and (optionally) STAGING_ACCESS_KEY from
# the environment. Never prints the key. A free instance sleeps after 15 idle minutes and takes about a minute to wake: every call is retried.
set -uo pipefail
: "${API_BASE_URL:?API_BASE_URL is required}"; : "${EXPECTED_SHA:?EXPECTED_SHA is required}"
base="${API_BASE_URL%/}"
case "$base" in https://*) ;; *) echo "API_BASE_URL must be https" >&2; exit 2;; esac
hdr=(); [ -n "${STAGING_ACCESS_KEY:-}" ] && hdr=(-H "X-Staging-Key: ${STAGING_ACCESS_KEY}")
WAIT="${VERIFY_TIMEOUT_SECONDS:-1500}"

get() { curl -sS -m 60 --retry 3 --retry-delay 5 --retry-all-errors "${hdr[@]}" -w '\n%{http_code}' "$@" 2>/dev/null; }

echo "== waiting for commit ${EXPECTED_SHA:0:7} on the deployed API (up to ${WAIT}s)"
deadline=$(( $(date +%s) + WAIT )); live=""
while [ "$(date +%s)" -lt "$deadline" ]; do
  live=$(curl -sS -m 60 "$base/actuator/info" 2>/dev/null | sed -n 's/.*"commit":"\([0-9a-f]*\)".*/\1/p')
  [ "$live" = "$EXPECTED_SHA" ] && break
  echo "   live commit: ${live:-unreachable}; retrying"; sleep 20
done
[ "$live" = "$EXPECTED_SHA" ] || { echo "FAIL: the live API does not report the merged commit (reports '${live:-nothing}')"; exit 1; }
echo "   live commit matches"

fail=0
check() {  # check <name> <expected-status> <path> [jq-ish grep]
  local out status body; out=$(get "$base$3"); status=${out##*$'\n'}; body=${out%$'\n'*}
  if [ "$status" != "$2" ]; then echo "FAIL $1: HTTP $status (expected $2)"; fail=1; return; fi
  if [ -n "${4:-}" ] && ! printf '%s' "$body" | grep -Eq "$4"; then echo "FAIL $1: body does not match /$4/"; fail=1; return; fi
  echo "ok   $1"
}
check "readiness"          200 /actuator/health/readiness '"status":"UP"'
check "program list"       200 "/api/v1/programs?size=5" '"content"|"items"|\['
check "university list"    200 "/api/v1/universities?size=5" '"content"|"items"|\['
check "discovery status"   200 /api/v1/discovery/status
check "unknown program"    404 /api/v1/programs/zz--does-not-exist
check "no env endpoint"    404 /actuator/env
[ -n "${STAGING_ACCESS_KEY:-}" ] && { code=$(curl -s -o /dev/null -m 60 -w '%{http_code}' "$base/api/v1/programs"); [ "$code" = 401 ] && echo "ok   gate closed without the key" || { echo "FAIL gate: HTTP $code without key (expected 401)"; fail=1; }; }
[ "$fail" = 0 ] && echo "== deployment verified" || { echo "== deployment verification FAILED"; exit 1; }
