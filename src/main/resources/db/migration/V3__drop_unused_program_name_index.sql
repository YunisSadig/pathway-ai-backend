-- V3: drop an index that V2 created speculatively and that measurement showed is never used.
--
-- Measured on PostgreSQL 17 with 20,008 programs: the name search is a case-insensitive "contains" match
-- (lower(name) LIKE '%text%'). A btree index on lower(name) cannot serve a leading-wildcard LIKE, so the
-- planner chose a sequential scan (about 5 ms) and idx_program_name_lower recorded 0 scans in pg_stat_user_indexes,
-- while it still cost write time and disk. A trigram (pg_trgm) index would help only at a much larger scale
-- than the measured 5 ms justifies, so none is added. See docs/engineering/known-issues.md.
-- V2 is already applied in some databases and is immutable, hence a new migration rather than an edit.
DROP INDEX IF EXISTS idx_program_name_lower;
