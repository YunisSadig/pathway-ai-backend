# Milestone 1: official university destinations

Review baseline (2026-11-16): backend `c298a36ff40df21dcdc5e990e0896438e0215d54`,
frontend `0be0f0194cc73e2c1c38124fe6240163a32f95f4`. Both feature branches start at these commits.
No production deployment, migration, backfill, scheduled synchronization or change to reuse permissions is authorized.

## Audit and scope

The existing Studyinfo importer, field provenance, fact history, review pipeline and API boundaries are retained.
The public production inventory was read without authentication or writes on 2026-11-16:
<https://pathway-ai-frontend-roan.vercel.app/api/v1/public/programs?size=100>.
It contains **56 masters, zero bachelors**: Aalto 2, Helsinki 30, Oulu 24.
The local development database contains nine of those programs (2/4/3 respectively).
[All 56 stable identifiers and outstanding associations](milestone-1-inventory.md) are recorded separately.
This is a bounded official-link vertical slice, not completion of every enrichment phase.

| Field / feature | Existing implementation and observed coverage | Work still needed |
|---|---|---|
| Program title, degree, university, stable OIDs | Imported and maintained by Studyinfo; all 56 masters | Bachelor's discovery review: combined bachelor/master records are deliberately skipped today; never relabel them as bachelor-only |
| Duration, teaching language, campus | Studyinfo parser and refresh pipeline; all 56 have duration and campus | Campus name and precise location need an official university connector |
| Academic field | 55/56 public records populated | Review unmapped source code rather than guess |
| Study mode | Existing model/DTO/UI; 0/56 populated | University structured data or permitted site-specific extraction |
| Official program URL | 56/56 legacy values point to Studyinfo | Five university candidates verified live in this slice; 51 not completed |
| Admissions URL | Existing field; 0/56 public records populated | Verify informational admissions pages separately |
| Application route | Existing cycle/status safeguards | No new submission route verified; no Apply link added |
| Tuition, intake/deadline, requirements | Existing dated tables, sources, history, API and UI | Protected fields; not returned by the public inventory, so their hosted population was not audited or inferred |
| Scholarships/waivers | Honest empty frontend state; existing Aalto tuition adapter has no active reviewed fee mapping | Dedicated extraction with applicant category, scope and explicit period; do not assume INTERNATIONAL means everyone outside Finland |
| Qualification, credits, description, structure, specializations | No suitable first-class program-detail fields populated by this work | Future schema/API proposal and permitted official connectors; no copied prose or invented facts |
| IELTS/TOEFL, SAT, prior education, documents, selection, instructions | Requirement model can represent some dated facts, but no maintained university admissions connector | Distinct bachelor/master adapters and explicit source cycles; unknown stays unknown |
| Verification / sync timestamps | Existing record verification, source checks, field provenance, synchronization DTO | University link checks use their own source_check and provenance; they do not change the program identity verification date |

## Verified candidates (read-only live preview)

The real PageFetcher succeeded for all five on 2026-11-16, respecting robots.txt, host/IP validation,
redirect-hop validation, pacing, response caps and access restrictions. The preview wrote nothing.
Exact education OID + institution + stored title + degree level are checked before a request.
The final URL must equal the reviewed URL, with one exact h1 and the stated qualification present.
A redirect to a homepage, changed heading, HTTP error or blocked request is not verification.

| University | Program | University page |
|---|---|---|
| Aalto | Civil Engineering | <https://www.aalto.fi/en/study-options/civil-engineering-master-of-science-technology> |
| Aalto | Energy Production, Economics and Sustainability | <https://www.aalto.fi/en/study-options/energy-production-economics-and-sustainability-master-of-science-technology> |
| Oulu | Architecture | <https://www.oulu.fi/en/apply/masters-architecture> |
| Oulu | Biochemistry | <https://www.oulu.fi/en/apply/masters-biochemistry> |
| Oulu | Biomedical Engineering | <https://www.oulu.fi/en/apply/masters-biomedical-engineering> |

The reviewed education OIDs are in `OfficialProgramPages.REVIEWED`; no URL is generated from a title.
Helsinki is left unknown: earlier connector work recorded HTTP 403; this slice does not bypass it or claim new verification.
Remaining Oulu associations, including similarly named offerings with distinct IDs, need individual review.

**Actual new production fields populated: zero.** Five officialProgramUrl values are ready for reviewed backfill.
New verified tuition/admissions/eligibility facts: zero. Bachelor's verified URLs: zero. Master's verified candidates: five.

## Repeatable operator preview and proposed backfill

`OfficialLinkCli` is disabled by default and is not called by `sync` or any workflow. No new scheduled job is enabled.
Use a reviewed build, JDK 21, a deliberately selected database and the existing credentials; never print credentials.
Run as a non-web application. Explicitly disable all startup importers and Flyway for this operation.

