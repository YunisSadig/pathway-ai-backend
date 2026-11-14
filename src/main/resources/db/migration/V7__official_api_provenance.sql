-- V7: field-level provenance, external program identifiers and preserved history for facts read from an official
-- API (first provider: Finland's Studyinfo / Opintopolku). Additive only: no existing table loses a column or a row.

-- Identifiers a provider assigns to a program. (scheme, value) is globally unique so one official record can never
-- be imported twice, and a re-import finds the program it already created.
CREATE TABLE program_external_id (
    program_id BIGINT       NOT NULL REFERENCES degree_program (id) ON DELETE CASCADE,
    scheme     VARCHAR(40)  NOT NULL,
    value      VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (scheme, value),
    CONSTRAINT ck_program_external_id_scheme CHECK (scheme ~ '^[A-Z0-9_]+$')
);
CREATE INDEX idx_program_external_id_program ON program_external_id (program_id);

-- Where each synchronised field of a program came from and when it was last written. One row per field; a row is
-- only rewritten when the field's published value changes, never merely because a refresh confirmed it. The last
-- time a refresh confirmed the source is source_monitor.last_success_at.
CREATE TABLE program_field_provenance (
    program_id           BIGINT       NOT NULL REFERENCES degree_program (id) ON DELETE CASCADE,
    field_name           VARCHAR(40)  NOT NULL,
    source_id            BIGINT       NOT NULL REFERENCES source (id),
    mechanism            VARCHAR(30)  NOT NULL,
    provider_ref         VARCHAR(120),
    observed_at          TIMESTAMPTZ  NOT NULL,
    verified_on          DATE         NOT NULL,
    academic_year        VARCHAR(9),
    parser_version       VARCHAR(60),
    PRIMARY KEY (program_id, field_name),
    CONSTRAINT ck_provenance_mechanism CHECK (mechanism IN ('OFFICIAL_API', 'STRUCTURED_DATA', 'REVIEWED_PAGE', 'MANUAL'))
);
CREATE INDEX idx_provenance_source ON program_field_provenance (source_id);

-- A value that was replaced. The provider does not say when the old value stopped being valid, so superseded_at is
-- when the replacement was published here, and that is all it claims.
CREATE TABLE program_fact_history (
    id                    BIGSERIAL PRIMARY KEY,
    program_id            BIGINT       NOT NULL REFERENCES degree_program (id) ON DELETE CASCADE,
    field_name            VARCHAR(40)  NOT NULL,
    value                 TEXT,
    recorded_source_id    BIGINT REFERENCES source (id),
    superseded_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    superseded_by_source  BIGINT REFERENCES source (id),
    note                  VARCHAR(300)
);
CREATE INDEX idx_fact_history_program ON program_fact_history (program_id, field_name, superseded_at DESC);

-- The change pipeline learns four identity/classification facts.
ALTER TABLE staged_change DROP CONSTRAINT IF EXISTS ck_staged_fact_type;
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
     'PROGRAM_NAME', 'DEGREE_LEVEL', 'ACADEMIC_FIELD', 'CAMPUS_CITY'));
