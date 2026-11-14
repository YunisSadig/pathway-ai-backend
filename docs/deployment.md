# Deployment

CI and CD are separate concerns. **Nothing in this document has been provisioned, published or paid for.**

## CI (implemented, automatic)
`.github/workflows/ci.yml` runs on every push to `main` and every pull request: checkout → Temurin 21 (Maven cache) → `./mvnw -B -ntp clean verify` (unit tests + Testcontainers PostgreSQL integration tests; Docker is preinstalled on `ubuntu-latest`) → upload surefire/failsafe reports on failure. Needs no secrets. CI never deploys.

## CD (NOT implemented — plan, needs owner approval)
The service is a plain executable Spring Boot jar (`target/pathway-ai-backend-0.0.1-SNAPSHOT.jar`) that needs only a PostgreSQL database and environment variables. Flyway migrates the schema on startup.

### Runtime requirements
| Requirement | Value |
|---|---|
| Java | 21 (or a container image based on a JDK/JRE 21) |
| Memory | ≥ 512 MB (Spring Boot + Hibernate; 1 GB is comfortable) |
| Port | reads `PORT` (default 8080); health probe `/actuator/health` (`/actuator/health/liveness`, `/readiness`) |
| PostgreSQL | 17 (managed instance; the DB user must be able to run DDL because Flyway migrates on boot) |

### Environment variables (set in the host's secret store, never in git)
| Variable | Production value |
|---|---|
| `DB_URL` | `jdbc:postgresql://<host>:5432/<db>?sslmode=require` |
| `DB_USERNAME` / `DB_PASSWORD` | managed-DB credentials |
| `CORS_ALLOWED_ORIGINS` | the exact deployed frontend origin(s), e.g. `https://app.example.com` (comma separated; wildcards are refused at startup) |
| `PORT` | usually injected by the platform |

The frontend then sets `NEXT_PUBLIC_API_BASE_URL` to the backend's public https origin.

### Options (costs are estimates to confirm before approval — pricing changes)
| Option | Shape | Likely cost |
|---|---|---|
| Container PaaS (Render / Railway / Fly.io) + the platform's managed PostgreSQL | needs a `Dockerfile` (not yet added) or a Java buildpack | paid web service + paid DB, typically a few USD/month each; free tiers usually sleep or expire the DB |
| Managed PostgreSQL elsewhere (Neon / Supabase free tier) + a small app host | same app, external DB | DB may be free; app host paid |
| AWS/Azure/GCP (App Runner / Container Apps / Cloud Run + managed Postgres) | most control | highest and least predictable |

### Proposed steps once approved
1. Owner picks a host and approves recurring charges.
2. Add a `Dockerfile` (multi-stage, JDK 21 build → JRE 21 run, non-root) and verify it locally.
3. Provision the managed database; create an app user; set the env vars above in the host's secret store.
4. Deploy from `main` after CI is green (host-side auto deploy, or a GitHub Actions deploy job that uses environment secrets and a protected `production` environment).
5. Smoke test `/actuator/health` and `/api/v1/universities`; then set the frontend's `NEXT_PUBLIC_API_BASE_URL` and the backend's `CORS_ALLOWED_ORIGINS`.
6. Take the database backup/retention policy from the provider's managed plan.

### Open decisions
Host choice, budget, custom domain/TLS, and when real source-cited data will be imported (the API returns an empty page until then).
