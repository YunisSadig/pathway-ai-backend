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

case "$MODE" in run|sync|force|bootstrap|freshness|verify|api-smoke|migrate) ;; *) die "unknown mode '$MODE' (run|sync|force|bootstrap|freshness|verify|api-smoke|migrate)";; esac

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
  # pgJDBC's verify-full looks for ~/.postgresql/root.crt and ignores the JVM trust store unless told otherwise (hosted run 43601512304 failed
  # with "Could not open SSL root certificate file"). DefaultJavaSSLFactory validates the chain against the JVM's CA bundle (Neon chains to
  # ISRG Root X1) and sslmode=verify-full still checks the host name. A caller may not supply another trust mechanism: sslfactory
  # NonValidatingFactory would silently turn verification off.
  case "$DB_URL" in *sslfactory=*|*sslrootcert=*|*sslhostnameverifier=*) die "DB_URL must not set sslfactory, sslrootcert or sslhostnameverifier: the script adds the verifying factory itself";; esac
  DB_URL="$DB_URL&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"
  export DB_URL
fi
[ "${APP_REFERENCE_DATA_ALLOW_STATIC_RECORDS:-}" != "true" ] || die "static academic records are not allowed through the unattended path"

JAR="${APP_JAR:-}"
if [ -z "$JAR" ]; then JAR="$(ls target/pathway-ai-backend-*.jar 2>/dev/null | grep -v -- '-plain' | head -1)"; fi
[ -n "$JAR" ] && [ -f "$JAR" ] || die "jar not found (build it first: ./mvnw -B -q package -DskipTests, or set APP_JAR)"

# nothing but the taxonomy and the reviewed universities may be loaded at startup, and only by `bootstrap`
export IMPORT_REFERENCE_DATA=false IMPORT_UNIVERSITY_CATALOGUE=false

LOG="$(mktemp)"; trap 'rm -f "$LOG" "$LOG.api"' EXIT
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

# api-smoke: boots the REAL API (production profile, so its guards apply) against this database with a throwaway staging key that exists
# only in this process, and reads it over HTTP. Proves the API code serves what the synchronization wrote. Prints counts only.
api_smoke() {
  local port=18080 key pid up=false code count ucount first body fail=0
  key="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  STAGING_ACCESS_KEY="$key" SPRING_PROFILES_ACTIVE=prod java -jar "$JAR" --server.port="$port" >"$LOG.api" 2>&1 &
  pid=$!
  for _ in $(seq 1 60); do
    if [ "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "http://127.0.0.1:$port/actuator/health/readiness")" = 200 ]; then up=true; break; fi
    kill -0 "$pid" 2>/dev/null || break
    sleep 3
  done
  if [ "$up" != true ]; then
    echo "api-smoke: the API did not become ready; last log lines:"; tail -n 15 "$LOG.api" | cut -c1-240; kill "$pid" 2>/dev/null; return 1
  fi
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/api/v1/programs"); echo "api-smoke: without the key -> HTTP $code (expect 401)"
  [ "$code" = 401 ] || fail=1
  body=$(curl -s -H "X-Staging-Key: $key" "http://127.0.0.1:$port/api/v1/programs?size=100")
  count=$(printf '%s' "$body" | sed -n 's/.*"totalElements":\([0-9]*\).*/\1/p'); echo "api-smoke: programs served by the API = ${count:-unreadable}"
  { [ -n "$count" ] && [ "$count" -ge 1 ]; } || fail=1
  ucount=$(curl -s -H "X-Staging-Key: $key" "http://127.0.0.1:$port/api/v1/universities?size=100" | sed -n 's/.*"totalElements":\([0-9]*\).*/\1/p')
  echo "api-smoke: universities served by the API = ${ucount:-unreadable}"
  first=$(printf '%s' "$body" | sed -n 's/.*"identifier":"\([^"]*\)".*/\1/p' | head -1)
  if [ -n "$first" ]; then
    code=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Staging-Key: $key" "http://127.0.0.1:$port/api/v1/programs/$first")
    echo "api-smoke: one program detail -> HTTP $code"; [ "$code" = 200 ] || fail=1
  fi
  echo "api-smoke: actuator/env -> HTTP $(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/actuator/env") (expect 404)"
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
  return "$fail"
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
  api-smoke) with_retries 1 1 api_smoke; rc=$? ;;
  # migrate: starts the application once with every importer off, so Flyway applies pending migrations (as the owner role) and the process ends
  migrate)   with_retries 2 "$((BASE < 60 ? BASE : 60))" java -jar "$JAR" --spring.main.web-application-type=none; rc=$? ;;
  verify)    with_retries 2 "$((BASE < 60 ? BASE : 60))" app --app.refresh.command=verify-data; rc=$? ;;
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
