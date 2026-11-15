# Release handoff (2026-11-15)

## 1. Deployed commits (verified 2026-11-15)

| Part | Commit | Source of the check |
| --- | --- | --- |
| Frontend (Vercel) | `49c5dd0` | `GET /api/version` on the site equals the head of `pathway-ai-frontend` main |
| Backend (Render) | `1ddf687` | latest GitHub deployment record equals the head of `pathway-ai-backend` main |

Both CI runs on those commits are green (backend 155 unit + 258 integration tests; frontend 131 unit tests, 350 Playwright passed, 6 skipped). The post-deploy live smoke (workflow "Verify deployment", run 43721447705) passed 12 of 12 on desktop and mobile: sign-up, profile, save, log out, log in, delete account, visitor discovery.

## 2. Verified production functionality

Checked read-only against the live site: `/`, `/universities`, two university pages, `/scholarships`, `/login`, `/signup`, `/forgot-password` return 200; the public API lists 3 universities (Aalto 2, Helsinki 30, Oulu 24 tracked programs) and 56 programs; search (`q=data&level=master&language=en` returns 2) and the field filter (`field=law` returns 1) work; a program page carries the official program link, `lastCheckedOn` (source check) and `verifiedOn` (record verification); protected endpoints (`/api/v1/programs`, `/api/v1/account/saved`) answer 401 to a visitor. Not re-checked by hand in this session: browser rendering of the new programs (covered by CI and the live smoke before the data growth).

## 3. Synchronization

Source: Studyinfo official API (`reference-data/studyinfo.json`, 30 programs per university, English-taught bachelor's and master's only; joint two-provider programs are refused by design, which is why Aalto has 2). Pipeline: official record, parse, validate, stage, publish or hold for review, with history and provenance (`docs/data/sync-policy.md`, tests in `docs/data/sync-test-matrix.md`). Automatic: duration, languages, campus, new application rounds, an unknown field. Review required: name, level, a changed field, changed dated facts. Unavailable from Studyinfo: tuition, requirements, application URL.

Workflow "Source refresh" (`.github/workflows/source-refresh.yml`): cron `17 5 * * *` UTC daily (`run`), Mondays also `sync` (discovery); one run at a time (`concurrency`), three attempts with growing waits per step, the freshness watchdog fails the run when any program is older than 10 days, a failed run opens a GitHub issue, secrets only in the protected `sync` environment, only triggers are schedule and manual dispatch. **Disabled: the variable `SYNC_ENABLED` is unset, so no scheduled run has ever happened.** Two manual runs have succeeded on production (discovery 43721293604, force refresh 43729771755: 56 of 56 programs fresh, 0 failures).

### Activating scheduling (owner)

1. healthchecks.io (free plan): create a check, period 1 day, grace 6 hours, email notification; copy its ping URL.
2. GitHub, Settings, Environments, `sync`, add the secret `HEALTHCHECK_PING_URL` (the value is the ping URL; never commit it).
3. Settings, Secrets and variables, Actions, Variables: add `SYNC_ENABLED` = `true`.
4. Optional: run "Source refresh" manually with mode `freshness` once to see the ping reach Healthchecks.
5. Verify the first automatic run the next morning after 05:17 UTC: Actions shows an event `schedule` run with every step green, Healthchecks shows a ping, and `GET /api/v1/public/programs/<id>` shows `lastCheckedOn` equal to that day. A missing ping alerts you by email; a failed run opens an issue.
6. To pause: set `SYNC_ENABLED` to anything but `true`.

## 4. Environment variables still needed (names only)

- Email: `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `MAIL_FROM` (a sender the provider has verified), `SITE_URL` (the public site origin, used in the links).
- Google sign-in (after it is built): `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`.
- Monitoring: `HEALTHCHECK_PING_URL` (secret, `sync` environment), `SYNC_ENABLED` (variable).
- Already set: database secrets, `API_BASE_URL`, `STAGING_SITE_URL`, `CORS_ALLOWED_ORIGINS`.

## 5. Google sign-in

Not implemented. Setup, design and security rules: `docs/engineering/google-oauth.md`. Exact steps: Google Cloud project; OAuth consent screen, External, testing mode, scopes `openid email profile`, add your test accounts; OAuth client ID of type Web application; authorized redirect URI `https://<site>/api/v1/account/oauth/google/callback` (the same-origin proxy path, chosen because the site proxy already carries the session cookie; confirm it when the route is built) and `http://localhost:3100/api/v1/account/oauth/google/callback` for local tests; set the two variables on the API host. Do not link a Google login to a password account unless Google reports a verified email AND the account proves ownership.

## 6. Email delivery (password reset and email verification)

Both flows exist and are tested with a captured outbox (`AccountEmailFlowIT`); delivery to a real inbox is UNVERIFIED because SMTP is unset (the API then reports `emailDeliveryAvailable=false` and the site hides the actions). Token rules in code: 256-bit random, only the SHA-256 stored, single use, verification link valid 24 h, reset link valid 1 h; reset ends all sessions of the account. Rate limits per hour: reset 6 per IP and 3 per address, resend verification 6 per IP and 3 per address, sign-up 8 per IP. Verification never gates sign-in or any feature (owner decision 2026-11-15). Test in production: set the variables, redeploy, request a reset for a throwaway account you own, receive the mail, follow the link once, confirm the second use is refused and the old password stops working.

## 7. Healthchecks.io

See section 3. It is the only alarm for "no run at all" (GitHub disables scheduled workflows after 60 days of repository inactivity).

## 8. Studyinfo permission

The official documentation states no licence or attribution terms. The request (`docs/data/studyinfo-permission-request.md`) is still unsent: fill in name and contact and send it to `palaute@opintopolku.fi`. The public program tier is an owner-accepted risk until a written answer exists; narrow it in `PublicProgramController` if the answer is restrictive.

## 9. Known data-coverage limits

3 universities, 56 programs, Finland only. Aalto 2 (joint programs and non-English offerings excluded). One program has no field because its code is not in the reviewed map (shows Unknown). No tuition, requirements, application links or scholarships. Fields are classified only from official codes, never from titles.

## 10. The six skipped Playwright tests

All are intentional, project-scoped skips, not defects: the desktop-only tests are skipped in the mobile project (`accounts.spec` account menu logout, `navigation.spec` desktop nav, `programs.spec` desktop nav, `public-discovery.spec` account-menu persistence) and the two mobile-only tests are skipped in the desktop project (`navigation.spec` mobile drawer). The same journeys have a counterpart in the other project or in the live smoke.

## 11. Repository settings

Branch protection is NOT enabled on either repository (the API answers 404), so nothing technically prevents a merge with failing checks; the practice has been to merge only after CI passes. Recommended: protect `main` and require the `build` (backend) and `frontend` checks.

## 12. Next developer session

1. Read this file, `docs/engineering/known-issues.md`, `docs/data/sync-policy.md`.
2. Owner actions first (below); then build Google sign-in from `google-oauth.md` with a Google test account.
3. After activation, check the first scheduled run (section 3) before expanding anything.
4. Next data work: tuition and requirements need an official source and permission.
