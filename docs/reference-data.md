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

## How the current batch was gathered (2026-11-11)

Each page was fetched and only statements the page makes were recorded. Items a page did not state were left out.
All programs are `VERIFIED` as of 2026-11-11, meaning "read on that date"; they are not a statement that the university
guarantees the data stays current. Re-check before relying on any date or fee.

| Program                                    | Source pages read                                                |
| ------------------------------------------ | ---------------------------------------------------------------- |
| University of Helsinki: BSc Science        | programme page, admissions page                                  |
| University of Helsinki: BA Liberal Arts and Sciences | programme page, admissions page                        |
| University of Helsinki: MSc Economics      | programme page                                                   |
| University of Helsinki: MSc Data Science   | programme page                                                   |
| University of Helsinki: MA English Studies | programme page, admissions page                                  |
| University of Amsterdam: MSc Cultural Psychology | programme page, application and admission page             |
| Aalto University: MSc Architecture         | study-option page, "Apply to master's programmes" page           |
| Aalto University: MSc Mechanical Engineering | programme page (only name, degree and a tuition rule stated)   |

### Judgement calls and known gaps (be skeptical of these)

- **Tuition year.** Pages state a yearly fee "for non-EU/EEA citizens" next to the admission round for autumn 2027
  (Aalto and the Helsinki English Studies and Liberal Arts pages say "autumn 2027" explicitly; the other Helsinki pages show
  the January 2027 application period beside the fee). The fee is stored as academic year `2027/2028`, because the schema
  needs a year. The page itself does not print "2027/2028".
- **Applicant category.** `INTERNATIONAL` here means "the group the university charges": non-EU/EEA citizens. It is not a
  legal definition.
- **Application links.** For several programs the page only names the national portal (Studyinfo) and gives no direct form
  URL. `officialApplicationUrl` is therefore the university's own official how-to-apply or admissions page that was read
  (and, for Helsinki, not the Studyinfo form). Programs whose admissions page could not be fetched (HTTP 403 to automated
  clients: Helsinki Economics and Data Science; Aalto Mechanical Engineering, no verified apply page) have **no**
  application URL.
- **University of Amsterdam deadline.** The page gives "1 October to 1 March" without a year, so the intake keeps that
  text in its label and no deadline date.
- **Aalto University city, campus cities, study mode (most programs), requirements (all but one):** not stated on the pages
  read, so left unknown.
- **Mechanical Engineering (Aalto):** the page states only the name, degree and a fee rule keyed to study-right start
  dates, so duration, language, intake and tuition are unknown. It is included to show unknown data staying unknown.
- Not researched: scholarships (none are created; none are independently verified).
