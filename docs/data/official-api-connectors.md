# Official-source connectors (Studyinfo API, Aalto tuition page)

These are the first connectors that **maintain program facts from an official source** instead of only detecting that
a page changed. A content-hash monitor says "something changed"; a connector reads named facts, validates them,
compares them with what is published, and publishes or stages the difference. Both run on the existing source
registry, crawler boundaries and review queue (`docs/data/automated-refresh.md`, `change-review.md`).

## 1. Studyinfo (Opintopolku) external API: program identity, language, duration, deadlines

Provider: Finnish National Agency for Education (Opetushallitus). Public, unauthenticated, JSON, and described in
the provider's own source as the interface for services independent of the Agency
(`konfo-backend` `/external/*`, with its OpenAPI text in `external/api.clj`).

| Used | Endpoint |
|---|---|
| Discovery of a university's offerings | `GET /konfo-backend/external/search/oppilaitos/{organisationOid}/tarjonta` |
| One program, with implementations, application targets and rounds | `GET /konfo-backend/external/koulutus/{koulutusOid}?toteutukset=true&hakukohteet=true&haut=true` |

Boundaries (all enforced in code, not by convention): https, host `opintopolku.fi` only, the provider's published
`Crawl-delay: 30` (the fetcher now honours `Crawl-delay` for every host, capped at 60 s), robots.txt, size cap,
bounded retries, 429/Retry-After handling. A program costs one request, so 30 programs take roughly 15 minutes.

**Open point: licence (unresolved; a publicly reachable API is not a licence).** The provider's code is EUPL-1.2. Searches of the Agency's own pages found **no
statement of the licence or terms of use of the Studyinfo data or of its external API**; the Agency's site terms only
say that using the site means accepting them. Until the Agency confirms in writing: treat the data as *not licensed
for republication*; use it for the internal catalogue and refresh only; do not republish the provider's free text
(none is read or stored); keep attribution to Opetushallitus (stored as the source publisher of every record); do not
build a commercial offer that depends on bulk redistribution of this data. Ask the Agency (Opintopolku / konfo-backend
maintainers) which licence covers `/konfo-backend/external/*` before public launch.

What was checked (2026-11-14): the provider's repositories (code licence only), OPH's Opintopolku service page (no API,
reuse or licence statement; footer "© Opetushallitus 2026"), the national open-data portal searches (no dataset or
licence for the course offering), and the API's own OpenAPI file (served empty). Public accessibility, the `external`
name and an unauthenticated endpoint are **not** a grant of redistribution rights, and Finnish law also protects
catalogues and databases made with substantial investment, so bulk redistribution needs the Agency's permission.
**Restrictions to apply until then:** internal catalogue and refresh only; no mirror or bulk export of provider data;
no provider free text; attribution kept; keep request volume at the published crawl delay; be ready to purge Studyinfo-
sourced rows (they are identifiable by `program_external_id` and `program_field_provenance.mechanism = 'OFFICIAL_API'`).

### What it supplies, and how it is validated

| Fact | Source field | Rule |
|---|---|---|
| Official program name | published implementation name, else program name (en, fi, sv in that order) | as published, whitespace collapsed; a trailing intake term (", Autumn 2027") is removed because it is when the program starts, not what it is called |
| Degree level | official level codes `koulutusastetaso1_6` / `_7` | bachelor / master only; a record carrying both (combined bachelor+master) or any other level is **skipped, never guessed** |
| Academic field | national field-of-education code | only codes in the reviewed `studyinfo-field-map.json` (each added after its label was seen in a real response); otherwise **unknown** |
| Institution association | the provider's own search for the institution, plus the record's provider | a record is imported when it names exactly one provider and either names the configured institution or was listed under it by the provider's search (a unit inside the university); joint programs with two or more providers are **skipped** |
| Duration | `suunniteltuKestoVuodet`/`Kuukaudet` | whole months; absent when several implementations disagree |
| Teaching languages | `opetuskieli` | only fi/sv/en by the provider's own label; one unknown language omits the whole list |
| Campus city | municipality of the offering organisation | omitted if implementations disagree |
| Application deadline | round (`haku`) closing time | only a published round with exactly one closing time; the date is stored with the round's own name as label; **no academic year is stored** because the record only states it as the system's "inferred" start |
| Program page | built from the provider's own sitemap pattern `/konfo/en/toteutus/{oid}` | |
| External ids | `koulutus` and `toteutus` oids, organisation oid | unique, so one record is never imported twice |

### What the API cannot supply (still needs another source)

* **Tuition, scholarships**: amounts exist, but the student category (who pays) is only in free text, and the schema
  requires a category. Not imported; the university's own fee page is the source (see section 2).
* **Admission requirements** (grades, language test scores, documents): free text only.
* **Application URL**: not stated by the API; only an admissions page is known. `officialApplicationUrl` stays empty.
* **Academic year of an intake**: not stated explicitly (only inferred by the provider), so not stored.
* Universities of applied sciences (`amk`): excluded in this milestone.
* Agriculture/forestry and any field not in the reviewed map: the academic field stays unknown.

### Publication policy

| Situation | Result |
|---|---|
| New program (operator `studyinfo-import`) | created with identity facts; every stated fact is published and recorded in `staged_change` as `AUTO_APPLIED` ("initial import") |
| Refresh, value identical | **nothing is written** (no row, no timestamp, no provenance change) |
| Refresh, an empty duration/language/city appears | published automatically |
| Refresh, a published value differs (name, level, field, duration, language, city) | staged `PENDING_REVIEW`; on approval the old value is archived in `program_fact_history`, labelled as superseded without claiming when it stopped being valid |
| Refresh, a dated fact (deadline) differs | staged; **approval is refused** (dated facts are never overwritten); correct it by retraction |
| New application round | staged for review |
| Record unpublished, malformed or provider down | published facts untouched; a content-review item or a recorded failure |

