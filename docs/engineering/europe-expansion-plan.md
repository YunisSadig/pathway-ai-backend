# Catalogue completeness, academic cycles and European expansion: audit and plan

Started 2026-11-18. Resume file for the follow-up to Finland 20 (`finland-20-progress.md`).

## 1. Audit of the 20 Finnish programs (2026-11-18)

Sources: production public API (live) and the local scratch copy of the identical import (fingerprint e9b169f5…).

| Field (owner's list) | State | Why |
|---|---|---|
| Name | 19 good; **LUT Digital Business is "Rolling Admission, Digital Business , …"** | extractor uses the implementation name; LUT puts the route in it. BUG |
| Pathway / degree level | complete (10/10; Turku rule) | — |
| Academic field | 18/20; **null for Helsinki Science (code 050) and Tampere CSEE (061 + 071)** | codes absent from the reviewed `studyinfo-field-map.json` (Helsinki: 050 = natural sciences, generic; Tampere: two fields) |
| Study track | only Helsinki Science has one | correct: the others have none. UI must not print "Study track: Not stated" for programs without tracks |
| Degree awarded | university fact `qualification` 18/20 (JYU pages have no degree line) | Studyinfo `metadata.tutkintonimike` has it for all 20 (not read yet) |
| Duration | 20/20 | — |
| ECTS | university fact 16/20 | Studyinfo `opintojenLaajuusNumero` has it for all 20 (not read yet) |
| Language | 20/20 | — |
| Campus city | 20/20 | — |
| Study mode | **0/20 in `degree_program.study_mode`** | Studyinfo `opetustapa` (contact/distance/blended codes) and `opetusaika` exist for all 20; never read |
| Tuition | **0 `tuition_fee` rows**; undated university facts for 20/20 (gated) | Studyinfo `opetus.maksut` = `lukuvuosimaksu` (per academic year) for all 20, attached to an implementation whose start term the source states ("Autumn 2027"); never read |
| Scholarships | university facts 5/20 | Studyinfo `opetus.apuraha` min/max + unit (euro or percent) for all 20 |
| Language tests | 2/20 (LUT) | other pages render tests client-side or in prose |
| Requirements / documents / exams | eligibility 4, documents 2 | Studyinfo application targets: `liitteet` (attachments, 0-4 each), `valintakokeet` (JYU ISE&AI online test), `pohjakoulutusvaatimus` (entry requirement codes), `aloituspaikat` (study places) |
| Deadlines / rounds | 33 routes, correct | — |
| Official pages | 20 program + 17 admissions pages, all VERIFIED (link run 44247740648) | Helsinki: no admissions link |
| Verified date / provenance | present | — |

**Public tier vs missing data:** the public program page deliberately omits fees, requirements and university facts
(catalogue gate, licence question). Some "missing" messages a visitor sees are gate restrictions, not absent data.

## 2. Academic-cycle behaviour in Studyinfo (observed in the 20 records)

- Education OID (koulutus) is stable across years; it is the program identity.
- Implementation OID (toteutus): two provider styles.
  - **Per-intake implementations** (Helsinki, Tampere, Jyväskylä: names end "Autumn 2027" / "autumn 2027"): a new
    academic year = a NEW implementation OID. Today this triggers the "NEW ADMISSION CYCLE" alert (manual review).
  - **Long-lived implementations** (Aalto, LUT, Oulu, Turku): the same implementation receives new application rounds.
- Application rounds (haku) and targets (hakukohde) are new OIDs each year; the round states the start term
  (`metadata.koulutuksenAlkamiskausi` + `koulutuksenAlkamisvuosi`). The "paatelty…" fields are inferred: never used.
- Discontinuation: `tila = arkistoitu / poistettu` (already handled).

## 3. Plan (in order; each step a focused PR with tests on recorded fixtures)

A. **Studyinfo structured facts (official API, no scraping):** read degree titles, ECTS, teaching mode,
   annual fee (amount + EUR + per year + audience; academic year ONLY if the fee text states one; see mistakes 48), scholarship range + unit,
   study places, attachment titles (names only, no prose), entrance exams (names + dates only). Store as facts with
   OFFICIAL_API provenance. Fee: `tuition_fee` row labelled with the intake ("students starting Autumn 2027"), applicant
   category only where a source states it. No provider free text stored.
B. **Name fix:** use the education name, strip the route prefix and the duration suffix by rule; existing names change
   through the review queue (names are REVIEW_REQUIRED by policy).
C. **Field map:** add 050 (natural sciences) and 071 (engineering) only with their labels as seen in a real response;
   multi-field records (Tampere) take the reviewed primary field or stay null.
D. **Cycle successor adoption:** when the reviewed implementation of an education is no longer published and exactly
   one new published implementation exists with the same provider, language, level codes/titles and study mode,
   adopt it automatically (membership + history row); anything else stays a review alert. Old routes/fees kept
   (HISTORICAL). Tested with synthetic "next year" variants of the recorded records.
E. **Change detection without URL change:** the university-program and guidance monitors already hash content and
   stage changed facts; add per-field change history to the status report.
F. **UI:** hide empty study-track row; tuition card from undated/dated fees with their stated audience; scholarship ranges; documents/exams list;
   clearer "awaiting review" / "year not stated" states.
G. **Unattended sync:** ON since 2026-11-19 (`SYNC_ENABLED=true`, Healthchecks ping configured). Poland sync is not enabled.
H. **Poland / Czech Republic:** source research report + 2x20 candidate lists (2026-11-19). Poland: 14 of its 20 candidates were imported on 2026-11-20 from POL-on and 6 are skipped pending identity mapping (docs/data/poland-polon.md). Czech Republic: candidate list only.
