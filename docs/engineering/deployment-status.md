# Deployment status (state of 2026-11-14)

COMPLETE = proven by a real run (link or measurement). UNVERIFIED = built but not proven. BLOCKED = needs the owner or an external answer.

| Area | Status | Evidence / what remains |
| --- | --- | --- |
| Neon database (Frankfurt, free) | COMPLETE | Hosted runs 43610948377 (bootstrap), 43613484847 (sync), 43614861221 (force), 43615748446 (verify). TLS `verify-full` through the JVM trust store; Flyway V1-V8 on PostgreSQL 17.12; 3 universities, 9 programs (Helsinki 4, Aalto 2, Oulu 3), 17 external ids, 46 provenance rows; every invariant of `verify-data` holds. |
| Repeat synchronization idempotent | COMPLETE | After a second `sync` and after `force` (9 records re-read: `unchanged=9`) counts and the write fingerprint were byte-identical; discovery: created 0, already imported 9. |
| Freshness watchdog | COMPLETE | 9 of 9 extractor-maintained programs fresh (runs 43613339367, 43615748446). |
| API reads the hosted database | COMPLETE | Run 43618013702 (`api-smoke`): the production-profile API on the runner served 9 programs and 3 universities from Neon, gate 401 without a key, detail 200, `/actuator/env` 404. |
| Container | COMPLETE (locally) | Image built and run: non-root, healthy, ready in about 15 s, graceful stop, no secret in logs, 401/200 gate. Not yet running on a host. |
| Hosted backend | COMPLETE (protected staging) | Render free web service `pathway-ai-api` (Frankfurt, Docker, Free), https://pathway-ai-api.onrender.com. Reads Neon through the read-only role `pathway_api` (SELECT only, verified) over TLS verify-full; 9 programs and 3 universities served with the staging key, 401 without it; verify-deployment run 43625469490 passed. Sleeps after 15 idle minutes. |
| Hosted frontend | BLOCKED (owner login) | Vercel Hobby is wired (`deploy.yml`, inert until `VERCEL_DEPLOY_ENABLED=true`); needs the owner's login and secrets; Hobby is non-commercial (frontend docs/deployment.md). |
| Backend CI | COMPLETE | Every PR and push to `main`: Linux build, unit tests, Testcontainers ITs, guard tests, executable-bit guard. Merged PRs #8-#13 and #16, all CI green. |
| Backend CD | UNVERIFIED | Render deploys `main` after checks pass (`autoDeployTrigger: checksPass`) and `verify-deployment.yml` proves the live commit; neither has run because the service does not exist yet. |
| Frontend CI | COMPLETE | format, lint, typecheck, 66 unit tests, build, 240 Playwright tests (desktop and mobile, axe light and dark) on Linux. PRs #10-#14 merged. |
| Frontend CD | UNVERIFIED | `deploy.yml` verified only by reading; never executed. |
| Source to website path | COMPLETE (locally) / UNVERIFIED (hosted) | Real API container + synthetic database + the frontend's proxy: a committed change was served through the frontend 310 ms later on revalidation; no rebuild. Browser cache bound 300 s. No catalogue exists in the frontend. Hosted end-to-end needs the two hosts above. |
| Protected staging | COMPLETE (code) | API gate (`STAGING_ACCESS_KEY`, fail-closed in production), frontend proxy and site password (fail-closed), `public` rewritten to `private`, bundle scan clean. |
| Scheduled synchronization | NOT ENABLED | `SYNC_ENABLED` is unset on purpose; manual dispatch works. Enable it only after the dead-man's switch exists. |
| Missed-run monitoring | BLOCKED (owner account) | Healthchecks.io (or equivalent) account and ping URL not created; no independent alert is configured or tested. Do not rely on missed-run protection. |
| Failure alerts | UNVERIFIED | The alert job (issue + optional webhook) has never fired. |
| Backups / restore | PARTIAL | Neon point-in-time window only; owner-run `pg_dump` documented; no restore drill done. |
| Public launch | BLOCKED | Studyinfo licence, attribution, persistent-copy and commercial terms are unanswered: the draft email (`docs/data/studyinfo-permission-request.md`) must be sent by the owner. |
| Paid resources | NONE | Nothing paid was created or requested. |
