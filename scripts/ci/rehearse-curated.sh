#!/usr/bin/env bash
# Runs ONLY inside the disposable database made by backup.sh. Never accepts a production DB URL.
# Mirrors the production order: migrate -> university catalogue -> preview -> apply (twice, for idempotence).
set -euo pipefail
container_name="${1:?scratch container required}"
jar_path="${2:?reviewed jar required}"
out="${3:?output directory required}"
case "$container_name" in pathway-restore-test-*) ;; *) echo 'refusing a non-rehearsal container' >&2; exit 2;; esac
[[ "$container_name" =~ ^pathway-restore-test-[0-9]+$ ]] || exit 2
[ -f "$jar_path" ] || { echo 'rehearsal jar missing' >&2; exit 2; }
: "${REHEARSAL_DATABASE_PASSWORD:?scratch password required}"
jar_absolute="$(realpath "$jar_path")"
HOSTS="opintopolku.fi,www.aalto.fi,www.helsinki.fi,www.tuni.fi,www.lut.fi,www.oulu.fi,www.jyu.fi,www.utu.fi"
sql() { docker exec "$container_name" psql -v ON_ERROR_STOP=1 -U postgres -d restore_test -At -c "$1"; }
# $1 = IMPORT_UNIVERSITY_CATALOGUE (true only for the catalogue step); the rest are application arguments
java_app() {
  local catalogue="$1"; shift
  docker run --rm --network "container:$container_name" -v "$jar_absolute:/app/backend.jar:ro" \
    -e DB_URL=jdbc:postgresql://localhost:5432/restore_test -e DB_USERNAME=postgres \
    -e DB_PASSWORD="$REHEARSAL_DATABASE_PASSWORD" -e IMPORT_REFERENCE_DATA=false -e IMPORT_UNIVERSITY_CATALOGUE="$catalogue" \
    eclipse-temurin:21-jre java -Xmx512m -jar /app/backend.jar --spring.main.web-application-type=none \
    --app.refresh.scheduler.enabled=false --spring.main.banner-mode=off --logging.level.root=ERROR \
    "--app.refresh.extra-approved-hosts=$HOSTS" "$@"
}
# runs one step; on failure shows the end of its log (no secrets are logged at ERROR level) and stops
step() {
  local name="$1"; shift
  if ! "$@" > "$out/rehearsal-$name.log" 2>&1; then
    echo "== REHEARSAL FAILED at step '$name'; last log lines:"; tail -25 "$out/rehearsal-$name.log" | cut -c1-300; exit 1
  fi
}

student_digest() {
  for table in $(sql "select tablename from pg_tables where schemaname='public' and (tablename like 'student_%' or tablename like 'saved_%' or tablename in ('user_account','external_identity')) order by tablename"); do
    [[ "$table" =~ ^[a-z_]+$ ]] || exit 2
    sql "select '$table:'||count(*)||':'||md5(coalesce(string_agg(payload,'' order by payload),'')) from (select row_to_json(t)::text payload from $table t) rows"
  done
}
table_digest() { sql "select md5(coalesce(string_agg(row_to_json(t)::text,'' order by row_to_json(t)::text),'')) from ($1) t"; }
# Pre-existing universities: copies of their rows at each stage (before / catalogue / final), compared row by row.
# These rows are public reference data (no personal data), so any difference is printed in full.
UTABLES='university:id university_external_id:university_id university_alias:university_id university_source:university_id'
snapshot_universities() {
  for spec in $UTABLES; do t="${spec%%:*}"; k="${spec##*:}"
    sql "create table rehearsal_${1}_${t} as select * from $t where $k in (select id from rehearsal_university_snapshot)" >/dev/null
  done
}
# rows of stage $1 missing at stage $2 ("removed") and rows of stage $2 not present at stage $1 ("added")
university_diff() {
  for spec in $UTABLES; do t="${spec%%:*}"
    sql "select '$t removed: '||row_to_json(d)::text from (select * from rehearsal_${1}_${t} except select * from rehearsal_${2}_${t}) d"
    sql "select '$t added: '||row_to_json(d)::text from (select * from rehearsal_${2}_${t} except select * from rehearsal_${1}_${t}) d"
  done
}
student_digest > "$out/student-before.manifest"
sql 'create table rehearsal_identity_snapshot as select id,university_id,slug from degree_program'
sql 'create table rehearsal_university_snapshot as select id,slug,name from university'
sql 'create table rehearsal_program_rows as select * from degree_program'
snapshot_universities before
programs_before="$(table_digest 'select * from degree_program')"
echo "== rehearsal: restored copy has $(sql 'select count(*) from degree_program') programs, $(sql 'select count(*) from university') universities"

