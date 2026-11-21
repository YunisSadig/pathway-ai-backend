# Poland: gap against a Finnish programme page (2026-11-21)

Every cell was checked against the university's own pages read on 2026-11-21 (robots.txt respected, no 403 bypassed) and, for the
POL-on columns, the official POL-on / RAD-on record. A Finnish programme page carries: programme page and admissions page links,
degree title, study mode, duration, campus, tuition by applicant category, application period, eligibility / selection / entrance
test, language requirement, scholarships, documents, and a link health status (`PROGRAM_PAGE`, `ADMISSIONS_PAGE`).

Legend. **live**: already published before this change. **NEW**: extracted by this change (rehearsal and preview first).
**found**: the official page states it but it is shared by several programmes, a link only, or a layout not safely parseable
(reason given); not extracted. **n/p**: the university's page does not publish it. **unsupp.**: source unsupported (never bypassed).

| Programme | Own programme page | Degree title | Mode | Duration | Campus | Tuition (category, year) | Application period / intake | Eligibility, selection, entrance test | Language requirement | Scholarships | Link health |
|---|---|---|---|---|---|---|---|---|---|---|---|
| AGH Computer Science (B) | n/p: offer list only | n/p | found: tab heading only | **NEW** 7 semesters | n/p | live: EUR 3000/semester, international, 2026/27 | **NEW** "June - September; start October 1", no year printed (UNKNOWN_YEAR) | found: link to "Recruitment rules" (not read) | found: link only | found: link only | n/a (no stored page link) |
| TUL Computer Science (B) | n/p: offer list only | n/p | POL-on full-time (live) | POL-on (live) | n/p | **NEW** PLN 9000/semester, international, 2026/27 | found: `apply.p.lodz.pl` enrolment schedule, not read | found: not read | found: not read | found: fee-waiver page, not read | n/a |
| TUL Information Technology (B) | n/p: offer list only | n/p | POL-on | POL-on | n/p | **NEW** PLN 9000/semester, 2026/27 | as above | as above | as above | as above | n/a |
| TUL Computer Science and IT (M) | n/p: offer list only | n/p | POL-on | POL-on | n/p | **NEW** PLN 9000/semester, 2026/27 | as above | as above | as above | as above | n/a |
| PUT Artificial Intelligence (B) | n/p: page 404 | n/p | POL-on | POL-on | n/p | live: verbatim EUR 1st semester + PLN later semesters, 2026/27 | n/p | n/p (no first-cycle admissions page found) | n/p | found: link "free of charge/scholarship" | n/a |
| PUT Artificial Intelligence (M) | n/p: PDF only | n/p | POL-on | POL-on | n/p | live: verbatim, 2026/27 | n/p | n/p | n/p | found: link only | n/a |
| UW Machine Learning (M) | live (IRK page) | **NEW** Magister (MSc) | **NEW** full-time | POL-on | **NEW** Ochota Campus | n/p on page; fees only in a PDF (merged cells, not parsed) | **NEW** exam 1 July 2026, results 22 July, document rounds 23-31 July 2026 (2026/27, ended) | **NEW** eligibility, 45% minimum, written exam topics | **NEW** English B2 rules | n/p | NEW: `PROGRAM_PAGE` |
| UW Data Science and Business Analytics (M) | live (WNE page) | n/p | n/p | POL-on | n/p | n/p | n/p: only an older (2025) IRK unit page is linked | n/p | n/p | n/p | NEW: `PROGRAM_PAGE` |
| UW Finance, Intl Investment and Accounting (B) | live (WNE page) | n/p | n/p | POL-on | n/p | n/p | n/p: as above | n/p | n/p | n/p | NEW: `PROGRAM_PAGE` |
| SGH International Business (M) | live | n/p | **NEW** full-time | **NEW** 2 years (4 semesters) | n/p | **NEW** sentence "2400 EUR (per semester) or 2450 EUR (instalments)"; page prints no year or category | found: "documents period" named, no dates | found: link to admissions page, not read | n/p | found: generic link | NEW: `PROGRAM_PAGE` |
| UEK International Business (B), Global Finance and Accounting (B), Financial Analytics (M) | unsupp. | unsupp. | POL-on | POL-on | unsupp. | unsupp. | unsupp. | unsupp. | unsupp. | unsupp. | unsupp. |
| PUEB Master in International Business (M) | n/p: masters landing page | n/p | POL-on | POL-on | n/p | live: verbatim PLN 7350/semester, year not stated | found: shared by all masters | found: shared by all masters (85 PLN fee, documents) | found: shared (B2, accepted certificates) | found: shared | n/a |

"POL-on" = already published from POL-on (name, level, field, language, duration in semesters). Shared pages (PUEB masters, TUL enrolment)
describe rules for every programme of the level; storing them on one programme would present a general rule as programme-specific, so
they stay unextracted until a per-programme statement exists.

## Not importable yet (6 candidates, identity unconfirmed)
WUT x4 and SGH x2: the POL-on name is Polish and a person has not confirmed the mapping. No facts are read for them.

## Link-status monitoring
Finland checks `PROGRAM_PAGE`/`ADMISSIONS_PAGE` weekly (`--app.curated.command=links`). Poland now does the same in the same command
(`PolishLinkMonitor`, same `program_link_status` table): a stored programme page is VERIFIED only if it still has exactly one h1 naming
the programme; 403/robots/timeouts are SOURCE_UNAVAILABLE; 404/410 twice in a row is BROKEN; a redirect off the university domain waits for
review. Polish links are never replaced automatically (no URL construction). Fee pages are covered by their `pl-university-page` monitors.
