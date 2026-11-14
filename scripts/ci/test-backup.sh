#!/usr/bin/env bash
# Guard tests for scripts/ci/backup.sh. They need no database and no network: every case must be refused BEFORE any docker call touches data.
# Run by CI on every pull request: bash scripts/ci/test-backup.sh
set -uo pipefail
S=scripts/ci/backup.sh
PW="0123456789abcdef0123456789abcdef"
R="jdbc:postgresql://ep-x-123.eu-central-1.aws.neon.tech/pathway_ai"
fail=0

case_() {  # case_ <name> <expected exit> <expected text> <env...> -- <args...>
  local name="$1" want="$2" text="$3"; shift 3
  local out rc
  out="$(env -i PATH="$PATH" HOME="${HOME:-/tmp}" "$@" 2>&1)"; rc=$?
  if [ "$rc" -eq "$want" ] && printf '%s' "$out" | grep -q -- "$text"; then echo "PASS  $name"
  else echo "FAIL  $name (exit $rc, wanted $want / '$text')"; echo "$out" | head -3; fail=1; fi
}

case_ "no mode"                                   2 "usage"                     bash "$S"
case_ "unknown mode"                              2 "usage"                     bash "$S" wipe
case_ "no passphrase"                             2 "BACKUP_PASSPHRASE is not"  bash "$S" dump
case_ "short passphrase"                          2 "at least 24"               BACKUP_PASSPHRASE=short bash "$S" dump
case_ "dump without DB_URL"                       2 "DB_URL is not set"         BACKUP_PASSPHRASE="$PW" bash "$S" dump
case_ "not a JDBC URL"                            2 "must start with jdbc"      BACKUP_PASSPHRASE="$PW" DB_URL=postgres://x DB_USERNAME=u DB_PASSWORD=p bash "$S" dump
case_ "remote without sslmode"                    2 "sslmode=verify-full"       BACKUP_PASSPHRASE="$PW" DB_URL="$R" DB_USERNAME=u DB_PASSWORD=p bash "$S" dump
case_ "remote with sslmode=require"               2 "sslmode=verify-full"       BACKUP_PASSPHRASE="$PW" DB_URL="$R?sslmode=require" DB_USERNAME=u DB_PASSWORD=p bash "$S" dump
case_ "two sslmode parameters"                    2 "exactly once"              BACKUP_PASSPHRASE="$PW" DB_URL="$R?sslmode=verify-full&sslmode=require" DB_USERNAME=u DB_PASSWORD=p bash "$S" dump
case_ "localhost on a hosted runner"              2 "refusing to back up"       GITHUB_ACTIONS=true BACKUP_PASSPHRASE="$PW" DB_URL="jdbc:postgresql://localhost:5432/pathway_ai" DB_USERNAME=u DB_PASSWORD=p bash "$S" dump
case_ "restore-test with no backup present"       2 "no backup file found"      OUT_DIR="$(mktemp -d)" BACKUP_PASSPHRASE="$PW" bash "$S" restore-test

# the password must never be echoed
out="$(env -i PATH="$PATH" HOME="${HOME:-/tmp}" GITHUB_ACTIONS=true BACKUP_PASSPHRASE="$PW" DB_URL="$R?sslmode=verify-full" DB_USERNAME=u DB_PASSWORD=SuperSecretDbPw99 bash "$S" dump 2>&1 || true)"
if printf '%s' "$out" | grep -q "SuperSecretDbPw99\|$PW"; then echo "FAIL  a secret appeared in the output"; fail=1; else echo "PASS  no secret in the output"; fi

[ "$fail" = 0 ] && echo "ALL BACKUP GUARDS HOLD" || { echo "BACKUP GUARD FAILURES"; exit 1; }
