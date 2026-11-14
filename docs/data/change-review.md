# Change review

The exception-review queue: where a detected change waits for a human before it can affect a published fact.

## What lands in the queue

| Item | Why it is queued |
| ---- | ---------------- |
| `TUITION`, `DEADLINE`, `REQUIREMENT` | high impact: never published without a decision |
| any fact that **differs** from the published value | a badly parsed value must not replace a verified one |
| `SOURCE_CONTENT` | a page whose text changed and for which no adapter extracts facts (admissions, catalogue and program pages); one open item per source, a newer change supersedes it |

Never queued: values that failed validation (they are recorded in the check's `error` column and the report), duplicates of something already staged, and changes to home pages (audit trail only).

Published without review: only an **empty** duration or teaching-language field filled from structured data (`AUTO_APPLIED`, actor `system:auto`, still recorded in `staged_change`).

## Working the queue (command line only)

```
java -jar target/pathway-ai-backend-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none <args>

--app.refresh.command=review-list
--app.refresh.command=review-approve --app.refresh.id=N --app.refresh.actor=your-name
--app.refresh.command=review-reject  --app.refresh.id=N --app.refresh.actor=your-name --app.refresh.reason="why"
```

`review-list` shows, for each item: id, fact type, program, risk, the old (published) value, the new value, why it is queued and the source URL. Open the source page and compare before deciding.

## What each decision does

- **Approve**
  - `SOURCE_CONTENT`: marks it reviewed (you re-read the page). Nothing is published; if the page changed a fact, update `programs.json` and import.
  - New `TUITION` / `DEADLINE`: inserts a **new** row for that period, citing the source, and links the source to the program. Older rows are untouched.
  - `DURATION_MONTHS` / `LANGUAGES` that differ: replaces the value (the reviewer has decided).
  - `REQUIREMENT`: refused ("cannot be published automatically"); enter it through the reference-data import after reading the page, then reject the item.
  - Before applying, the change is **re-compared** with the database. If the fact was published meanwhile (for example by the import) the item is marked `SUPERSEDED` and nothing is written. If a different dated value now exists for the same period, approval is refused: dated facts are never overwritten. Correct it through the import with a reasoned retraction, then reject the item.
- **Reject**: records the actor, time and optional reason; the same change is never queued again (the dedupe key remembers it) unless the page's value changes again.
- A decided item cannot be decided twice.

Every decision is stored (`status`, `decided_at`, `decided_by`, `reason`), so the history of what was proposed, by which source and parser version, and what a person decided stays queryable:

```sql
select c.id, c.fact_type, c.status, c.decided_by, c.decided_at, s.url
from staged_change c join source s on s.id = c.source_id order by c.id;
```

## Example (real, on the project database)

During testing the stored hash of `https://www.tuwien.at/en/studies/admission` was altered (the page itself was not changed) to simulate "the page changed since the last check". The next `run` reported `changed=1, pendingReview=1`; `review-list` showed item #2 with the old and new hashes and the source; it was rejected with the reason "controlled test: stored hash altered by the operator ...". Item #1, a university home-page news change raised before home pages were switched to audit-only, was rejected as "homepage news content, not a fact change".

## Guidance for reviewers

- Compare against the **official page**, not against another aggregator.
- Do not infer a year, a cycle or an applicant category the page does not state. Leave it absent.
- If the page is ambiguous, reject with a reason and leave the published value as it is: unknown is better than a guess.
