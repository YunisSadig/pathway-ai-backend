# Reference data: how real records get in

Real facts are **data, not schema**. Flyway migrations (`src/main/resources/db/migration`) only create structure.
Facts live in a versioned JSON file and are loaded by an idempotent importer.

## The file

`src/main/resources/reference-data/programs.json` (schema version 1). It contains countries, a two-level academic-field
taxonomy, official sources, universities and programs with their intakes, tuition and requirements. The Java model is
`program/importer/ReferenceData.java`; unknown properties are rejected, so a typo cannot silently drop a fact.

Rules (enforced by `ReferenceDataValidator`, the importer, database constraints and `ReferenceDataFileTest`):

- Every changing fact (intake, tuition, requirement) cites a source that is listed in the file.
- A source is an official page: `https://` URL, title, publisher, type and the date it was read (`retrievedOn`).
- A `VERIFIED` program has a verification date and at least one source.
- An `officialApplicationUrl` is only allowed for a `VERIFIED` program, and must itself be one of the program's cited
  sources (so it was actually read, not guessed).
- Everything else is optional. **Omit what the page does not state.** Omitted means unknown, never zero or a guess.

## Running the import

```powershell
$env:IMPORT_REFERENCE_DATA = "true"          # off by default
.\mvnw.cmd spring-boot:run                   # imports at startup, then serves
# optional: $env:REFERENCE_DATA_LOCATION = "file:C:/path/to/other.json"
```

- **Idempotent:** natural keys decide identity (ISO code, field slug, source URL, university slug,
  university+program slug, intake label/year/category, tuition program/year/category). Running the same file again
  logs `0 new rows`. A corrected file updates rows in place.
- **Atomic:** the file is validated first and imported in one transaction: all of it or none of it. A failed import stops
  startup.
- **Additive:** the importer never deletes. Removing a record is a deliberate manual or future-admin action.
- **Safe on conflicts:** a tuition figure that differs from an existing one for the same program, year and category is
  refused rather than overwritten.

## The year rule (applies to every year-specific fact)

An academic year, application cycle or date range is stored **only when the cited source states it**.

- Never take a year from the date a page was read.
- Never take a year from a neighbouring heading, an admission round shown beside a fee, or wording such as
  "autumn 2027". Such wording stays in the intake `label` verbatim; the `academic_year` column stays empty.
- A tuition fee records what the source said: a printed academic year (`academic_year`), or a start-date range
  (`effective_from` / `effective_to`, for example "rights to study beginning on or after 1 August 2025"), or neither
  (period unknown). `scope_note` keeps the source's own wording.
- A newer year is a **new row**. Older rows are never overwritten (tuition is keyed by program, year and category, or by
  its start-date range).
- The API derives `temporalStatus` from the stated period and today's date (an academic year is taken to start on
  1 August): `CURRENT`, `HISTORICAL` or `UNKNOWN_YEAR`. Nothing stored says "current", so facts age correctly.

### Application links versus admissions links

- `officialAdmissionsUrl`: an official page that **explains** admission. Informational only.
- `officialApplicationUrl` + `officialApplicationCycle`: an official page where one **applies**, verified for that
  cycle. The API only returns it as an active Apply link while that cycle is current or upcoming.
- The pages read so far are admissions-information pages. The actual application forms live on national portals
  (Studyinfo.fi, Studielink) that were **not** opened, so **no program currently has an active Apply link**.

### Correcting a published error: retractions

The importer never deletes, except for an explicit, reasoned `retractions` entry in the file (tuition or intake, with
`reason`). Retracting something already gone does nothing, so re-running stays safe. The first batch stored academic year
`2027/2028` on six tuition figures and four intakes. That year was **inferred**, not printed by the sources. They were
retracted (10 retractions, reasons in `programs.json`) and stored again without a year.

## How the current batch was gathered (read 2026-11-11)

Each page was fetched and only statements the page makes were recorded. Unstated items were left out. "VERIFIED" means
read on that date; it is not a guarantee the data stays current.

