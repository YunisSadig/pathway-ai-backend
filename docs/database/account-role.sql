-- Runtime database role of the API once student accounts exist.
--
-- Run ONCE as the database OWNER (Neon SQL editor), AFTER migration V9 has been applied (workflow "Source refresh", mode "migrate").
-- Create the role's password in the Neon console (Roles) or with ALTER ROLE; it never belongs in this file or in Git.
--
-- Principle of least privilege:
--   * every CATALOGUE table stays SELECT-only for the API, exactly as before;
--   * DML (insert/update/delete) is granted on the ACCOUNT tables only;
--   * the earlier read-only role (pathway_api) must not read credentials, so its default SELECT on the new tables is revoked.

CREATE ROLE pathway_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
GRANT CONNECT ON DATABASE pathway_ai TO pathway_app;
GRANT USAGE ON SCHEMA public TO pathway_app;

-- catalogue and everything else: read only
GRANT SELECT ON ALL TABLES IN SCHEMA public TO pathway_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO pathway_app;

-- account storage: the only tables the API may write
GRANT INSERT, UPDATE, DELETE ON
    user_account, account_token, student_profile, student_language_qualification, student_destination_preference,
    saved_program, saved_university, auth_throttle, spring_session, spring_session_attributes
TO pathway_app;
GRANT USAGE, SELECT ON SEQUENCE account_token_id_seq TO pathway_app;

-- the old read-only role has no business reading password hashes, tokens or sessions
REVOKE ALL ON
    user_account, account_token, student_profile, student_language_qualification, student_destination_preference,
    saved_program, saved_university, auth_throttle, spring_session, spring_session_attributes
FROM pathway_api;

-- Check (each should be as stated):
--   select has_table_privilege('pathway_app', 'degree_program', 'INSERT');   -- false
--   select has_table_privilege('pathway_app', 'degree_program', 'SELECT');   -- true
--   select has_table_privilege('pathway_app', 'user_account',   'INSERT');   -- true
--   select has_table_privilege('pathway_app', 'user_account',   'SELECT');   -- true
--   select has_table_privilege('pathway_api', 'user_account',   'SELECT');   -- false
