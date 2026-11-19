# Preview: LUT Digital Business name correction (#608) — NOT APPLIED

Status: preview only. Nothing was written to any database; production untouched. Prepared 2026-11-19.

## Defect
The published name is the implementation name, which LUT prefixes with its admission route:
`Rolling Admission, Digital Business , Bachelor and Master of Science in Economics and Business Administration (3 yrs + 2 yrs)`
(source: the recorded Studyinfo implementation `1.2.246.562.17.00000000000000033130`, `src/test/resources/curated20/lut-digital-business.json`;
the route also appears as "International Rolling Admission" in the application-target name).

## Code fix
Already in `main` since #44 (`StudyinfoParser.withoutRoutePrefix`): leading comma segments the education's own name does not contain
are dropped, a stray space before a comma is removed. #51 adds the missing regression test. The parser only changes what a
FUTURE read proposes.

## Proposed correction (for review)
| Field | Published now | Proposed |
|---|---|---|
| name | `Rolling Admission, Digital Business , Bachelor and Master of Science in Economics and Business Administration (3 yrs + 2 yrs)` | `Digital Business, Bachelor and Master of Science in Economics and Business Administration (3 yrs + 2 yrs)` |

Caveat (unverified): the proposed value assumes the education (koulutus) record's own name contains "Digital Business". The koulutus
record is not in the repo fixtures, so the exact output is confirmed only by step 1 below.

## Path (names are REVIEW_REQUIRED, `SyncPolicy`)
1. Read-only check, no writes: `--app.refresh.command=studyinfo-import --app.refresh.dry-run=true --app.refresh.university=lut`
   against the real service; confirm the printed LUT Digital Business name equals the proposed value.
2. Real run (owner decision, later): the changed name is queued as a `staged_change` (PENDING_REVIEW, old value kept published).
3. Reviewer compares with the official Studyinfo/LUT page: `review-list`, then `review-approve --app.refresh.id=N --app.refresh.actor=...`.
   The old name is archived in `program_fact_history`. The program slug is not derived from the name; check it does not change
   before approving (a changed slug would break public identifiers).
4. Verify via the public API `GET /api/v1/programs/{id}` and the curated status report.

Open check before step 3: that the slug/identifier stays stable when the name changes.
