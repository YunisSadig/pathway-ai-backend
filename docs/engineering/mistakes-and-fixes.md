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

## 25. A parser fix was invisible because the adapter version was not bumped

- **Evidence:** after changing how the Studyinfo parser reads program names, `studyinfo-sync` reported `unchanged=9` and re-read nothing: the page hash and the parser version string were both identical, so no extraction ran and the stored names kept an intake term ("..., Autumn 2027").
- **Root cause:** the monitor only re-extracts when the content hash or `canon+adapter@version` changes. The adapter `VERSION` constant was not bumped with the parser change, although its own javadoc says to.
- **Files:** `StudyinfoAdapter`.
- **Fix:** `VERSION` 1 -> 2. The next sync re-baselined all nine programs and staged exactly the four affected name changes for review (verified by SELECT: 9 baselines, 4 `PENDING_REVIEW`).
- **Prevention rule:** any change to what an adapter, parser or field mapping returns bumps that adapter's version in the same commit.
- **Status:** fixed; the four name changes await a human review decision.

## 26. Program names carried the intake term, so they would have changed every cycle

- **Evidence:** the live import stored "Master's Programme in Agricultural Sciences, Autumn 2027" for Helsinki.
- **Root cause:** the provider's implementation name includes the intake season and year. Stored as the name, it would change every cycle and flood the review queue.
- **Fix:** a trailing season+year is removed from the name (it is intake information, not identity); slugs also drop apostrophes ("masters-programme-...", not "master-s-programme-..."). Existing slugs of the first four Helsinki programs predate this and were not renamed (identifiers are a decision for the owner).
- **Prevention rule:** read the first live response of a new provider before fixing the shape of an identity field.
- **Status:** fixed for new records; four existing names pending review.

## 27. Joint and sub-unit programs were all rejected for Aalto

