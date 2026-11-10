-- V1: core reference schema for university discovery.
-- No data is seeded here: admission/tuition facts must come from cited official sources.
-- Migration rule: never edit an applied migration; add V2, V3, ... instead.

CREATE TABLE country (
    id          BIGSERIAL    PRIMARY KEY,
    iso2_code   CHAR(2)      NOT NULL,
    name        VARCHAR(120) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_country_iso2 UNIQUE (iso2_code),
    CONSTRAINT uq_country_name UNIQUE (name),
    CONSTRAINT ck_country_iso2_upper CHECK (iso2_code = upper(iso2_code))
);

-- Official web pages/documents that back every changing fact (requirements, tuition).
CREATE TABLE source (
    id           BIGSERIAL     PRIMARY KEY,
    url          VARCHAR(1000) NOT NULL,
    title        VARCHAR(300)  NOT NULL,
    publisher    VARCHAR(200),
    source_type  VARCHAR(40)   NOT NULL,
    retrieved_on DATE          NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_source_url UNIQUE (url),
    CONSTRAINT ck_source_type CHECK (source_type IN
        ('UNIVERSITY_WEBSITE', 'GOVERNMENT', 'ACCREDITATION_BODY', 'OTHER'))
);

CREATE TABLE university (
    id          BIGSERIAL     PRIMARY KEY,
    country_id  BIGINT        NOT NULL REFERENCES country (id),
    slug        VARCHAR(120)  NOT NULL,
    name        VARCHAR(250)  NOT NULL,
    city        VARCHAR(120),
    website_url VARCHAR(500),
    description TEXT,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_university_slug UNIQUE (slug),
    CONSTRAINT ck_university_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);
CREATE INDEX idx_university_country_id ON university (country_id);
CREATE INDEX idx_university_name_lower ON university (lower(name));

CREATE TABLE degree_program (
    id                BIGSERIAL    PRIMARY KEY,
    university_id     BIGINT       NOT NULL REFERENCES university (id) ON DELETE CASCADE,
    slug              VARCHAR(120) NOT NULL,
    name              VARCHAR(250) NOT NULL,
    degree_level      VARCHAR(20)  NOT NULL,
    field_of_study    VARCHAR(150),
    duration_months   INTEGER,
    teaching_language VARCHAR(60),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_program_university_slug UNIQUE (university_id, slug),
    CONSTRAINT ck_program_level CHECK (degree_level IN
        ('BACHELOR', 'MASTER', 'DOCTORATE', 'DIPLOMA', 'CERTIFICATE')),
    CONSTRAINT ck_program_duration CHECK (duration_months IS NULL OR duration_months > 0)
);
CREATE INDEX idx_program_university_id ON degree_program (university_id);
CREATE INDEX idx_program_level_field ON degree_program (degree_level, field_of_study);

-- Requirements change every cycle, so each row is scoped to an application cycle
-- (e.g. '2026/27') and must cite a source.
CREATE TABLE academic_requirement (
    id                BIGSERIAL    PRIMARY KEY,
    program_id        BIGINT       NOT NULL REFERENCES degree_program (id) ON DELETE CASCADE,
    source_id         BIGINT       NOT NULL REFERENCES source (id),
    application_cycle VARCHAR(20)  NOT NULL,
    requirement_type  VARCHAR(40)  NOT NULL,
    description       TEXT         NOT NULL,
    minimum_value     NUMERIC(8,2),
    value_unit        VARCHAR(40),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_requirement_type CHECK (requirement_type IN
        ('MIN_GPA', 'LANGUAGE_TEST', 'ENTRANCE_EXAM', 'PRIOR_DEGREE', 'DOCUMENT', 'OTHER'))
);
CREATE INDEX idx_requirement_program_cycle ON academic_requirement (program_id, application_cycle);
CREATE INDEX idx_requirement_source_id ON academic_requirement (source_id);

-- One tuition figure per program, academic year and student category.
CREATE TABLE tuition_fee (
    id               BIGSERIAL     PRIMARY KEY,
    program_id       BIGINT        NOT NULL REFERENCES degree_program (id) ON DELETE CASCADE,
    source_id        BIGINT        NOT NULL REFERENCES source (id),
    academic_year    VARCHAR(9)    NOT NULL,
    student_category VARCHAR(20)   NOT NULL,
    amount           NUMERIC(12,2) NOT NULL,
    currency         CHAR(3)       NOT NULL,
    billing_period   VARCHAR(20)   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_tuition_program_year_category UNIQUE (program_id, academic_year, student_category),
    CONSTRAINT ck_tuition_amount CHECK (amount >= 0),
    CONSTRAINT ck_tuition_category CHECK (student_category IN ('DOMESTIC', 'INTERNATIONAL')),
    CONSTRAINT ck_tuition_period CHECK (billing_period IN ('PER_YEAR', 'PER_SEMESTER', 'PER_PROGRAM')),
    CONSTRAINT ck_tuition_currency_upper CHECK (currency = upper(currency))
);
CREATE INDEX idx_tuition_source_id ON tuition_fee (source_id);
