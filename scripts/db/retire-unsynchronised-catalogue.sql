-- Retire every program that has no working extractor, and every university left without a program.
--
-- Retention rule (data, not names): a program is KEPT only if a source monitor of it uses a fact-extracting method
-- (ADAPTER = official API connector, JSON_LD = permitted structured-data extractor). A CONTENT_HASH monitor only notices
-- that a page changed; it is not synchronization. A university is kept only if it has a kept program.
--
-- Safety (the whole script is ONE statement, so it runs inside whatever transaction the caller opened):
--   * refuses to run unless current_database() equals cleanup.expected_database (default 'pathway_ai');
--   * refuses to run without the V8 archive table, or when the batch name was already used;
--   * snapshots every removed program, university, monitor and orphaned source into catalogue_archive BEFORE deleting;
--   * never touches flyway_schema_history, country, academic_field, or any other database;
--   * verifies afterwards: expected counts (cleanup.expect_universities / cleanup.expect_programs when given), every
--     kept program has an extractor, no university without a program, no unreferenced source, every foreign key
--     satisfied, and the untouched tables unchanged. ANY failed check raises and the caller's transaction is rolled back.
--
-- Run it through scripts/db/retire-unsynchronised-catalogue.sh (--dry-run rolls back, --commit commits), after a
-- verified pg_dump. Tuning knobs are set with set_config(..., true) before this statement; see docs/data/sync-policy.md.
DO $cleanup$
DECLARE
    v_expected_db text := coalesce(nullif(current_setting('cleanup.expected_database', true), ''), 'pathway_ai');
    v_batch       text := coalesce(nullif(current_setting('cleanup.batch', true), ''),
                                   'retire-' || to_char(now() AT TIME ZONE 'UTC', 'YYYYMMDD"T"HH24MISS'));
    v_exp_uni     text := nullif(current_setting('cleanup.expect_universities', true), '');
    v_exp_prog    text := nullif(current_setting('cleanup.expect_programs', true), '');
    b_uni bigint; b_prog bigint; b_mon bigint; b_src bigint; b_flyway bigint; b_country bigint; b_field bigint;
    a_uni bigint; a_prog bigint; a_mon bigint; a_src bigint;
    r_uni bigint; r_prog bigint; r_mon bigint; r_src bigint; r_checks bigint;
    n bigint;
    fk record;
