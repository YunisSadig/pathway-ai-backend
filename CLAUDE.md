# CLAUDE.md — Pathway AI Backend

Spring Boot 4.1.2 / Java 21 / Maven / PostgreSQL / Flyway. Package-by-feature under `com.pathwayai.backend`.

## Read first
- `docs/product/MVP_SPEC.md`: the permanent product direction (global, program-first, all disciplines, bachelor and master, sourced data, USER/ADMIN server authorization, no invented acceptance probabilities). Read it before any product, schema or API work. The frontend repo keeps a matching copy.
Before any significant change (schema, dependencies, API contract, test setup), read:
- `docs/engineering/architecture-decisions.md`
- `docs/engineering/mistakes-and-fixes.md` (follow the "prevention rules")
- `docs/engineering/known-issues.md`

After hitting a real problem, add it to `mistakes-and-fixes.md` (error output, root cause, files, fix, verification, prevention rule, status). Unresolved items go in `known-issues.md`. Never record an unverified fix as successful.

## Rules
- Build with JDK 21: set `JAVA_HOME` to a JDK 21 before `.\mvnw.cmd clean verify` (machine default may be JDK 25). Run Maven from the repo root.
- Docker must be running for integration tests (`*IT`, Testcontainers).
- Never edit an applied Flyway migration; add a new version.
- Never fabricate university, admission or tuition data. Real facts need a `source` row. Tests use only synthetic fixtures (`SyntheticFixtures`).
- Never return `null` specifications / pass `null` to `Specification.and()` (Spring Data 4).
- Never expose JPA entities in API responses; use DTO records.
- Never commit `.env`, passwords or tokens.
- Use Conventional Commits; use feature branches + PRs after the initial commit; do not force-push.
- Do not add Security/AI/Redis/Kafka/etc. dependencies without an explicit request.
- Frontend contract: documented in README "REST API contract", pinned by `UniversityApiIT.jsonContractFieldNamesAreStable`. Renaming a JSON field is a breaking change: update README + test and tell the frontend.
- CORS: explicit origins only via `CORS_ALLOWED_ORIGINS` (`config/CorsConfig`); never a wildcard.
- Do not modify the frontend repo from here. CI (`.github/workflows/ci.yml`) and CD (`docs/deployment.md`) are documented separately; never provision hosting or incur charges without approval.
- Program-first API (Milestone 3A): `GET /api/v1/programs[/{identifier}]`; identifier is `<university-slug>--<program-slug>` (slugs never contain `--`). Contract is pinned by `ProgramApiIT`. Adding a field is non-breaking; renaming or removing one is breaking.
- NO static academic records (policy 2026-11-14, `docs/data/sync-policy.md`): a program, tuition, admission or scholarship fact is published only if a working, tested, repeatable extractor keeps it current. `reference-data/taxonomy.json` holds countries and academic fields only (`ReferenceDataFileTest` fails if a record is added; the startup runner refuses one unless `app.reference-data.allow-static-records=true`). Every fact needs a source, provenance and a refresh mechanism; unknown stays null. To add a program, add or extend a CONNECTOR first, never a JSON record.
- An official application URL is only stored for a VERIFIED program (database CHECK + importer validation). Do not weaken it.
- Year rule: store an academic year, cycle or date range ONLY when the cited source states it. Never infer it from the date a page was read, an adjacent heading or wording like "autumn 2027". Keep older-year rows; a new year is a new row. A wrong fact is corrected only by an explicit, reasoned, audited change (the old value is archived in `program_fact_history` / `catalogue_archive`), never silently.
- Apply link rule: `officialApplicationUrl` needs `officialApplicationCycle` and is only returned for a current/upcoming cycle; admissions-information pages go in `officialAdmissionsUrl`. Do not put an unread application platform page in either.
- After changing entity/DTO signatures run `mvnw clean verify` (not just `test-compile`): incremental compilation once reported success against stale test classes.
- pgAdmin/database access: see `docs/database/PGADMIN_GUIDE.md`. The Pathway AI database is port 5433 (Docker), not the native PostgreSQL on 5432. Never reset schemas or delete the Docker volume.
- Institution catalogue: `reference-data/universities.json` lists exactly the connector-backed universities (`UniversityCatalogueFileTest` compares it with `studyinfo.json`), imported by `UniversityCatalogueImporter` (`IMPORT_UNIVERSITY_CATALOGUE=true`; idempotent; fills empty columns only; verification only moves up). `VERIFIED` = registry match AND official site read. Never store a URL you did not read, never fill `institution_type` by guessing, and never add a university that has no retrievable, refreshable program. Process and limits: `docs/data/`.
- Source monitoring and synchronization: code in `com.pathwayai.backend.refresh` and `provider`. A program is eligible for publication only if a fact-extracting monitor (`ADAPTER` official API or `JSON_LD`) of its own is active and fresh; a `CONTENT_HASH` monitor never qualifies and is not registered by any importer. Deterministic official-API fields (duration, languages, campus city, new application rounds) update automatically; name, level, field, tuition, requirements and changed dated facts wait for review (`SyncPolicy` is the single statement of this); a dated fact for an existing period is never overwritten. Failures never touch published facts. There is NO HTTP control surface: sync, review and reactivation run via `--app.refresh.command=...` (`docs/data/sync-policy.md`, `change-review.md`).
- Crawler boundaries are code: https + approved hosts + public IPs on every redirect hop, robots.txt (RFC 9309), per-host spacing, size cap, bounded retries. Never bypass a challenge page, CAPTCHA, login or 403; mark the source UNSUPPORTED instead. Tests use recorded fixtures (`FakeTransport`), never live sites; run a new crawler path once against the real service before trusting the fixtures (see mistakes 18-19).
- Windows: stop the running API before `mvnw package/verify` (it locks `target/*.jar`), and set `JAVA_HOME` to the JDK 21 directory for every Maven call.
