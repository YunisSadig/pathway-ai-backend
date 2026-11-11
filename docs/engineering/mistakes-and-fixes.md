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

## 10. `DegreeProgram.describe(...)` emptied its own language set

- **Evidence:** `ProgramApiIT.detailReturnsFactsEachWithItsOfficialSource` failed with `No value at JSON path "$.teachingLanguages[0]"`, and `filtersByCountryUniversityAndLanguage` counted 2 instead of 3.
- **Root cause:** a test fixture called `describe(p, ..., p.getTeachingLanguageCodes(), ...)`. The method did `teachingLanguageCodes.clear()` and then `addAll(languageCodes)`; the argument was the same collection, so `clear()` erased it before `addAll`.
- **Affected files:** `program/entity/DegreeProgram.java`, `support/ProgramFixtures.java`.
- **Fix:** copy the argument into a new set before clearing.
- **Verification:** both tests pass; `mvnw clean verify` is green.
- **Prevention rule:** a method that replaces an owned collection from a parameter must copy the parameter first.
- **Status:** fixed.

## 11. Several test-only mistakes caught on the first run

- `ReferenceDataFileTest` threw `NullPointerException`: records bound from JSON leave omitted lists `null`. Fixed by normalising them to empty lists in a compact constructor of `ReferenceData.ProgramData`.
- `ProgramControllerTest` expected 400 for a 133-character identifier but the limit is 245. The test, not the code, was wrong; it now uses 250 characters.
- `ProgramSchemaConstraintsIT` helper inserted the program into every university, so a second university hit `uq_program_university_slug`. The helper now targets one university.
- **Prevention rule:** run new tests before assuming the failure is in production code; keep expected limits next to the constraints they test.
- **Status:** fixed.

## 12. A speculative index was measured as unused

- **Evidence:** with 20,008 programs, `pg_stat_user_indexes` showed `idx_program_name_lower` at 0 scans and `EXPLAIN ANALYZE` showed a sequential scan for `lower(name) LIKE '%x%'`.
- **Root cause:** a btree on `lower(name)` cannot serve a leading-wildcard search; I added it in V2 without measuring.
- **Fix:** `V3__drop_unused_program_name_index.sql` (V2 was already applied locally and is immutable). No trigram index was added because 5.5 ms does not justify it.
- **Verification:** `FlywayMigrationIT` asserts the index is gone; the live app migrated V2 to V3 cleanly.
- **Prevention rule:** add an index only after `EXPLAIN` shows it is used for the real query shape.
- **Status:** fixed.

## 13. Some official pages could not be fetched

- **Evidence:** automated requests to the Helsinki Economics and Data Science admissions pages returned HTTP 403; guessed ETH Zurich and TU Delft URLs returned 404 or lacked the facts; the Oxford page returned 403.
- **Root cause:** bot blocking and URL guesses.
- **Fix:** those programs were not added (ETH, TU Delft, Oxford) or were added without an application URL (Helsinki Economics and Data Science). No fact was taken from search snippets or memory.
- **Prevention rule:** record a fact only from a page that was actually read; omit the application URL when the apply page was not read.
- **Status:** fixed (limitation recorded in known issue 14).

## 14. Academic years were inferred and stored as if the sources had stated them

- **Evidence:** the first import stored academic year `2027/2028` on six tuition figures and four intakes. The pages printed a fee and, separately, an application round; none printed "2027/2028".
- **Root cause:** the schema required an academic year on every tuition row, and I filled it from the admission round shown beside the fee.
- **Affected:** `reference-data/programs.json` and the dev database, plus `tuition_fee.academic_year NOT NULL`.
- **Fix:** V4 made the year optional and added `effective_from` / `effective_to` / `scope_note`; the importer gained audited `retractions`; the ten wrong rows were retracted and stored again without a year (or with the exact start-date range the source stated); the API now reports `temporalStatus`. A pre-change backup of the database was taken.
- **Verification:** two imports against the real database (first run: 10 retracted, new facts added; second run: 0 new rows); `ReferenceDataFileTest` and `ProgramApiIT` cover it.
- **Prevention rule:** a field that must be known should never be satisfied by guessing; make the schema allow "unknown" and test that unknown is not relabelled.
- **Status:** fixed.

## 15. "Application URLs" were admissions-information pages

- **Evidence:** the five stored `officialApplicationUrl` values pointed at university admissions/how-to-apply pages; the actual forms are on national portals that were never opened.
- **Fix:** V4 added `official_admissions_url` and `official_application_cycle`; the pages moved to the admissions field; the application field is now empty. The API only exposes an Apply link for a verified link in a current or upcoming cycle.
- **Prevention rule:** an Apply link must be a page that was read and is the application itself.
- **Status:** fixed.

