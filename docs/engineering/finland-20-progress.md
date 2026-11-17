# Finland 20: progress checkpoint

Resume file for the "Finland 20" milestone (20 curated English-taught programs: 10 bachelor's-entry, 10 master's).
Read this first in a new session; do not rediscover the architecture.

## Phase 1: audit and mapping (2026-11-17)

### Preserved work
- Uncommitted Codex work in both repos backed up to `C:\Users\Lenova\pathway-ai-wip-backup\2026-11-17\`
  (`*-tracked.patch` = `git diff`, `*-untracked.tar` = untracked files). Working trees left untouched.
- Branch in both repos: `feat/official-program-links`. Open PRs: backend #39, frontend #50 (both CI green, unmerged).
  Their committed content is the official-link slice (Studyinfo URL no longer used as `officialProgramUrl`).
- Uncommitted on top of #39 (backend): V13 migration (`catalog_release`, `catalog_membership`,
  `program_university_fact`), `CuratedCatalog` (discovery filter by enabled release), `CuratedSources` +
  `reference-data/curated-finland.json` (12 Aalto/Oulu candidates, NOT the owner's 20), `CuratedCatalogCli`
  (preview -> fingerprint -> apply), `UniversityProgramAdapter` / `UniversityAdmissionsAdapter` (Aalto/Oulu HTML only),
  parser support for combined bachelor+master entry, workflow modes `curated-preview` / `curated-apply`.
  Frontend uncommitted: `university-information.tsx` + detail-page sections for `universityInformation`.

### Reuse decision
Reuse the Codex curated framework (release/membership tables, fingerprinted preview/apply, Studyinfo identity checks).
Generalise: 12 -> 20 (10+10), hosts/publishers from a per-university table instead of hardcoded switches,
per-site parser profiles for Tampere, LUT, Jyväskylä, Turku.

### Concern found
`UniversityAdmissionsAdapter.section()` stores whole page sections (up to 6000 chars) of university prose.
The owner's brief forbids copying whole pages/copyrighted text. Plan: store short structured values
(test names + scores, dates, fee amounts) and the source URL; long prose stays on the university page.

### Studyinfo facts (from the API's own sources)
- Endpoints (konfo-backend `external/api.clj`): `/koulutus/:oid`, `/toteutus/:oid`, `/hakukohde/:oid`, `/haku/:oid`,
  `/search/koulutukset`, `/search/toteutukset-koulutuksittain`, `/search/oppilaitos/:oid/tarjonta`, `/search/filters`.
  No Caller-Id requirement appears in the OpenAPI text; we send `Caller-Id: pathwayai.backend` anyway (harmless).
- robots.txt: `crawl-delay: 30` -> one request per 30 s.
- Source-backed classification: filter `koulutustyyppi=yo` subtypes `kandi` (bachelor), `kandi-ja-maisteri`
  (combined bachelor+master, bachelor's entry), `maisteri` (master). English filter: `opetuskieli=oppilaitoksenopetuskieli_4`.

### New universities: EU HEI registry identity (read 2026-11-17)
| University | SCHAC | Registry label | Erasmus | PIC |
|---|---|---|---|---|
| Tampere University | tampere-university.fi | TAMPEREEN KORKEAKOULUSAATIO SR | SF TAMPERE17 | 902999288 |
| LUT University | lut.fi | (see raw record) | | |
| University of Jyväskylä | jyu.fi | JYVASKYLAN YLIOPISTO | SF JYVASKY01 | 999842245 |
| University of Turku | utu.fi | TURUN YLIOPISTO | SF TURKU01 | 999903064 |
Raw records: `C:\Users\Lenova\pathway-ai-research\studyinfo-2026-11-17\hei\`. `tuni.fi` is NOT a registry id (404).

### Raw evidence (reuse; do not re-request)
`C:\Users\Lenova\pathway-ai-research\studyinfo-2026-11-17\`
- `search/*.json`: 16 `/search/koulutukset` responses (keyword, `koulutustyyppi=yo`, English filter).
- `koulutus/<education-oid>.json`: 22 full `/koulutus/{oid}?toteutukset&hakukohteet&haut` records (all HTTP 200, 2026-11-17).
  These are the recorded fixtures for tests (copy to `src/test/resources/curated/`).
- `pages/NN.html`: the 20 university program pages (all HTTP 200, one matching `<h1>`, 2026-11-17). NN = row number below.
- `hei/`: EU HEI registry records. Helper scripts (scratch, not kept): search/details/pagefacts summarisers.

## Phase 1 result: shortlist (research only; awaiting owner approval)

Status key: **CONFIRMED** = published Studyinfo record + English implementation + current 2027 round + university page
(200, correct `<h1>`, degree stated). Tuition = non-EU/EEA, EUR per academic year, from the university page; the page
does not label the fee with an academic year unless noted ("intake 2027 stated" = page says studies start autumn 2027).
"In prod" = present in the public-API inventory snapshot of 2026-11-16 (`milestone-1-inventory.md`), NOT a Neon query.

### Bachelor's entry (education OID / implementation OID; prefix 1.2.246.562.13. / .17. omitted)
| # | University | Program | Field | Degree | ECTS / years | Tuition | Studyinfo | Deadline (Studyinfo) | Status |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Aalto | Data Science | IT | BSc(Tech)+MSc(Tech) combined | 180+120 / 3+2 | 12 000 (+17 000 master phase) | …2815 / …8195 | rounds A and B 7–22 Jan 2027 | CONFIRMED |
| 2 | Aalto | Economics | Business | BSc+MSc (Econ & BA) combined | 180+120 / 3+2 | 12 000 (+15 000) | …2810 / …8190 | A/B 7–22 Jan 2027; competition route 9–23 Mar 2027 | CONFIRMED |
| 3 | Aalto | International Business | Business | BSc+MSc (Econ & BA) combined | 180+120 / 3+2 | 12 000 (+15 000) | …10636 / …31744 | A/B 7–22 Jan 2027 | CONFIRMED; university page still shows 2026 dates (stale) |
| 4 | Helsinki | Bachelor's Programme in Science | IT/science | BSc+MSc combined (Studyinfo); page shows bachelor 180/3 | 180 / 3 (+2) | 13 000 | …0628 / …39769 | group 1 5–19 Jan 2027; group 2 9–23 Mar 2027 | CONFIRMED; "Computer and Data Science" is a study option inside this degree, not a separate offering |
| 5 | Tampere | Computing Sciences and Electrical Engineering | IT | BSc(Tech)+MSc(Tech) combined | 180+120 / 3+2 | 10 000 (+12 000) | …12744 / …42206 | Joint Application 7–21 Jan 2027 15:00 (+ MAOL, transfer, open-university routes) | CONFIRMED |
| 6 | LUT | Digital Business | Business | BSc+MSc (Econ & BA) combined | 180+120 / 3+2 | 12 000; EUR 2 000 early-bird 1st year; application fee EUR 100; intake 2027 stated | …10933 / …33130 | rolling 1 Sep 2026 – 30 Apr 2027 (may close early) | CONFIRMED |
| 7 | Oulu | Computer Science and Engineering | IT | BSc(Tech)+MSc(Tech) combined | 180+120 / 3+2 | 10 000; 30 % waiver from 2nd year | …11061 / …35101 | Joint Application 7–21 Jan 2027 15:00 | CONFIRMED |
| 8 | Jyväskylä | Immersive Software Engineering and AI | IT | BSc(Tech), standalone | 180 / 3 | 16 000; early-bird discount (amount not on page) | …12852 / …42904 | Joint Application 7–21 Jan 2027 15:00 | CONFIRMED |
| 9 | Jyväskylä | Business Innovation and Sustainability | Business | BSc+MSc (Econ & BA) combined (Studyinfo); page describes 180/3 bachelor | 180 / 3 (+2) | 10 000 | …9323 / …42620 | Joint Application 7–21 Jan 2027 15:00 | CONFIRMED |
| 10 | Turku | Information and Communication Technology | IT | BSc(Tech)+MSc(Tech) combined | 180+120 / 3+2 | 10 000 (+12 000); intake Aug 2027 stated | …9103 / …25047 | Joint Application 7–21 Jan 2027 15:00 | CONFIRMED with classification rule (below) |

### Master's
| # | University | Program | Field | Degree | ECTS / years | Tuition | Studyinfo | Deadline | Status / in prod |
|---|---|---|---|---|---|---|---|---|---|
| 11 | Aalto | Computer Science | IT | MSc(Tech) | 120 / 2 | 17 000 | …2764 / …8123 (not the double-degree impl …8175) | 7 Dec 2026 – 5 Jan 2027 | CONFIRMED |
| 12 | Aalto | Economics | Business | MSc (Econ & BA) | 120 / 2 | 15 000 | …2704 / …8019 | 7 Dec 2026 – 5 Jan 2027 | CONFIRMED |
| 13 | Helsinki | Computer Science | IT | MSc | 120 / 2 | 15 000 | …0565 / …39900 | 5–19 Jan 2027 | CONFIRMED; in prod |
| 14 | Helsinki | Data Science | IT | MSc | 120 / 2 | 15 000 | …0553 / …39878 | 5–19 Jan 2027 | CONFIRMED; in prod |
| 15 | Tampere | AI-native Software | IT | MSc | 120 / 2 | 12 000 | …12726 / …42113 | 14 Dec 2026 – 7 Jan 2027 | CONFIRMED |
| 16 | LUT | Software Engineering | IT | MSc(Tech) | 120 / 2 | 15 000; EUR 5 000 early-bird 1st year; fee EUR 100; intake 2027 stated | …10955 / …33628 | rolling 1 Oct 2026 – 31 Mar 2027 | CONFIRMED |
| 17 | LUT | Business Analytics | Business | MSc (Econ & BA) | 120 / 2 | 15 000; same discounts; intake 2027 stated | …0614 / …41507 | rolling 1 Oct 2026 – 31 Mar 2027 (two applicant-category rounds) | CONFIRMED |
| 18 | Oulu | Software Engineering and Information Systems | IT | MSc | 120 / 2 | 10 000; 30 % waiver for 2nd year | …0867 / …25774 | Joint Application 7–21 Jan 2027 15:00 | CONFIRMED; in prod |
| 19 | Oulu | Business Analytics | Business | MSc (Econ & BA) | 120 / 2 | 12 000 (Business Analytics option) | …0816 / …1405 | Joint Application 7–21 Jan 2027 15:00 | CONFIRMED; in prod; page shared by 3 options (…0948, …10834 are the other two) |
| 20 | Turku | Global Innovation Management | Business | MSc (Econ & BA) | 120 / 2 | 10 000; intake Aug 2027 stated | …0913 / …1548 | Joint Application 7–21 Jan 2027 15:00 | CONFIRMED |

URLs: rows 1–20 are exactly the owner's research pointers (all live-verified 2026-11-17).

### Decisions and caveats
- **Turku ICT (#10):** education code `koulutus_751301` and level codes are master-only, BUT the same record's
  `metadata.tutkintonimike` lists Bachelor of Science (Technology) AND Master of Science (Technology), its only target is in
  the national Joint Application (bachelor-entry route), duration 5 y, and the university page states 3+2 years. Classify by
  degree titles + round type for reviewed records, never by name text. Backup if the owner prefers: Oulu Software
  Engineering and Information Systems bachelor-entry, search hit …10833 / impl …32386 (record and URL NOT yet fetched).
- **Helsinki Science (#4):** one program; tracks stay attributes of the parent degree. If a strictly IT bachelor is wanted,
  swap for the Oulu backup above or Aalto Digital Systems and Design (…2814, Codex fixture 2026-11-16).
- **Aalto rounds:** one program, several application targets (round A, round B, competition route) -> store as separate
  intake rows under one program; never as separate programs.
- **Multilingual:** Helsinki master pages list Finnish/English/Swedish for the programme overall; the selected
  implementations (…39900, …39878, …39769) are English-only in Studyinfo and sit in the English-programme rounds.
- **Aalto master records** carry 2 providers at education level; the implementation has 1 -> use the reviewed
  implementation-provider filter (already in Codex `StudyinfoParser.parse(..., reviewedProviderOid)`).
- **Excluded routes for "main deadline":** transfer, open-university, MAOL/competition, double-degree, HEBUT routes.
  Keep them as separate routes; do not present them as the main deadline.
- **Tuition year:** no page labels the fee with an academic year; LUT (#6, #16, #17) and Turku (#10, #20) state a 2027
  intake on the same page. Store fee with `academic_year=null` unless the reviewer accepts the intake statement.

### Counts
IT 12 (incl. Helsinki Science) / business-economics 8. Universities: Aalto 5, Helsinki 3, LUT 3, Oulu 3, Tampere 2,
Jyväskylä 2, Turku 2. Tuition (bachelor phase / master): 15 000–17 000: 7; 12 000–13 000: 7; 10 000: 6.
Existing: 4 in the inventory snapshot (#13, #14, #18, #19); 16 new. Neon itself was NOT queried.

### What each source can maintain
| Fact | Studyinfo API | University connector |
|---|---|---|
| Identity, status (published/archived), provider | yes | identity check of page `<h1>` + degree |
| Degree titles, level codes, education code | yes (titles more reliable than level codes) | degree line on page |
| Teaching language, duration, campus city | yes (implementation) | confirmation |
| ECTS | yes (`opintojenLaajuusNumero`) | yes |
| Application rounds, dates, target status | yes (authoritative; university pages can be stale, see #3) | no |
| Tuition, waivers, early-bird, application fee | no | yes (all 7 sites show it in a fixed info block) |
| Requirements, tests, documents | no | yes (admissions pages; store short structured values + link, not prose) |
| Official program URL health | n/a | link monitor (200 + `<h1>` + degree) |

### Implementation work required (after approval)
1. `curated-finland.json` -> these 20 (keep keys/OIDs above); `CuratedSources` 10+10; host/publisher table for 7 sites.
2. Parser: reviewed bachelor-entry classification by `tutkintonimike` + round (Turku); multi-target intakes (Aalto, Helsinki).
3. Universities: add Tampere, LUT, Jyväskylä, Turku to `universities.json` + `studyinfo.json` (HEI ids above; institution
   OIDs from Studyinfo: Tampere 1.2.246.562.10.79559059674, LUT …42777795784, Jyväskylä …91392558028, Turku …78305677532).
4. Site profiles for tuition/ECTS/duration extraction: Helsinki, Tampere, LUT, Jyväskylä, Turku (Aalto/Oulu exist).
   Oulu #19: scope the tuition value to the "Business Analytics" option of the shared page.
5. Replace whole-section prose storage in `UniversityAdmissionsAdapter` with structured values + link.
6. Fixtures from the raw evidence folder; tests for 10+10, Turku rule, multi-route intakes, shared Oulu page.
7. Then: link monitor, cycle discovery, preview -> approved fingerprint -> apply (needs explicit Neon approval).

## Phase A: backend selection and stable identities (2026-11-17) — DONE, committed on `feat/finland-curated-20`
Branch is cut from `feat/official-program-links` (PR #39) and includes the preserved Codex curated framework.
- `curated-finland.json`: the approved 20 (keys, education/implementation/provider OIDs, headings, URLs).
  `CuratedSources`: 10+10 enforced, distinct OIDs/keys, host+publisher table for 7 universities (`OWNERS`).
- Turku rule: `StudyinfoParser.parse(..., reviewedTitleCodes)` -> BACHELOR only if `metadata.tutkintonimike` equals the
  reviewed codes AND every target of the implementation requires `pohjakoulutusvaatimuskouta_123` (general university
  eligibility) and no completed-degree requirement. Otherwise level = null (review). Only `turku-ict` carries it.
- Routes: reviewed parse emits one deadline per route; same-round targets with different dates (Helsinki admission
  groups) get "<distinguishing name segment> · <round>"; labels > 100 chars keep start + end; collisions -> issue.
- Helsinki study option: `studyOption` on the candidate, re-validated against the implementation's own
  `opetus.lisatiedot` ("study track" + option) on every run; stored in `catalog_membership.study_option`.
- Duplicate prevention: `StudyinfoImporter.existingProgram(education, implementation)` (implementation OID fallback
  for the 4 programs already in prod; conflict if both point to different programs).
- Universities: Tampere, LUT, Jyväskylä, Turku added to `universities.json` (HEI registry + homepage read 2026-11-17)
  and `studyinfo.json` with `maxPrograms: 0` (curated-only; general discovery skips them).
- V13 (never applied anywhere; local dev at V8, prod at V12) extended: `study_option`, unique implementation per
  release, fact `applicant_category`/`academic_year`, `program_link_status`, `program_link_history`. Fixed a Codex
  bug: V13's fact-type CHECK dropped `LIFECYCLE` (archive detection would have failed in production).
- `UniversityProgramAdapter` v2: one extractor + per-site `Profile` (7 sites), values <= 400 chars, fee audience
  NON_EU_EEA only when stated, never an academic year; Oulu shared page -> option-specific fee.
- API (additive): `universityInformation.qualificationPathway`, `.studyOption`; facts carry `applicantCategory`,
  `academicYear`. `FactValidator` URL owner check covers all 7 universities.
- Fixtures: `src/test/resources/curated20/` (Studyinfo JSON + whitelisted fact-box HTML, ~206 KB pages).
- Tests: 173 unit tests pass; targeted ITs (CuratedCatalogIT, UniversityFactsPipelineIT, ProgramApiIT,
  PublicCatalogueIT, StudyinfoConnectorIT) 57/57 pass.

### Next (Phase B)
1. `UniversityAdmissionsAdapter`: replace whole-section prose (<= 6000 chars) with short structured values + link.
2. Admissions/financial supporting sources for Helsinki, Tampere, LUT, Jyväskylä, Turku (read pages first).

## Phase B: university admissions/financial sources (2026-11-17) — DONE, committed
- `UniversityAdmissionsAdapter`: no copied passages. Every value <= 400 chars, else only the official link is kept.
  Language tests are structured "TEST SCORE" lines (e.g. LUT: "IELTS Academic 6.5; New TOEFL iBT 4.5; TOEFL iBT 92;
  PTE Academic 68"); a page without such lines yields only its link. Heading check normalises apostrophes.
- `curated-supporting-sources.json`: +16 pages, all read 2026-11-17 (raw: research folder `supporting/`), each taken
  from a link on the program page or its site navigation: Tampere (2 admissions, 2 language, financial; final URLs
  after redirect), Turku (admissions, 2 language, financial), Jyväskylä (bachelor admissions, financial),
  LUT (bachelor/master admissions, master language, bachelor + master financial).
- Separate references per fact family: deadlines/routes = Studyinfo record; tuition = program page fact box (+
  financial page link); requirements/language = admissions/language pages; scholarships = financial pages.
- Gap: Helsinki exposes no admissions/financial links in server-rendered HTML (navigation is client-side); no URL was
  guessed. Helsinki programs carry program-page facts (level, language, credits, fee) + Studyinfo routes only.
- Gap: Tampere language pages and Turku language pages render their test lists client-side -> link only, no tests.
- Tests: `UniversityAdmissionsAdapterTest` (4) + evidence tests (8) pass.

## Phase C: synchronization, cycles, link monitoring (2026-11-17) — DONE, committed
- Refresh: `curated-apply` registers per program a Studyinfo monitor (identity, language, duration, campus, routes),
  a university-program monitor (fact box) and per-university guidance monitors; the existing `run` (daily) /
  `sync` (Mondays) jobs re-read them. Failures never touch published facts. Expired routes are `HISTORICAL`.
- New admission cycle: a published implementation that is not the reviewed one is NEVER adopted; the Studyinfo
  monitor fails with "NEW ADMISSION CYCLE candidates ... <oid "name">" -> FAILING -> workflow alert issue. New rounds
  inside the reviewed implementation are new route rows automatically (older rows kept).
- `OfficialLinkMonitor` (+ `--app.curated.command=links`, weekly inside `sync`; `links` mode for manual runs):
  VERIFIED / REDIRECTED_VERIFIED (move followed, history row) / REPLACEMENT_VERIFIED (exactly one sitemap page passes the
  identity check) / STALE->PENDING_REVIEW / BROKEN (404/410 on 2nd consecutive check) / SOURCE_UNAVAILABLE (403,
  robots, timeout, 5xx: never broken, never discontinued). No URL is constructed; old URLs in `program_link_history`.
  Exit code != 0 when any link needs a person -> existing alert job opens/comments the issue.
- `--app.curated.command=status` (and `status` workflow mode): read-only operator report (selection counts, last
  successful check, programs not read in 10 days, facts older than 45 days, failing sources, link states and review
  list, pending review items, upcoming deadlines).
- API: `universityInformation.linkChecks[]` (type, status, lastCheckedAt, lastVerifiedAt).
- Not verified yet: scheduled operation. `SYNC_ENABLED` is unset and `HEALTHCHECK_PING_URL` absent (owner decisions);
  nothing runs unattended until the owner enables it and a scheduled run succeeds.
- Tests: OfficialLinkMonitorIT 6, evidence 9 (incl. new-cycle), CuratedCatalogIT 5, ProgramApiIT 24, PublicCatalogueIT 6.

## Phase D: website integration (2026-11-17) — DONE, committed in both repos (branch `feat/finland-curated-20`)
- Backend public detail adds `qualificationPathway`, `studyOption`, `programLinkStatus` (no fact values; allow-list test
  extended deliberately). README "Curated catalogue fields" documents every additive field.
- Frontend (built on the preserved Codex WIP of PR #50): types for pathway/study option/link checks/fee audience+year;
  `degreeLabelKey` (combined 3+2 = "Bachelor's entry · combined bachelor's + master's pathway"; JYU ISE&AI = "Bachelor's
  degree"); study-option line; fee shows "Applies to non-EU/EEA applicants" + "Academic year not stated by the
  university"; a STALE/BROKEN/PENDING_REVIEW program page is never the primary button (notice + "last known page" link);
  SOURCE_UNAVAILABLE keeps the button. EN + RU strings.
- Data freshness on the site: the proxy fetches with `no-store` and forwards the backend's Cache-Control/ETag, so a
  published backend change is visible on the next page load; nothing is hardcoded in the frontend.
- Filters: degree level, university, subject, language, country use the existing explorer. Tuition-range filtering
  only matches dated CURRENT fees (`tuition` rows); the curated fees are undated university facts, so they are shown
  on the program page but deliberately NOT used by the tuition filter (rule: never present an undated fee as current).
- Tests: frontend 156 unit tests, typecheck, lint, format pass; backend PublicCatalogueIT 6, CuratedCatalogIT 5,
  UniversityApiIT 12 pass.