```powershell
java -jar target/pathway-ai-backend-0.0.1-SNAPSHOT.jar `
  --spring.main.web-application-type=none --spring.flyway.enabled=false `
  --app.reference-data.import-on-startup=false --app.catalogue.import-on-startup=false `
  --app.refresh.scheduler.enabled=false `
  --app.refresh.extra-approved-hosts=opintopolku.fi,www.aalto.fi,www.oulu.fi `
  --app.official-links.enabled=true
```

The `OFFICIAL_LINK_REPORT` line reports every tracked row, including all missing associations.
Default is **dry run, no writes**. A production commit run requires separate explicit owner approval;
only then add `--app.official-links.commit=true`. Keep the preview and commit reports with the change record.

On commit, existing rows are selected by unique Studyinfo education OID, never recreated. Slugs and external IDs do not change.
Only a null/legacy Studyinfo destination can be replaced. Unmapped legacy destinations become null and are archived;
Studyinfo source records and IDs stay intact. An existing university URL that no longer validates is retained and
reported PENDING_REVIEW, not replaced or erased. Such reports require operator follow-up; they are not silently approved.
Repeated successful checks add check evidence but no duplicate program or replacement history.
Each program's publication is transactional and checks for a concurrently changed URL before writing.
Future Studyinfo imports leave officialProgramUrl unknown; refresh never overwrites an existing university URL.
The API suppresses legacy Studyinfo destinations even before an approved data backfill is run.

This connector verifies links only. It has no new unattended refresh integration. Re-run the preview/approved operation
to check links; source_check and field provenance identify successful checks and parser version. Pending-review and
failure reasons are in the operator report. A failed recheck does not update successful provenance. The source's
retrievedOn must not be interpreted as a verified current tuition/admissions cycle.

## Reuse and API boundaries

Studyinfo remains the fact provider, with its IDs, source publisher and existing refresh intact.
Its licence question remains unanswered; see [public tier policy](public-catalogue-reuse.md).
Only links and our verification evidence are added here, with no university prose, images, fees or requirements copied.
Robots permission is not treated as a content reuse licence. No claims of permission for bulk reuse are added.
The existing public field allow-list remains unchanged; private tuition, requirements, sources and application links remain protected.
The frontend uses a primary informational university link, secondary admissions information and a separately eligible Apply link.
When an application URL uses Studyinfo, its role as the national submission portal is explained.

## Migration impact and reversible rollout plan

**No schema migration, new dependency, credential, hosting service or authorization change.**
Merging code alone does not execute a backfill. Both CLI flags default off.

Before any separately approved production data change:

1. Take a verified encrypted backup using the existing backup procedure. Restore into an isolated database and verify counts.
2. Export a scoped before-image of the affected degree_program IDs/URLs/updated_at, program_field_provenance,
   program_source links and related source rows. Keep the dry-run report and exact approved commit/config version.
3. Test the commit operation twice on that restored database. Compare all external IDs, program counts, accounts,
   profiles and saved-program relationships; only proposed link/evidence changes may differ.
4. Review the complete hosted preview, obtain explicit approval, then run the bounded commit operation once.
5. Verify all stable identifiers and existing auth/saved-program behavior, and retain reports and backup.

Code rollback: revert the focused feature commits through a reviewed PR; the baseline SHAs above remain the known-good references.
Do not force-push or reset production main. Code rollback requires no schema change and does not delete source evidence.
If an approved backfill later needs reversal, restore only the changed URL columns/provenance from the scoped before-image
in one reviewed transaction, and only where current values still equal that backfill's values. Abort on later edits.
Archive the reversal reason. Leave unrelated rows and later evidence intact; never restore a whole production database
over new accounts/profiles/saves. `program_fact_history` also retains the original destination, but the scoped backup is
required to restore exact provenance. Test that reversal on the isolated restore before authorizing it in production.

## Validation and delivery

Targeted backend verification passed: URL identity/content validation and PostgreSQL backfill/import/refresh tests.
Final local `mvnw -B -ntp clean verify` passed: **165 unit tests + 280 integration tests, no failures/skips**.
The executable-bit, refresh safety-guard and backup safety-guard shell checks passed.
Frontend formatting, ESLint, TypeScript, 144 unit tests and production build passed; browser run completed with
**403 passed, 7 existing device-specific skips**. It required stopping its own Windows Next server during teardown,
after which Playwright exited 0. Hosted Linux PR CI is required separately.
Frontend review: <https://github.com/YunisSadig/pathway-ai-frontend/pull/50>.
No deployed commit is claimed. Code and documentation are separate focused commits, each revertible.

Unfinished: 51 URL associations, all bachelor's coverage, admissions/application routes, tuition/scholarships/requirements
enrichment, new structure/credits/qualification fields, ongoing link-check scheduling, and any approved production backfill.
