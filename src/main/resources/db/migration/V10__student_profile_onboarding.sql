-- Student profile v2: guided onboarding, education history on named grading scales, skills, experience, exam results, destinations,
-- budget status, language and avatar. ADDITIVE ONLY: nothing is dropped or rewritten. The V9 columns (gpa, gpa_scale_max, current_*, field_of_study)
-- and the V9 language table stay exactly as they are and are shown to the student as "entered earlier"; they are never converted.

ALTER TABLE student_profile
    ADD COLUMN citizenship_country    CHAR(2),
    ADD COLUMN education_status       VARCHAR(12),
    ADD COLUMN completed_qualification VARCHAR(10),
    ADD COLUMN study_area             VARCHAR(20),
    ADD COLUMN specialization         VARCHAR(40),
    ADD COLUMN internships_status     VARCHAR(8),
    ADD COLUMN volunteering_status    VARCHAR(8),
    ADD COLUMN exams_status           VARCHAR(10),
    ADD COLUMN budget_status          VARCHAR(10),
    ADD COLUMN ui_language            VARCHAR(5),
    ADD COLUMN onboarding_status      VARCHAR(12) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN onboarding_step        SMALLINT    NOT NULL DEFAULT 0,
    ADD COLUMN onboarding_completed_at TIMESTAMPTZ;

-- Budgets in KZT or UZS exceed the old NUMERIC(10,2) range.
ALTER TABLE student_profile ALTER COLUMN max_annual_tuition TYPE NUMERIC(16,2);

-- Profiles that already hold data continue the guided onboarding instead of starting from nothing.
UPDATE student_profile SET onboarding_status = 'IN_PROGRESS' WHERE onboarding_status = 'NOT_STARTED';

