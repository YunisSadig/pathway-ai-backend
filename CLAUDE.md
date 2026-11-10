# CLAUDE.md — Pathway AI Backend

Spring Boot 4.1.2 / Java 21 / Maven / PostgreSQL / Flyway. Package-by-feature under `com.pathwayai.backend`.

## Read first
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
