-- V12: sign-in with an external provider (Google). Additive: one new table, one relaxed constraint.
-- The provider's stable subject id identifies the person, never the email address. An account that only signs in
-- with Google has no password, so password_hash may be NULL (it must never be filled with a made-up value).
ALTER TABLE user_account ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE external_identity (
    id         BIGSERIAL    PRIMARY KEY,
    account_id UUID         NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    provider   VARCHAR(20)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    email      VARCHAR(254),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_external_identity_provider CHECK (provider IN ('GOOGLE')),
    CONSTRAINT uq_external_identity_subject UNIQUE (provider, subject),
    CONSTRAINT uq_external_identity_account_provider UNIQUE (account_id, provider)
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pathway_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON external_identity TO pathway_app;
        GRANT USAGE, SELECT ON SEQUENCE external_identity_id_seq TO pathway_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pathway_api') THEN
        REVOKE ALL ON external_identity FROM pathway_api;
    END IF;
END
$$;