ALTER TABLE student_profile
    ADD CONSTRAINT ck_profile_citizenship CHECK (citizenship_country IS NULL OR citizenship_country ~ '^[A-Z]{2}$'),
    ADD CONSTRAINT ck_profile_education_status CHECK (education_status IS NULL OR education_status IN ('HIGH_SCHOOL', 'BACHELOR', 'MASTER', 'GRADUATED')),
    ADD CONSTRAINT ck_profile_completed_qualification CHECK (completed_qualification IS NULL OR completed_qualification IN ('BACHELOR', 'MASTER')),
    ADD CONSTRAINT ck_profile_study_area CHECK (study_area IS NULL OR study_area IN ('CS_IT', 'ECON_BUSINESS')),
    ADD CONSTRAINT ck_profile_internships_status CHECK (internships_status IS NULL OR internships_status IN ('NONE', 'HAS')),
    ADD CONSTRAINT ck_profile_volunteering_status CHECK (volunteering_status IS NULL OR volunteering_status IN ('NONE', 'HAS')),
    ADD CONSTRAINT ck_profile_exams_status CHECK (exams_status IS NULL OR exams_status IN ('NOT_TAKEN', 'TAKEN')),
    ADD CONSTRAINT ck_profile_budget_status CHECK (budget_status IS NULL OR budget_status IN ('AMOUNT', 'NOT_SURE')),
    ADD CONSTRAINT ck_profile_ui_language CHECK (ui_language IS NULL OR ui_language IN ('en', 'ru')),
    ADD CONSTRAINT ck_profile_onboarding_status CHECK (onboarding_status IN ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETED')),
    ADD CONSTRAINT ck_profile_onboarding_step CHECK (onboarding_step BETWEEN 0 AND 6);

-- One row per education stage the student has told us about. A grade is stored exactly as printed, on a NAMED scale; it is never converted.
CREATE TABLE student_education (
    account_id      UUID        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    stage           VARCHAR(10) NOT NULL,
    institution     VARCHAR(160),
    country         CHAR(2),
    program         VARCHAR(160),
    current_year    SMALLINT,
    graduation_year SMALLINT,
    grade_status    VARCHAR(18),
    grade_scale     VARCHAR(12),
    grade_value     NUMERIC(10,3),
    grade_letter    VARCHAR(3),
    scale_label     VARCHAR(60),
    scale_min       NUMERIC(10,3),
    scale_max       NUMERIC(10,3),
    PRIMARY KEY (account_id, stage),
    CONSTRAINT ck_education_stage CHECK (stage IN ('SCHOOL', 'BACHELOR', 'MASTER')),
    CONSTRAINT ck_education_country CHECK (country IS NULL OR country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_education_current_year CHECK (current_year IS NULL OR current_year BETWEEN 1 AND 12),
    CONSTRAINT ck_education_graduation_year CHECK (graduation_year IS NULL OR graduation_year BETWEEN 1990 AND 2045),
    CONSTRAINT ck_education_grade_status CHECK (grade_status IS NULL OR grade_status IN ('ENTERED', 'NOT_AVAILABLE_YET')),
    CONSTRAINT ck_education_grade_scale CHECK (grade_scale IS NULL OR grade_scale IN
        ('FIVE_POINT', 'TEN_POINT', 'PERCENT_100', 'GPA_4', 'LETTER', 'OTHER')),
    -- defence in depth: the service validates the same ranges and explains them to the student
    CONSTRAINT ck_education_grade_range CHECK (
        grade_scale IS NULL OR grade_value IS NULL OR
        (grade_scale = 'FIVE_POINT'  AND grade_value BETWEEN 1 AND 5) OR
        (grade_scale = 'TEN_POINT'   AND grade_value BETWEEN 1 AND 10) OR
        (grade_scale = 'PERCENT_100' AND grade_value BETWEEN 0 AND 100) OR
        (grade_scale = 'GPA_4'       AND grade_value BETWEEN 0 AND 4) OR
        (grade_scale = 'OTHER'       AND scale_min IS NOT NULL AND scale_max IS NOT NULL AND scale_min < scale_max
                                     AND grade_value BETWEEN scale_min AND scale_max)),
    CONSTRAINT ck_education_letter CHECK (grade_letter IS NULL OR grade_letter ~ '^[A-F][+-]?$|^FX$'),
    CONSTRAINT ck_education_entered CHECK (
        grade_status IS DISTINCT FROM 'ENTERED' OR
        (grade_scale IS NOT NULL AND ((grade_scale = 'LETTER' AND grade_letter IS NOT NULL) OR (grade_scale <> 'LETTER' AND grade_value IS NOT NULL))))
);

CREATE TABLE student_skill (
    account_id UUID        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    skill_key  VARCHAR(70) NOT NULL,
    label      VARCHAR(60),
    PRIMARY KEY (account_id, skill_key),
    CONSTRAINT ck_skill_key CHECK (skill_key ~ '^[a-z0-9_]{1,40}$|^custom:.{1,60}$')
);

CREATE TABLE student_experience (
    id           BIGSERIAL    PRIMARY KEY,
    account_id   UUID         NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    kind         VARCHAR(12)  NOT NULL,
    organization VARCHAR(120) NOT NULL,
    role         VARCHAR(120) NOT NULL,
    field        VARCHAR(60),
    start_month  DATE,
    end_month    DATE,
    ongoing      BOOLEAN      NOT NULL DEFAULT FALSE,
    description  VARCHAR(1000),
    CONSTRAINT ck_experience_kind CHECK (kind IN ('INTERNSHIP', 'VOLUNTEERING')),
    CONSTRAINT ck_experience_dates CHECK (end_month IS NULL OR start_month IS NULL OR end_month >= start_month),
    CONSTRAINT ck_experience_ongoing CHECK (NOT ongoing OR end_month IS NULL)
);
CREATE INDEX idx_student_experience_account ON student_experience (account_id);

-- IELTS, TOEFL iBT and SAT only. The score is stored as reported, with the report format; scores of different exams are never compared.
CREATE TABLE student_exam_result (
    id         BIGSERIAL    PRIMARY KEY,
    account_id UUID         NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    exam       VARCHAR(8)   NOT NULL,
    variant    VARCHAR(14),
    test_date  DATE         NOT NULL,
    overall    NUMERIC(6,2) NOT NULL,
    CONSTRAINT ck_exam_name CHECK (exam IN ('IELTS', 'TOEFL', 'SAT')),
    CONSTRAINT ck_exam_variant CHECK (
        (exam = 'IELTS' AND variant IN ('ACADEMIC', 'GENERAL')) OR
        (exam = 'TOEFL' AND variant IN ('NEW_1_6', 'LEGACY_0_120')) OR
        (exam = 'SAT' AND variant IS NULL)),
    CONSTRAINT ck_exam_overall CHECK (
        (exam = 'IELTS' AND overall BETWEEN 1 AND 9 AND (overall * 2) = trunc(overall * 2)) OR
        (exam = 'TOEFL' AND variant = 'NEW_1_6' AND overall BETWEEN 1 AND 6 AND (overall * 2) = trunc(overall * 2)) OR
        (exam = 'TOEFL' AND variant = 'LEGACY_0_120' AND overall BETWEEN 0 AND 120 AND overall = trunc(overall)) OR
        (exam = 'SAT' AND overall BETWEEN 400 AND 1600 AND overall = trunc(overall))),
    CONSTRAINT uq_exam_result UNIQUE (account_id, exam, variant, test_date)
);
CREATE INDEX idx_student_exam_account ON student_exam_result (account_id);

CREATE TABLE student_exam_section (
    result_id BIGINT       NOT NULL REFERENCES student_exam_result (id) ON DELETE CASCADE,
    section   VARCHAR(16)  NOT NULL,
    score     NUMERIC(6,2) NOT NULL,
    PRIMARY KEY (result_id, section),
    CONSTRAINT ck_exam_section_name CHECK (section IN ('LISTENING', 'READING', 'WRITING', 'SPEAKING', 'MATH', 'READING_WRITING')),
    CONSTRAINT ck_exam_section_score CHECK (score >= 0 AND score <= 800)
);

-- Universities the student prefers; public slugs only (no foreign key to the catalogue, like saved items).
CREATE TABLE student_preferred_university (
    account_id      UUID         NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    university_slug VARCHAR(120) NOT NULL,
    PRIMARY KEY (account_id, university_slug),
    CONSTRAINT ck_preferred_university_slug CHECK (university_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

-- The avatar is stored in this database (already durable and backed up) as a small re-encoded JPEG: no ephemeral disk, no new service.
CREATE TABLE student_avatar (
    account_id   UUID        PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    content_type VARCHAR(20) NOT NULL DEFAULT 'image/jpeg',
    data         BYTEA       NOT NULL,
    version      INTEGER     NOT NULL DEFAULT 1,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_avatar_type CHECK (content_type = 'image/jpeg'),
    CONSTRAINT ck_avatar_size CHECK (octet_length(data) BETWEEN 100 AND 262144)
);

-- Runtime privileges. The migration runs as the database owner. When the runtime role (docs/database/account-role.sql) already exists it
-- receives DML on the NEW account tables only, and the earlier read-only role must not read them. A fresh database without these roles
-- (tests, local development) skips this block.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pathway_app') THEN
        GRANT INSERT, UPDATE, DELETE ON student_education, student_skill, student_experience, student_exam_result,
            student_exam_section, student_preferred_university, student_avatar TO pathway_app;
        GRANT USAGE, SELECT ON SEQUENCE student_experience_id_seq, student_exam_result_id_seq TO pathway_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pathway_api') THEN
        REVOKE ALL ON student_education, student_skill, student_experience, student_exam_result, student_exam_section,
            student_preferred_university, student_avatar FROM pathway_api;
    END IF;
END
$$;
