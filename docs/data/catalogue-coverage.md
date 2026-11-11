# Catalogue coverage

State of the database after the EU + Asia catalogue import (2026-11-11). Numbers are from direct queries against PostgreSQL, not estimates. Regenerate any time with the monitoring report (`app.refresh.command=report`, see `automated-refresh.md`).

## Summary

| Measure                                              | Count |
| ---------------------------------------------------- | ----- |
| Universities                                         | 48    |
| of which VERIFIED (registry match + official site read) | 39    |
| of which REGISTRY_ONLY (official site not readable by automated access) | 9 |
| Degree programs                                      | 10    |
| Countries                                            | 30 (27 EU + China, Japan, South Korea) |
| Stable external identifiers stored                   | 180 (SCHAC, Erasmus code, ECHE number, PIC) |
| Universities with an official admissions URL         | 14    |
| Universities with an official programme-catalogue URL | 17   |
| Sources (cited pages)                                | 131   |

## What "verified" means here

- **VERIFIED**: the institution is an active record in the EU HEI registry (matched by its SCHAC identifier) **and** its official home page was fetched (HTTP 200, the institution's name found on the page) on the date shown. The three Asian institutions are not in the registry (it covers EU/Erasmus institutions only), so for them verification is the official site alone.
- **REGISTRY_ONLY**: the registry confirms the institution, but its website could not be read without bypassing an access control (bot-protection challenge, 403, connection failure). **No website URL is stored** for these rows (a database check enforces it): an address nobody could read is not published as fact.
- Verification only moves up. A site that is down next week never un-verifies an institution.

## Coverage by country

`*` = REGISTRY_ONLY. Programs counts are all that exist; most institutions have none yet (see below).

| ISO | Universities (verified)                       | Programs |
| --- | --------------------------------------------- | -------- |
| AT  | University of Vienna, TU Wien                 | 0        |
| BE  | Ghent University, KU Leuven\*                 | 0        |
| BG  | Sofia University\*                            | 0        |
| HR  | University of Zagreb                          | 0        |
| CY  | University of Cyprus\*                        | 0        |
| CZ  | Charles University, Brno University of Technology | 0    |
| DK  | University of Copenhagen, Aarhus University   | 2        |
| EE  | University of Tartu, Tallinn University of Technology | 0 |
| FI  | University of Helsinki, Aalto University      | 7        |
| FR  | Sorbonne University, Université Paris-Saclay  | 0        |
| DE  | Technical University of Munich, Heidelberg University | 0 |
| GR  | National and Kapodistrian University of Athens | 0       |
| HU  | Eötvös Loránd University, Budapest University of Technology and Economics | 0 |
| IE  | Trinity College Dublin, University College Dublin\* | 0  |
| IT  | University of Bologna, University of Padua    | 0        |
| LV  | University of Latvia                          | 0        |
| LT  | Vilnius University, Kaunas University of Technology | 0  |
| LU  | University of Luxembourg\*                    | 0        |
| MT  | University of Malta                           | 0        |
| NL  | University of Amsterdam, Utrecht University   | 1        |
| PL  | University of Łódź, Łódź University of Technology | 0    |
| PT  | University of Porto, University of Lisbon\*   | 0        |
| RO  | Babeș-Bolyai University, University of Bucharest\* | 0   |
| SK  | Slovak University of Technology in Bratislava | 0        |
| SI  | University of Ljubljana                       | 0        |
| ES  | Autonomous University of Barcelona, University of Barcelona\* | 0 |
| SE  | Lund University, Uppsala University\*         | 0        |
| CN  | Peking University                             | 0        |
| JP  | University of Tokyo                           | 0        |
| KR  | Seoul National University                     | 0        |

**All 27 EU member states are represented, but only 24 have a fully VERIFIED institution.** Bulgaria, Cyprus and Luxembourg each had a single target and its site could not be read:

| Institution                   | Why it is REGISTRY_ONLY                                              |
| ----------------------------- | -------------------------------------------------------------------- |
| Sofia University (BG)         | connection failed (timeouts from this network, no response)         |
| University of Cyprus (CY)     | Cloudflare "Attention Required" (HTTP 403)                           |
| University of Luxembourg (LU) | HTTP 202 with a JavaScript challenge, no content                     |
| KU Leuven (BE)                | connection timeouts                                                  |
| University College Dublin (IE) | CloudFront 403                                                      |
| University of Lisbon (PT)     | HTTP 403                                                             |
| University of Barcelona (ES)  | Cloudflare "Just a moment" challenge                                 |
| Uppsala University (SE)       | challenge page / connection failure                                  |
| University of Bucharest (RO)  | JavaScript challenge page ("One moment, please")                     |

The challenges were not solved or bypassed. Government and accreditation portals for BG, CY and LU were tried as alternative official sources and were also blocked or unreachable. These institutions become VERIFIED by re-running the import once their sites can be read (for example from another network, or after the university allows the crawler); `UniversityCatalogueImporter` raises the status and fills the website then, without creating duplicates.

## Programs: what is and is not done

10 programs exist: the original 8 (Helsinki, Aalto, Amsterdam) and 2 new University of Copenhagen master's programs (MSc Computer Science, MSc Economics). Each was read from its official page and carries only what that page states:

- **Computer Science MSc**: two years of full-time study (24 months, full-time).
- **Economics MSc**: "a two-year degree programme of 120 ECTS" (24 months). Study mode not stated.
- Teaching language is **not stated** at programme level on either page (only that elective courses are taught in English), so it is left unknown. Tuition, deadlines and requirements are on other pages and were **not** read, so none is stored.

**The goal of one to three programs per institution was not met.** The institutions' catalogues are dynamic (script-rendered lists, language switchers) with no common structure, and several block automated reading. Entering programs without reading their official pages would mean inventing facts, so the batch stops at what was actually read. Adding programs is a per-institution task: read the catalogue, add records to `programs.json` with their cited sources, run the importer. The monitoring pipeline will then watch each program's pages.

## Other incomplete fields

- `institution_type` is empty for every institution: neither the registry nor the home pages state it in a machine-readable, verifiable way, so it is not inferred from the name.
- 14 of 39 verified institutions have an admissions URL and 17 a programme-catalogue URL. Only URLs that returned HTTP 200 with a title and were different from the home page are stored; the rest are unknown, not absent.
- `city` is stored only where it agrees with the registry (the registry's city field is wrong for Helsinki, so the existing value was kept) or appears on the official page (China, Japan, South Korea).
- `native_name` (40 rows): accepted when it equals the registry's ASCII-folded name with diacritics restored, or when the name appears on the official page. Chinese and Korean names are not set (not found on the pages read).

## API note for the frontend team

No contract changed: `GET /api/v1/universities[/{slug}]` returns the same fields and now serves 48 institutions. Things a client should expect:

- `websiteUrl` is `null` for the 9 REGISTRY_ONLY institutions, and `programs` is empty for most institutions. Both are valid, honest states.
- The new institution columns (`nativeName`, `admissionsUrl`, `programmeCatalogueUrl`, `verificationStatus`, `verifiedOn`) are **not** exposed yet. If the frontend wants to show verification (for example "official site not confirmed"), an additive `verificationStatus` field on the university DTOs is the proposed change; adding a field is non-breaking. Not implemented here because the frontend repository is out of scope.
