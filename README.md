# Pathway AI Backend

REST backend for **Pathway AI**, an international university discovery and education-planning platform. The first release exposes a read-only university discovery API backed by PostgreSQL. The frontend lives in a separate repository: [pathway-ai-frontend](https://github.com/YunisSadig/pathway-ai-frontend).

> Product direction: [docs/product/MVP_SPEC.md](docs/product/MVP_SPEC.md) (global, program-first, all disciplines, bachelor and master).
>
> There are **no static academic records**, neither in the migrations nor in a data file. Programs enter only through an official-API connector and stay current through `--app.refresh.command=sync`: see [docs/data/sync-policy.md](docs/data/sync-policy.md). Every fact has a source and a field-level provenance row; a year is stored only when the source states it; unknown stays unknown.
>
> Inspecting the database in pgAdmin 4: [docs/database/PGADMIN_GUIDE.md](docs/database/PGADMIN_GUIDE.md).

## Technology stack
Java 21 · Spring Boot 4.1.2 (Web MVC, Data JPA, Validation, Actuator) · PostgreSQL 17 · Flyway · Maven Wrapper · JUnit 5 + Mockito + Testcontainers · GitHub Actions

## Architecture
Package-by-feature under `com.pathwayai.backend`:

```
university/  controller service repository entity dto mapper   <- public API
program/     controller service repository entity dto mapper    <- public API (program-first)
program/importer  ReferenceData, validator, idempotent importer   <- reference data, not migrations
field/       entity repository                                  <- academic-field taxonomy (rows, not enums)
requirement/ entity repository                                  <- per application cycle, cites a source
tuition/     entity repository                                  <- per academic year, cites a source
source/      entity repository                                  <- official source provenance
country/     entity repository
common/      exception response                                 <- ApiError, PageResponse, global handler
src/main/resources/db/migration/                                <- Flyway migrations
docs/engineering/                                               <- decisions, mistakes, known issues
```
See [architecture decisions](docs/engineering/architecture-decisions.md).

## Requirements
JDK 21, Docker Desktop (database + integration tests), PowerShell. Build with JDK 21 even if your default `JAVA_HOME` differs:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"   # adjust to your JDK 21
```

## Run PostgreSQL
```powershell
Copy-Item .env.example .env        # then edit .env and set your own DB_PASSWORD
docker compose up -d
docker compose ps                  # wait until "healthy"
```
Flyway creates the schema automatically when the app starts. The database listens on host port **5433** (override with `POSTGRES_PORT` and `DB_URL`).

## Run the application
```powershell
.\mvnw.cmd spring-boot:run
# or
.\mvnw.cmd clean package -DskipTests; java -jar target\pathway-ai-backend-0.0.1-SNAPSHOT.jar
```
Health: <http://localhost:8080/actuator/health>

## Where the data comes from (no static academic records)
The catalogue holds **no static university, program, tuition, admission or scholarship records**. `reference-data/taxonomy.json` only carries the
countries and academic fields; programs arrive only through an official-API connector and are kept current by `--app.refresh.command=sync`
(see [docs/data/sync-policy.md](docs/data/sync-policy.md)). `IMPORT_REFERENCE_DATA=true` loads the taxonomy and refuses a file that carries academic records.
```powershell
$env:JAVA_HOME = "<JDK 21>"
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--app.refresh.command=sync --spring.main.web-application-type=none"
```

## Run tests
```powershell
.\mvnw.cmd test            # unit/slice tests, no Docker needed
.\mvnw.cmd clean verify    # everything, incl. *IT tests on a Testcontainers PostgreSQL (Docker required)
```

## REST API
| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/universities` | Paged list |
| GET | `/api/v1/universities/{slug}` | University detail with programs |
| GET | `/api/v1/programs` | Paged, filterable program discovery (program-first) |
| GET | `/api/v1/programs/{identifier}` | Program detail with intakes, tuition, requirements and official sources |

## REST API contract (what the frontend consumes)
Base URL (local): `http://localhost:8080`. Read-only: only `GET` (plus CORS `OPTIONS`/`HEAD`); other methods return `405`. No authentication.

### `GET /api/v1/universities`
| Param | Type | Default | Rules |
|---|---|---|---|
| `q` | string | – | name contains, case-insensitive, ≤100 chars; `%`/`_` are literal |
| `country` | string | – | one 2-letter ISO code (case-insensitive); a single value, not a list |
| `page` | int | `0` | ≥ 0 (zero-based) |
| `size` | int | `20` | 1–100 |
| `sort` | string | `name` | `name` \| `city` (anything else → 400) |
| `direction` | string | `asc` | `asc` \| `desc` |

Response `200` (matches the frontend's `Page<T>`; an empty result is `200` with `content: []`, never an error):
```json
{
  "content": [
    { "slug": "string", "name": "string", "city": "string", "countryCode": "DE",
      "countryName": "string", "websiteUrl": "string | null" }
  ],
  "number": 0, "size": 20, "totalElements": 0, "totalPages": 0
}
```

### `GET /api/v1/universities/{slug}`
`slug` must match `[a-z0-9]+(-[a-z0-9]+)*`, ≤120 chars. Response `200`:
```json
{
  "slug": "string", "name": "string", "city": "string", "countryCode": "DE", "countryName": "string",
  "websiteUrl": "string | null", "description": "string | null",
  "programs": [
    { "slug": "string", "name": "string", "degreeLevel": "BACHELOR",
      "fieldOfStudy": "string | null", "durationMonths": 36, "teachingLanguage": "string | null" }
  ]
}
```
`degreeLevel` is one of `BACHELOR | MASTER | DOCTORATE | DIPLOMA | CERTIFICATE` (upper-case). Nullable values are serialised as `null`. The API does **not** yet return an `id`, tuition, admission requirements, or source/verification info; they are not exposed until source-cited data exists.

### `GET /api/v1/programs` (program-first discovery)
Read-only. Each result is a **program**, with the **university** that offers it.

| Param | Type | Default | Rules |
|---|---|---|---|
| `q` | string | – | program name contains, case-insensitive, ≤100 chars; `%`/`_` literal |
| `field` | string | – | academic-field slug; also matches that field's direct children (e.g. `social-sciences` includes `economics`) |
| `level` | string | – | one of `bachelor`, `master`, `doctorate`, `diploma`, `certificate` (case-insensitive) |
| `country` | string | – | 2-letter ISO code of the university's country |
| `university` | string | – | university slug |
| `language` | string | – | teaching-language code (`en`, `fi`, ...); a program may have several |
| `tuitionMax` + `tuitionCurrency` | number + 3-letter code | – | programs with a **known** yearly tuition ≤ the amount in that currency. Both are required together. Unknown tuition is never treated as free; currencies and billing periods are never mixed |
| `tuitionCategory` | string | – | `domestic` or `international`; only with `tuitionMax` |
| `page` / `size` | int | `0` / `20` | `page` ≥ 0, `size` 1–100 |
| `sort` / `direction` | string | `name` / `asc` | `name` \| `university` \| `level`; `asc` \| `desc` |

Response `200` (same `Page` envelope; empty result is `200` with `content: []`):
```json
{ "content": [ {
    "identifier": "aalto-university--architecture-msc", "slug": "architecture-msc",
    "name": "Architecture, Master of Science in Architecture", "degreeLevel": "MASTER",
    "field": { "slug": "architecture-and-design", "name": "Architecture and Design" },
    "studyMode": "FULL_TIME", "durationMonths": 24, "teachingLanguages": ["en"], "campusCity": null,
    "university": { "slug": "aalto-university", "name": "Aalto University", "city": null,
                    "countryCode": "FI", "countryName": "Finland" },
    "officialProgramUrl": "https://...", "officialApplicationUrl": "https://...",
    "verification": { "status": "VERIFIED", "verifiedOn": "2026-11-11" } } ],
  "number": 0, "size": 20, "totalElements": 8, "totalPages": 1 }
```
`degreeLevel`: `BACHELOR | MASTER | DOCTORATE | DIPLOMA | CERTIFICATE`. `studyMode`: `FULL_TIME | PART_TIME | DISTANCE | BLENDED | null`.
`field`, `studyMode`, `durationMonths`, `campusCity`, the URLs and `verifiedOn` are `null` when unknown; `teachingLanguages` is `[]`.
Two link fields: `officialAdmissionsUrl` is an official admissions-**information** page; `officialApplicationUrl` is an **active Apply link** and is only non-null for a `VERIFIED` program whose link was verified for a current or upcoming cycle. A link for an older cycle is withheld. Today no program has an active Apply link: the application forms (national portals) have not been verified.

### `GET /api/v1/programs/{identifier}`
`identifier` is `<university-slug>--<program-slug>` (globally unique and stable; slugs never contain `--`; ≤245 chars). Response `200` is the list item plus the facts, **each with its official source**:
```json
{ "...list item fields...": "...",
  "sources":      [ { "url": "...", "title": "...", "publisher": "...", "type": "UNIVERSITY_WEBSITE", "retrievedOn": "2026-11-11" } ],
  "intakes":      [ { "label": "Autumn 2027 admission", "academicYear": "2027/2028", "startDate": null,
                      "applicationDeadline": "2027-01-05", "applicantCategory": null, "source": { "...": "..." } } ],
  "tuition":      [ { "academicYear": "2027/2028", "studentCategory": "INTERNATIONAL", "amount": 20000.00,
                      "currency": "EUR", "billingPeriod": "PER_YEAR", "source": { "...": "..." } } ],
  "requirements": [ { "applicationCycle": "2027", "type": "ENTRANCE_EXAM", "description": "...",
                      "minimumValue": null, "valueUnit": null, "source": { "...": "..." } } ] }
```
Empty arrays mean "nothing known", not "none exist". **Every `intakes`, `tuition` and `requirements` item carries `temporalStatus`**: `CURRENT`, `HISTORICAL` (the period has ended; never show it as current) or `UNKNOWN_YEAR` (the source stated no period). Tuition has `academicYear` (only when the source prints one), `effectiveFrom` / `effectiveTo` (a stated start-date range) and `scopeNote` (the source's wording); all may be `null`. The `tuitionMax` filter only matches `CURRENT` fees. `404` for an unknown program, `400` for a malformed identifier. The `programs` rows inside `GET /api/v1/universities/{slug}` gained an additive `identifier` field.

### `synchronization` block of `GET /api/v1/programs/{identifier}` (additive)
```json
"synchronization": {
  "status": "AUTOMATIC | NOT_SYNCHRONIZED",
  "connector": "studyinfo-external", "sourceKind": "OFFICIAL_API",
  "lastSuccessfulSyncAt": "2026-11-14T07:16:03Z", "nextCheckDueAt": "2026-11-21T07:16:03Z",
  "fresh": true, "freshnessWindowDays": 45,
  "fields": [ { "field": "duration_months", "updateMode": "AUTOMATIC | REVIEW_REQUIRED | ADDITIVE_AUTOMATIC | FIXED_AT_CREATION", "verifiedOn": "2026-11-14" } ],
  "notSynchronized": ["tuition", "scholarships", "admissionRequirements", "applicationUrl"]
}
```
`status: AUTOMATIC` means a working extractor maintains the program, **not** that an unattended scheduler is running: `fresh` and `lastSuccessfulSyncAt` say how recently it was read.
A field with `REVIEW_REQUIRED` is re-read automatically but a change waits for a person (the old value stays until approved). Facts listed in `notSynchronized` have no connector
and are omitted rather than shown as maintained. A program the official source has discontinued, or whose extractor is failing or stale, is withheld from discovery.

### `GET /api/v1/discovery/status` (additive)
Explains the state of public discovery: `state` (`FRESH`, `PARTIAL`, `STALE`, `EMPTY`, `UNRESTRICTED`), `programsTotal`, `programsListed`, `programsWithheld`,
`withheldBecause` (`notVerified`, `discontinued`, `noExtractor`, `neverChecked`, `sourcesStale`, `extractorFailing`), `lastSuccessfulSourceCheck`, the scheduler status and a plain-language `message`.

### Errors
Every non-2xx API response: `{ "timestamp": "ISO-8601", "status": 400, "error": "Bad Request", "message": "...", "path": "/api/v1/universities", "violations": [{ "field": "size", "message": "must be between 1 and 100" }] }` (`violations` omitted when empty). `400` invalid/ill-typed parameter or malformed slug, `404` unknown slug or path, `405` wrong method, `500` unexpected. A CORS rejection by Spring returns `403` with plain text `Invalid CORS request`.

### CORS
Browsers may call `/api/**` cross-origin only from origins in `CORS_ALLOWED_ORIGINS` (default `http://localhost:3000`). Methods `GET, HEAD, OPTIONS`; headers `Accept, Content-Type`; no credentials; preflight cached 1 h. Wildcards are rejected at startup. Set `CORS_ALLOWED_ORIGINS` to the real frontend origin in production.

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/universities?q=tech&country=de&size=10&sort=name"
```

## Environment variables
| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/pathway_ai` | JDBC URL |
| `DB_USERNAME` | `pathway` | DB user |
| `DB_PASSWORD` | *(required)* | DB password — never committed |
| `POSTGRES_DB` / `POSTGRES_PORT` | `pathway_ai` / `5433` | Used by Docker Compose |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated explicit browser origins allowed to call `/api/**` (no wildcards) |
| `IMPORT_REFERENCE_DATA` | `false` | `true` runs the idempotent reference-data import at startup |
| `APP_API_CACHE_MAX_AGE_SECONDS` | `300` | How long a browser or CDN may reuse a list/detail answer before it must revalidate (ETag); errors are never cached |
| `REFERENCE_DATA_LOCATION` | `classpath:reference-data/taxonomy.json` | The taxonomy file (countries and academic fields only; a file with academic records is refused) |
| `PORT` | `8080` | HTTP port (injected by most hosts) |

A git-ignored `.env` in the working directory is read automatically; real environment variables take precedence.

## Connecting the frontend locally
1. `docker compose up -d`, then start the backend (`.\mvnw.cmd spring-boot:run`); backend URL `http://localhost:8080`.
2. In the frontend repo set `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080` (e.g. git-ignored `.env.local`) and run it on `http://localhost:3000`.
3. Check `Invoke-RestMethod http://localhost:8080/api/v1/universities`. The database ships empty; run the reference-data import (see above) to get 8 real, source-cited programs, then check `Invoke-RestMethod "http://localhost:8080/api/v1/programs"`.

## Performance findings (measured, not assumed)
Measured on a developer laptop (Windows, PostgreSQL 17 in Docker, JDK 21, local HTTP via curl, median of 10 requests) with **20,008 programs** (8 real + 20,000 temporary synthetic rows, since removed):

| Request | Median | Max |
|---|---|---|
| `GET /programs` (default page) | 35 ms | 43 ms |
| `?level=master` / `?field=social-sciences` / `?language=sv` | 28 / 23 / 28 ms | ≤ 52 ms |
| `?q=program 12` (contains search) | 16 ms | 22 ms |
| `?tuitionMax=5000&tuitionCurrency=EUR` | 26 ms | 40 ms |
| `?country=fi&level=bachelor&language=en&size=50` | 27 ms | 49 ms |
| `?sort=university&direction=desc` | 29 ms | 30 ms |
| `?page=500` (offset 10,000) | 87 ms | 107 ms |
| `GET /programs/{identifier}` | 22 ms | 52 ms |

- **No N+1:** `ProgramQueryCountIT` counts SQL statements with Hibernate statistics. A list page costs **3 statements whether it holds 3 or 25 rows** (page + count + one batched language query); a detail view costs **6** (program with university, country and field in one query, then languages, sources, intakes, tuition, requirements). Both are asserted, so a regression fails the build.
- **Indexes:** `EXPLAIN ANALYZE` showed the level, field and language filters use `idx_program_degree_level`, `idx_program_academic_field_id` and `idx_program_language_code` (index scans recorded in `pg_stat_user_indexes`).
- **One change made because of a measurement:** V2 had added `idx_program_name_lower`. It recorded **0 scans**: a btree cannot serve a leading-wildcard `LIKE '%x%'`; the planner uses a sequential scan (about 5.5 ms for 20k rows). V3 drops it. A `pg_trgm` index was **not** added: 5.5 ms does not justify it. Revisit above roughly 10⁵–10⁶ programs.
- **Not optimised:** deep offset pagination (87 ms at offset 10,000) is acceptable for a UI that pages a few times; keyset pagination would change the contract and is not warranted yet. `idx_program_level_field` (V1) also shows 0 scans; it is immutable history, noted in known issues.
- Numbers are indicative for one machine and a synthetic distribution; they are not a capacity claim.

## CI and CD
- **CI** (implemented): `.github/workflows/ci.yml` runs `./mvnw clean verify` (incl. Testcontainers integration tests) on every push to `main` and every PR. It never deploys.
- **CD** (not implemented; awaiting owner approval and a hosting decision): see [docs/deployment.md](docs/deployment.md) for requirements, env vars, options and cost notes.

## Troubleshooting
- **`role "pathway" does not exist`** — another PostgreSQL owns the port. Check `Get-NetTCPConnection -LocalPort 5433` and change `POSTGRES_PORT`/`DB_URL`.
- **`Could not find a valid Docker environment`** — start Docker Desktop and retry.
- **`Failed to bind properties ... password`/connection refused** — copy `.env.example` to `.env` and run `docker compose up -d`.
- **Wrong Java version** — set `JAVA_HOME` to JDK 21 (see above).
- **`clean` cannot delete `target`** — don't run Maven from inside `target\`.
- More: [known issues](docs/engineering/known-issues.md), [mistakes and fixes](docs/engineering/mistakes-and-fixes.md).

## University catalogue and automated source monitoring

The active catalogue is deliberately small: only institutions that have at least one program kept current by a working official-API connector (today the University of Helsinki, Aalto University and the University of Oulu with their Studyinfo programs). A source monitor that only notices a changed page is not synchronization and does not make a program eligible. The policy, what updates automatically, what still needs review, how stale data is withheld and how the development database was cleaned are in [docs/data/sync-policy.md](docs/data/sync-policy.md); the connectors are in [docs/data/official-api-connectors.md](docs/data/official-api-connectors.md); the monitoring and review machinery in [docs/data/](docs/data/) (`automated-refresh.md`, `change-review.md`). Everything runs from the command line (`--app.refresh.command=sync|run|report|review-list|review-approve|review-reject|studyinfo-import|studyinfo-sync|assign-adapter|sync-adapter`); there is no HTTP control surface.
