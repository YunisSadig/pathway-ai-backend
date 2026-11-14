# Deploying the API (Spring Boot) and verifying it

Status: the container, the production profile and the verification workflow are built and tested. **The hosted service does not exist yet**: creating it needs your interactive login (section 3). Public launch is **BLOCKED** (section 6).

## 1. Choice of host (researched 2026-11-14)

**Render, free web service, Frankfurt** (same region as the Neon database).

| Fact (source: Render docs, `render.com/docs/free`, read 2026-11-14) | Consequence |
| --- | --- |
| A free web service **spins down after 15 minutes without inbound traffic**; waking it takes **about one minute** | The first request after idle is slow. Visitors see a loading state; the frontend must tolerate a 60 s first response. A paid always-on instance ($7/month, not purchased) removes it. |
| **750 free instance hours per workspace per month** | One always-on service fits (720-744 h); it is shared by every free service in the workspace. |
| No payment method is needed to create it; without one, exceeding bandwidth **suspends** the free services for the month instead of billing | No spend is possible without your action. |
| Docker is supported through a Blueprint (`render.yaml`) | `Dockerfile` is used as is. |
| Free instance memory (reported as 512 MB by Render's docs and some reviews, 256 MB by one mid-2026 review; **unverified**) | The JVM is capped at 65 % of the container limit; the image was tested at 512 MB (starts in ~9 s locally, readiness in ~15 s). Confirm the figure in the dashboard. |

Rejected: Koyeb (card required for the free instance, per its pricing FAQ), Google Cloud Run / Oracle Cloud / Azure (billing account or card), Fly.io (no free tier). Re-check before relying on any of this; free tiers change.

## 2. What is already in the repository

* `Dockerfile`: multi-stage, JRE-only runtime, non-root user, JVM flags for a small instance, `HEALTHCHECK`, graceful shutdown.
* `application-prod.yaml`: graceful shutdown, no error details, 4-connection pool with short lifetimes (Neon suspends idle compute), TLS through the JVM trust store, Flyway **off** (schema changes are applied by the controlled sync/bootstrap run with the owner role, before a deploy; set `FLYWAY_ENABLED=true` only deliberately), only `health,info` actuator endpoints, no health details.
* Fail-closed startup guards (production profile): the API **refuses to start** unless `STAGING_ACCESS_KEY` is set (or `PUBLIC_LAUNCH=true` was set on purpose), and unless a remote database URL has `sslmode=verify-full` exactly once.
* `X-Staging-Key` gate on `/api/v1/*`, an in-memory per-client rate limit (default 240/min, `RATE_LIMIT_PER_MINUTE`), explicit-origin CORS, `no-store` on errors, ETag/`Cache-Control` (see README).
* `render.yaml` (Blueprint, `autoDeployTrigger: checksPass`: a push to `main` deploys only after its GitHub checks pass), `.github/workflows/verify-deployment.yml` (waits until the live `/actuator/info` reports the merged commit, then smoke-tests; skipped until `API_BASE_URL` is set).

## 3. Owner steps (cannot be done for you: interactive login)

1. Sign in at render.com with GitHub, authorize the `pathway-ai-backend` repository, **New > Blueprint**, pick this repository. Do not add a payment method.
2. Enter the secrets Render asks for (they stay in Render, never in Git):
   * `DB_URL`: `jdbc:postgresql://<unpooled Neon host>/pathway_ai?sslmode=verify-full` (the same value as the GitHub secret).
   * `DB_USERNAME`, `DB_PASSWORD`: **preferably a read-only role** (section 5), not the owner role.
   * `STAGING_ACCESS_KEY`: a long random string (`openssl rand -base64 32`). Keep a copy for step 3.
   * `CORS_ALLOWED_ORIGINS`: the frontend origin(s), comma separated, no wildcard.
3. GitHub: create the environment `staging` (restrict to `main`) with secret `STAGING_ACCESS_KEY`; add repository variable `API_BASE_URL` = the `https://....onrender.com` origin. The next merge to `main` is then verified end to end.

## 4. Rollback

* **Code:** Render dashboard > the service > Events > a previous successful deploy > **Rollback** (instant, no rebuild). Or `git revert` the merge commit; the push redeploys after CI.
* **Schema:** migrations are additive and are applied by the controlled run before the deploy, so the previous image keeps working against the newer schema. A destructive change needs a two-step release (stop reading, then drop). Never reset the database.
* **Data:** restore from the Neon point-in-time window or the owner-run dump (section 7).

## 5. Least-privilege database role (owner step, one time)

Run once in the Neon SQL editor as the owner (put the password in Render only):

```sql
CREATE ROLE pathway_api LOGIN PASSWORD '<generate one>';
GRANT CONNECT ON DATABASE pathway_ai TO pathway_api;
GRANT USAGE ON SCHEMA public TO pathway_api;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO pathway_api;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO pathway_api;
```

The API only reads. The sync/bootstrap workflow keeps the owner credentials in the protected `sync` environment.

## 6. Public launch: BLOCKED

Whether Studyinfo data may be stored and displayed publicly (licence, attribution, persistent copies, commercial use) is unresolved: `docs/data/studyinfo-permission-request.md` is a draft that **you** must send (to the contact named in the API documentation). Until a written answer is recorded, run the API as protected staging only (`STAGING_ACCESS_KEY` set, `PUBLIC_LAUNCH` unset). A browser cannot hold the key: the frontend reaches the API through a server-side proxy that adds it (see the frontend repository's deployment document), and the frontend itself must also be access-protected. Setting `PUBLIC_LAUNCH=true` is the single, deliberate switch, and only after the rights are established.

## 7. Backups and recovery (limits)

Neon's free plan keeps a short point-in-time history window (check the console for the current figure) and no scheduled dumps. The authoritative academic data is reproducible from the official API, so the realistic recovery is: restore within the window, or re-run `bootstrap` + `sync` against an empty database (about 15 minutes). Owner-run dump: `pg_dump "<unpooled connection string>" -Fc -f pathway_ai.dump` from a trusted machine (the file contains data, keep it private). A restore drill has **not** been performed: UNVERIFIED.
