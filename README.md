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

List parameters: `q` (name contains, ≤100 chars), `country` (2-letter ISO code), `page` (≥0, default 0), `size` (1–100, default 20), `sort` (`name`\|`city`, default `name`), `direction` (`asc`\|`desc`).

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/universities?q=tech&country=de&size=10&sort=name"
```
Response: `{ "content": [...], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }`.
Errors share one shape: `{ timestamp, status, error, message, path, violations[] }` — `400` for invalid parameters, `404` for an unknown slug/path.

## Environment variables
| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/pathway_ai` | JDBC URL |
| `DB_USERNAME` | `pathway` | DB user |
| `DB_PASSWORD` | *(required)* | DB password — never committed |
| `POSTGRES_DB` / `POSTGRES_PORT` | `pathway_ai` / `5433` | Used by Docker Compose |

A git-ignored `.env` in the working directory is read automatically; real environment variables take precedence.

## Troubleshooting
- **`role "pathway" does not exist`** — another PostgreSQL owns the port. Check `Get-NetTCPConnection -LocalPort 5433` and change `POSTGRES_PORT`/`DB_URL`.
- **`Could not find a valid Docker environment`** — start Docker Desktop and retry.
- **`Failed to bind properties ... password`/connection refused** — copy `.env.example` to `.env` and run `docker compose up -d`.
- **Wrong Java version** — set `JAVA_HOME` to JDK 21 (see above).
- **`clean` cannot delete `target`** — don't run Maven from inside `target\`.
- More: [known issues](docs/engineering/known-issues.md), [mistakes and fixes](docs/engineering/mistakes-and-fixes.md).
