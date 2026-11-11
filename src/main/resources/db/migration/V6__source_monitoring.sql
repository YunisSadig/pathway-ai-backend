-- V6: automated official-source monitoring. Purely additive. Reuses the existing `source` table (an official page is
-- already identified by URL and cited by every fact); monitoring state lives in its own 1:1 table so facts and their
-- provenance are never touched by crawler bookkeeping.

-- 1. The source registry: one row per monitored official page.
CREATE TABLE source_monitor (
    source_id            BIGINT       PRIMARY KEY REFERENCES source (id) ON DELETE CASCADE,
    university_id        BIGINT       REFERENCES university (id) ON DELETE CASCADE,
    program_id           BIGINT       REFERENCES degree_program (id) ON DELETE CASCADE,
    domain               VARCHAR(255) NOT NULL,
    source_kind          VARCHAR(30)  NOT NULL,
    supported_fields     VARCHAR(300) NOT NULL DEFAULT '',
    extraction_method    VARCHAR(30)  NOT NULL DEFAULT 'CONTENT_HASH',
    adapter_name         VARCHAR(60)  NOT NULL DEFAULT 'content-hash',
    adapter_version      VARCHAR(20)  NOT NULL DEFAULT '1',
    -- ACTIVE: checked on schedule. FAILING: recent failures, still retried with back-off.
    -- UNSUPPORTED: cannot be accessed legitimately (robots.txt, challenge page, 403): never retried automatically.
    -- PAUSED: switched off by an operator.
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    unsupported_reason   VARCHAR(300),
    verification_status  VARCHAR(20)  NOT NULL DEFAULT 'UNVERIFIED',
    check_interval_days  INTEGER      NOT NULL DEFAULT 7,
    last_attempt_at      TIMESTAMPTZ,
    last_success_at      TIMESTAMPTZ,
    last_change_at       TIMESTAMPTZ,
    etag                 VARCHAR(300),
    last_modified        VARCHAR(100),
    content_hash         CHAR(64),
    -- Version of the canonicaliser/adapter that produced content_hash. A new version re-baselines the hash instead
    -- of reporting a phantom change.
    hash_parser_version  VARCHAR(40),
    last_result          VARCHAR(30),
    failure_count        INTEGER      NOT NULL DEFAULT 0,
    next_check_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_monitor_kind CHECK (source_kind IN
        ('UNIVERSITY_INFO', 'ADMISSIONS', 'PROGRAM_CATALOGUE', 'PROGRAM', 'TUITION', 'SCHOLARSHIP', 'DEADLINES')),
    CONSTRAINT ck_monitor_method CHECK (extraction_method IN ('CONTENT_HASH', 'JSON_LD', 'ADAPTER')),
    CONSTRAINT ck_monitor_status CHECK (status IN ('ACTIVE', 'FAILING', 'UNSUPPORTED', 'PAUSED')),
    CONSTRAINT ck_monitor_verification CHECK (verification_status IN ('UNVERIFIED', 'VERIFIED')),
    CONSTRAINT ck_monitor_interval CHECK (check_interval_days BETWEEN 1 AND 90),
    CONSTRAINT ck_monitor_failures CHECK (failure_count >= 0)
);
CREATE INDEX idx_monitor_due ON source_monitor (next_check_at) WHERE status IN ('ACTIVE', 'FAILING');
CREATE INDEX idx_monitor_domain ON source_monitor (domain);
CREATE INDEX idx_monitor_university ON source_monitor (university_id);
CREATE INDEX idx_monitor_program ON source_monitor (program_id);

-- 2. Every check attempt, successful or not (audit trail and failure analysis).
CREATE TABLE source_check (
    id             BIGSERIAL    PRIMARY KEY,
    source_id      BIGINT       NOT NULL REFERENCES source (id) ON DELETE CASCADE,
    checked_at     TIMESTAMPTZ  NOT NULL,
    outcome        VARCHAR(20)  NOT NULL,
    http_status    INTEGER,
    content_hash   CHAR(64),
    duration_ms    INTEGER,
    parser_version VARCHAR(40),
    error          VARCHAR(500),
    CONSTRAINT ck_check_outcome CHECK (outcome IN
        ('BASELINE', 'UNCHANGED', 'NOT_MODIFIED', 'CHANGED', 'FAILED', 'BLOCKED', 'RATE_LIMITED'))
);
CREATE INDEX idx_check_source_time ON source_check (source_id, checked_at DESC);
CREATE INDEX idx_check_time ON source_check (checked_at);

-- 3. Staging area: a proposed change to a published fact is written here first. Nothing is published from a fetched
--    page directly. The dedupe_key makes processing the same page twice a no-op.
CREATE TABLE staged_change (
    id                BIGSERIAL    PRIMARY KEY,
    source_id         BIGINT       NOT NULL REFERENCES source (id) ON DELETE CASCADE,
    university_id     BIGINT       REFERENCES university (id) ON DELETE CASCADE,
    program_id        BIGINT       REFERENCES degree_program (id) ON DELETE CASCADE,
    fact_type         VARCHAR(30)  NOT NULL,
    fact_key          VARCHAR(200) NOT NULL DEFAULT '',
    old_value         TEXT,
    new_value         TEXT         NOT NULL,
    risk              VARCHAR(10)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    reason            VARCHAR(500),
    parser_version    VARCHAR(40)  NOT NULL,
    dedupe_key        CHAR(64)     NOT NULL,
    detected_at       TIMESTAMPTZ  NOT NULL,
    decided_at        TIMESTAMPTZ,
    decided_by        VARCHAR(100),
    CONSTRAINT uq_staged_change_dedupe UNIQUE (dedupe_key),
    CONSTRAINT ck_staged_fact_type CHECK (fact_type IN
        ('SOURCE_CONTENT', 'DURATION_MONTHS', 'LANGUAGES', 'TUITION', 'DEADLINE', 'REQUIREMENT')),
    CONSTRAINT ck_staged_risk CHECK (risk IN ('LOW', 'HIGH')),
    CONSTRAINT ck_staged_status CHECK (status IN
        ('PENDING_REVIEW', 'AUTO_APPLIED', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    CONSTRAINT ck_staged_decided CHECK (status = 'PENDING_REVIEW' OR decided_at IS NOT NULL)
);
CREATE INDEX idx_staged_status ON staged_change (status, detected_at);
CREATE INDEX idx_staged_source ON staged_change (source_id);
