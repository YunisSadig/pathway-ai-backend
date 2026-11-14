-- V4: represent facts that belong to a specific year or period without pretending they are current.
-- Additive and backwards compatible: V1-V3 are untouched and every existing row stays valid.
--
-- 1. Tuition may now say WHICH period it applies to, in the way a source actually states it:
--      academic_year   an academic year the source prints (e.g. 2026/2027), or NULL when it prints none
--      effective_from / effective_to   a range of study-right start dates (e.g. "rights beginning on or after
--                      1 August 2025"), either end may be open
--      scope_note      the source's own wording of the scope, kept verbatim for audit
--    A fee whose source states no year or range keeps NULL for all of them: unknown stays unknown, and it
--    is never relabelled with a year taken from an adjacent heading or from the date a page was read.
ALTER TABLE tuition_fee ALTER COLUMN academic_year DROP NOT NULL;
ALTER TABLE tuition_fee
    ADD COLUMN effective_from DATE,
    ADD COLUMN effective_to   DATE,
    ADD COLUMN scope_note     VARCHAR(300),
    ADD CONSTRAINT ck_tuition_effective_range CHECK
        (effective_from IS NULL OR effective_to IS NULL OR effective_from <= effective_to);

-- The V1 unique key (program, academic_year, student_category) cannot dedupe rows whose year is NULL
-- (NULLs are distinct), so rows without a year get their own natural key.
CREATE UNIQUE INDEX uq_tuition_without_year ON tuition_fee
    (program_id, student_category, billing_period,
     COALESCE(effective_from, DATE '0001-01-01'), COALESCE(effective_to, DATE '9999-12-31'))
    WHERE academic_year IS NULL;

-- 2. Separate "where to read about admission" from "where to apply".
--    official_admissions_url  an official admissions-information page (always safe to show as information)
--    official_application_url  an official application page; V2 already requires a VERIFIED program
--    official_application_cycle  the cycle that application link is for (e.g. 2027). The API only presents the
--                  link as an active "Apply" link while that cycle is current or upcoming; an older cycle's link
--                  is never shown as active.
ALTER TABLE degree_program
    ADD COLUMN official_admissions_url    VARCHAR(1000),
    ADD COLUMN official_application_cycle VARCHAR(20),
    ADD CONSTRAINT ck_program_admissions_url_scheme CHECK
        (official_admissions_url IS NULL OR official_admissions_url ~ '^https?://'),
    -- NOT VALID: enforced for every new or updated row; rows imported before this migration are corrected by
    -- the importer (idempotent update), not by this migration.
    ADD CONSTRAINT ck_program_application_url_has_cycle CHECK
        (official_application_url IS NULL OR official_application_cycle IS NOT NULL) NOT VALID;