- **Evidence:** first live import: 0 of 9 Aalto candidates imported ("expected exactly one providing institution, found 2" and a provider oid that is not the university's).
- **Root cause:** the record's provider list names a unit inside the university, or several universities for joint degrees; only the provider's own search is keyed by the university.
- **Fix:** a record is accepted when it names exactly one provider and either names the configured institution or was listed under it by the provider's own search. Joint programs (two or more providers) stay refused: their city, duration and ownership are not single-valued.
- **Status:** fixed (2 Aalto programs imported; 7 joint programs skipped by design).

## 28. A published Crawl-delay was silently capped

- **Evidence:** first implementation capped any `Crawl-delay` at 60 s.
- **Fix:** the delay is honoured exactly up to 5 minutes; above that the host is not fetched and the failure says why. It is never shortened.
- **Prevention rule:** a safety limit must refuse, not quietly weaken.
- **Status:** fixed (`PageFetcherTest`).

## 29. The scoped Aalto extractor could not be attached to the existing fee monitor

- **Evidence:** the development fee monitor (source 14) has no university, so the extractor would have had no program to attach facts to; before the fix this degraded into a vague "page changed" review item.
- **Fix:** `assign-adapter` can bind a university; a scoped adapter on an unbound source now fails visibly (a recorded failure, nothing staged); verified live (2 facts extracted, 2 unchanged) and in `AaltoTuitionSyncIT`.
- **Prevention rule:** a misconfigured source must fail loudly, never fall through to a generic review item.
- **Status:** fixed.

## 30. "Fresh" meant any page of the program, so a broken extractor could stay hidden

- **Evidence:** discovery counted any successful source check; a program whose fact-extracting monitor was failing stayed listed because an admissions page was still read.
- **Fix:** a failing, unsupported or stale extractor monitor withholds the program (`DiscoveryVisibilityIT`).
- **Status:** fixed.

## 31. A test that runs every startup importer polluted the shared test database (67 failures in 10 classes)

- **Evidence:** the first full `verify` of the clean-catalogue branch: `UniversityApiIT` saw 6 universities instead of 3, `ProgramApiIT` failed with "duplicate key ... uq_academic_field_slug", `ProgramSchemaConstraintsIT` with "duplicate key ... uq_university_slug", and so on.
- **Root cause:** `NoStaticCatalogueIT` starts the application with every importer enabled and therefore commits the real catalogue (universities, countries, fields) into the one PostgreSQL container shared by all integration tests.
- **Files:** `NoStaticCatalogueIT`, new `IsolatedPostgresConfiguration`.
- **Fix:** that test class gets a container of its own. Verified: the next full run had 2 failures left (unrelated causes, below), then 0.
- **Prevention rule:** a test that commits real rows or runs startup importers must not use the shared container.
- **Status:** fixed.

## 32. The retirement script could not be run twice in one transaction

- **Evidence:** `CatalogueRetirementIT.aBatchNameCannotBeReused...`: `relation "keep_program" already exists`.
- **Root cause:** the script's temp tables were `ON COMMIT DROP`, so a second run inside the same transaction (which the test does to prove idempotence) found them still there.
- **Fix:** the script drops its own temp tables at the start and end, schema-qualified with `pg_temp` so a permanent table of that name could never be dropped.
- **Status:** fixed (`CatalogueRetirementIT`).

## 33. Unfinished work from an interrupted session was caught only by the full build

- **Evidence:** `ReferenceDataFileTest`: `Unrecognized property "_comment"` in the new `taxonomy.json`; `FlywayMigrationIT` still pinned V7; `ProgramApiIT` still pinned the old detail keys; `AaltoTuitionSyncIT` depended on a now-empty production mapping.
- **Fix:** comment removed (the strict reader stays strict), pins updated for V8 and the additive `synchronization` object, the Aalto mapping location made configurable so the test supplies its own synthetic mapping.
- **Prevention rule:** run the complete `verify` and read every failure BEFORE any destructive database step. Build in a scratch copy when a developer's own server is running from the working tree.
- **Status:** fixed; full `verify` 332 tests green before the cleanup was executed.

## 34. The first hosted run failed: `./mvnw: Permission denied`

- **Evidence:** Actions run 43599319418, step `./mvnw -B -q package -DskipTests`, exit 126.
- **Root cause:** the wrapper was committed from Windows as mode `100644`; `ci.yml` had its own `chmod +x`, the new refresh workflow did not, and the workflow had never run before the first hosted attempt.
- **Files:** `mvnw` (now `100755`), `source-refresh.yml`, new `scripts/ci/check-exec-bits.sh` (CI fails when a CI-executed file loses the bit).
- **Verification:** CI green with the guard; hosted run 43601512304 passed the build step.
- **Prevention rule:** a workflow that has never run is untested; run every new workflow once manually before relying on it. Commit scripts with `git add --chmod=+x`.
- **Status:** fixed.

## 35. `sslmode=verify-full` failed on the hosted runner: `Could not open SSL root certificate file /home/runner/.postgresql/root.crt`

- **Evidence:** run 43601512304, three attempts, Flyway `FlywaySqlUnableToConnectToDbException`.
- **Root cause:** pgJDBC's `verify-full` reads `~/.postgresql/root.crt` and ignores the JVM trust store unless an `sslfactory` says otherwise. The earlier "verify-full is mandatory" research checked the verification semantics but never ran a handshake.
- **Fix:** `sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` (JVM CA bundle; the host name is still verified), appended by `run-refresh.sh` and set in `application-prod.yaml`; a caller-supplied `sslfactory`/`sslrootcert`/`sslhostnameverifier` is refused (a `NonValidatingFactory` would silently disable verification).
- **Verification:** run 43610948377 connected to Neon (PostgreSQL 17.12), applied V1-V8 and synchronized.
- **Prevention rule:** a TLS configuration is verified only by a real handshake against the real endpoint (the fixtures in `test-run-refresh.sh` prove the guards, not the connection).
- **Status:** fixed.

## 36. A BOM was written in front of every secret piped to the GitHub CLI (local owner tool)

- **Evidence:** the stub test of the unrun `set-sync-secrets.ps1`: stdin bytes began `EF BB BF`.
- **Root cause:** under a UTF-8 console code page, .NET Framework wraps a child's stdin in a UTF-8 `StreamWriter` that emits a preamble, even when bytes are written through `BaseStream`.
- **Fix:** the console input code page is switched to 437 only while the child starts, then restored. Verified byte-for-byte with a compiled stub (37 non-secret tests).
- **Prevention rule:** test secret-handling code with a byte-exact stub before it touches a real credential.
- **Status:** fixed (the tool is local-only and git-ignored: `/scripts/ops/`).

## 37. `git add -A scripts` swept an untracked local-only tool into a commit

- **Evidence:** the PR for `verify-data` listed `scripts/ops/set-sync-secrets.ps1`.
- **Fix:** removed from the branch before merge and `/scripts/ops/` added to `.gitignore`. Nothing secret was in the file.
- **Prevention rule:** stage explicit paths, never `-A` over a directory that holds local tools; read `git show --stat` before pushing.
- **Status:** fixed.

## 38. A secret piped from Windows PowerShell 5.1 carried a trailing newline (staging key: 401 in the verification workflow)

- **Evidence:** `verify-deployment` run 43625400578: readiness and the closed-gate check passed, every authenticated check got HTTP 401 although the same key worked from a local request.
- **Root cause:** `$value | gh secret set` in PowerShell 5.1 appends `\r\n`, so the GitHub secret was the key plus a newline and never matched the key on the host.
- **Fix:** secrets for GitHub are written as raw bytes through the process's standard input (as `scripts/ops/set-sync-secrets.ps1` already did). Verification: run 43625469490 passed all checks.
- **Prevention rule:** never pipe a secret with `|` in PowerShell 5.1; write bytes. A post-deploy workflow that sends the key is the test that catches this class.
- **Status:** fixed.

## 39. A database role created through the Neon console is not read-only

- **Evidence:** `REVOKE neon_superuser FROM <role>` failed with "Only roles with the ADMIN option ... may revoke"; console-created roles join `neon_superuser`, which inherits broad read/write rights.
- **Fix:** the API's role is created with SQL (`CREATE ROLE ... LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE`) plus `GRANT SELECT` only. Verified in SQL: not superuser, not in `neon_superuser`, `SELECT` yes, `INSERT`/`UPDATE` no; the Neon SQL-history entry that held the generated password was deleted.
- **Prevention rule:** least-privilege roles are created in SQL, and their privileges are asserted with `has_table_privilege`, not assumed from a console screen.
- **Status:** fixed.

## 40. The post-deploy verification of the frontend never started (wrong trigger)

- **Evidence:** after the first production deployment on Vercel no `Verify deployment` run existed and the repository had zero GitHub Deployments.
- **Root cause:** the workflow used `on: deployment_status`, but this Vercel integration reports a commit status (context `Vercel`) and creates no GitHub Deployment.
- **Fix:** trigger on `status` (context `Vercel`, state success, sha equal to the head of `main`). Verification: frontend run 43628497905 started by itself after a merge and passed.
- **Prevention rule:** prove a trigger by observing it fire; run the workflow manually first (it passed 10 smoke tests) and then wait for the automatic run.
- **Status:** fixed.

## 41. A UI element reference pointed at a different control after the page re-rendered (GitHub app install)

- **Evidence:** selecting "Only select repositories" for Render's GitHub app by element reference installed the app on all repositories.
- **Fix:** narrowed immediately to the single repository in the installation settings; later installs were done by screenshot and coordinates with a check before pressing Install.
- **Prevention rule:** on a page that re-renders, click by what the screenshot shows, and verify the selected scope on screen before an irreversible button.
- **Status:** fixed.

## 42. The first hosted backup failed: `pg_dump: SSL error: certificate verify failed`

- **Evidence:** backup run 43633074004, step "Encrypted dump". The Java driver verified the same server successfully.
- **Root cause:** the `postgres:17` image ships without a CA bundle (`/etc/ssl/certs` has two entries), so libpq's `sslrootcert=system` had no roots to verify against. Reproduced without credentials: the handshake fails before authentication.
- **Fix:** mount the runner's CA bundle read-only into the container and set `PGSSLROOTCERT=/ca-bundle.crt`; refuse to run when the bundle is missing (guard test added). Verified without credentials: with the bundle the handshake succeeds and the attempt reaches "password authentication failed", which is the expected next step.
- **Prevention rule:** a container is not a trust store; verify TLS from inside the same container image that will do the work.
- **Status:** fixed (see the green run recorded in `docs/engineering/deployment-status.md`).

## 43. The frontend proxy gave up before the sleeping backend woke up

- **Evidence:** the measured cold start of the free Render instance was 84.6 s; the proxy's upstream timeout was 75 s, so the first visitor after an idle period would have received `502 The data service could not be reached`. No visitor was affected: found by measuring, before any public use.
- **Root cause:** the timeout was chosen from Render's "about one minute" without a measurement.
- **Fix:** frontend PR #26: upstream wait 130 s, `maxDuration = 150` (Fluid Compute is enabled on the Hobby project), a unit test that pins the wait above 100 s and below the function limit, and a loading state that explains a long wait after 6 s.
- **Prevention rule:** timeouts come from measured values, and a documented figure is a hypothesis until measured.
- **Status:** fixed; verified through the live site on 2026-11-15: first request after 17 idle minutes, HTTP 200 in 108.7 s, 9 programs.

## 44. Account work: six problems found while building student accounts (2026-11-15)

- **Cookie settings ignored in tests.** `server.servlet.session.cookie.*` only configures embedded servers; MockMvc saw `SESSION=...; Path=/`. **Fix:** an explicit `DefaultCookieSerializer` bean (`PATHWAY_SESSION`, HttpOnly, SameSite=Lax, Secure by property). **Rule:** configure security-relevant cookie attributes in code and pin them with a test.
- **Anonymous 401s created database sessions.** Spring Security's request cache saved the denied request. **Fix:** `requestCache` disabled (nothing here needs post-login replay). **Rule:** after adding a security chain, count rows in the session table after anonymous calls.
- **`@WebMvcTest` slices returned 401** once Security was on the classpath. **Fix:** `@AutoConfigureMockMvc(addFilters = false)` in those slices; security is covered by the integration tests.
- **Hikari "connection not available" in the full suite:** many cached Spring contexts times a pool of 10 exceeded PostgreSQL `max_connections`. **Fix:** test pool size 3, min idle 0. I also overwrote the tracked `src/test/resources/application.properties` once and lost a setting (restored with `git checkout`, then appended). **Rule:** append to tracked test config, never replace it.
- **CLI jobs (`web-application-type=none`) broke** because web-only security beans were required. **Fix:** the chain is `@ConditionalOnWebApplication` and `SessionTerminator` is optional.
- **Secrets shown on screen three times** (Neon SQL history entry opened by a click, then a pasted Render value that the field revealed). **Fix:** the role password was rotated each time, history entries deleted, never screenshot after pasting a secret; save first, screenshot only after the page reloads masked. **Rule:** a SQL-history entry loads its text into the editor when clicked; use its menu instead.
- **Status:** fixed; verified by 228 backend tests and a hosted register, profile, gate and delete round trip on 2026-11-15.

## 45. Profile v2: problems found while building and verifying it (2026-11-15)

- **A missing primitive failed the whole request.** A record with `boolean ongoing` made Jackson 3 reject a body that simply omitted it (HttpMessageNotReadable, no violations). **Fix:** `Boolean` and `Boolean.TRUE.equals(...)`. **Rule:** optional JSON fields are boxed types.
- **Stale Playwright server.** `reuseExistingServer` silently tested an old build on port 3100 (strange 30-60 s timeouts, assertions about code that was already changed). **Rule:** stop whatever listens on 3100 before an e2e run that follows source changes; with Docker Desktop running, use `--workers=4`, not 10.
- **Hidden previous page counted by locators.** After a client navigation Next keeps the previous route mounted but hidden, so `getByText(...).toHaveCount(0)` counted the wizard. **Rule:** use `.filter({ visible: true })` for "this text must not be shown" assertions.
- **A banner meant for the wizard appeared on the finished profile page** ("press Finish"). Found by the hosted smoke test, not by the mocked e2e suite. **Fix:** `hideReady`. **Rule:** run the live smoke test against the real deployment after every UI change to a shared component.
- **A failed live smoke test leaves disposable accounts behind** (it deletes the account only at the end). They were removed with a scoped `delete ... where email_normalized like 'pathway-smoke-%@example.com'`.
- **Layout regressions from a harmless addition:** a two-button language switch overflowed the header at 360 px; fixed with a single compact toggle and by hiding the header Sign up button below 400 px (the menu and the hero still offer it).
- **Status:** all fixed and verified (251 backend tests, 112 unit, 311 browser tests, 8 hosted tests on 2026-11-15).

## 46. Google callback returned HTTP 500 in production: the code was deployed before its migration (2026-11-15)

- **Error output (Render log, 20:43:42Z and again on `GET /api/v1/account/me` at 20:44:29Z):** `PSQLException: ERROR: relation "external_identity" does not exist` (`BadSqlGrammarException ... select provider from external_identity where account_id = ?`).
- **Root cause:** the hosted API runs with Flyway OFF; schema changes reach Neon only through the workflow "Source refresh", mode `migrate`. Backend #37 (V12, the `external_identity` table) was merged and auto-deployed without that run, so the new code queried a table that did not exist. Credentials, token checks, the proxy and the session store were all fine (the failure was the first database read after Google answered). Because `/me` also reads the table, every signed-in request failed too.
- **Files:** `GoogleAuthController` (callback now catches unexpected failures and redirects to `/login?error=google_failed`, logging the exception but never the code or tokens), `GoogleAuthIT.anUnexpectedFailureInTheCallbackIsASafeRedirectNeverAServerError` (reproduces the failing lookup).
- **Fix:** run `Source refresh` mode `migrate` (additive: one new table, one relaxed NOT NULL) BEFORE relying on the new code. See status.
- **Prevention rule:** a PR that adds a Flyway version is not "deployed" until the `migrate` run has succeeded on Neon; run it BEFORE merging, or merge only with the run queued immediately after, and confirm with `verify`/a read of `flyway_schema_history`. A deployed feature flag (here, the Google credentials) must be enabled only after its migration.
- **Status:** the code fix and regression test are tested; the production migration was NOT run by me (the action was declined by the permission layer) and is still pending the owner's run.

## 47. Windows verification artifacts and checkout line endings (2026-11-16)

- **Errors:** frontend Prettier reported 67 untouched files after a CRLF checkout; the first browser build failed with `EBUSY: resource busy or locked, unlink .next/official-links-check.log`.
- **Cause:** core.autocrlf produced CRLF while Prettier expects LF. A concurrent check wrote its log inside Next's disposable build directory, which the build tried to clean.
- **Fix:** normalize working-tree text to LF, refresh the Git index and confirm no unrelated content diff; keep check logs outside `.next`; run the build after check completion. No global Git setting or formatting policy changed.
- **Verification:** frontend format/lint/typecheck and 144 unit tests passed. The rerun built successfully and completed 403 browser tests, with 7 existing device-specific skips. Windows Next teardown hung after the cases completed; stopping that run's own server let Playwright exit 0.
- **Prevention:** never put active logs in `.next`/`target` while another command cleans them; avoid concurrent type generation and production builds. Treat Windows server teardown separately from assertion results; require hosted Linux CI too.
- **Status:** local checks completed; hosted PR checks must pass before any owner-approved merge.

## 48. A fee's academic year was derived from the intake again (PR #44, caught 2026-11-19 before any production import)
- **Evidence:** `StudyinfoParser.details` set `academicYear` `2027/2028` on twelve Studyinfo fees because the record states an autumn 2027 start. No fee text of the 20 programs names an academic year (checked: 0 of 20; the only "2027" hits near a fee were application periods).
- **Root cause:** I repeated mistake 14 (a year taken from an adjacent admission round), asked the owner a leading question that made it sound like a decision about presentation, and implemented the answer without re-reading the Year rule in CLAUDE.md. The owner then restated the rule: a 2027 intake alone does not date a fee.
- **Affected:** merged code of PR #44 only; nothing was written to production (V14 and the import had not run), so no wrong row exists in Neon.
- **Fix:** a fee gets an academic year only when the provider's fee text itself names two consecutive years; otherwise the row has no year, its note says the source does not state one, and its key is `undated|INTERNATIONAL`. 16 of the 20 state the audience (non-EU/EEA) and are published undated; LUT x3 and Turku GIM state no audience and publish no fee from Studyinfo. Tests assert both cases and that a new intake dates nothing.
- **Prevention rule:** never convert an intake, start date or admission round into an academic year. Check the source text for the year and keep the schema's "unknown" state; do not offer the owner a "map it" option for something the Year rule already forbids.
- **Status:** fixed in PR "fix: a fee's academic year is never inferred from an intake"; verified by unit tests; production import still pending.