echo '== rehearsal: applying additive migration to the isolated restore only'
step migration java_app false --spring.flyway.enabled=true
echo "== rehearsal: schema now at V$(sql "select max(version::int) from flyway_schema_history where success")"

echo '== rehearsal: importing the reviewed university catalogue (idempotent; adds curated-only universities)'
step catalogue java_app true --spring.flyway.enabled=false
echo "== rehearsal: universities now $(sql 'select count(*) from university'); new: $(sql "select coalesce(string_agg(slug,',' order by slug),'none') from university where id not in (select id from rehearsal_university_snapshot)")"
[ "$(table_digest 'select * from degree_program')" = "$programs_before" ] || { echo '== REHEARSAL FAILED: the catalogue step changed program rows'; exit 1; }
[ "$(sql 'select count(*) from (select slug from university group by slug having count(*)>1) d')" = 0 ] || { echo '== REHEARSAL FAILED: duplicate university slug'; exit 1; }
step catalogue-repeat java_app true --spring.flyway.enabled=false
snapshot_universities catalogue
university_diff before catalogue > "$out/universities-catalogue.diff"
echo "== rehearsal: catalogue step changes to pre-existing universities: $(wc -l < "$out/universities-catalogue.diff") row(s)"
cat "$out/universities-catalogue.diff"
universities_after_catalogue="$(sql 'select count(*) from university')"
echo "== rehearsal: catalogue repeated: universities still $universities_after_catalogue; program rows unchanged"

echo '== rehearsal: verifying official sources and preparing the exact operation (live reads, crawl delays honoured)'
step preview java_app false --spring.flyway.enabled=false --app.curated.command=preview
fingerprint="$(sed -n 's/^CURATED_PLAN .*fingerprint=\([a-f0-9]\{64\}\)$/\1/p' "$out/rehearsal-preview.log" | tail -1)"
[[ "$fingerprint" =~ ^[a-f0-9]{64}$ ]] || { echo '== REHEARSAL FAILED: no plan fingerprint'; tail -25 "$out/rehearsal-preview.log" | cut -c1-300; exit 1; }
grep -E '^CURATED_(CANDIDATE|PLAN) ' "$out/rehearsal-preview.log"

echo "== rehearsal: applying reviewed plan $fingerprint to the isolated restore"
step apply java_app false --spring.flyway.enabled=false --app.curated.command=apply "--app.curated.expected-fingerprint=$fingerprint"
echo '== rehearsal: repeating the identical operation to verify idempotence'
programs_after_first="$(sql 'select count(*) from degree_program')"
step repeat java_app false --spring.flyway.enabled=false --app.curated.command=apply "--app.curated.expected-fingerprint=$fingerprint"
[ "$(sql 'select count(*) from degree_program')" = "$programs_after_first" ] || { echo '== REHEARSAL FAILED: the repeat created programs'; exit 1; }

student_digest > "$out/student-after.manifest"
cmp "$out/student-before.manifest" "$out/student-after.manifest" || { echo '== REHEARSAL FAILED: student/account/save rows changed'; exit 1; }
snapshot_universities final
university_diff catalogue final > "$out/universities-apply.diff"
# The import may only ADD provenance links from a pre-existing university to the Studyinfo record of a program it
# newly created there (StudyinfoImporter links each created program's source to its university, as discovery always did).
if grep -v '^university_source added: ' "$out/universities-apply.diff" | grep -q .; then
  echo '== REHEARSAL FAILED: the curated import changed or removed pre-existing university data:'
  grep -v '^university_source added: ' "$out/universities-apply.diff"; exit 1
