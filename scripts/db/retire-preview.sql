-- READ-ONLY preview of scripts/db/retire-unsynchronised-catalogue.sql: what would be kept and what would go.
-- Same retention rule: a program is kept only if one of its monitors extracts facts (ADAPTER / JSON_LD).
-- Run: docker exec -i -e PGPASSWORD=... pathway-ai-postgres psql -U pathway -d pathway_ai -f - < scripts/db/retire-preview.sql
\set ON_ERROR_STOP on
BEGIN READ ONLY;
SELECT current_database() AS database, inet_server_port() AS container_port, version() AS server;

\echo === Kept / removed programs
SELECT u.slug AS university, p.slug AS program, p.id,
       CASE WHEN EXISTS (SELECT 1 FROM source_monitor m WHERE m.program_id = p.id AND m.extraction_method IN ('ADAPTER','JSON_LD'))
            THEN 'KEEP' ELSE 'REMOVE' END AS decision,
       (SELECT string_agg(DISTINCT m.adapter_name, ',') FROM source_monitor m WHERE m.program_id = p.id) AS monitors
FROM degree_program p JOIN university u ON u.id = p.university_id ORDER BY decision DESC, u.slug, p.slug;

\echo === Kept / removed universities
SELECT u.slug, u.verification_status,
       CASE WHEN EXISTS (SELECT 1 FROM degree_program p JOIN source_monitor m ON m.program_id = p.id
                         WHERE p.university_id = u.id AND m.extraction_method IN ('ADAPTER','JSON_LD'))
            THEN 'KEEP' ELSE 'REMOVE' END AS decision
FROM university u ORDER BY decision DESC, u.slug;

\echo === Summary by table (kept / removed)
WITH keep_program AS (
    SELECT p.id, p.university_id FROM degree_program p
    WHERE EXISTS (SELECT 1 FROM source_monitor m WHERE m.program_id = p.id AND m.extraction_method IN ('ADAPTER','JSON_LD'))),
keep_university AS (SELECT DISTINCT university_id AS id FROM keep_program),
keep_monitor AS (SELECT m.source_id FROM source_monitor m JOIN keep_program k ON k.id = m.program_id
                 WHERE m.extraction_method IN ('ADAPTER','JSON_LD'))
SELECT 'university' AS entity, count(*) AS total, count(*) FILTER (WHERE id IN (SELECT id FROM keep_university)) AS kept,
       count(*) FILTER (WHERE id NOT IN (SELECT id FROM keep_university)) AS removed FROM university
UNION ALL SELECT 'degree_program', count(*), count(*) FILTER (WHERE id IN (SELECT id FROM keep_program)),
       count(*) FILTER (WHERE id NOT IN (SELECT id FROM keep_program)) FROM degree_program
UNION ALL SELECT 'source_monitor', count(*), count(*) FILTER (WHERE source_id IN (SELECT source_id FROM keep_monitor)),
       count(*) FILTER (WHERE source_id NOT IN (SELECT source_id FROM keep_monitor)) FROM source_monitor
-- dependents that go with a removed program (ON DELETE CASCADE)
UNION ALL SELECT 'program_language', count(*), count(*) FILTER (WHERE program_id IN (SELECT id FROM keep_program)), count(*) FILTER (WHERE program_id NOT IN (SELECT id FROM keep_program)) FROM program_language
UNION ALL SELECT 'program_intake', count(*), count(*) FILTER (WHERE program_id IN (SELECT id FROM keep_program)), count(*) FILTER (WHERE program_id NOT IN (SELECT id FROM keep_program)) FROM program_intake
UNION ALL SELECT 'tuition_fee', count(*), count(*) FILTER (WHERE program_id IN (SELECT id FROM keep_program)), count(*) FILTER (WHERE program_id NOT IN (SELECT id FROM keep_program)) FROM tuition_fee
UNION ALL SELECT 'academic_requirement', count(*), count(*) FILTER (WHERE program_id IN (SELECT id FROM keep_program)), count(*) FILTER (WHERE program_id NOT IN (SELECT id FROM keep_program)) FROM academic_requirement
UNION ALL SELECT 'program_source', count(*), count(*) FILTER (WHERE program_id IN (SELECT id FROM keep_program)), count(*) FILTER (WHERE program_id NOT IN (SELECT id FROM keep_program)) FROM program_source
UNION ALL SELECT 'program_external_id', count(*), count(*) FILTER (WHERE program_id IN (SELECT id FROM keep_program)), count(*) FILTER (WHERE program_id NOT IN (SELECT id FROM keep_program)) FROM program_external_id
UNION ALL SELECT 'program_field_provenance', count(*), count(*) FILTER (WHERE program_id IN (SELECT id FROM keep_program)), count(*) FILTER (WHERE program_id NOT IN (SELECT id FROM keep_program)) FROM program_field_provenance
UNION ALL SELECT 'program_fact_history', count(*), count(*) FILTER (WHERE program_id IN (SELECT id FROM keep_program)), count(*) FILTER (WHERE program_id NOT IN (SELECT id FROM keep_program)) FROM program_fact_history
UNION ALL SELECT 'staged_change', count(*), count(*) FILTER (WHERE program_id IN (SELECT id FROM keep_program)), count(*) FILTER (WHERE program_id IS NULL OR program_id NOT IN (SELECT id FROM keep_program)) FROM staged_change
-- dependents that go with a removed university
UNION ALL SELECT 'university_alias', count(*), count(*) FILTER (WHERE university_id IN (SELECT id FROM keep_university)), count(*) FILTER (WHERE university_id NOT IN (SELECT id FROM keep_university)) FROM university_alias
UNION ALL SELECT 'university_external_id', count(*), count(*) FILTER (WHERE university_id IN (SELECT id FROM keep_university)), count(*) FILTER (WHERE university_id NOT IN (SELECT id FROM keep_university)) FROM university_external_id
UNION ALL SELECT 'university_source', count(*), count(*) FILTER (WHERE university_id IN (SELECT id FROM keep_university)), count(*) FILTER (WHERE university_id NOT IN (SELECT id FROM keep_university)) FROM university_source
-- monitoring log of removed monitors
UNION ALL SELECT 'source_check (of removed monitors)', count(*), count(*) FILTER (WHERE source_id IN (SELECT source_id FROM keep_monitor)), count(*) FILTER (WHERE source_id NOT IN (SELECT source_id FROM keep_monitor)) FROM source_check
-- never touched
UNION ALL SELECT 'country (never touched)', count(*), count(*), 0 FROM country
UNION ALL SELECT 'academic_field (never touched)', count(*), count(*), 0 FROM academic_field
UNION ALL SELECT 'flyway_schema_history (never touched)', count(*), count(*), 0 FROM flyway_schema_history;
ROLLBACK;
