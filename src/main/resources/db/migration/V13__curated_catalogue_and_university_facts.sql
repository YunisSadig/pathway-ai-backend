-- Additive only. No release is activated and no academic record is inserted by this migration.
CREATE TABLE catalog_release (
    id VARCHAR(80) PRIMARY KEY,
    country_code CHAR(2) NOT NULL REFERENCES country(iso2_code),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    approved_by VARCHAR(100),
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_catalog_approval CHECK (NOT enabled OR (approved_by IS NOT NULL AND approved_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_enabled_catalog_country ON catalog_release(country_code) WHERE enabled;

CREATE TABLE catalog_membership (
    release_id VARCHAR(80) NOT NULL REFERENCES catalog_release(id),
    program_id BIGINT NOT NULL REFERENCES degree_program(id),
    entry_level VARCHAR(20) NOT NULL CHECK (entry_level IN ('BACHELOR','MASTER')),
    qualification_pathway VARCHAR(30) NOT NULL CHECK (qualification_pathway IN ('SINGLE_DEGREE','BACHELOR_AND_MASTER')),
    education_oid VARCHAR(120) NOT NULL,
    implementation_oid VARCHAR(120) NOT NULL,
    -- A study option of the parent degree that the curation highlights; re-checked against the source on every import.
    study_option VARCHAR(120),
    PRIMARY KEY(release_id,program_id),
    UNIQUE(release_id,education_oid),
    UNIQUE(release_id,implementation_oid)
);

CREATE TABLE program_university_fact (
    program_id BIGINT NOT NULL REFERENCES degree_program(id),
    field_name VARCHAR(40) NOT NULL,
    scope_key VARCHAR(120) NOT NULL DEFAULT '',
    value TEXT NOT NULL,
    source_id BIGINT NOT NULL REFERENCES source(id),
    checked_at TIMESTAMPTZ NOT NULL,
    application_cycle VARCHAR(30),
    -- Only when the source states them; never inferred (a fee without a stated year keeps academic_year NULL).
    applicant_category VARCHAR(20) CHECK (applicant_category IS NULL OR applicant_category IN ('NON_EU_EEA','EU_EEA','ALL')),
    academic_year VARCHAR(9) CHECK (academic_year IS NULL OR academic_year ~ '^[0-9]{4}-[0-9]{4}$'),
    PRIMARY KEY(program_id,field_name,scope_key),
    CONSTRAINT ck_university_fact_field CHECK (field_name IN
      ('qualification','credits','duration_structure','study_mode','campus','eligibility','application_period',
       'tuition_scope','scholarship','overview','structure','specializations','documents','selection',
       'language_tests','entrance_tests','instructions','financial_url','curriculum_url',
       'official_program_url','official_admissions_url')),
    CONSTRAINT ck_university_fact_value CHECK (length(value) BETWEEN 1 AND 12000)
);

-- Health of each official destination of a program (university program page, admissions page, application portal).
-- A failed check never replaces or hides a verified URL by itself; only a verified replacement changes `url`.
CREATE TABLE program_link_status (
    program_id BIGINT NOT NULL REFERENCES degree_program(id),
    link_type VARCHAR(30) NOT NULL CHECK (link_type IN ('PROGRAM_PAGE','ADMISSIONS_PAGE','APPLICATION_PORTAL')),
    url VARCHAR(2048) NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('VERIFIED','REDIRECTED_VERIFIED','STALE','BROKEN','SOURCE_UNAVAILABLE',
        'PENDING_REVIEW','REPLACEMENT_VERIFIED')),
    final_url VARCHAR(2048),
    candidate_url VARCHAR(2048),
    detail VARCHAR(500),
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_checked_at TIMESTAMPTZ NOT NULL,
    last_verified_at TIMESTAMPTZ,
    PRIMARY KEY(program_id,link_type)
);

CREATE TABLE program_link_history (
    id BIGSERIAL PRIMARY KEY,
    program_id BIGINT NOT NULL REFERENCES degree_program(id),
    link_type VARCHAR(30) NOT NULL,
    old_url VARCHAR(2048),
    new_url VARCHAR(2048) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    changed_by VARCHAR(100) NOT NULL
);
CREATE INDEX idx_link_history_program ON program_link_history(program_id,changed_at);

ALTER TABLE staged_change DROP CONSTRAINT ck_staged_fact_type;
ALTER TABLE staged_change ADD CONSTRAINT ck_staged_fact_type CHECK (fact_type IN
 ('SOURCE_CONTENT','DURATION_MONTHS','LANGUAGES','TUITION','DEADLINE','REQUIREMENT',
  'PROGRAM_NAME','DEGREE_LEVEL','ACADEMIC_FIELD','CAMPUS_CITY','LIFECYCLE','UNIVERSITY_FACT'));

-- Existing runtime roles remain read-only for catalogue data. Operator writes use the established owner workflow.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='pathway_app') THEN
    GRANT SELECT ON catalog_release,catalog_membership,program_university_fact,program_link_status TO pathway_app;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='pathway_api') THEN
    GRANT SELECT ON catalog_release,catalog_membership,program_university_fact,program_link_status TO pathway_api;
  END IF;
END $$;