BEGIN
    -- ---- guards ---------------------------------------------------------------------------------------------------
    IF current_database() <> v_expected_db THEN
        RAISE EXCEPTION 'refusing to run: connected to database %, expected %', current_database(), v_expected_db;
    END IF;
    IF to_regclass('public.catalogue_archive') IS NULL THEN
        RAISE EXCEPTION 'refusing to run: public.catalogue_archive is missing (apply Flyway V8 first)';
    END IF;
    IF EXISTS (SELECT 1 FROM catalogue_archive WHERE batch = v_batch) THEN
        RAISE EXCEPTION 'refusing to run: archive batch % already exists', v_batch;
    END IF;

    SELECT count(*) INTO b_uni FROM university;
    SELECT count(*) INTO b_prog FROM degree_program;
    SELECT count(*) INTO b_mon FROM source_monitor;
    SELECT count(*) INTO b_src FROM source;
    SELECT count(*) INTO b_flyway FROM flyway_schema_history;
    SELECT count(*) INTO b_country FROM country;
    SELECT count(*) INTO b_field FROM academic_field;

    DROP TABLE IF EXISTS pg_temp.keep_program, pg_temp.keep_university, pg_temp.keep_monitor, pg_temp.orphan_source;

    -- ---- retention rule -------------------------------------------------------------------------------------------
    CREATE TEMP TABLE keep_program ON COMMIT DROP AS
        SELECT p.id FROM degree_program p
        WHERE EXISTS (SELECT 1 FROM source_monitor m
                      WHERE m.program_id = p.id AND m.extraction_method IN ('ADAPTER', 'JSON_LD'));
    CREATE TEMP TABLE keep_university ON COMMIT DROP AS
        SELECT DISTINCT p.university_id AS id FROM degree_program p JOIN keep_program k ON k.id = p.id;
    CREATE TEMP TABLE keep_monitor ON COMMIT DROP AS
        SELECT m.source_id FROM source_monitor m JOIN keep_program k ON k.id = m.program_id
        WHERE m.extraction_method IN ('ADAPTER', 'JSON_LD');

    -- ---- archive BEFORE deleting ----------------------------------------------------------------------------------
    INSERT INTO catalogue_archive (batch, entity_type, entity_key, snapshot, reason)
    SELECT v_batch, 'PROGRAM', u.slug || '--' || p.slug,
           jsonb_build_object(
               'program', to_jsonb(p),
               'universitySlug', u.slug,
               'languages', coalesce((SELECT jsonb_agg(l.language_code ORDER BY l.language_code)
                                      FROM program_language l WHERE l.program_id = p.id), '[]'::jsonb),
               'intakes', coalesce((SELECT jsonb_agg(to_jsonb(i)) FROM program_intake i WHERE i.program_id = p.id), '[]'::jsonb),
               'tuition', coalesce((SELECT jsonb_agg(to_jsonb(t)) FROM tuition_fee t WHERE t.program_id = p.id), '[]'::jsonb),
               'requirements', coalesce((SELECT jsonb_agg(to_jsonb(r)) FROM academic_requirement r WHERE r.program_id = p.id), '[]'::jsonb),
               'externalIds', coalesce((SELECT jsonb_agg(to_jsonb(e)) FROM program_external_id e WHERE e.program_id = p.id), '[]'::jsonb),
               'fieldProvenance', coalesce((SELECT jsonb_agg(to_jsonb(fp)) FROM program_field_provenance fp WHERE fp.program_id = p.id), '[]'::jsonb),
               'factHistory', coalesce((SELECT jsonb_agg(to_jsonb(h)) FROM program_fact_history h WHERE h.program_id = p.id), '[]'::jsonb),
               'stagedChanges', coalesce((SELECT jsonb_agg(to_jsonb(sc)) FROM staged_change sc WHERE sc.program_id = p.id), '[]'::jsonb),
               'sources', coalesce((SELECT jsonb_agg(jsonb_build_object('id', s.id, 'url', s.url, 'title', s.title,
                                       'publisher', s.publisher, 'type', s.source_type, 'retrievedOn', s.retrieved_on))
                                   FROM program_source ps JOIN source s ON s.id = ps.source_id WHERE ps.program_id = p.id), '[]'::jsonb)),
           'no source monitor with a working extractor (page-change monitoring is not synchronization)'
    FROM degree_program p JOIN university u ON u.id = p.university_id
    WHERE p.id NOT IN (SELECT id FROM keep_program);
    GET DIAGNOSTICS r_prog = ROW_COUNT;

    INSERT INTO catalogue_archive (batch, entity_type, entity_key, snapshot, reason)
    SELECT v_batch, 'UNIVERSITY', u.slug,
           jsonb_build_object(
               'university', to_jsonb(u),
               'country', (SELECT c.iso2_code FROM country c WHERE c.id = u.country_id),
               'aliases', coalesce((SELECT jsonb_agg(to_jsonb(a)) FROM university_alias a WHERE a.university_id = u.id), '[]'::jsonb),
               'externalIds', coalesce((SELECT jsonb_agg(to_jsonb(x)) FROM university_external_id x WHERE x.university_id = u.id), '[]'::jsonb),
               'sources', coalesce((SELECT jsonb_agg(jsonb_build_object('id', s.id, 'url', s.url, 'title', s.title,
                                       'publisher', s.publisher, 'type', s.source_type, 'retrievedOn', s.retrieved_on))
                                   FROM university_source us JOIN source s ON s.id = us.source_id WHERE us.university_id = u.id), '[]'::jsonb)),
           'no program with a working extractor, so the university is not needed by the catalogue'
    FROM university u WHERE u.id NOT IN (SELECT id FROM keep_university);
    GET DIAGNOSTICS r_uni = ROW_COUNT;

    INSERT INTO catalogue_archive (batch, entity_type, entity_key, snapshot, reason)
    SELECT v_batch, 'SOURCE_MONITOR', s.url,
           jsonb_build_object(
               'monitor', to_jsonb(m),
               'url', s.url,
               'universitySlug', (SELECT u.slug FROM university u WHERE u.id = m.university_id),
               'programId', m.program_id,
               'checks', (SELECT jsonb_build_object('count', count(*), 'first', min(c.checked_at), 'last', max(c.checked_at))
                          FROM source_check c WHERE c.source_id = m.source_id)),
           'monitor only detects page changes or belongs to a retired record'
    FROM source_monitor m JOIN source s ON s.id = m.source_id
    WHERE m.source_id NOT IN (SELECT source_id FROM keep_monitor);
    GET DIAGNOSTICS r_mon = ROW_COUNT;

    -- ---- delete (children first; most children cascade from their program or university) ----------------------------
    DELETE FROM source_check WHERE source_id IN
        (SELECT source_id FROM source_monitor WHERE source_id NOT IN (SELECT source_id FROM keep_monitor));
    GET DIAGNOSTICS r_checks = ROW_COUNT;
    DELETE FROM source_monitor WHERE source_id NOT IN (SELECT source_id FROM keep_monitor);
    DELETE FROM degree_program WHERE id NOT IN (SELECT id FROM keep_program);
    DELETE FROM university WHERE id NOT IN (SELECT id FROM keep_university);

    -- sources nothing refers to any more: archive, then delete
    CREATE TEMP TABLE orphan_source ON COMMIT DROP AS
        SELECT s.id FROM source s
        WHERE NOT EXISTS (SELECT 1 FROM university_source x WHERE x.source_id = s.id)
          AND NOT EXISTS (SELECT 1 FROM program_source x WHERE x.source_id = s.id)
          AND NOT EXISTS (SELECT 1 FROM tuition_fee x WHERE x.source_id = s.id)
          AND NOT EXISTS (SELECT 1 FROM program_intake x WHERE x.source_id = s.id)
          AND NOT EXISTS (SELECT 1 FROM academic_requirement x WHERE x.source_id = s.id)
          AND NOT EXISTS (SELECT 1 FROM program_field_provenance x WHERE x.source_id = s.id)
          AND NOT EXISTS (SELECT 1 FROM program_fact_history x WHERE x.recorded_source_id = s.id OR x.superseded_by_source = s.id)
          AND NOT EXISTS (SELECT 1 FROM source_monitor x WHERE x.source_id = s.id)
          AND NOT EXISTS (SELECT 1 FROM source_check x WHERE x.source_id = s.id)
          AND NOT EXISTS (SELECT 1 FROM staged_change x WHERE x.source_id = s.id);
    INSERT INTO catalogue_archive (batch, entity_type, entity_key, snapshot, reason)
    SELECT v_batch, 'SOURCE', s.url, to_jsonb(s), 'source no longer referenced by any retained record'
    FROM source s WHERE s.id IN (SELECT id FROM orphan_source);
    GET DIAGNOSTICS r_src = ROW_COUNT;
    DELETE FROM source WHERE id IN (SELECT id FROM orphan_source);

    -- ---- verification: any failure raises, so the caller's transaction is rolled back ------------------------------
    SELECT count(*) INTO a_uni FROM university;
    SELECT count(*) INTO a_prog FROM degree_program;
    SELECT count(*) INTO a_mon FROM source_monitor;
    SELECT count(*) INTO a_src FROM source;

    IF v_exp_uni IS NOT NULL AND a_uni <> v_exp_uni::bigint THEN
        RAISE EXCEPTION 'verification failed: % universities remain, expected %', a_uni, v_exp_uni;
    END IF;
    IF v_exp_prog IS NOT NULL AND a_prog <> v_exp_prog::bigint THEN
        RAISE EXCEPTION 'verification failed: % programs remain, expected %', a_prog, v_exp_prog;
    END IF;
    IF a_prog <> (SELECT count(*) FROM keep_program) OR a_uni <> (SELECT count(*) FROM keep_university) THEN
        RAISE EXCEPTION 'verification failed: remaining rows differ from the retention decision';
    END IF;
    SELECT count(*) INTO n FROM degree_program p WHERE NOT EXISTS
        (SELECT 1 FROM source_monitor m WHERE m.program_id = p.id AND m.extraction_method IN ('ADAPTER', 'JSON_LD'));
    IF n <> 0 THEN RAISE EXCEPTION 'verification failed: % program(s) without an extractor remain', n; END IF;
    SELECT count(*) INTO n FROM university u WHERE NOT EXISTS (SELECT 1 FROM degree_program p WHERE p.university_id = u.id);
    IF n <> 0 THEN RAISE EXCEPTION 'verification failed: % university(ies) without a program remain', n; END IF;
    SELECT count(*) INTO n FROM source_monitor m WHERE m.extraction_method NOT IN ('ADAPTER', 'JSON_LD');
    IF n <> 0 THEN RAISE EXCEPTION 'verification failed: % page-change-only monitor(s) remain', n; END IF;
    IF (SELECT count(*) FROM flyway_schema_history) <> b_flyway
       OR (SELECT count(*) FROM country) <> b_country OR (SELECT count(*) FROM academic_field) <> b_field THEN
        RAISE EXCEPTION 'verification failed: a table this script must not touch changed';
    END IF;
    IF (SELECT count(*) FROM catalogue_archive WHERE batch = v_batch) <> r_prog + r_uni + r_mon + r_src THEN
        RAISE EXCEPTION 'verification failed: archive row count does not match the removed records';
    END IF;

    -- every single-column foreign key in public must still be satisfied (no orphans)
    FOR fk IN
        SELECT c.conname, c.conrelid::regclass AS child, a.attname AS child_col,
               c.confrelid::regclass AS parent, pa.attname AS parent_col
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        JOIN pg_attribute pa ON pa.attrelid = c.confrelid AND pa.attnum = c.confkey[1]
        WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace AND array_length(c.conkey, 1) = 1
    LOOP
        EXECUTE format('SELECT count(*) FROM %s ch WHERE ch.%I IS NOT NULL AND NOT EXISTS (SELECT 1 FROM %s pa WHERE pa.%I = ch.%I)',
                       fk.child, fk.child_col, fk.parent, fk.parent_col, fk.child_col) INTO n;
        IF n <> 0 THEN
            RAISE EXCEPTION 'verification failed: % orphan row(s) violate foreign key % on %', n, fk.conname, fk.child;
        END IF;
    END LOOP;

    DROP TABLE IF EXISTS pg_temp.keep_program, pg_temp.keep_university, pg_temp.keep_monitor, pg_temp.orphan_source;

    RAISE NOTICE 'batch %: universities % -> % (archived %), programs % -> % (archived %), monitors % -> % (archived %, % check rows removed), sources % -> % (archived %)',
        v_batch, b_uni, a_uni, r_uni, b_prog, a_prog, r_prog, b_mon, a_mon, r_mon, r_checks, b_src, a_src, r_src;
END
$cleanup$;
