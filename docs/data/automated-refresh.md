# Automated refresh

How the platform keeps itself current without a person re-reading every university site. Code: `com.pathwayai.backend.refresh`. Schema: `V6__source_monitoring.sql`.

## Design in one paragraph

Every official page the platform cites is a **monitored source** with its own state (ETag, Last-Modified, content hash, failure count, next check). A run fetches what is due, reduces each page to its main text, hashes it, and compares. A change never edits published facts directly: whatever an adapter extracts is **validated and staged** first; only an empty, low-impact field read from structured data is published automatically, everything else waits in a **review queue**. Failures leave the last verified record untouched.

```
FETCH → CANONICALISE → EXTRACT → NORMALIZE → VALIDATE → COMPARE → STAGE → APPLY or REVIEW → AUDIT
```

## Source registry (`source_monitor`)

One row per cited page, 1:1 with the existing `source` table (no duplicate source table). It records: university and/or program, domain, kind (`UNIVERSITY_INFO`, `ADMISSIONS`, `PROGRAM_CATALOGUE`, `PROGRAM`, `TUITION`, `SCHOLARSHIP`, `DEADLINES`), supported fields, extraction method, adapter name and version, status (`ACTIVE`, `FAILING`, `UNSUPPORTED`, `PAUSED`), last attempt / success / change, ETag, Last-Modified, canonical content hash and the parser version that produced it, last result, failure count, next scheduled check, verification status. `source_check` keeps every attempt; `staged_change` holds proposed changes with their decision and who made it.

Today: 128 monitored sources: 81 institution sources (45 registry records, 36 home pages on the `content-watch` adapter), 19 admissions pages (university and program level), 17 programme catalogues, 10 program pages and 1 tuition page. Every source is read with the `content-hash` or `content-watch` adapter; no source yet has a structured-data (`jsonld-program`) or site-specific adapter.

## Checking schedule

| Source | Interval |
| ------ | -------- |
| General university information, programme and admissions pages, tuition and scholarship pages | weekly (`app.refresh.general-interval-days=7`) |
| Any source whose program has a verified application deadline within 60 days (`deadline-window-days`) | daily |
| After a failure | back-off 6 h, 12 h, 24 h, ... never longer than the normal interval |
| After `429` / `503` with Retry-After | the Retry-After (capped at 24 h) |

These are starting values, not a claim that every institution needs the same frequency. The schedule lives in the database (`next_check_at`), so running more often than needed is harmless: a source that is not due is not fetched.

## Change detection

Conditional requests (`If-None-Match`, `If-Modified-Since`), then a hash of the **canonical text**: scripts, styles, navigation, header/footer/aside, banners, cookie notices and advertising are removed, timestamps and copyright lines stripped, whitespace normalised. A changed hash means the page's main content changed, not that tuition or requirements did: adapters decide what facts, if any, changed. When the canonicaliser or an adapter changes version the hash is re-baselined instead of reporting every page as changed.

