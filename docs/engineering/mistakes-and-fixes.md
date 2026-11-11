# Mistakes and Fixes

Only problems that actually happened during development. Each entry was verified by a re-run unless stated otherwise.

---

## 1. Spring Data 4 rejects `null` specifications → search returned HTTP 500

- **Error output:** `java.lang.IllegalArgumentException: Other specification must not be null` (HTTP 500 on `GET /api/v1/universities` with no filters).
- **Root cause:** `UniversitySpecifications.nameContains/inCountry` returned `null` for blank input (the Spring Data JPA 3 idiom). `Specification.and(null)` is no longer tolerated in Spring Data JPA 4 / Boot 4.
- **Affected files:** `university/repository/UniversitySpecifications.java`, `UniversitySpecificationsTest`.
- **Fix:** blank input now returns an explicit match-all spec (`cb.conjunction()`); unit test asserts the non-null match-all behaviour.
- **Verification:** `UniversityRepositoryIT` and `UniversityApiIT` pass in `mvnw clean verify`; live smoke test of the unfiltered list returned 200.
- **Prevention rule:** never return `null` from a `Specification` factory or pass `null` to `and()/or()`. Any code written against Boot 3 idioms must be checked against Boot 4 behaviour by an integration test, not assumed.
- **Status:** fixed.

## 2. Docker port 5432 already taken by a native PostgreSQL

- **Error output:** app failed with `FATAL: role "pathway" does not exist` although `psql` inside the container worked.
- **Root cause:** a Windows-native `postgres` process was listening on `0.0.0.0:5432`, so the host connection never reached the container.
- **Affected files:** `docker-compose.yaml`, `.env.example`, `application.yaml`.
- **Fix:** default host port changed to **5433** everywhere (override with `POSTGRES_PORT`/`DB_URL`). The unrelated native PostgreSQL was not touched.
- **Verification:** app started, Flyway V1 applied, all endpoints smoke-tested against the container.
- **Prevention rule:** check `Get-NetTCPConnection -LocalPort <port>` before assuming a published Docker port is reachable.
- **Status:** fixed.

## 3. Maven ran on JDK 25 although the project targets Java 21

- **Evidence:** baseline test log showed `Starting ... using Java 25.0.2`; `JAVA_HOME=C:\Program Files\Java\jdk-25.0.2` while `java -version` (PATH) was Temurin 21.
- **Root cause:** `mvnw` uses `JAVA_HOME`, not `PATH`.
- **Fix:** all verified builds ran with `JAVA_HOME` set to Temurin 21; CI uses `actions/setup-java` Java 21. The baseline build (JDK 25, `release 21`) was also green.
- **Prevention rule:** set `JAVA_HOME` to JDK 21 before building locally (see README).
- **Status:** worked around locally (machine env var not changed). See known-issues.

## 4. Generated Testcontainers config used unpinned `postgres:latest`

- **Evidence:** baseline log: `PostgreSQL 18.6` from `postgres:latest`.
- **Root cause:** Spring Initializr template default; also created one container per Spring context.
- **Fix:** pinned `postgres:17` (same as `docker-compose.yaml`) and made the container a JVM-wide singleton (`@Bean(destroyMethod = "")`; Ryuk cleans up).
- **Verification:** full `verify` passes; all IT classes share one container.
- **Prevention rule:** pin database image tags in tests and compose; keep them identical.
- **Status:** fixed.

## 5. Integration test mistakes (test code, not production code)

| Symptom | Root cause | Fix |
|---|---|---|
| `expected: 5 but was: 6` in `FlywayMigrationIT` | I miscounted foreign keys (the schema has 6) | assertion corrected to 6 |
| `current transaction is aborted` in `SchemaConstraintsIT` | after an expected constraint violation PostgreSQL aborts the transaction; later inserts in the same test failed | split into separate tests |
| `$.programs[0].slug` missing; stale `programs` list | `@Transactional` tests share one persistence context, so the in-memory entity never reloaded | `entityManager.clear()` after seeding |

- **Verification:** all pass in the final `mvnw clean verify`.
- **Prevention rule:** in `@Transactional`/`@DataJpaTest` tests, clear the persistence context before asserting on loaded graphs; one expected constraint violation per test.
- **Status:** fixed.

## 6. `mvnw clean` failed: `target\failsafe-reports` in use

- **Root cause:** my shell's working directory was inside `target/failsafe-reports`, locking the folder on Windows.
- **Fix:** run Maven from the project root only.
- **Status:** fixed (tooling issue, no code change).

## 7. Initial setup issues

- The Spring Initializr ZIP wraps files in a `pathway-ai-backend/` folder; contents were copied up so `pom.xml` is in the repo root.
- The working directory was inside the user's home Git repository (`C:/Users/Lenova`); an independent repo was created with `git init` in the project folder and `git rev-parse --show-toplevel` verified.
- Generated `.gitignore` did not ignore `.env`; added `.env`, `.env.local` etc.
- The current Initializr UI has no Name/Description fields (name defaults to the artifact); the pom `name`/`description` were set manually.
- **Status:** all fixed.

## 8. Page envelope field names did not match the frontend contract (`page` vs `number`)

- **Evidence:** read-only comparison with `pathway-ai-frontend/src/types/api.ts`: `Page<T>` expects `{content, number, size, totalElements, totalPages}`; the backend `PageResponse` returned `page`, so a client reading `number` would get `undefined`.
- **Root cause:** the envelope was designed before the frontend contract was compared; no contract test existed.
- **Affected files:** `common/response/PageResponse.java`, `UniversityControllerTest`, `UniversityServiceTest`.
- **Fix:** renamed to `number` (same as Spring Data's `Page`); added `UniversityApiIT.jsonContractFieldNamesAreStable` pinning exact key sets for list, detail and program items.
- **Verification:** `mvnw clean verify`: 55 tests (18 unit + 37 IT), 0 failures; live jar `GET /api/v1/universities?size=2` returned `"number":0`.
- **Prevention rule:** compare DTOs with the consumer's types before shipping an endpoint, and pin JSON key sets in a test.
- **Status:** fixed. Remaining mismatches are frontend-side or data-dependent: known-issues #8.

## 9. CI ran the test suite twice and used outdated action majors

- **Evidence:** `ci.yml` ran `test` and then `clean verify` (which re-runs unit tests); actions were `checkout@v4`, `setup-java@v4`, `upload-artifact@v4` while the latest releases are v7, v6, v7 (`gh api repos/<action>/releases/latest`).
- **Fix:** single `clean verify` step, actions bumped, concurrency group added. The old workflow had passed on `main` (run 42888019048): outdated, not broken.
- **Verification:** pending the PR's CI run (result recorded in the PR description).
- **Prevention rule:** check latest action majors whenever CI is touched.
- **Status:** changed; not considered verified until the PR CI run is green.
