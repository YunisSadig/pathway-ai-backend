# Poland: POL-on connector (private, not public)

Status 2026-11-20: **applied to production** (PR #57, fingerprint `249381a4…`): 14 programmes (7 bachelor's, 7 master's) at 8 universities. **6 candidates are skipped**
pending a person's identity mapping (Warsaw University of Technology x4, SGH x2). **Sync is enabled** (since 2026-11-20). `cataloguePublic` unchanged (false). Candidate list and source
assessment: [country-candidates-2026-11-19.md](country-candidates-2026-11-19.md).

## Source and licence
- Official register: POL-on via RAD-on open data, `https://radon.nauka.gov.pl/opendata/polon/courses` (one record per course, filtered by `courseUuid`).
- Licence found: **CC0 1.0** on the dane.gov.pl record of the Ministry's courses dataset (dataset 1892, "Studia prowadzone na określonym kierunku",
  publisher Ministerstwo Nauki i Szkolnictwa Wyższego): `https://creativecommons.org/publicdomain/zero/1.0/legalcode.pl`
  (via `https://api.dane.gov.pl/1.4/catalog/dataset/1892.rdf?lang=pl`).
- **Not stated:** reuse terms of the live RAD-on API itself (no terms page found). Summary: licence CC0 on dane.gov.pl; API terms not stated. The data stays private until the owner decides.

## What is imported (and what is not)
- From POL-on only: programme name, level, teaching language (English instance), duration (semesters, stored as months = semesters x 6),
  ISCED field when the reviewed field map has it, status. ECTS, form and start date are printed in the preview but not stored yet.
- **From the universities' own pages (phase 2a, adapter `pl-university-page`):** the official programme link only if the page is the programme's own
  (exactly one `h1`, and it contains the programme name; checked on every read). An offer or landing page that merely lists the programme is NOT
  stored as a programme link and no offer-link type exists, so the programme URL stays Not stated: AGH ("Education offer - Bachelor studies"),
  TUL ("Educational offer") and Poznan UEB (masters landing page). The fees-page link is stored where the page names the programme or states its
  fee. The SGH master pages answered 403 at times (never bypassed), the Poznan UT bachelor page is a 404, the Poznan UT master page is a PDF. A fee only where the page states it unambiguously: AGH Computer Science bachelor (EUR 3000 per semester,
  international students, academic year 2026/2027 printed in the table heading; stored as a structured fee), Poznan UT Artificial Intelligence
  bachelor and master (the fee row word for word, year 2026/2027 from the heading) and Poznan UEB Master in International Business (7 350 PLN
  per semester, kept word for word; the page calls it "current" and prints no academic year, so none is stored). Every fact carries its
  source URL and read date; a changed value waits for review.
- **Unsupported source:** `study.uek.krakow.pl` (UEK, 3 programmes) resets connections on repeated reads (observed in four rehearsal runs: the
  first read succeeded, later ones failed even after two retries). A source that cannot be read reliably cannot be kept current, so its pages
  are not read at all (`pageMatch` is null in the candidate file): no programme link, no fees link, no fee. Revisit when the site answers reliably.
- **Not imported (Not stated):** tuition for the other programmes (TUL prints 9000 PLN without stating the period, UEK/UW/SGH/PW have no
  unambiguous row or page), deadlines, admission and language requirements, application URL, admissions URL. Nothing is guessed.
- Page facts go through the normal pipeline (monitor bound to the university, program null, facts per programme slug), the POL-on course id
  is the education identifier, and the URL facts must be https pages of the university's own registered domain (`PolonSources.owned`).
- Identity: a candidate whose POL-on name is Polish or differs from the university page has `identityConfirmed=false` and is never imported
  until a person confirms the mapping (6 of 20 on 2026-11-20).

- Slugs: a name shared by two programmes of one university carries the degree level on each (`artificial-intelligence-bachelor` / `-master`), never a number.
  An existing numbered slug is renamed by `apply` only if no `saved_program` row references it (refused before any write otherwise); the old slug is
  archived in `program_fact_history` (`field_name = 'slug'`).

## Files
`reference-data/curated-poland.json` (candidates), `reference-data/universities-poland.json` (8 universities, registry-matched by SCHAC id,
home page read 2026-11-20), `provider/polon/*` (parser, adapter `polon-courses`, CLI), release `poland-curated-v1` (country PL).
`CuratedCatalog` restricts discovery per country with an enabled release; Finland's behaviour is unchanged.

## Run (operator, explicit)
1. Universities (idempotent): `IMPORT_UNIVERSITY_CATALOGUE=true UNIVERSITY_CATALOGUE_LOCATION=classpath:reference-data/universities-poland.json`.
2. Preview (read-only, prints per programme what is verified with URL and date read, and what is missing): `--app.poland.command=preview`.
3. Apply only with the approved fingerprint: `--app.poland.command=apply --app.poland.expected-fingerprint=<fingerprint>`.
Rehearse on a Neon branch first; never run against production without the owner's approval. Against Neon add
`--spring.datasource.hikari.keepalive-time=60000`: the plan reads about 25 pages and takes over 5 minutes, and Neon suspends an idle compute
after 5 minutes (otherwise "Failed to obtain JDBC Connection"). A page whose connection fails is retried twice (15 s apart) in the plan,
because some university servers drop connections at random and the apply refuses any plan that differs from the approved fingerprint.

## Sync: ENABLED (since 2026-11-20)
The 14 `polon-courses` monitors (7-day interval) and the 10 `pl-university-page` monitors are read by the daily scheduled run like Finland's.
Enabled after two clean manual reads in production on 2026-11-20 (`sync-adapter`): `polon-courses` checked 14, unchanged 14, failed 0, auto-applied 0,
pending review 0; `pl-university-page` checked 10, baseline 10, failed 0, auto-applied 0, pending review 0, rejected 0. A changed name/level/field waits for
review, a status change to not-active is the only discontinuation signal, and errors never touch published facts.
**To pause Poland** set `REFRESH_EXCLUDED_ADAPTERS=polon-courses,pl-university-page` (`app.refresh.excluded-adapters`): the scheduled run and the
freshness watchdog then skip those monitors (the watchdog would otherwise turn Finland's run red) and Poland programs leave discovery 45 days after
their last read (`app.discovery.max-source-age-days`). Reminder: the reuse terms of the live POL-on API are not stated (the dataset is CC0); the data stays private.
Fee and deadline extractors for the other universities are the next increment.
