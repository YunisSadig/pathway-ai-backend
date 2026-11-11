-- V2: program-first model. Purely additive: nothing from V1 is dropped or rewritten.
-- No data is seeded here. Reference data is imported separately (see docs/engineering/architecture-decisions.md, ADR-11).
-- Every new column on degree_program is nullable (or has a safe default) so that legitimate programs
-- with incomplete information can still be imported. Unknown stays NULL.

-- Normalised academic-field taxonomy. New subjects are new rows, never code changes.
-- Two levels are supported by the API filter (a field and its direct children).
CREATE TABLE academic_field (
    id         BIGSERIAL    PRIMARY KEY,
    parent_id  BIGINT       REFERENCES academic_field (id),
    slug       VARCHAR(120) NOT NULL,
    name       VARCHAR(150) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_academic_field_slug UNIQUE (slug),
    CONSTRAINT ck_academic_field_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_academic_field_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX idx_academic_field_parent_id ON academic_field (parent_id);

ALTER TABLE degree_program
    ADD COLUMN academic_field_id        BIGINT REFERENCES academic_field (id),
    ADD COLUMN campus_city              VARCHAR(120),
    ADD COLUMN study_mode               VARCHAR(20),
    ADD COLUMN official_program_url     VARCHAR(1000),
    ADD COLUMN official_application_url VARCHAR(1000),
    ADD COLUMN verification_status      VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
    ADD COLUMN verified_on              DATE,
    ADD CONSTRAINT ck_program_study_mode CHECK (study_mode IS NULL OR study_mode IN
        ('FULL_TIME', 'PART_TIME', 'DISTANCE', 'BLENDED')),
    ADD CONSTRAINT ck_program_verification_status CHECK (verification_status IN ('UNVERIFIED', 'VERIFIED')),
    ADD CONSTRAINT ck_program_verified_has_date CHECK (verification_status <> 'VERIFIED' OR verified_on IS NOT NULL),
    -- An application link is only ever stored for a verified program, so a client can trust it.
    ADD CONSTRAINT ck_program_application_url_verified CHECK
        (official_application_url IS NULL OR verification_status = 'VERIFIED'),
    ADD CONSTRAINT ck_program_program_url_scheme CHECK
        (official_program_url IS NULL OR official_program_url ~ '^https?://'),
    ADD CONSTRAINT ck_program_application_url_scheme CHECK
        (official_application_url IS NULL OR official_application_url ~ '^https?://'),
    -- NOT VALID: enforced for new rows without failing on any pre-existing row.
    -- The "--" separator of the public program identifier relies on slugs never containing "--".
    ADD CONSTRAINT ck_program_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$') NOT VALID;

CREATE INDEX idx_program_academic_field_id ON degree_program (academic_field_id);
CREATE INDEX idx_program_degree_level ON degree_program (degree_level);
CREATE INDEX idx_program_name_lower ON degree_program (lower(name));

-- ISO 639-1 (or 639-3) language codes; a program may be taught in several languages.
CREATE TABLE program_language (
    program_id    BIGINT      NOT NULL REFERENCES degree_program (id) ON DELETE CASCADE,
    language_code VARCHAR(8)  NOT NULL,
    PRIMARY KEY (program_id, language_code),
    CONSTRAINT ck_program_language_code CHECK (language_code ~ '^[a-z]{2,3}$')
);
CREATE INDEX idx_program_language_code ON program_language (language_code, program_id);

-- Official pages that back a program's descriptive facts (name, level, language, duration...).
CREATE TABLE program_source (
    program_id BIGINT NOT NULL REFERENCES degree_program (id) ON DELETE CASCADE,
    source_id  BIGINT NOT NULL REFERENCES source (id),
    PRIMARY KEY (program_id, source_id)
);
CREATE INDEX idx_program_source_source_id ON program_source (source_id);

-- Intake periods and deadlines change every year and per applicant category, so they are
-- their own facts, each citing a source. All dates are optional: unknown stays unknown.
CREATE TABLE program_intake (
    id                   BIGSERIAL    PRIMARY KEY,
    program_id           BIGINT       NOT NULL REFERENCES degree_program (id) ON DELETE CASCADE,
    source_id            BIGINT       NOT NULL REFERENCES source (id),
    label                VARCHAR(100) NOT NULL,
    academic_year        VARCHAR(9),
    start_date           DATE,
    application_deadline DATE,
    applicant_category   VARCHAR(20),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_intake_category CHECK (applicant_category IS NULL
        OR applicant_category IN ('DOMESTIC', 'INTERNATIONAL'))
);
CREATE INDEX idx_intake_program_id ON program_intake (program_id);
CREATE INDEX idx_intake_source_id ON program_intake (source_id);
-- Natural key used by the idempotent importer.
CREATE UNIQUE INDEX uq_intake_natural ON program_intake
    (program_id, label, COALESCE(academic_year, ''), COALESCE(applicant_category, ''));