Observed on live sites (second pass minutes after the baseline, 128 sources): 89 unchanged, 36 answered `304 Not Modified`, 1 changed (a home page whose news changed), 2 failed (a server error on the site's robots.txt). Home pages are therefore watched but raise no review items (`content-watch`).

## Adapters

`ExtractionAdapter` (name, version, `extract(page)`). Preference order: official API or feed, then structured data in the page, then a reviewed per-site HTML adapter. Present: `jsonld-program` (duration in months and language codes from schema.org), `content-hash`, `content-watch`. There is intentionally no generic HTML scraper. See `official-sources.md`.

## Publication policy

| Fact | New (field empty) | Differs from a published value |
| ---- | ----------------- | ------------------------------ |
| Duration, teaching languages, from structured data | **published automatically** (`AUTO_APPLIED`, actor `system:auto`) | review |
| Tuition, deadlines, requirements, anything else | review | review; a dated fact for an existing period is never overwritten |
| Page changed, no adapter facts | review item (`SOURCE_CONTENT`), one per distinct content | n/a |

Validation rejects (and records, never stages): ambiguous amounts (`20,000`), unknown currencies, wrong fee units, unknown applicant categories, academic years that are not consecutive, dates that are malformed or implausible, a deadline after the study start, contradictory values in one extraction, and any program fact from a source that is not bound to a single program. Staging is idempotent: the dedupe key covers source, fact and value, so processing a page twice (or under a new parser version) creates nothing new.

## History is preserved

A fee or deadline for a **new** period or cycle is a **new row**; older academic years and cycles are untouched and keep their temporal status. A different value for a period that already exists is a conflict: it cannot be approved over the old row (correct it through the reference-data import with a reasoned retraction, then reject the item). Years and periods are stored only when the source prints them (V4 rules).

## Failure behaviour

A failed fetch, timeout, HTTP 4xx/5xx, oversize or unsupported response, an adapter exception, or a rollback **never touches published facts**. The check is logged, the failure count rises and the check is retried with back-off; after 3 consecutive failures the source shows FAILING. A `404` is a failure, not proof that a programme was discontinued. Each source is processed in its own transaction, so one bad page cannot affect another.

## Crawling boundaries

- https only, no credentials in URLs, default port; every URL and every redirect hop must belong to an **approved host** (the domain of a registered source) and resolve only to public addresses (loopback, private, link-local including cloud metadata, carrier-grade NAT, multicast, reserved and IPv6 unique-local ranges are refused): SSRF protection.
- `robots.txt` per RFC 9309 (own product token, longest match wins, missing = allowed, server error = assume disallow and retry later), cached 24 h.
- At most one request per host every 3 s; 20 s timeout; 4 MB response cap (rejected, not truncated); allowed types: HTML, XHTML, JSON, JSON:API, JSON-LD, XML, text; 2 retries with exponential back-off for transient errors; `Retry-After` honoured.
- A `401`/`403`, a bot-protection or challenge page is recorded as **blocked**: the source becomes `UNSUPPORTED` and is not retried until an operator re-activates it. Nothing is bypassed.
- Limitation: DNS may change between the address check and the connection (time-of-check/time-of-use); the approved-host and https-only rules bound the risk.

## Operating it (command line only; there is no HTTP control surface)

Start the packaged application without a web server and give it a command:

```
java -jar target/pathway-ai-backend-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none --app.refresh.command=<command>
```

| Command | Effect |
| ------- | ------ |
| `register` | registers the cited pages as monitored sources (idempotent) |
| `run` | checks every source that is due (in batches of 100, up to 20 batches), then exits |
| `report` | prints the monitoring report |
| `reactivate` (`--app.refresh.id=SOURCE_ID` optional) | puts UNSUPPORTED / FAILING / PAUSED sources back on schedule |
| `review-list`, `review-approve`, `review-reject` | the review queue (see `change-review.md`) |

Database access uses the usual `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. Settings: `app.refresh.*` (see `RefreshProperties`): user agent, host interval, timeout, size cap, retries, intervals, batch size, failure threshold, extra approved hosts.

## Monitoring report

`report` prints: universities (total, verified, registry-only) and by country with program counts; programs with a sourced requirement, with tuition, with a deadline today or later; monitored sources by status; checks in the last 7 days (successful, failed or blocked, changed pages, by outcome); changes applied automatically and awaiting review; never-checked and stale sources; the first unresolved sources. It is read-only.

## Unattended operation: what must be deployed

A laptop that sleeps, or a free host that suspends the app, will silently miss runs. The in-process scheduler (`app.refresh.scheduler.enabled=true`, cron `0 0 5 * * *` UTC) exists but is **off by default** and only suitable for an always-on server. Recommended architecture, independent of any laptop:

1. A **hosted PostgreSQL** (not provisioned; needs approval) that both the API and the refresh job use.
2. A **scheduled job** that runs `--app.refresh.command=run` once a day. Either a platform cron job running the same container image (Render/Railway/Fly cron, Cloud Run Jobs, Kubernetes CronJob) or the manual-only GitHub Actions template `.github/workflows/source-refresh.yml` (enable its `schedule` block once secrets are set; the runner must be able to reach the database, so it needs a database that accepts connections from GitHub or a private network arrangement).
3. Secrets in the platform's secret store: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.
4. A weekly `report` run whose output someone reads, and `review-list` so changes do not wait unseen.

Egress: outbound HTTPS to university domains and `hei.api.uni-foundation.eu`. Nothing is deployed or paid for by this repository. Because due-ness is stored in the database, the job is safe to run twice, late, or after downtime: it simply catches up.

## Limitations

- Tuition, deadlines and requirements are **not** extracted automatically yet: there is no reviewed adapter for any site. The pipeline for them (validation, staging, review, history-safe apply) is built and tested with synthetic facts; real extraction needs adapters written against each site's actual markup.
- Content hashing watches the page text, so an unrelated edit in the main content still produces a review item for admissions/catalogue pages.
- 9 institutions are not monitorable (their sites block automated access), and ELTE's robots.txt currently errors.
