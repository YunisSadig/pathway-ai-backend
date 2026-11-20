# Deployment status (state of 2026-11-14, evening)

COMPLETE = observed in a real run (link or measurement). PARTIAL = works, with a stated gap. UNVERIFIED = built, not proven. BLOCKED = needs the owner or an external answer.

## Current state (2026-11-20)

The rows below are the 2026-11-14 snapshot; they are not rewritten except where marked.

* Live: frontend on Vercel, backend on Render, database on Neon (Flyway V14). An UptimeRobot check pings `/actuator/info` every 5 minutes.
* The catalogue is private (`cataloguePublic=false`). Studyinfo permission was asked on 2026-11-19; no reply yet.
* Finland: 7 universities, 72 programs (20 curated); daily sync ON since 2026-11-19 with Healthchecks; curated preview fingerprint baseline `3a38029b…` (finland-20-progress.md).
* Poland: 8 universities, 14 programs imported 2026-11-20 from POL-on (CC0 dataset; API terms not stated); 6 candidates skipped pending identity mapping; official programme links for 4 programmes and fee statements for 4 (AGH structured, Poznan UT x2 and Poznan UEB word for word) from the universities' own pages, no deadlines or requirements yet; sync ENABLED since 2026-11-20 after two clean manual production reads (`REFRESH_EXCLUDED_ADAPTERS=polon-courses,pl-university-page` pauses it; docs/data/poland-polon.md).
* Google sign-in is verified in production. Email delivery is OFF (password reset and email verification unavailable), postponed: no domain yet; the SMTP configuration was reverted (#56).

## Live endpoints (public site, closed catalogue)

| What | URL | Access |
| --- | --- | --- |
| Frontend (Vercel Hobby, project `pathway-ai-frontend`) | https://pathway-ai-frontend-roan.vercel.app | Public site with no site password (the password prompt was removed; accounts and a closed catalogue, see the frontend repository's `docs/deployment.md`) |
| Backend API (Render Free, service `pathway-ai-api`, Frankfurt) | https://pathway-ai-api.onrender.com | `/actuator/health/readiness` and `/actuator/info` are open; every `/api/v1/*` call needs the `X-Staging-Key` header. **Cold start measured: 84.6 s for the first request after 17 idle minutes** (then 0.4 s and 0.13 s) |
| Database (Neon Free, project `pathway-ai`, Frankfurt, PostgreSQL 17) | not exposed | TLS `verify-full`; the API uses the read-only role `pathway_api` |

## Acceptance criteria

| # | Requirement | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Running hosted Spring Boot backend | COMPLETE | Render Free service; readiness 200; the commit reported by `/actuator/info` equals the merged commit (backend run 43625791497) |
| 2 | Running protected Next.js staging site | COMPLETE | Vercel production deployment of `main`; 401 without the password, 200 with it; `/api/version` equals the merged commit (frontend run 43628497905) |
| 3 | Both communicate | COMPLETE | Browser tests through the site's proxy: 10 of 10 passed (desktop and mobile) against the live site and the hosted API |
| 4 | Correct Neon data flow | COMPLETE | The hosted API serves 9 programs and 3 universities read from Neon through a verified read-only role (SELECT yes, INSERT/UPDATE no, not in `neon_superuser`) |
| 5 | Nine verified programs through the protected application | COMPLETE | Helsinki 4, Aalto 2, Oulu 3; visible only with the site password or staging key |
| 6 | Automatic deployment after approved merges | COMPLETE (observed twice) | Backend: PR #18 merged, Render deployed 208 s later, verification passed. Frontend: PR #24 merged, Vercel deployed, the `status`-triggered verification passed. The two pipelines are independent: each repository deploys only itself |
| 7 | Academic-data updates reach the website without a frontend rebuild | PARTIAL | Measured locally with a synthetic fixture: a committed database change was served through the frontend proxy 310 ms later on revalidation (upper bound 300 s from `max-age`). The frontend holds no catalogue. A hosted propagation test was not run: it would mean changing hosted rows that must stay source-backed |
| 8 | Scheduled synchronization operating unattended | DONE (2026-11-19) | `SYNC_ENABLED=true`; `HEALTHCHECK_PING_URL` set in environment `sync`; first scheduled run on 2026-11-19 succeeded with the ping sent (freshness 92/92). An UptimeRobot check on `/actuator/info` every 5 minutes also keeps the free Render backend awake |
| 9 | Failure and missed-run monitoring | PARTIAL | Failing deployments fail the verification workflows visibly. The sync alert job has never fired (UNVERIFIED). Missed-run alarm (Healthchecks.io): configured 2026-11-19 |
| 10 | Security and backups | PARTIAL | See below. A restore drill passed on a Neon branch, but no independent off-site backup exists |
| 10b | Cold start of the free backend | COMPLETE (measured) | 84.6 s after 17 idle minutes (2026-11-15 20:02Z). This exceeded the frontend proxy's 75 s upstream wait, so the first visitor after an idle period would have received a 502: fixed (proxy waits 130 s, `maxDuration` 150, loading state explains a long wait; frontend PR #26). **Verified through the live site:** after 17 idle minutes the first request via the site returned HTTP 200 with all 9 programs in 108.7 s (then 0.9 s and 0.6 s). The margin below 130 s is about 20 s, so a rare slower wake-up can still show the error state with its retry button |
| 11 | Zero paid services | COMPLETE | Neon Free, Render Free, Vercel Hobby, GitHub free; no payment method added anywhere |
| 12 | Real logs and verified URLs | COMPLETE | Links below |

## Pipelines (proven by real runs)

* Backend: PR, CI (Linux build, unit tests, Testcontainers ITs, guards), merge, Render auto-deploys `main` after checks pass (`render.yaml`, `autoDeployTrigger: checksPass`), then workflow `Verify deployment` (trigger `workflow_run` after CI) waits for the live commit and smoke-tests with the staging key. Run: https://github.com/YunisSadig/pathway-ai-backend/actions/runs/43625791497
* Frontend: PR, CI (format, lint, typecheck, unit, build, e2e), merge, Vercel Git integration deploys `main`, then workflow `Verify deployment` (trigger: the `Vercel` commit status) waits for the served commit, runs the health script and 5 Playwright tests on desktop and mobile against the live site. Run: https://github.com/YunisSadig/pathway-ai-frontend/actions/runs/43628497905
* Data: manual dispatch of `bootstrap`, `sync`, `force`, `verify`, `api-smoke` and `freshness` on the hosted database succeeded (runs 43610948377, 43613484847, 43614861221, 43615748446, 43618013702). A repeat sync and a forced re-read of all 9 records changed nothing (counts and write fingerprint identical).

## Security

* TLS to Neon: `sslmode=verify-full` through the JVM trust store, enforced by code (the API refuses to start otherwise; the scheduler script refuses any other mode).
* Credentials: the Neon owner secrets live only in GitHub environment `sync`; Render holds a separate read-only role; the staging key lives in Render, the GitHub `staging` environment and Vercel (sensitive variables). Nothing is in the repositories, in public logs or in a `NEXT_PUBLIC_*` variable (bundle scan clean).
* Third-party GitHub access: Render's app and Vercel's app are each installed on exactly one repository (a first Render install accidentally covered all repositories for about a minute and was narrowed immediately).
* Secret hygiene incident: one staging key was visible in a screenshot taken during setup; it was rotated before it was used anywhere.
* Staging protection: API gate (fail-closed in production), site password (fail-closed), and shared caches never store gated data (`public` is rewritten to `private`).

## Not done / owner decisions

1. **Public launch: BLOCKED.** Studyinfo licence, attribution, persistent-copy and commercial terms are unanswered. The email is drafted in `docs/data/studyinfo-permission-request.md` (repository URL filled in; your name and contact remain). The permission request was made on 2026-11-19; no reply yet.
2. **Healthchecks.io (DONE 2026-11-19, see row 8):** create an account (emailed link), create a check with a 1-day period and a short grace, store its ping URL as the `HEALTHCHECK_PING_URL` secret of environment `sync`, send a test ping, then set `SYNC_ENABLED=true`.
3. **Backups:** workflow `Backup` (encrypted `pg_dump` kept 30 days as an artifact, then an automatic restore test into a throwaway database). Manual dispatch works; the weekly schedule is off until you (or I, after a green run) set the repository variable `BACKUP_ENABLED=true`. Residual risk: artifacts live in GitHub, so a lost GitHub account loses them; a copy elsewhere is an owner decision.
4. **Commercial use:** Vercel Hobby is for non-commercial use; revisit before any commercial launch.
5. **The 37 `staged_change` rows are not a review queue.** Inspected 2026-11-14 in the database: all 37 are `AUTO_APPLIED`, risk `LOW`, reason "initial import of a new program from the official source" (9 deadlines, 9 durations, 9 campus cities, 9 languages, 1 academic field). Nothing is `PENDING_REVIEW`. Each deadline states its own year in the round label (Spring 2027, Admission 2027), so the year rule holds. An earlier report called them "awaiting review"; that was wrong.
