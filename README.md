# Pathway AI Backend

REST backend for **Pathway AI**, an international university discovery and education-planning platform. The first release exposes a read-only university discovery API backed by PostgreSQL. The frontend lives in a separate repository: [pathway-ai-frontend](https://github.com/YunisSadig/pathway-ai-frontend).

> No real admission, tuition or university data ships with this repository. Data must come from cited official sources (`source` table).

## Technology stack
Java 21 · Spring Boot 4.1.2 (Web MVC, Data JPA, Validation, Actuator) · PostgreSQL 17 · Flyway · Maven Wrapper · JUnit 5 + Mockito + Testcontainers · GitHub Actions

## Architecture
Package-by-feature under `com.pathwayai.backend`:

```
university/  controller service repository entity dto mapper   <- public API
program/     entity repository dto
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
| `PORT` | `8080` | HTTP port (injected by most hosts) |

A git-ignored `.env` in the working directory is read automatically; real environment variables take precedence.

## Connecting the frontend locally
1. `docker compose up -d`, then start the backend (`.\mvnw.cmd spring-boot:run`); backend URL `http://localhost:8080`.
2. In the frontend repo set `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080` (e.g. git-ignored `.env.local`) and run it on `http://localhost:3000`.
3. Check `Invoke-RestMethod http://localhost:8080/api/v1/universities`. The database ships empty, so expect `content: []` until real, source-cited data is imported.

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
