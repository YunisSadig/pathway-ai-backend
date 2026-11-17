#!/usr/bin/env bash
# Runs ONLY inside the disposable database made by backup.sh. Never accepts a production DB URL.
set -euo pipefail
container_name="${1:?scratch container required}"
jar_path="${2:?reviewed jar required}"
out="${3:?output directory required}"
case "$container_name" in pathway-restore-test-*) ;; *) echo 'refusing a non-rehearsal container' >&2; exit 2;; esac
[[ "$container_name" =~ ^pathway-restore-test-[0-9]+$ ]] || exit 2
[ -f "$jar_path" ] || { echo 'rehearsal jar missing' >&2; exit 2; }
: "${REHEARSAL_DATABASE_PASSWORD:?scratch password required}"
jar_absolute="$(realpath "$jar_path")"
sql() { docker exec "$container_name" psql -v ON_ERROR_STOP=1 -U postgres -d restore_test -At -c "$1"; }
java_app() {
  docker run --rm --network "container:$container_name" -v "$jar_absolute:/app/backend.jar:ro" \
    -e DB_URL=jdbc:postgresql://localhost:5432/restore_test -e DB_USERNAME=postgres \
    -e DB_PASSWORD="$REHEARSAL_DATABASE_PASSWORD" -e IMPORT_REFERENCE_DATA=false -e IMPORT_UNIVERSITY_CATALOGUE=false \
    eclipse-temurin:21-jre java -Xmx512m -jar /app/backend.jar --spring.main.web-application-type=none \
    --app.refresh.scheduler.enabled=false --spring.main.banner-mode=off --logging.level.root=ERROR \
    --app.refresh.extra-approved-hosts=opintopolku.fi,www.aalto.fi,www.helsinki.fi,www.tuni.fi,www.lut.fi,www.oulu.fi,www.jyu.fi,www.utu.fi "$@"
}

student_digest() {
  for table in $(sql "select tablename from pg_tables where schemaname='public' and (tablename like 'student_%' or tablename like 'saved_%' or tablename in ('user_account','external_identity')) order by tablename"); do
    [[ "$table" =~ ^[a-z_]+$ ]] || exit 2
    sql "select '$table:'||count(*)||':'||md5(coalesce(string_agg(payload,'' order by payload),'')) from (select row_to_json(t)::text payload from $table t) rows"
  done
}
student_digest > "$out/student-before.manifest"
sql 'create table rehearsal_identity_snapshot as select id,university_id,slug from degree_program'
echo '== rehearsal: applying additive migration to the isolated restore only'
java_app --spring.flyway.enabled=true > "$out/rehearsal-migration.log" 2>&1
echo '== rehearsal: verifying official sources and preparing the exact operation'
java_app --spring.flyway.enabled=false --app.curated.command=preview > "$out/rehearsal-preview.log" 2>&1
fingerprint="$(sed -n 's/^CURATED_PLAN .*fingerprint=\([a-f0-9]\{64\}\)$/\1/p' "$out/rehearsal-preview.log" | tail -1)"
[[ "$fingerprint" =~ ^[a-f0-9]{64}$ ]] || { tail -12 "$out/rehearsal-preview.log"; exit 1; }
echo "== rehearsal: applying reviewed plan $fingerprint to the isolated restore"
java_app --spring.flyway.enabled=false --app.curated.command=apply "--app.curated.expected-fingerprint=$fingerprint" > "$out/rehearsal-apply.log" 2>&1
echo '== rehearsal: repeating the identical operation to verify idempotence'
java_app --spring.flyway.enabled=false --app.curated.command=apply "--app.curated.expected-fingerprint=$fingerprint" > "$out/rehearsal-repeat.log" 2>&1
student_digest > "$out/student-after.manifest"
cmp "$out/student-before.manifest" "$out/student-after.manifest"
[ "$(sql 'select count(*) from (select id,university_id,slug from rehearsal_identity_snapshot except select id,university_id,slug from degree_program) missing')" = 0 ]
[ "$(sql "select count(*) from catalog_membership where release_id='finland-curated-v1' and entry_level='BACHELOR'")" = 10 ]
[ "$(sql "select count(*) from catalog_membership where release_id='finland-curated-v1' and entry_level='MASTER'")" = 10 ]
echo '== CURATED REHEARSAL PASSED: additive migration, 10+10 activation, repeat operation, old identities and all student/account/save rows preserved'
grep '^CURATED_PLAN ' "$out/rehearsal-preview.log"
