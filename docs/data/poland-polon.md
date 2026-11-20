# Poland: POL-on connector (private, not public)

Status 2026-11-20: built and tested; **not applied to production**; `cataloguePublic` unchanged (false). Candidate list and source
assessment: [country-candidates-2026-11-19.md](country-candidates-2026-11-19.md).

## Source and licence
- Official register: POL-on via RAD-on open data, `https://radon.nauka.gov.pl/opendata/polon/courses` (one record per course, filtered by `courseUuid`).
- Licence found: **CC0 1.0** on the dane.gov.pl record of the Ministry's courses dataset (dataset 1892, "Studia prowadzone na określonym kierunku",
  publisher Ministerstwo Nauki i Szkolnictwa Wyższego): `https://creativecommons.org/publicdomain/zero/1.0/legalcode.pl`
  (via `https://api.dane.gov.pl/1.4/catalog/dataset/1892.rdf?lang=pl`).
- **Not stated:** reuse terms of the live RAD-on API itself (no terms page found). The data stays private until the owner decides.

## What is imported (and what is not)
- From POL-on only: programme name, level, teaching language (English instance), duration (semesters, stored as months = semesters x 6),
  ISCED field when the reviewed field map has it, status. ECTS, form and start date are printed in the preview but not stored yet.
- **Not imported (Not stated):** tuition, deadlines, admission requirements, application URL. POL-on does not state them and no university
  extractor exists yet. The candidate file keeps each university's programme/fee page URL; the preview reports whether it loaded over https
  from the university's own registered domain, and only a page read in that preview is recorded as a source.
- Identity: a candidate whose POL-on name is Polish or differs from the university page has `identityConfirmed=false` and is never imported
  until a person confirms the mapping (6 of 20 on 2026-11-20).

## Files
`reference-data/curated-poland.json` (candidates), `reference-data/universities-poland.json` (8 universities, registry-matched by SCHAC id,
home page read 2026-11-20), `provider/polon/*` (parser, adapter `polon-courses`, CLI), release `poland-curated-v1` (country PL).
`CuratedCatalog` restricts discovery per country with an enabled release; Finland's behaviour is unchanged.

## Run (operator, explicit)
1. Universities (idempotent): `IMPORT_UNIVERSITY_CATALOGUE=true UNIVERSITY_CATALOGUE_LOCATION=classpath:reference-data/universities-poland.json`.
2. Preview (read-only, prints per programme what is verified with URL and date read, and what is missing): `--app.poland.command=preview`.
3. Apply only with the approved fingerprint: `--app.poland.command=apply --app.poland.expected-fingerprint=<fingerprint>`.
Rehearse on a Neon branch first; never run against production without the owner's approval.

## Daily/weekly sync: plan only, NOT enabled
POL-on programmes are refreshed by the existing monitor pipeline (`ADAPTER polon-courses`, 7-day interval): a changed name/level/field waits
for review, a status change to not-active is the only discontinuation signal, errors never touch published facts. Enabling needs the
existing `SYNC_ENABLED` + Healthchecks ping owner action; nothing is enabled for Poland today. Fee and deadline extractors per university
(AGH, TUL, PUT, WUT, SGH publish them per academic year) are the next increment.
