#!/usr/bin/env bash
# Regression test for the safety guards of scripts/ci/run-refresh.sh. It needs no jar, no database and no network: every case must be
# refused (exit 2) BEFORE Java would start, and the one positive case must get past the TLS check and stop only at the missing jar.
# Run by CI on every pull request: bash scripts/ci/test-run-refresh.sh
set -u
cd "$(dirname "$0")/../.."
S=scripts/ci/run-refresh.sh
PW="test-password-must-never-appear-in-output"
fails=0

# case <name> <expected exit> <expected message fragment> <env assignments...>
case_() {
  local name="$1" want="$2" frag="$3"; shift 3
  local out rc
  out="$(env -i PATH="$PATH" HOME="$HOME" DB_USERNAME=u DB_PASSWORD="$PW" APP_JAR=/nonexistent.jar "$@" bash "$S" run 2>&1)"; rc=$?
  local ok=true
  [ "$rc" = "$want" ] || ok=false
  printf '%s' "$out" | grep -qF -- "$frag" || ok=false
  printf '%s' "$out" | grep -qF -- "$PW" && ok=false   # the password must never be printed
  if $ok; then echo "PASS  $name"; else echo "FAIL  $name (exit $rc, wanted $want, expected message containing: $frag)"; echo "$out" | head -3 | sed 's/^/        /'; fails=$((fails+1)); fi
}

L="jdbc:postgresql://localhost:5433/pathway_ai"
R="jdbc:postgresql://db.example.com/pathway"

case_ "missing DB_URL"                                   2 "DB_URL is not set"                  DB_URL=
case_ "not a PostgreSQL URL"                             2 "must start with jdbc:postgresql"    DB_URL=jdbc:mysql://x/y
case_ "hosted runner against localhost"                  2 "localhost database"                 DB_URL="$L" GITHUB_ACTIONS=true
case_ "remote without any sslmode"                       2 "sslmode=verify-full"                DB_URL="$R"
case_ "remote with sslmode=require (encrypts, no verify)" 2 "sslmode=verify-full"                DB_URL="$R?sslmode=require"
case_ "remote with sslmode=verify-ca (no host name check)" 2 "sslmode=verify-full"               DB_URL="$R?sslmode=verify-ca"
case_ "remote with sslmode=prefer"                       2 "sslmode=verify-full"                DB_URL="$R?sslmode=prefer"
case_ "remote with ssl=true only"                        2 "sslmode=verify-full"                DB_URL="$R?ssl=true"
case_ "remote with two sslmode parameters"               2 "exactly once"                       DB_URL="$R?sslmode=verify-full&sslmode=require"
case_ "static records override"                          2 "static academic records"            DB_URL="$L" APP_REFERENCE_DATA_ALLOW_STATIC_RECORDS=true
case_ "positive: verify-full passes TLS, stops at jar"   2 "jar not found"                      DB_URL="$R?sslmode=verify-full"
case_ "positive: verify-full as a middle parameter"      2 "jar not found"                      DB_URL="$R?user=u&sslmode=verify-full&channel_binding=require"
case_ "positive: local database needs no TLS"            2 "jar not found"                      DB_URL="$L"

# in Actions the derived connection details (host, database name) are masked before anything can print them; the secrets themselves are
# masked by GitHub and must never be echoed by the script, not even inside a mask command
out="$(env -i PATH="$PATH" HOME="$HOME" DB_USERNAME=u DB_PASSWORD="$PW" APP_JAR=/nonexistent.jar GITHUB_ACTIONS=true DB_URL="$R?sslmode=verify-full" bash "$S" run 2>&1)"
for part in "db.example.com" "pathway"; do
  printf '%s' "$out" | grep -qF -- "::add-mask::$part" || { echo "FAIL  not masked in Actions logs: $part"; fails=$((fails+1)); }
done
printf '%s' "$out" | grep -qF -- "$PW" && { echo "FAIL  the password appears in the output"; fails=$((fails+1)); }
printf '%s' "$out" | grep -qF -- "jdbc:postgresql://" && { echo "FAIL  the JDBC URL appears in the output"; fails=$((fails+1)); }
[ "$fails" -eq 0 ] && echo "PASS  host and database name are masked; neither the password nor the URL is echoed"

[ "$fails" -eq 0 ] && echo "ALL GUARDS HOLD" || { echo "$fails guard test(s) FAILED"; exit 1; }
