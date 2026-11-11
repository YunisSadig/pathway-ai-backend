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
- Real reference data lives in `src/main/resources/reference-data/programs.json` and is imported by `ReferenceDataImporter` (idempotent, additive, never deletes). It is NOT a migration. Every fact needs a cited official source; `ReferenceDataFileTest` guards the file. Never add a record you did not read on an official page; unknown stays null.
- An official application URL is only stored for a VERIFIED program (database CHECK + importer validation). Do not weaken it.
- Year rule: store an academic year, cycle or date range ONLY when the cited source states it. Never infer it from the date a page was read, an adjacent heading or wording like "autumn 2027". Keep older-year rows; a new year is a new row. Wrong facts are removed only by an explicit, reasoned `retractions` entry in the reference-data file.
- Apply link rule: `officialApplicationUrl` needs `officialApplicationCycle` and is only returned for a current/upcoming cycle; admissions-information pages go in `officialAdmissionsUrl`. Do not put an unread application platform page in either.
- After changing entity/DTO signatures run `mvnw clean verify` (not just `test-compile`): incremental compilation once reported success against stale test classes.
- pgAdmin/database access: see `docs/database/PGADMIN_GUIDE.md`. The Pathway AI database is port 5433 (Docker), not the native PostgreSQL on 5432. Never reset schemas or delete the Docker volume.
