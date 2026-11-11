-- V5: institution identity for the EU + Asia catalogue. Purely additive: nothing from V1-V4 is dropped or rewritten,
-- and every existing university row stays valid (new columns are nullable or have a safe default).
-- No data is seeded here. Institutions are imported from reference-data/universities.json by UniversityCatalogueImporter.

-- 1. Facts about the institution itself. Unknown stays NULL; nothing is inferred.
--    verification_status VERIFIED       identity confirmed in the EU HEI registry (where covered) AND the official
--                                       website was read (HTTP 200, identity found on the page) on verified_on
--                        REGISTRY_ONLY  identity confirmed in an authoritative register, but the official website
--                                       could not be read by automated access, so no website is stored
--                        UNVERIFIED     default for rows created before this migration
ALTER TABLE university
    ADD COLUMN native_name             VARCHAR(250),
    ADD COLUMN institution_type        VARCHAR(40),
    ADD COLUMN admissions_url          VARCHAR(1000),
    ADD COLUMN programme_catalogue_url VARCHAR(1000),
    ADD COLUMN verification_status     VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
    ADD COLUMN verified_on             DATE,
    ADD CONSTRAINT ck_university_verification_status CHECK
        (verification_status IN ('UNVERIFIED', 'REGISTRY_ONLY', 'VERIFIED')),
    ADD CONSTRAINT ck_university_verified_has_date CHECK
        (verification_status = 'UNVERIFIED' OR verified_on IS NOT NULL),
    -- A website is only ever stored for an institution whose official site was actually read.
    ADD CONSTRAINT ck_university_registry_only_no_website CHECK
        (verification_status <> 'REGISTRY_ONLY' OR website_url IS NULL),
    ADD CONSTRAINT ck_university_admissions_url_scheme CHECK
        (admissions_url IS NULL OR admissions_url ~ '^https?://'),
    ADD CONSTRAINT ck_university_catalogue_url_scheme CHECK
        (programme_catalogue_url IS NULL OR programme_catalogue_url ~ '^https?://');

-- 2. Stable external identifiers (SCHAC home organisation, Erasmus code, ECHE number, PIC, ...).
--    A given (scheme, value) identifies exactly one university, which is what makes the import idempotent
--    and survives a rename.
CREATE TABLE university_external_id (
    university_id BIGINT       NOT NULL REFERENCES university (id) ON DELETE CASCADE,
    scheme        VARCHAR(40)  NOT NULL,
    value         VARCHAR(120) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (scheme, value),
    CONSTRAINT ck_university_external_id_scheme CHECK (scheme ~ '^[A-Z0-9_]+$')
);
CREATE INDEX idx_university_external_id_university ON university_external_id (university_id);

-- 3. Alternative names (registry label, former names, native-language spellings) used to resolve an institution
--    that is already present under a different name instead of creating a duplicate.
CREATE TABLE university_alias (
    university_id BIGINT       NOT NULL REFERENCES university (id) ON DELETE CASCADE,
    alias         VARCHAR(300) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (university_id, alias)
);
CREATE INDEX idx_university_alias_lower ON university_alias (lower(alias));

-- 4. Pages that back the institution's identity (registry record, official homepage).
CREATE TABLE university_source (
    university_id BIGINT NOT NULL REFERENCES university (id) ON DELETE CASCADE,
    source_id     BIGINT NOT NULL REFERENCES source (id),
    PRIMARY KEY (university_id, source_id)
);
CREATE INDEX idx_university_source_source_id ON university_source (source_id);

CREATE INDEX idx_university_verification ON university (verification_status);
