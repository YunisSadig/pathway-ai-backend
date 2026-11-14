# Automatic-synchronization-only catalogue (policy, 2026-11-14)

**Rule: a record is published only if something keeps it current by itself.** Static university, program, tuition,
admission or scholarship records are not part of the active catalogue. Page-change detection is not synchronization.

```
OFFICIAL SOURCE -> SCHEDULED FETCH -> EXTRACTION -> VALIDATION -> DATABASE UPDATE -> FRONTEND
```

## 1. Publication eligibility (enforced in code, `DiscoveryPolicy`)

A program is listed by `GET /api/v1/programs` and reachable by its identifier only if **all** hold:

| Condition | Where it comes from |
|---|---|
| program `VERIFIED`, university `VERIFIED` | catalogue identity (registry match + official site read) |
| `lifecycle_status = ACTIVE` | the official source has not said the program is discontinued |
| has a **fact-extracting** monitor (`ADAPTER` official API or `JSON_LD` structured data) | `source_monitor.extraction_method` |
| that monitor is `ACTIVE` and succeeded within `app.discovery.max-source-age-days` (45) | `source_monitor.last_success_at` |
| no extractor monitor of the program is failing, unsupported or stale | same |

A `CONTENT_HASH` monitor never makes a program eligible. Held-back programs are kept, not deleted, and return after the
next successful extractor run. `GET /api/v1/discovery/status` explains why (`notVerified`, `discontinued`, `noExtractor`,
`neverChecked`, `sourcesStale`, `extractorFailing`) and reports `scheduler.inProcessEnabled`.

## 2. What is updated automatically and what is not

One class states it, `refresh/pipeline/SyncPolicy`; the pipeline enforces it and the API reports it per field
(`synchronization.fields[].updateMode` in `GET /api/v1/programs/{identifier}`).

| Field | Source | Change at the source | Mode |
|---|---|---|---|
| duration (months) | Studyinfo official API | different value | `AUTOMATIC`: published, old value archived in `program_fact_history`, audited in `staged_change` (`AUTO_APPLIED`, `system:auto`) |
| teaching languages | Studyinfo | different value | `AUTOMATIC` (a record with an unknown language omits the whole list; nothing is removed) |
| campus city | Studyinfo | different value | `AUTOMATIC` |
| application deadline | Studyinfo (round closing time) | **new** application round | `ADDITIVE_AUTOMATIC`: added; an existing round is never overwritten |
| application deadline | Studyinfo | closing time of a published round changed | **review** (dated facts are never overwritten, even by approval; correct by retraction) |
| program name | Studyinfo | changed | `REVIEW_REQUIRED` (old value stays shown until approved) |
| degree level | Studyinfo | changed | `REVIEW_REQUIRED` |
| academic field | Studyinfo | changed | `REVIEW_REQUIRED` |
| official program URL, identifiers | Studyinfo | - | `FIXED_AT_CREATION` (stable) |
| discontinued program | Studyinfo (`tila` = `arkistoitu`/`poistettu`) | state change | automatic: marked `DISCONTINUED`, hidden, nothing deleted, audited (`LIFECYCLE`); reinstated if published again |
| new program | Studyinfo tarjonta listing | appears (within the university's `maxPrograms`) | created by `sync` with the full pipeline, verified, monitored |
| tuition, scholarships, admission requirements, application URL | **no connector** | - | **unavailable**: listed in `synchronization.notSynchronized`, never shown as maintained |

Not discontinuations (they are failures and change nothing): HTTP errors, timeouts, a 404, a draft state (`tallennettu`,
goes to review), invalid JSON. A failing extractor makes the program drop out of discovery after the freshness window.

Anything that needs manual approval is **not** "automatically updated" and the API labels it `REVIEW_REQUIRED`.

## 3. The synchronization pass

`--app.refresh.command=sync` (`SyncOrchestrator`): (1) discover new Studyinfo programs for the reviewed universities
(an existing program is skipped without a request), (2) refresh every monitor that is due, honouring Studyinfo's
`Crawl-delay: 30`. Repeating it is safe: identical data writes nothing (`StudyinfoConnectorIT`). `run` refreshes without
discovering; `studyinfo-sync` forces every Studyinfo program to be re-read now.

```
$env:JAVA_HOME = "<JDK 21>"
java -jar target/pathway-ai-backend-*.jar --spring.main.web-application-type=none --app.refresh.command=sync
```

### Is it unattended? (honest status)

* **Schedule configured:** yes, two ways, both off by default: `app.refresh.scheduler.enabled=true` (in-process, cron
  `0 0 5 * * *` UTC, only while the app runs) and `.github/workflows/source-refresh.yml` (commented-out `schedule`).
* **Operational unattended:** **no.** There is no hosted database or always-on host, and provisioning one needs approval and
  incurs cost. Until then synchronization runs only when `sync` is started by a person. Programs therefore show
  `synchronization.status: AUTOMATIC` (an extractor maintains them) but are only *kept* fresh while that command runs; if it
  stops for 45 days they leave discovery by design.

## 4. Safe cleanup of the development database

Files: `scripts/db/retire-preview.sql` (read-only), `scripts/db/retire-unsynchronised-catalogue.sql` (the procedure),
`scripts/db/retire-unsynchronised-catalogue.sh` (`--dry-run` / `--commit`). Rule: keep a program only if one of its monitors
extracts facts; keep a university only if it has a kept program.

Safeguards: database-name guard (`cleanup.expected_database`, default `pathway_ai`) **and a development marker on the database itself**
(the name alone cannot tell production apart; set once, by a person, on the development database only:
`COMMENT ON DATABASE pathway_ai IS 'pathway-ai:development';` , an unmarked database is refused and no wrapper sets it); verified `pg_dump` first (restore
into a scratch database and compare counts); preview; one transaction; every removed program, university, monitor and
orphaned source is snapshotted into `catalogue_archive` **before** deleting; verification inside the transaction (expected
counts, extractor present for every kept program, no page-change monitor left, no university without a program, no
unreferenced source, every foreign key satisfied, `flyway_schema_history`/`country`/`academic_field` untouched) and any
failure rolls everything back. The PostgreSQL volume, Flyway history and other databases are never touched
(`CatalogueRetirementIT` covers keep/remove, dry-run rollback, failed check, wrong database and idempotence).

## 5. Keeping static data from coming back

* `reference-data/taxonomy.json` holds countries and academic fields only; `ReferenceDataFileTest` fails if a program,
  university, source or retraction is added; the startup runner refuses such a file unless
  `app.reference-data.allow-static-records=true` is set deliberately.
* `reference-data/universities.json` lists exactly the connector-backed universities (`UniversityCatalogueFileTest` compares
  it with `studyinfo.json`).
* `aalto-fee-groups.json` is empty: Aalto's tuition adapter needs a program mapping *and* a monitor for that program.
* No migration inserts academic data and the content-hash registrar was removed (`NoStaticCatalogueIT`: startup with every
  importer enabled yields only the three universities and no programs, fees, deadlines or monitors).
* To add a university or program, add a **connector** first (an official API, or a permitted per-site extractor with a
  recorded fixture and one live run), then register it; never a JSON record.
