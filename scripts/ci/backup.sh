#!/usr/bin/env bash
# Encrypted logical backup of the hosted database and a non-destructive restore test.
#
#   scripts/ci/backup.sh dump          pg_dump the database named by DB_URL / DB_USERNAME / DB_PASSWORD, encrypted on the fly with
#                                      BACKUP_PASSPHRASE (AES-256-CBC, PBKDF2) into $OUT_DIR/pathway_ai-<UTC time>.dump.enc, plus a
#                                      manifest of row counts taken from the SOURCE at dump time. The source is only READ.
#   scripts/ci/backup.sh restore-test  decrypt the newest backup in $OUT_DIR, restore it into a THROWAWAY PostgreSQL 17 container that
#                                      exists only for this run, compare row counts with the manifest and Flyway's version, then delete the
#                                      container. No hosted database is touched: this is the proof that a backup can actually be restored.
#
# Why this shape: the backup artifact of a public repository is downloadable by any GitHub user, so it is stored ENCRYPTED only; there is no
# plaintext file on disk (pg_dump streams into openssl). pg_dump/pg_restore run from the postgres:17 image so their version matches the server.
# Needs docker, openssl, bash. A remote database must be reached with sslmode=verify-full (libpq: sslrootcert=system); a database on this
# machine needs no TLS (used by the local test, see test-backup.sh).
set -euo pipefail

MODE="${1:-}"
OUT_DIR="${OUT_DIR:-backup-out}"
PG_IMAGE="${PG_IMAGE:-postgres:17}"
PG_DOCKER_NETWORK="${PG_DOCKER_NETWORK:-host}"   # tests use container:<name> to reach a throwaway database as "localhost"

die() { echo "ERROR: $*" >&2; exit "${2:-2}"; }
case "$MODE" in dump|restore-test) ;; *) die "usage: backup.sh dump|restore-test";; esac
command -v docker >/dev/null || die "docker is required"
command -v openssl >/dev/null || die "openssl is required"

[ -n "${BACKUP_PASSPHRASE:-}" ] || die "BACKUP_PASSPHRASE is not set"
[ "${#BACKUP_PASSPHRASE}" -ge 24 ] || die "BACKUP_PASSPHRASE must be at least 24 characters"
mkdir -p "$OUT_DIR"

enc() { openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt -pass env:BACKUP_PASSPHRASE "$@"; }

# the counts that prove a restore is complete; identical text from source and from the restored copy
MANIFEST_SQL="select 'universities=' || (select count(*) from university) || ' programs=' || (select count(*) from degree_program) || ' external_ids=' || (select count(*) from program_external_id) || ' provenance=' || (select count(*) from program_field_provenance) || ' fact_history=' || (select count(*) from program_fact_history) || ' monitors=' || (select count(*) from source_monitor) || ' staged_changes=' || (select count(*) from staged_change) || ' flyway_max=' || (select max(version::int) from flyway_schema_history where success)"

