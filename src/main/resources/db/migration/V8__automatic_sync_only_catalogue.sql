-- V8: automatic-synchronization-only catalogue.
--
-- 1. A program can be DISCONTINUED when its official source says so (for Studyinfo: the record's own state is
--    "arkistoitu"/"poistettu"). The row, its facts and its provenance are kept; public discovery hides it.
-- 2. catalogue_archive keeps a queryable snapshot of records removed when the catalogue is cleaned of programs that have
--    no working extractor (scripts/db/retire-unsynchronised-catalogue.sql). It has no foreign keys on purpose, so it
--    survives the deletes it documents. A full pg_dump is taken before any cleanup as well.
-- 3. The change pipeline audits automatic lifecycle changes in staged_change (fact type LIFECYCLE).

ALTER TABLE degree_program
    ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN discontinued_at  TIMESTAMPTZ,
    ADD CONSTRAINT ck_program_lifecycle CHECK (lifecycle_status IN ('ACTIVE', 'DISCONTINUED')),
    ADD CONSTRAINT ck_program_discontinued_at CHECK (lifecycle_status = 'ACTIVE' OR discontinued_at IS NOT NULL);

DO $$
DECLARE c TEXT;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'staged_change'::regclass AND contype = 'c'
               AND pg_get_constraintdef(oid) LIKE '%fact_type%' LOOP
        EXECUTE 'ALTER TABLE staged_change DROP CONSTRAINT ' || quote_ident(c);
    END LOOP;
END $$;
ALTER TABLE staged_change ADD CONSTRAINT ck_staged_fact_type CHECK (fact_type IN
    ('SOURCE_CONTENT', 'DURATION_MONTHS', 'LANGUAGES', 'TUITION', 'DEADLINE', 'REQUIREMENT',
     'PROGRAM_NAME', 'DEGREE_LEVEL', 'ACADEMIC_FIELD', 'CAMPUS_CITY', 'LIFECYCLE'));

CREATE TABLE catalogue_archive (
    id          BIGSERIAL    PRIMARY KEY,
    batch       VARCHAR(80)  NOT NULL,
    entity_type VARCHAR(40)  NOT NULL,
    entity_key  VARCHAR(300) NOT NULL,
    snapshot    JSONB        NOT NULL,
    reason      VARCHAR(300) NOT NULL,
    archived_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_catalogue_archive_batch ON catalogue_archive (batch, entity_type);