## 16. `? IS NULL OR column = ?` with a null date failed on PostgreSQL

- **Evidence:** `ERROR: could not determine data type of parameter $4` in the importer's tuition lookup.
- **Root cause:** a typed JPQL parameter bound as null has no inferable type in `:p is null`.
- **Fix:** derived query for the stable columns and the null-safe comparison done in Java (`TuitionFeeRepository.findWithoutYear`).
- **Prevention rule:** avoid `:param is null` for non-string parameters; compare in Java or use derived queries.
- **Status:** fixed.

## 17. Maven reported `BUILD SUCCESS` against stale test classes

- **Evidence:** after changing entity and DTO signatures, `mvnw -o test-compile` succeeded although test sources no longer matched; `clean test-compile` revealed 11 compile errors.
- **Root cause:** incremental compilation did not recompile the dependent test sources.
- **Prevention rule:** use `clean verify` after signature changes, as the project rules already require.
- **Status:** fixed (process).

## 18. Live crawl: the EU HEI registry was rejected as "unsupported content type"

- **Evidence:** the first real run recorded 45 FAILED checks, all `unsupported content type: application/vnd.api+json`.
- **Root cause:** the fetcher's content-type allow-list had `application/json` but not JSON:API, which the registry returns. Fixture tests never used that header.
- **Fix:** `application/vnd.api+json` added; `PageFetcherTest.jsonApiResponsesAreAccepted`. The 45 sources were reset and re-checked.
- **Prevention rule:** when a crawler depends on an external service, run it once against the real service before trusting fixtures, and copy the real headers into the fixture.
- **Status:** fixed.

## 19. A transient robots.txt error permanently marked sources UNSUPPORTED

- **Evidence:** two ELTE sources became `UNSUPPORTED` ("robots.txt unavailable (server error)") and would never have been retried.
- **Root cause:** a robots.txt fetch failure (5xx) was reported as BLOCKED, the same outcome as a real disallow, and BLOCKED is terminal.
- **Fix:** `RobotsPolicy.Decision.temporary`; the fetcher reports a temporary one as FAILED (retried with back-off). Added `app.refresh.command=reactivate` for sources a defect wrongly parked. `PageFetcherTest.anUnreachableRobotsTxtIsATemporaryFailureNotAPermanentBlock`.
- **Prevention rule:** terminal states are only for conditions that are provably not transient.
- **Status:** fixed. ELTE's robots.txt still errors, so those two sources show FAILING and are retried.

## 20. A validator crashed on a missing field instead of rejecting it

- **Evidence:** `NullPointerException` in `FactValidationTest` for a tuition fact without a billing period.
- **Root cause:** `Set.of(...).contains(null)` throws; the missing value was never checked.
- **Fix:** null checks before the set lookups; test covers missing and wrong units.
- **Prevention rule:** never call `contains` on an immutable `Set.of`/`Map.of` with an untrusted value that may be null.
- **Status:** fixed.

## 21. Watching home pages produced a review item within minutes

- **Evidence:** a second pass 13 minutes after the baseline reported KTU's home page as changed (news).
- **Root cause:** home pages change constantly without any fact changing; hashing them for review would flood the queue (about 1 in 128 sources within minutes).
- **Fix:** the `content-watch` adapter records the change in the audit trail and report but raises no review item; identity is watched through the registry record. `RefreshServiceIT.aHomePageThatChangesIsRecordedButRaisesNoReviewItem`.
- **Prevention rule:** monitor the pages that carry facts; a page that churns by design needs an audit-only adapter.
- **Status:** fixed.

## 22. A 2.03 MB home page exceeded the 2 MB response cap

- **Evidence:** `response larger than 2000000 bytes` for unipd.it.
- **Fix:** default cap raised to 4 MB (still bounded; oversized responses are rejected, never truncated).
- **Status:** fixed.

## 23. `mvnw package` failed because the running backend locked its jar (Windows)

- **Evidence:** `Unable to rename ...jar to ...jar.original` during `verify`/`package` while the API ran from `target/*.jar`.
- **Fix:** stop the API process first, build, restart it. For integration tests alone, `mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test=...` does not touch the jar.
- **Prevention rule:** on Windows stop the API before `clean verify`.
- **Status:** fixed (process).

## 24. Maven used JDK 25 although `java` was JDK 21

- **Evidence:** `JAVA_HOME` pointed at `jdk-25.0.2` while `java -version` reported 21.
- **Fix:** set `JAVA_HOME` to the JDK 21 directory for every Maven call (CLAUDE.md rule already says so).
- **Status:** fixed (process).
