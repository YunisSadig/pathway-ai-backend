# Poland: gap against a Finnish programme page (state after phase 2c, 2026-11-21)

Every cell was checked against the university's own pages read on 2026-11-21 (robots.txt respected, no 403 bypassed) and, for the
POL-on columns, the official POL-on / RAD-on record. A Finnish programme page carries: programme page and admissions page links,
degree title, study mode, duration, campus, tuition by applicant category, application period, eligibility / selection / entrance
test, documents, language requirement, scholarships, and a link health status (`PROGRAM_PAGE`, `ADMISSIONS_PAGE`).

Legend. **live**: published (phase 2a/2b or POL-on). **2c**: added in phase 2c. **2d**: added 2026-11-23 (SGH sub-pages). **found**: the official page states it but it is not
level-specific, is a link only, or sits in a layout that cannot be read unambiguously (reason given); not extracted. **n/p**: the
university's pages do not publish it. **unsupp.**: source unsupported (never bypassed).

| Programme | Admissions page | Own programme page | Title / mode / duration / campus | Tuition | Application period | Eligibility, selection, tests, documents | Language | Scholarships | Link health |
|---|---|---|---|---|---|---|---|---|---|
| AGH Computer Science (B) | **2c** `/en/studies/recruitment` | n/p: offer list | live: 7 semesters; mode found as tab heading only | live: EUR 3000/semester, 2026/27 | live: undated window; **2c** "no admission now, recruitment 2027/2028 starts June 2027" (bachelor deadlines page, 2027-2028) | **2c** 300-point minimum rule and documents (bachelor pages only); subject choice per field found, heading context unclear | **2c** language-document rule (bachelor rules page) | found: generic AGH page, not level-specific | `PROGRAM_PAGE` n/a; **2c** `ADMISSIONS_PAGE` |
| TUL Computer Science (B), Information Technology (B) | **2c** `/en/enrollment/enroll` | n/p: offer list | POL-on | live: PLN 9000/semester, 2026/27 | found: schedule tables for both cycles mixed on one page (second block carries a 2025/2026 note), not safely parseable | **2c** bachelor entrance exam: English at advanced level, minimum 60% (English-taught first-cycle); documents page found, not read | **2c** B2 rule and accepted certificates (page applies to all English programmes) | found: free-places contest and NAWA programmes, page does not state a degree level | **2c** `ADMISSIONS_PAGE` |
| TUL Computer Science and IT (M) | **2c** same | n/p | POL-on | live: PLN 9000/semester, 2026/27 | found: as above | n/p (first-cycle exam text is not stated for master's) | **2c** as above | found: as above | **2c** `ADMISSIONS_PAGE` |
| PUT Artificial Intelligence (B), (M) | **2c** `/en/admission-for-international` | n/p: 404 / PDF | POL-on | live: verbatim, 2026/27 | n/p | n/p | n/p | found: "free of charge / scholarship" page, not read | **2c** `ADMISSIONS_PAGE` |
| UW Machine Learning (M) | **2c** `rekrutacja.uw.edu.pl/en/` | live (IRK page) | live: Magister (MSc), full-time, Ochota Campus | n/p on page: fees only in a PDF (below) | live: exam and rounds 2026 (ended) | live: eligibility, 45% minimum, written exam | live: B2 rules | n/p | live `PROGRAM_PAGE`; **2c** `ADMISSIONS_PAGE` |
| UW Data Science and Business Analytics (M); Finance, Intl Investment and Accounting (B) | **2c** same | live (WNE pages) | n/p | n/p: PDF (below) | n/p: only an older (2025) IRK unit page is linked | n/p | n/p | n/p | live `PROGRAM_PAGE`; **2c** `ADMISSIONS_PAGE` |
| SGH International Business (M) | **2c** graduate admission page for international applicants | live | live: full-time, 2 years (4 semesters) | live: sentence "2400 EUR ... 2450 EUR", no year | Not stated (no academic year is printed with the dates, so no period is stored) | **2d** documents list (required-documents page, "graduate studies"); selection basis and places not stated for the English programme (the thresholds and entrance-exam pages are for Polish-taught programmes) | **2d** accepted English certificates and the B2 minimum (page states it for first-, second- and long-cycle) | found: generic link | live `PROGRAM_PAGE`; **2c** `ADMISSIONS_PAGE` |
| UEK International Business (B), Global Finance and Accounting (B), Financial Analytics (M) | unsupp. | unsupp. | POL-on | unsupp. | unsupp. | unsupp. | unsupp. | unsupp. | unsupp. |
| PUEB Master in International Business (M) | **2c** masters-level page | n/p: landing page | POL-on | live: PLN 7350/semester, no year | found: calendar months only, no year | **2c** documents list (masters-level page) | **2c** accepted English certificates (B2) | n/p: the page's scholarship sentence concerns first-year undergraduates | **2c** `ADMISSIONS_PAGE` |

## UW fees (PDF: queued for review, nothing published)
The official PDF (`rekrutacja.uw.edu.pl/files/pdf/tuition_fees_2026-2027_05.10.2026.pdf`, 3 pages, Last-Modified 2026-10-05) lays fees out in merged cells. Since
2026-11-23 `--app.poland.command=uw-fees-pdf-preview|uw-fees-pdf-stage` reads it with Apache PDFBox (pinned 3.0.8; see architecture-decisions.md) and
reconstructs each row by position. **A PDF fee is never applied automatically**: `stage` writes only `PENDING_REVIEW` items (source = the PDF URL, reason = page
number + exact row text + PDF SHA-256) and a person approves or rejects each with `--app.refresh.command=review-approve|review-reject`. There is no monitor for
the PDF. A row is staged only if the year (page header), the cycle column (1st = bachelor, 2nd = master, must match the programme level), the per-year
header and a printed non-EU/EFTA sub-column header all agree and exactly one amount sits in that sub-column; otherwise the preview prints "Not stated" and the
reason. Result on 2026-11-23: Machine Learning (M) is staged; Data Science and Business Analytics (M) and Finance, International Investment and Accounting (B)
print one centred amount with no applicant-category header, so their category is Not stated and nothing is staged. EU/EFTA "free of charge" is not stored.
The fees page link stays stored as `financial_url`.

## Not importable yet (6 candidates, identity unconfirmed)
WUT x4 and SGH x2: the POL-on name is Polish and a person has not confirmed the mapping. No facts are read for them. PW has no imported programme, so
it has no admissions link.

## How requirement facts are tied to a level
Each requirement is a verbatim section of one reviewed page (`extras` in `curated-poland.json`), stored with that page as its source. It is stored
only if the page also contains a level guard (for example "Fields of Bachelor (first-cycle) studies" on AGH's bachelor rules page), the section
fits its size bound, and a year only if the page prints one. A page that does not name a level is used only where it states a rule for all
programmes in English (TUL language page); scholarship pages that do not name a degree level are not stored.

## Link-status monitoring
Finland checks `PROGRAM_PAGE`/`ADMISSIONS_PAGE` weekly (`--app.curated.command=links`). Poland does the same in the same command
(`PolishLinkMonitor`, same `program_link_status` table): a stored programme page is VERIFIED only if it still has exactly one h1 naming
the programme; a stored admissions page only if it still carries the reviewed title; 403/robots/timeouts are SOURCE_UNAVAILABLE; 404/410
twice in a row is BROKEN; a redirect off the university domain waits for review. Polish links are never replaced automatically (no URL
construction). Fee and requirement pages are covered by their `pl-university-page` monitors.
