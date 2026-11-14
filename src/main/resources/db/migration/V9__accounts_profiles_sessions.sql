-- V9: student accounts, profiles, saved items, one-time tokens, login throttling and server-side HTTP sessions.
-- Additive only: no catalogue table is touched. The runtime role gets DML on these tables ONLY (docs/database/account-role.sql);
-- it keeps SELECT-only access to every catalogue table.

CREATE TABLE user_account (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(254) NOT NULL,
    email_normalized    VARCHAR(254) NOT NULL,
    password_hash       VARCHAR(100) NOT NULL,
    display_name        VARCHAR(80),
    -- USER or ADMIN, enforced on the server only (docs/product/MVP_SPEC.md section 5)
    role                VARCHAR(10)  NOT NULL DEFAULT 'USER',
    -- access to the academic catalogue while it is in protected beta (the Studyinfo reuse conditions are not established yet)
    catalogue_access    BOOLEAN      NOT NULL DEFAULT FALSE,
    email_verified_at   TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_account_email UNIQUE (email_normalized),
    CONSTRAINT ck_user_account_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_user_account_email_normalized CHECK (email_normalized = lower(btrim(email_normalized)))
);

-- One-time tokens (email verification, password reset). Only the SHA-256 of the token is stored.
CREATE TABLE account_token (
    id          BIGSERIAL    PRIMARY KEY,
    account_id  UUID         NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    purpose     VARCHAR(20)  NOT NULL,
    token_hash  CHAR(64)     NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_account_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_account_token_purpose CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD'))
);
CREATE INDEX idx_account_token_account ON account_token (account_id, purpose);

-- The student's own information. Every column is optional; nothing here is a score or a recommendation.
CREATE TABLE student_profile (
    account_id                 UUID         PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    current_country            CHAR(2),
    current_university         VARCHAR(160),
    current_degree_level       VARCHAR(20),
    field_of_study             VARCHAR(120),
    gpa                        NUMERIC(6,2),
    gpa_scale_max              NUMERIC(6,2),
    target_degree_level        VARCHAR(20),
    intake_season              VARCHAR(10),
    intake_year                SMALLINT,
    max_annual_tuition         NUMERIC(10,2),
    max_annual_tuition_currency CHAR(3),
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_profile_country CHECK (current_country IS NULL OR current_country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_profile_current_level CHECK (current_degree_level IS NULL OR current_degree_level IN
        ('SECONDARY', 'BACHELOR', 'MASTER', 'DOCTORATE', 'OTHER')),
    CONSTRAINT ck_profile_target_level CHECK (target_degree_level IS NULL OR target_degree_level IN
        ('BACHELOR', 'MASTER', 'DOCTORATE')),
    CONSTRAINT ck_profile_gpa CHECK (gpa IS NULL OR (gpa >= 0 AND gpa_scale_max IS NOT NULL AND gpa <= gpa_scale_max)),
    CONSTRAINT ck_profile_gpa_scale CHECK (gpa_scale_max IS NULL OR gpa_scale_max > 0),
    CONSTRAINT ck_profile_intake_season CHECK (intake_season IS NULL OR intake_season IN ('SPRING', 'SUMMER', 'AUTUMN', 'WINTER')),
    CONSTRAINT ck_profile_intake_year CHECK (intake_year IS NULL OR intake_year BETWEEN 2024 AND 2045),
    CONSTRAINT ck_profile_budget CHECK (max_annual_tuition IS NULL OR (max_annual_tuition >= 0 AND max_annual_tuition_currency IS NOT NULL)),
    CONSTRAINT ck_profile_budget_currency CHECK (max_annual_tuition_currency IS NULL OR max_annual_tuition_currency ~ '^[A-Z]{3}$')
);

-- Normalised so a future matching engine can query them directly instead of parsing a blob.
CREATE TABLE student_language_qualification (
    account_id  UUID         NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    test        VARCHAR(20)  NOT NULL,
    test_name   VARCHAR(60),
    overall     NUMERIC(6,2) NOT NULL,
    PRIMARY KEY (account_id, test),
    CONSTRAINT ck_language_test CHECK (test IN ('IELTS', 'TOEFL_IBT', 'PTE', 'DUOLINGO', 'CAMBRIDGE', 'OTHER')),
    CONSTRAINT ck_language_overall CHECK (overall >= 0 AND overall <= 1000)
);

CREATE TABLE student_destination_preference (
    account_id    UUID     NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    country_code  CHAR(2)  NOT NULL,
    PRIMARY KEY (account_id, country_code),
    CONSTRAINT ck_destination_country CHECK (country_code ~ '^[A-Z]{2}$')
);

-- Saved items keep the stable public identifier, not a database id: they survive a program being withdrawn and re-listed, and the
-- account schema stays independent of the catalogue schema. Names are never copied here; they are read from the catalogue when shown.
CREATE TABLE saved_program (
    account_id          UUID         NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    program_identifier  VARCHAR(250) NOT NULL,
    saved_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, program_identifier),
    CONSTRAINT ck_saved_program_identifier CHECK (program_identifier ~ '^[a-z0-9]+(-{1,2}[a-z0-9]+)*$')
);

CREATE TABLE saved_university (
    account_id       UUID         NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    university_slug  VARCHAR(120) NOT NULL,
    saved_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, university_slug),
    CONSTRAINT ck_saved_university_slug CHECK (university_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

-- Brute-force and abuse limits that survive restarts. The key is a SHA-256 of "<kind>:<value>" (an email or an address), never the value.
CREATE TABLE auth_throttle (
    key_hash          CHAR(64)    PRIMARY KEY,
    hits              INTEGER     NOT NULL DEFAULT 0,
    window_started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until      TIMESTAMPTZ
);
CREATE INDEX idx_auth_throttle_window ON auth_throttle (window_started_at);

-- Spring Session JDBC (server-side sessions; the browser only holds an opaque, HttpOnly cookie).
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36) NOT NULL,
    SESSION_ID            CHAR(36) NOT NULL,
    CREATION_TIME         BIGINT   NOT NULL,
    LAST_ACCESS_TIME      BIGINT   NOT NULL,
    MAX_INACTIVE_INTERVAL INT      NOT NULL,
    EXPIRY_TIME           BIGINT   NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);