Provenance is per field in `program_field_provenance`: source record, mechanism (`OFFICIAL_API`), provider id,
verification date. The last successful refresh is `source_monitor.last_success_at`.

### Running it

```
# one-off, operator-run (the first run reads and writes; --app.refresh.dry-run=true only reads)
java -jar target/pathway-ai-backend-*.jar --spring.main.web-application-type=none \
     --app.refresh.command=studyinfo-import [--app.refresh.university=university-of-helsinki] [--app.refresh.limit=3]

# re-read every Studyinfo program now, ignoring the schedule
... --app.refresh.command=studyinfo-sync
```

The universities, their organisation oids and per-university quota are in `reference-data/studyinfo.json`.
A university missing from the catalogue can be created as **UNVERIFIED** (identity then rests on the provider only);
public discovery hides UNVERIFIED universities (section 4).

### Stable links when a name changes

The program identifier (`<university-slug>--<program-slug>`), the Studyinfo page URL and the provider ids are fixed when
the program is created. A name change is staged, and approving it changes only `name` (the old name goes to
`program_fact_history`); the identifier, links and external ids stay, an old URL keeps working, and the next import
recognises the program by its provider id instead of creating a duplicate (`StudyinfoConnectorIT`). Slugs created before
the apostrophe fix (four Helsinki programs) keep their identifiers on purpose.

### Hosted scheduler (after deployment)

Nothing new is needed: every imported program has a `source_monitor` row (`ADAPTER`, `studyinfo-external`), so the
existing unattended job covers it. `.github/workflows/source-refresh.yml` runs `--app.refresh.command=run`, which
checks everything that is due (weekly; daily inside the deadline window). To enable it, with your approval and a
hosted PostgreSQL: add `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` secrets and uncomment the `schedule` block. Allow
one request per 30 s: 100 due programs need about 50 minutes (the job has a 60-minute limit, and the next run
continues with what is still due). `studyinfo-sync` can be added as a second step to force a full re-read.
Provision nothing without approval; this repository provisions nothing.

## 2. Aalto University tuition page (HTML, no API): tuition per field group and period

`https://www.aalto.fi/en/admission-services/scholarships-and-tuition-fees` states fees per degree level and field
group, **for the period it names** ("rights to study beginning on or after 1 August 2025"), for students from outside
the EU, EEA and Switzerland in programmes taught in English. `AaltoTuitionAdapter` reads exactly those statements.

* The audience sentence must be present, otherwise no fee is produced.
* The period is the stated date, never derived from the day the page was read; no end date is invented.
* Only programs in the reviewed `aalto-fee-groups.json` (program, level, fee group, evidence page) get a fee; a
  program that is not listed keeps an unknown fee. The adapter never creates a program.
* One page feeds several programs through `ScopedExtractionAdapter`; each program goes through the normal pipeline
  (`TUITION` is never auto-published; an existing period's fee is never overwritten; a newly stated period is a new
  row after review).
* Switch the page's monitor to it with `--app.refresh.command=assign-adapter --app.refresh.id=<source id>
  --app.refresh.adapter=aalto-tuition`.

## 3. Restricted sources found

* **University of Helsinki** program pages answer our bot with HTTP 403 (no `User-Agent` trickery was attempted; per
  policy the source is marked unsupported). Helsinki is covered through Studyinfo instead.
* University of Amsterdam admissions pages are readable, but deadlines are prose where the academic year is not tied
  to the deadline sentence, so a deadline extractor would have to infer a year. Not built.

## 2b. Aalto extractor on the development monitor (2026-11-14)

Source 14 (the fee page) was bound to Aalto and switched to `aalto-tuition` with `assign-adapter`, then read once:
2 facts extracted (Architecture 20 000 EUR, Mechanical Engineering 17 000 EUR, international, per year, from 2025-08-01),
2 unchanged against the stored fees, 0 staged, tuition rows byte-identical. What can genuinely be maintained: those two
programs. The two Aalto programs imported from Studyinfo have **no** fee group in the reviewed mapping (their school is
not evidenced), so their fee is unknown; no academic year, category or price is ever inferred. A source that states
facts for several programs but is not bound to a university now fails visibly (it used to degrade into a vague
"page changed" review item).

## 4. Public discovery

`app.discovery.restrict-to-verified-fresh` (default **true**) and `app.discovery.max-source-age-days` (default 45).
A program is listed only if it is VERIFIED, its university is VERIFIED, and at least one of its sources was read
successfully within the window **and no fact-extracting monitor of the program (`ADAPTER`/`JSON_LD`) is failing,
unsupported or stale** (a fresh admissions page must not keep a program listed while the monitor that maintains its
facts is broken). Held-back programs are not deleted and reappear after the next successful refresh. A withheld
program's detail answers the same 404 with a specific message ("Program withheld: ..."), and
`GET /api/v1/discovery/status` returns `state` (`FRESH`, `PARTIAL`, `STALE`, `EMPTY`, `UNRESTRICTED`), listed/withheld
counts, the reasons (`notVerified`, `sourcesStale`, `neverChecked`, `extractorFailing`), the last successful source
check and a plain-language message for a frontend to show. Facts bound to a period keep their own `temporalStatus`
(`CURRENT`, `HISTORICAL`, `UNKNOWN_YEAR`), so an expired deadline is never shown as current even from a fresh source.
Legacy programs drop out on 2026-12-26 and Studyinfo programs on 2026-12-29 if nothing refreshes.
UNVERIFIED universities are not listed. Response shapes are unchanged; only fewer rows can appear. If the scheduler
stops, discovery empties after 45 days by design: that is the freshness guarantee, so monitor the refresh job.
