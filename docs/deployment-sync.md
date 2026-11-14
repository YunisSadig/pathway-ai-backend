# Unattended synchronization: architecture, limits and the steps only you can take

Goal: program data keeps updating **while your Windows computer is off**. Nothing in this repository creates an account, a database
or any paid resource; every step that needs your authorization is in section 5.

## 1. Recommended architecture (simplest thing that is reliable)

```
GitHub Actions (cron, free on a public repo)  --TLS-->  Neon Postgres (free plan)  <--TLS--  the API, wherever it runs later
        |                                                                                          |
        +--> official Studyinfo API (30 s crawl delay)                                    frontend (GET only, 5 min cache)
```

* **Scheduler = GitHub Actions.** No server to keep alive, no container, no extra account: the workflow builds the jar, runs
  `scripts/ci/run-refresh.sh` against the hosted database, and ends. Free for public repositories.
* **Database = Neon (free plan).** The catalogue is a few hundred KB, far below the free storage. Scale-to-zero suits a job that wakes it
  for ~20 minutes a day.
* **No always-on host is needed for synchronization.** The API (for the frontend) is a separate decision (section 6); until it exists the
  synchronized data sits in the hosted database and the API on your laptop can read it by pointing `DB_URL` at it.

Why not the alternatives (limits verified against each provider's own documentation on 2026-11-14 unless marked UNVERIFIED):

| Option | Verdict | Reason |
|---|---|---|
| Neon free Postgres | **chosen** | 1 GB storage, 100 compute-hours/month, suspends after 5 min idle (cannot be disabled), 6 h restore window, no scheduled backups, limits suspend compute and "none of these limits delete your data"; a payment card is not mentioned for the free plan |
| Supabase free Postgres | fallback | 500 MB, 2 projects, but a project is **paused after low activity over 7 days** (restorable up to 1 year); a daily job would normally count as activity, but a pause needs a manual resume |
| Render free Postgres | rejected | **expires 30 days after creation and is deleted** after a 14 day grace period |
| In-process scheduler on a free web host | rejected | Render-style free web services spin down after 15 min idle, so scheduled work would be silently skipped |
| Cloud Run / Cloud Run jobs | not chosen | needs a billing account; its pricing page could not be read, so its free-tier numbers are UNVERIFIED |
| Always-on VPS | not chosen | a paid, maintained server for a job that runs 20 minutes a day |

GitHub Actions facts that shape the design (documentation read 2026-11-14): shortest schedule interval 5 minutes; a scheduled run can be
**delayed** under load (so the cron is off the hour); scheduled workflows run **only on the default branch**; in a **public** repository
they are **disabled automatically after 60 days without repository activity**; a job may run up to 6 hours; standard runners are free
for public repositories (private repositories get an included monetary allowance and then bill about $0.006 per Linux minute).

Expected cost: **$0**. Billing conditions can change: read each sign-up page, and stop if a card is requested (section 5).

## 2. What runs, when, and how failure is noticed

`.github/workflows/source-refresh.yml` (its daily schedule does nothing until you set the repository variable `SYNC_ENABLED=true`; a manual "Run workflow" dispatch always runs, so you can test first):

| When | Mode | What it does |
|---|---|---|
| daily 05:17 UTC | `run` | re-reads every source that is due (each program weekly; daily when an application window is near) |
| Mondays | `sync` | discovers new programs for the three reviewed universities, then `run` |
| every run | `freshness` | **last-success watchdog**: fails unless *every* extractor-maintained program was read successfully within 10 days |
| manual | `run`, `sync`, `force`, `bootstrap`, `freshness` | Actions tab, "Run workflow" |

* **Retries:** each step is attempted 3 times with 5 and 10 minute pauses (database cold start, network). Inside a run, the crawler also
  retries transient HTTP errors, honours robots.txt and the provider's 30 s crawl delay, and never bypasses a 403 or challenge page.
* **Failure alerts:** a failed run (or a failed watchdog) opens or comments on a GitHub issue, calls `ALERT_WEBHOOK_URL` if you add that
  secret, and GitHub emails the user who last edited the cron line (documented GitHub behaviour).
* **One flaky source does not fail the job:** it is marked FAILING (visible in `report` and `discovery/status`); the job fails when more
  than half of the checks fail.
* **Last-success monitoring:** `scripts/ci/run-refresh.sh freshness`, `GET /api/v1/discovery/status` (`lastSuccessfulSourceCheck`,
  `state`) and the per-program `synchronization.lastSuccessfulSyncAt`.
* **Three different failures need three different alarms.** A watchdog inside the job can tell the first two apart; it can never notice the third:

  | What went wrong | Who notices | How |
  |---|---|---|
  | A run fails, or more than half of the source checks fail | the workflow | the run fails, the `alert` job opens or comments on an issue (+ webhook), GitHub emails the user who last edited the cron line |
  | Runs "succeed" but a program is no longer current (a source quietly stopped, a monitor FAILING) | the `freshness` watchdog step | it exits non-zero unless **every** extractor-maintained program was read within 10 days, which fails the run and triggers the alert above |
  | **No run happens at all**: schedule disabled after 60 days of repository inactivity, a GitHub outage or dropped run, `SYNC_ENABLED` switched off by mistake | **nothing inside GitHub can** | an **independent dead-man's switch**: after every fully successful run the workflow pings `HEALTHCHECK_PING_URL`; the external service alerts you when the expected ping does not arrive |

  **Set up the independent missed-run alert, it is not optional.** Use any dead-man's-switch ("cron monitoring") service that is not GitHub: for
  example Healthchecks.io, whose pricing page lists a free plan with 20 jobs and 100 log entries per job (its notification options, grace
  period and whether a card is required were not stated on that page: UNVERIFIED, check at sign-up). Configure an expected period of 1 day and a
  grace period of at least 12 hours, and put its ping URL in the `HEALTHCHECK_PING_URL` secret. Without it the workflow prints a notice on every run
  and the only remaining backstop is that programs leave public discovery after 45 days without a successful read. Once the API is hosted, an
  external uptime check on `GET /api/v1/discovery/status` (alert when `state` is not `FRESH`) is a second independent alarm. Any push or merge
  resets the 60-day clock.

## 3. Security

* **Your local database is never exposed.** `docker-compose.yaml` now publishes PostgreSQL on `127.0.0.1` only (before, `0.0.0.0` made it
  reachable from your network). **Your running container still uses the old mapping until you recreate it.** Safe procedure, tested on a throwaway
  Compose project (the container was recreated with only `127.0.0.1` published, the volume was the same volume, and a row written beforehand survived):
  1. Stop anything using the database (your `spring-boot:run`).
  2. Optional but sensible: `docker exec pathway-ai-postgres pg_dump -U pathway -d pathway_ai -Fc > backup.dump` (keep it outside the repository).
  3. From the repository root: `docker compose up -d`. Compose sees the changed port mapping and recreates only the container; the named volume
     `pathway-ai-backend_pathway_ai_pgdata` is reused.
  4. Check: `docker port pathway-ai-postgres` must show only `127.0.0.1:5433`, and your data (3 universities, 9 programs) must still be there.
  **Never** run `docker compose down -v`, `docker volume rm` or `docker volume prune` on this project: they delete the database volume.
  The hosted runner is refused a localhost database by the script.
* **Secrets** (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, optional `ALERT_WEBHOOK_URL`, `HEALTHCHECK_PING_URL`) live in a protected GitHub **Environment**
  named `sync` that you restrict to the `main` branch, so a workflow on any other branch can never read them. Inside the workflow they exist only in
  the two steps that need the database (never in checkout or the Maven build) and the alert job holds no database secret and only `issues: write`.
  The script never prints the password and runs without `set -x`. **Actions logs of a public repository are public**: GitHub masks secret values, and
  the script additionally masks the derived host name and database name (which Flyway and Hikari log) before anything can print them, and never prints
  the connection string. The third-party actions are pinned to commit SHAs (checkout v7.0.1, setup-java v6.1.0, chosen for being weeks old, not
  the release of the day). The only triggers are the schedule and manual dispatch; never `pull_request`.
* **TLS with server verification is mandatory** for any non-local database: the script refuses anything but `sslmode=verify-full`. The PostgreSQL JDBC
  driver documents `sslmode=require` as accepting all server certificates (encryption without authentication: no protection against an impostor
  server), and `verify-ca` does not check the host name. `verify-full` verifies the certificate chain and the host name. Neon documents that it
  recommends `verify-full` and that its certificates chain to ISRG Root X1 (Let's Encrypt), which is in the system root stores and which Java
  trusts by default. **Neon's connection-string screen shows `sslmode=require`: change it to `sslmode=verify-full`.** The handshake itself could not be
  tested without a Neon database; if it ever fails, the failure is immediate and visible (the first `bootstrap` run), never silent.
* **Public repository:** workflows triggered from forks never receive secrets, the schedule runs on the default branch only, and the CI
  workflow (`ci.yml`) uses throw-away Testcontainers, never the hosted database.
* **Least privilege (recommended, not yet done):** start with the single role Neon creates; once the API is hosted, give it a second
  role without DDL rights and keep migrations on the owner role. Rotate the password if it is ever pasted anywhere.
* **The development-database cleanup script is inert on the hosted database:** it refuses any database that lacks the marker comment
  `pathway-ai:development`, which you should never set on a hosted database.
* **No static academic data can enter:** the unattended path switches the importers off (`IMPORT_REFERENCE_DATA=false`,
  `IMPORT_UNIVERSITY_CATALOGUE=false`) for every mode except `bootstrap`, which loads only the taxonomy (countries, academic fields)
  and the three reviewed universities, then lets the connector import programs. The static-records override is refused.
  `NoStaticCatalogueIT` proves a fresh database starts with no programs.

## 4. Backups and recovery

Neon's free plan keeps a 6 hour restore window and takes no scheduled backups. The hosted database holds **no user data**: programs are
re-created from the official API by `bootstrap`. What would be lost on a total loss: review decisions (`staged_change`), replaced-value
history and the retired-catalogue archive. If that matters, add a weekly `pg_dump` to private storage later (do **not** upload dumps as
artifacts of a public repository).

## 5. Steps that need YOUR account authorization (in order)

1. **Neon account and project** (free): sign up at neon.com; create a project (Postgres 17 if offered; pick an EU region). If any step asks
   for a payment card, **stop** and tell me. From the dashboard copy the **direct (not pooled)** connection details: host, database, user,
   password. Build the JDBC URL as `jdbc:postgresql://<host>/<database>?sslmode=verify-full` (NOT the `sslmode=require` that Neon shows).
2. **GitHub repository settings:**
   * **First create the Environment** (Settings, Environments, New environment) named exactly `sync`, and under "Deployment branches" choose
     "Selected branches" and allow only `main`. (If the variable below is set before this environment exists, GitHub creates it unprotected.)
   * In that environment add the secrets: `DB_URL` = `jdbc:postgresql://<host>/<database>?sslmode=verify-full`, `DB_USERNAME`, `DB_PASSWORD`; and
     `HEALTHCHECK_PING_URL` from your dead-man's-switch check (section 2; expected period 1 day, grace at least 12 hours). Optional: `ALERT_WEBHOOK_URL`.
   * Only after all that, add the repository **variable** (Settings, Secrets and variables, Actions, Variables) `SYNC_ENABLED` = `true`.
     Until it exists every scheduled run is skipped.
3. **First run on the empty hosted database:** Actions, "Source refresh", "Run workflow", mode `bootstrap` (about 20 minutes: Flyway,
   taxonomy, the three universities, then the Studyinfo programs at one request per 30 s). Open the run summary.
4. **Verify:** run mode `freshness` (must pass), then check the data, for example with the Neon SQL editor:
   `select count(*) from university;` (3) and `select count(*) from degree_program;` (about 9; the discovery quota can add a few).
5. **Leave the schedule alone:** it is already in the workflow on `main`. Add a calendar reminder for the 60-day rule or make sure the
   repository has some activity.
6. **Before any public traffic:** send the Studyinfo permission request (`docs/data/studyinfo-permission-request.md`) from your own
   address and wait for the answer (section 7).
7. **Phase 2, separate authorization:** host the API (section 6), set `CORS_ALLOWED_ORIGINS` and `DB_URL` there, add the external uptime
   check on `/api/v1/discovery/status`.

I can do none of 1, 2, 3 or 6 for you: they need your accounts, your repository settings and your name.

## 6. The frontend and caching (what it will see)

The API now sends `Cache-Control: public, max-age=300, must-revalidate` plus a content `ETag` on lists and details, `no-cache` on
`/api/v1/discovery/*`, and `no-store` on every error (`ApiCacheIT`). After at most **5 minutes** a browser or CDN must revalidate: an
unchanged answer is a cheap `304`, a synchronized change is a fresh `200` with a new ETag. A withheld program that returns later is never
remembered as "not found". Tune with `app.api.cache-max-age-seconds`.

The frontend repository was not touched and its own caching cannot be verified from here. Check that it does not use `force-cache`, a
service worker cache-first strategy, or a build-time snapshot or revalidation window longer than 5 minutes, and consider showing a notice
when `GET /api/v1/discovery/status` reports a state other than `FRESH`.

Hosting the API (not chosen yet): it needs a Java 21 host, `DB_URL` pointing at the hosted database and `CORS_ALLOWED_ORIGINS`. A free
web service that sleeps (Render's spins down after 15 minutes and takes about a minute to wake) works for a prototype but gives slow first
requests; there is no Dockerfile yet. Decide after the Studyinfo answer, because public traffic is what the data-reuse conditions govern.

## 7. Resolving Studyinfo's data-reuse conditions before public deployment

What the Agency itself says (read 2026-11-14 on the guidance page linked from the API's OpenAPI text): published offerings may be
fetched "for display in a third-party web service through the open external API", and the data "is open data". What it does **not**
say: a licence name, attribution wording, whether a persistent, refreshed copy may be kept, whether bulk redistribution is allowed, or
any rate limit beyond the published 30 s crawl delay. "Open data" without a named licence is not a licence.

Required before the catalogue is public:

1. **Written confirmation from Opetushallitus** (they named `palaute@opintopolku.fi` for questions about the interface). Ask for: the
   licence of the data returned by `/konfo-backend/external/*`, the attribution to show, permission to keep and refresh a stored copy in
   our database for display, any limits on frequency or volume, whether commercial use is covered, and a contact for takedown. A draft
   (English and Finnish) is in `docs/data/studyinfo-permission-request.md`; you send it, and keep the reply.
2. **Record the answer** in `docs/data/official-api-connectors.md` and close known issue 36 only then.
3. **Apply the conditions:** show the attribution in the frontend; if a persistent copy is not allowed, switch to on-demand display
   (no stored copy) or drop the connector; if commercial use is excluded, do not launch commercially on this data.
4. **Until the answer:** no public launch, no bulk export or mirror, the provider's free text is never stored, the crawl delay stays,
   and Studyinfo rows stay purgeable (identifiable by `program_external_id` and `program_field_provenance.mechanism = 'OFFICIAL_API'`).

I am not a lawyer: if the answer is ambiguous or restrictive, take it to someone qualified before launch.