fi
unexplained="$(sql "select count(*) from (select * from rehearsal_final_university_source except select * from rehearsal_catalogue_university_source) a
  where a.source_id not in (select ps.source_id from program_source ps join catalog_membership m on m.program_id=ps.program_id
                            where m.program_id not in (select id from rehearsal_identity_snapshot))")"
[ "$unexplained" = 0 ] || { echo "== REHEARSAL FAILED: $unexplained provenance link(s) added by the import do not belong to a newly created curated program"; cat "$out/universities-apply.diff"; exit 1; }
echo "== RESULT provenance links added by the import (new curated programs' Studyinfo records): $(grep -c '^university_source added: ' "$out/universities-apply.diff" || true)"
# the catalogue step may only ADD provenance links (university_source rows); any other change or removal fails
if grep -v '^university_source added: ' "$out/universities-catalogue.diff" | grep -q .; then
  echo '== REHEARSAL FAILED: the catalogue step changed or removed pre-existing university data:'
  grep -v '^university_source added: ' "$out/universities-catalogue.diff"; exit 1
fi
echo "== RESULT pre-existing universities: rows, identifiers and aliases unchanged; provenance links added by the catalogue: $(grep -c '^university_source added: ' "$out/universities-catalogue.diff" || true)"
[ "$(sql 'select count(*) from university')" = "$universities_after_catalogue" ] || { echo '== REHEARSAL FAILED: university count changed during apply'; exit 1; }
untouched_changed="$(sql 'select count(*) from (select * from rehearsal_program_rows where id not in (select program_id from catalog_membership) except select * from degree_program) d')"
[ "$untouched_changed" = 0 ] || { echo "== REHEARSAL FAILED: $untouched_changed non-curated program rows changed"; exit 1; }
reused_changes="$(sql "select coalesce(string_agg(p.slug,',' order by p.slug),'none') from degree_program p join rehearsal_program_rows o on o.id=p.id where p.id in (select program_id from catalog_membership) and row(p.*) is distinct from row(o.*)")"
echo "== RESULT reused curated programs whose row changed (expected: reviewed university URL/admissions only): $reused_changes"
[ "$(sql 'select count(*) from (select id,university_id,slug from rehearsal_identity_snapshot except select id,university_id,slug from degree_program) missing')" = 0 ] \
  || { echo '== REHEARSAL FAILED: an existing program identity changed or disappeared'; exit 1; }
bachelors="$(sql "select count(*) from catalog_membership where release_id='finland-curated-v1' and entry_level='BACHELOR'")"
masters="$(sql "select count(*) from catalog_membership where release_id='finland-curated-v1' and entry_level='MASTER'")"
[ "$bachelors" = 10 ] && [ "$masters" = 10 ] || { echo "== REHEARSAL FAILED: membership $bachelors bachelor / $masters master"; exit 1; }
reused="$(sql "select count(*) from catalog_membership m where release_id='finland-curated-v1' and program_id in (select id from rehearsal_identity_snapshot)")"
created="$(sql "select count(*) from catalog_membership m where release_id='finland-curated-v1' and program_id not in (select id from rehearsal_identity_snapshot)")"
echo "== RESULT selected: bachelor=$bachelors master=$masters reused=$reused new=$created"
echo "== RESULT programs: before=$(sql 'select count(*) from rehearsal_identity_snapshot') after=$(sql 'select count(*) from degree_program') (nothing deleted)"
echo "== RESULT release enabled: $(sql "select enabled||' approved_by='||approved_by from catalog_release where id='finland-curated-v1'")"
echo "== RESULT monitors registered for curated programs: $(sql "select count(*) from source_monitor where program_id in (select program_id from catalog_membership)")"
echo "== RESULT university facts: $(sql 'select count(*) from program_university_fact'), intakes on curated programs: $(sql "select count(*) from program_intake where program_id in (select program_id from catalog_membership)")"
echo "== RESULT student/account/save tables byte-identical: $(wc -l < "$out/student-after.manifest") tables compared"
echo '== CURATED REHEARSAL PASSED: additive migration, university catalogue, 10+10 activation, repeat operation, old identities and all student/account/save rows preserved'