if [ "$MODE" = dump ]; then
  for v in DB_URL DB_USERNAME DB_PASSWORD; do [ -n "${!v:-}" ] || die "$v is not set"; done
  case "$DB_URL" in jdbc:postgresql://*) ;; *) die "DB_URL must start with jdbc:postgresql://";; esac
  rest="${DB_URL#jdbc:postgresql://}"
  host="${rest%%[/?]*}"; port=5432
  case "$host" in *:*) port="${host##*:}"; host="${host%%:*}";; esac
  db="${rest#*/}"; db="${db%%[?]*}"
  [ -n "$host" ] && [ -n "$db" ] || die "could not read host and database from DB_URL"
  case "$host" in localhost|127.0.0.1) is_local=true;; *) is_local=false;; esac
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    [ "$is_local" = false ] || die "refusing to back up a localhost database on a hosted runner"
    for derived in "$host" "$db"; do echo "::add-mask::$derived"; done
  fi
  ca_mount=()
  if [ "$is_local" = false ]; then
    [ "$(printf '%s' "$DB_URL" | grep -o 'sslmode=' | wc -l)" -eq 1 ] || die "DB_URL must contain sslmode=verify-full exactly once"
    case "$DB_URL" in *[?\&]sslmode=verify-full|*[?\&]sslmode=verify-full\&*) ;; *) die "a remote database must be reached with sslmode=verify-full";; esac
    # the postgres image ships WITHOUT a CA bundle (sslrootcert=system finds nothing: 'certificate verify failed', backup run 43633074004),
    # so the host's bundle is mounted read-only and libpq is pointed at it
    CA_BUNDLE="${CA_BUNDLE:-/etc/ssl/certs/ca-certificates.crt}"
    [ -r "$CA_BUNDLE" ] || die "CA bundle not found at $CA_BUNDLE (set CA_BUNDLE)"
    ca_mount=(-v "$CA_BUNDLE:/ca-bundle.crt:ro")
    export PGSSLMODE=verify-full PGSSLROOTCERT=/ca-bundle.crt
  fi
  export PGPASSWORD="$DB_PASSWORD"
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  file="$OUT_DIR/pathway_ai-$stamp.dump.enc"
  pg() { docker run --rm -i --network "$PG_DOCKER_NETWORK" "${ca_mount[@]}" -e PGPASSWORD -e PGSSLMODE -e PGSSLROOTCERT "$PG_IMAGE" "$@"; }

  echo "== backup: dumping database '${db:0:2}...' (read-only) to an encrypted file"
  pg pg_dump -h "$host" -p "$port" -U "$DB_USERNAME" -d "$db" -Fc --no-owner --no-privileges | enc -out "$file"
  [ -s "$file" ] || die "the backup file is empty"
  pg psql -h "$host" -p "$port" -U "$DB_USERNAME" -d "$db" -At -c "$MANIFEST_SQL" > "$OUT_DIR/manifest.txt"
  ( cd "$OUT_DIR" && sha256sum "$(basename "$file")" > "$(basename "$file").sha256" )
  echo "== backup written: $(basename "$file") ($(wc -c < "$file") bytes encrypted)"
  echo "== manifest (counts at dump time): $(cat "$OUT_DIR/manifest.txt")"
  exit 0
fi

# ---- restore-test
file="$(ls -1t "$OUT_DIR"/pathway_ai-*.dump.enc 2>/dev/null | head -1 || true)"
[ -n "$file" ] || die "no backup file found in $OUT_DIR"
[ -s "$OUT_DIR/manifest.txt" ] || die "manifest.txt is missing"
( cd "$OUT_DIR" && sha256sum -c "$(basename "$file").sha256" >/dev/null ) || die "checksum mismatch: the backup file is damaged"

name="pathway-restore-test-$$"
scratch_pw="$(openssl rand -hex 16)"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT
echo "== restore test: starting a throwaway PostgreSQL 17 container"
docker run -d --name "$name" -e POSTGRES_PASSWORD="$scratch_pw" "$PG_IMAGE" >/dev/null
for _ in $(seq 1 40); do docker exec "$name" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 2; done
docker exec "$name" pg_isready -U postgres >/dev/null 2>&1 || die "the scratch database did not start"
docker exec "$name" createdb -U postgres restore_test

echo "== restore test: decrypting and restoring $(basename "$file")"
enc -d -in "$file" | docker exec -i "$name" pg_restore -U postgres -d restore_test --no-owner --no-privileges
restored="$(docker exec "$name" psql -U postgres -d restore_test -At -c "$MANIFEST_SQL")"
expected="$(cat "$OUT_DIR/manifest.txt")"
echo "   expected: $expected"
echo "   restored: $restored"
[ "$restored" = "$expected" ] || die "the restored data does not match the manifest" 1
if [ -n "${REHEARSAL_JAR:-}" ]; then
  REHEARSAL_DATABASE_PASSWORD="$scratch_pw" bash scripts/ci/rehearse-curated.sh "$name" "$REHEARSAL_JAR" "$OUT_DIR"
fi
echo "== restore test PASSED: the backup restores completely into an independent database (scratch container removed)"
