#!/usr/bin/env bash
# Runs scripts/db/retire-unsynchronised-catalogue.sql against the Pathway AI DEVELOPMENT database in the Docker
# container, inside one transaction.
#
#   scripts/db/retire-unsynchronised-catalogue.sh --dry-run    run everything, then ROLLBACK (nothing is kept)
#   scripts/db/retire-unsynchronised-catalogue.sh --commit     run everything and COMMIT if every check passed
#
# Preconditions you must have met first: a fresh verified pg_dump (see docs/data/sync-policy.md), the preview
# (scripts/db/retire-preview.sql) read, and Flyway V8 applied. Reads DB_PASSWORD from .env. Never run it against a
# production database: the database name guard only accepts the one named below.
set -euo pipefail
export MSYS_NO_PATHCONV=1
MODE="${1:---dry-run}"
CONTAINER="${PATHWAY_PG_CONTAINER:-pathway-ai-postgres}"
DB="${PATHWAY_PG_DATABASE:-pathway_ai}"
EXPECT_UNIVERSITIES="${EXPECT_UNIVERSITIES:-3}"
EXPECT_PROGRAMS="${EXPECT_PROGRAMS:-9}"
cd "$(dirname "$0")/../.."
PW="$(grep '^DB_PASSWORD=' .env | cut -d= -f2-)"
case "$MODE" in --dry-run) END="ROLLBACK" ;; --commit) END="COMMIT" ;; *) echo "usage: $0 --dry-run|--commit"; exit 2 ;; esac

docker exec -i -e PGPASSWORD="$PW" "$CONTAINER" psql -v ON_ERROR_STOP=1 -U pathway -d "$DB" \
  -c "BEGIN" \
  -c "SELECT set_config('cleanup.expected_database', '$DB', true), set_config('cleanup.expect_universities', '$EXPECT_UNIVERSITIES', true), set_config('cleanup.expect_programs', '$EXPECT_PROGRAMS', true)" \
  -f - < scripts/db/retire-unsynchronised-catalogue.sql \
  -c "$END"
echo "Finished with $END."
