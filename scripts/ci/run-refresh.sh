#!/usr/bin/env bash
# One unattended synchronization step against the database named by DB_URL / DB_USERNAME / DB_PASSWORD.
# The scheduled GitHub workflow and a person on a laptop run exactly this file, so what is tested locally is what runs unattended.
#
#   scripts/ci/run-refresh.sh run        refresh every source that is due (the daily mode)
#   scripts/ci/run-refresh.sh sync       discover new programs for the reviewed universities, then refresh what is due (weekly)
#   scripts/ci/run-refresh.sh force      re-read every Studyinfo program now, ignoring the schedule (testing / catch-up)
#   scripts/ci/run-refresh.sh bootstrap  first run on an empty hosted database: taxonomy + the three reviewed universities, then sync
#   scripts/ci/run-refresh.sh freshness  last-success watchdog: fails unless EVERY extractor-maintained program was read in time
#
# Environment (all optional except the three DB_ variables):
#   APP_JAR               path of the built jar (default: target/pathway-ai-backend-*.jar)
#   RETRY_ATTEMPTS        attempts per step before giving up (default 3)
#   RETRY_BASE_SECONDS    pause after failed attempt n is n * this (default 300; use 1 in tests)
#   FAILURE_RATIO         share of failed/blocked source checks above which the run fails (default 0.5)
#   MAX_AGE_HOURS         freshness threshold for the watchdog (default 240 = 10 days)
#
# Safety it enforces before starting Java (it never prints the password and never runs with `set -x`):
#   * DB_URL must be a PostgreSQL JDBC URL; a database that is not on this machine must be reached with sslmode=verify-full: the server
#     certificate AND host name are verified. The PostgreSQL JDBC driver's sslmode=require "accepts all server certificates" (it encrypts but
#     does not authenticate the server), so it is refused. Neon's certificates chain to ISRG Root X1, which Java trusts by default;
#   * a hosted runner (GITHUB_ACTIONS=true) is refused a localhost database: it cannot reach your computer and must never be pointed at it;
#   * static academic records can never come back through this path: the reference-data importers are switched off for every mode
#     except `bootstrap`, which loads only the taxonomy and the reviewed universities, and the "allow static records" override is refused.
set -uo pipefail

MODE="${1:-${REFRESH_MODE:-run}}"
ATTEMPTS="${RETRY_ATTEMPTS:-3}"
BASE="${RETRY_BASE_SECONDS:-300}"
FAILURE_RATIO="${FAILURE_RATIO:-0.5}"
MAX_AGE_HOURS="${MAX_AGE_HOURS:-240}"

die() { echo "ERROR: $*" >&2; exit "${2:-2}"; }

case "$MODE" in run|sync|force|bootstrap|freshness) ;; *) die "unknown mode '$MODE' (run|sync|force|bootstrap|freshness)";; esac

for v in DB_URL DB_USERNAME DB_PASSWORD; do
  [ -n "${!v:-}" ] || die "$v is not set (on GitHub it comes from a repository secret)"
done
case "$DB_URL" in jdbc:postgresql://*) ;; *) die "DB_URL must start with jdbc:postgresql://";; esac

host="${DB_URL#jdbc:postgresql://}"; host="${host%%[/?]*}"; host="${host%%:*}"; host="${host#\[}"; host="${host%\]}"
case "$host" in localhost|127.0.0.1|::1) is_local=true ;; *) is_local=false ;; esac
db_name="${DB_URL#jdbc:postgresql://}"; db_name="${db_name#*/}"; db_name="${db_name%%[?]*}"
# Actions logs of a PUBLIC repository are public. GitHub already masks the values of repository secrets (DB_URL, DB_USERNAME, DB_PASSWORD) but NOT
# the parts Flyway and Hikari print when they log the URL (without its query string): mask the host name and the database name too. The
# secrets themselves are deliberately not echoed here, not even inside a mask command.
if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
  for derived in "$host" "$db_name"; do
    [ -n "$derived" ] && echo "::add-mask::$derived"
  done
fi
if [ "$is_local" = true ] && [ "${GITHUB_ACTIONS:-}" = "true" ]; then
  die "refusing to run on a hosted runner against a localhost database: point DB_URL at the hosted database"