| Program | Source pages read |
| --- | --- |
| University of Helsinki: BSc Science | programme page, admissions page |
| University of Helsinki: Liberal Arts and Sciences (BSc) | programme page, admissions page |
| University of Helsinki: MSc Economics | programme page |
| University of Helsinki: MSc Data Science | programme page |
| University of Helsinki: English Studies (MA) | programme page, admissions page |
| University of Amsterdam: Cultural Psychology (MSc) | programme page, application and admission page |
| Aalto University: Architecture (MSc) | study-option page, "Apply to master's programmes", "Scholarships and tuition fees" |
| Aalto University: Mechanical Engineering (MSc) | programme page, "Scholarships and tuition fees" |

## Which periods could actually be verified

| Program | Facts stored | Period stated by the source | Historical evidence |
| --- | --- | --- | --- |
| Helsinki BSc Science | deadlines 2027-01-19 and 2027-03-23; fee 13,000 EUR (non-EU/EEA); SAT/ACT admission rule | deadlines carry their own year; **fee: none stated** | none |
| Helsinki Liberal Arts and Sciences | deadlines 2027-01-19, 2027-03-23 ("autumn 2027 starts" kept in the label); fee 13,000 EUR | fee: none stated | none |
| Helsinki MSc Economics | deadline 2027-01-19; fee 18,000 EUR | fee: none stated | none |
| Helsinki MSc Data Science | deadline 2027-01-19; fee 15,000 EUR | fee: none stated | none |
| Helsinki English Studies | deadline 2027-01-19 ("autumn 2027"); fee 13,000 EUR | fee: none stated | none |
| Amsterdam Cultural Psychology | intake text "1 October to 1 March" | **no year printed**, so no deadline date | none (see below) |
| Aalto Architecture | deadline 2027-01-05 ("autumn 2027"); fee 20,000 EUR | fee: rights beginning on or after **2025-08-01** | only the current era |
| Aalto Mechanical Engineering | fee 17,000 EUR from **2025-08-01**; fee 15,000 EUR up to **2024-08-01** | both stated explicitly | **two eras** |

Academic years **2022/2023 to 2026/2027 and 2027/2028 could not be verified as separate cycles for any program.** Why:

- Wayback Machine access is blocked for the tools used (`web.archive.org` refused), and none of the three universities
  exposes an official archive of earlier programme pages that could be fetched.
- Aalto publishes an official PDF, "Tuition fees at Aalto University" (Oct 2024); it downloaded but its text could not be
  extracted with the available tools, so nothing was taken from it.
- Helsinki and Amsterdam programme pages show only the upcoming round.
- The University of Amsterdam fee page prints explicit years ("2026-2027" statutory fee 2,694 EUR for EU/EEA students;
  a "2027-2028 institutional fee per faculty" table whose Social and Behavioural Sciences master's fee for non-EEA
  students is 23,455 EUR). Those are **faculty-level** figures; the page does not say they apply to Cultural Psychology, so
  they are **not** stored for it.

### Did the admissions process change? (classification of the verified comparisons)

| Program / criterion | Classification |
| --- | --- |
| Aalto Mechanical Engineering: tuition for tuition-fee students | **Significant documented change** in the checked criterion: 15,000 EUR (study rights up to 2024-08-01) to 17,000 EUR (from 2025-08-01), +13.3%. Nothing is stated for study rights started between 2024-08-02 and 2025-07-31. Two eras are not enough to compute a change frequency, so none is given. |
| Aalto Architecture: tuition | Insufficient historical evidence (only the era from 2025-08-01 is stated) |
| Every other program, every other criterion (eligibility, language requirements, qualifications, documents, opening dates, deadlines, selection, platform) | **Insufficient historical evidence.** Absence of an archive is not evidence that nothing changed, so none is called "unchanged". |

## Judgement calls and known gaps (be skeptical of these)

- **Tuition period.** Helsinki fees have no stated period (UNKNOWN_YEAR). They are shown to users as the figure printed
  on the programme page, but are excluded from tuition price filters because their period is unknown.
- **Applicant category.** `INTERNATIONAL` means the group the university charges: non-EU/EEA citizens.
- **Aalto city, campus cities, study mode (most programs), requirements (all but one):** not stated, so unknown.
- **Mechanical Engineering (Aalto):** the programme page gives only the name, degree and dated fee rules. It states no
  language, duration or intake, so those are unknown.
- **Pages that block automated clients (HTTP 403):** Helsinki Economics and Data Science admissions pages, Aalto
  Architecture programme page; those facts are not stored.
- Scholarships were not researched and none are stored.