fi
if [ "$is_local" = false ]; then
  # exactly one sslmode, and it must be verify-full (certificate + host name verified)
  [ "$(printf '%s' "$DB_URL" | grep -o 'sslmode=' | wc -l)" -eq 1 ] || die "DB_URL must contain sslmode=verify-full exactly once"
  case "$DB_URL" in
    *[?\&]sslmode=verify-full|*[?\&]sslmode=verify-full\&*) ;;
    *) die "a remote database must be reached with sslmode=verify-full (sslmode=require encrypts but does not verify the server)";;
  esac
fi
[ "${APP_REFERENCE_DATA_ALLOW_STATIC_RECORDS:-}" != "true" ] || die "static academic records are not allowed through the unattended path"

JAR="${APP_JAR:-}"
if [ -z "$JAR" ]; then JAR="$(ls target/pathway-ai-backend-*.jar 2>/dev/null | grep -v -- '-plain' | head -1)"; fi
[ -n "$JAR" ] && [ -f "$JAR" ] || die "jar not found (build it first: ./mvnw -B -q package -DskipTests, or set APP_JAR)"

# nothing but the taxonomy and the reviewed universities may be loaded at startup, and only by `bootstrap`
export IMPORT_REFERENCE_DATA=false IMPORT_UNIVERSITY_CATALOGUE=false

LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT
app() { java -jar "$JAR" --spring.main.web-application-type=none "$@" 2>&1 | tee -a "$LOG"; return "${PIPESTATUS[0]}"; }

with_retries() {  # with_retries <attempts> <base> command...
  local max="$1" base="$2"; shift 2
  local n=1 rc
  while true; do
    "$@"; rc=$?
    [ "$rc" -eq 0 ] && return 0
    echo "--- attempt $n/$max of '$MODE' failed (exit $rc)" | tee -a "$LOG"
    [ "$n" -ge "$max" ] && return "$rc"
    echo "--- pausing $((base * n)) s before the next attempt" | tee -a "$LOG"
    sleep $((base * n))
    n=$((n + 1))
  done
}

started=$(date -u +%FT%TZ)
host_label="hosted database"; [ "$is_local" = true ] && host_label="local database"
echo "== Pathway AI refresh: mode=$MODE database=$host_label attempts=$ATTEMPTS started=$started"

fail_flags=(--app.refresh.fail-on-failures=true "--app.refresh.failure-ratio-threshold=$FAILURE_RATIO")
rc=0
case "$MODE" in
  run)       with_retries "$ATTEMPTS" "$BASE" app --app.refresh.command=run "${fail_flags[@]}"; rc=$? ;;
  sync)      with_retries "$ATTEMPTS" "$BASE" app --app.refresh.command=sync "${fail_flags[@]}"; rc=$? ;;
  force)     with_retries "$ATTEMPTS" "$BASE" app --app.refresh.command=studyinfo-sync "${fail_flags[@]}"; rc=$? ;;
  freshness) with_retries 2 "$((BASE < 60 ? BASE : 60))" app --app.refresh.command=freshness-check "--app.refresh.max-age-hours=$MAX_AGE_HOURS"; rc=$? ;;
  bootstrap)
    # step 1 loads only the taxonomy and the three reviewed universities (the application exits after startup), step 2 syncs the programs
    with_retries "$ATTEMPTS" "$BASE" env IMPORT_REFERENCE_DATA=true IMPORT_UNIVERSITY_CATALOGUE=true \
        java -jar "$JAR" --spring.main.web-application-type=none; rc=$?
    if [ "$rc" -eq 0 ]; then with_retries "$ATTEMPTS" "$BASE" app --app.refresh.command=sync "${fail_flags[@]}"; rc=$?; fi ;;
esac

outcome="succeeded"; [ "$rc" -eq 0 ] || outcome="FAILED (exit $rc)"
echo "== Pathway AI refresh $outcome: mode=$MODE finished=$(date -u +%FT%TZ)"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Pathway AI refresh: \`$MODE\` $outcome"
    echo "Target: \`$host_label\`; started $started UTC."
    echo '```'
    grep -E "Refresh run|Discovery|Freshness|attempt|ERROR|Exception|WARN" "$LOG" | grep -viE "password|secret|token" | tail -n 25
    echo '```'
  } >> "$GITHUB_STEP_SUMMARY"
fi
[ "$rc" -eq 0 ] || exit 1
