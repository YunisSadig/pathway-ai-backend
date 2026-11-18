# Pathway AI — Milestone 1 progress and delivery report

**Date:** 16 November 2026  
**Status:** Tested official-link vertical slice delivered in two open pull requests. Full program enrichment remains incomplete. Production approval has not been given.

## 1. Delivered work

The existing Spring Boot and Next.js architecture was extended. Studyinfo remains the program fact provider, with its identifiers and refresh pipeline preserved.

### Backend

- New Studyinfo imports leave `officialProgramUrl` unknown instead of storing a Studyinfo destination in that field.
- API mappings suppress legacy Studyinfo URLs in the university-program slot without modifying the underlying entity or source identifiers.
- A reviewed association registry connects five specific Studyinfo education OIDs to university-owned program pages.
- An operator-only verifier/backfill checks the exact education OID, university, stored program title and degree level before fetching the destination.
- Verification requires successful HTML retrieval, the reviewed final URL, one matching program heading and the stated qualification. Homepage redirects and mismatched content fail verification.
- The fetch uses existing robots, host/IP, redirect, pacing, response-size and access-control safeguards.
- The backfill defaults to a read-only preview. A separately enabled commit operation uses existing source, source-check, provenance and history tables. It does not create programs or change stable identifiers.
- Previously stored university links that fail a recheck are retained and reported for review. Failed checks do not advance successful verification evidence.
- Tests verify that an existing university URL survives repeat Studyinfo import and a reviewed refresh.

### Frontend

- Public and authenticated program detail pages share a consistent destination component.
- **View official university program** is the primary action.
- Admissions information remains a separate informational action.
- Apply remains a separate action dependent on the backend's current/upcoming-cycle safeguards.
- Verified Studyinfo submission links explain the national portal's role.
- Missing university destinations are explicit. Invalid URLs and URLs containing credentials are rejected by the component.
- External links announce that they open a new tab and use `noopener noreferrer`.
- English and Russian copy, existing theme tokens and responsive button styles are retained.
- The identity verification caption no longer implies that every linked page or dated fact was checked at the same time.

Existing university navigation, program sections, authentication, accounts, profiles, saved programs and public/private API field boundaries were retained. University detail pages were not redesigned in this slice.

## 2. Factual coverage

The hosted public catalogue was inventoried through a read-only request. The local development database contains a smaller subset.

| Institution | Hosted programs inventoried | Local programs inspected | University program URLs verified live | Outstanding URL associations |
|---|---:|---:|---:|---:|
| Aalto University | 2 | 2 | 2 | 0 |
| University of Helsinki | 30 | 4 | 0 | 30 |
| University of Oulu | 24 | 3 | 3 | 21 |
| **Total** | **56** | **9** | **5** | **51** |

All 56 hosted records are master's programs. No bachelor's programs are currently represented in this inventory. The importer deliberately skips combined bachelor/master records; expanding bachelor's coverage requires a separate identity/discovery review rather than relabelling those offerings.

“Inventoried” means public identities and available metadata were audited. It does not mean that all 56 official university pages or protected admissions/tuition facts were verified.

### Five live-verified destinations

| Institution | Program | Official university destination |
|---|---|---|
| Aalto | Civil Engineering | [Program page](https://www.aalto.fi/en/study-options/civil-engineering-master-of-science-technology) |
| Aalto | Energy Production, Economics and Sustainability | [Program page](https://www.aalto.fi/en/study-options/energy-production-economics-and-sustainability-master-of-science-technology) |
| Oulu | Architecture | [Program page](https://www.oulu.fi/en/apply/masters-architecture) |
| Oulu | Biochemistry | [Program page](https://www.oulu.fi/en/apply/masters-biochemistry) |
| Oulu | Biomedical Engineering | [Program page](https://www.oulu.fi/en/apply/masters-biomedical-engineering) |

These five passed the real guarded fetcher in a dry run on 16 November 2026. They are verified candidates ready for a separately approved backfill; they have not been written to production.

The remaining 51 associations were not completed. This does not establish that their official pages are unavailable. Earlier connector work recorded HTTP 403 responses from Helsinki; this slice did not bypass restrictions or claim fresh Helsinki verification.

### Observed field coverage and enrichment limits

| Field | Hosted public inventory |
|---|---|
| Existing program URL | 56/56 populated, all pointing to Studyinfo before these changes |
| Official admissions URL | 0/56 populated |
| Duration | 56/56 populated |
| Campus city | 56/56 populated |
| Academic field | 55/56 populated |
| Study mode | 0/56 populated |

**New production facts populated: zero.** No new tuition, scholarship, eligibility, language-test, entrance-test, document, selection, deadline or application-route facts were imported. Protected hosted fields were not exposed or inferred from the public inventory. Qualification, credits, descriptions, structure and specializations were not added as new first-class detail fields.

The complete list of 56 stable identifiers and outstanding associations is in [the inventory](data/milestone-1-inventory.md).

## 3. Sources, reuse and synchronization

- The existing public Pathway catalogue supplied the inventory; existing local Studyinfo education OIDs supplied the five reviewed associations.
- The five university pages above supplied live destination and identity evidence.
- Studyinfo continues to maintain its existing supported facts. No new scheduled synchronization was enabled.
- The new link verifier is operator-run and is not integrated into unattended refresh. Repeat checks require an explicit operation; failures and pending review appear in its report.
- Successful committed checks can record source URL, organization, check time, parser version and field provenance. Informational program URLs do not establish an admissions cycle or tuition period.
- The Studyinfo data-licence question remains unresolved. Existing public reuse limits were not widened.
- No university descriptions, images, fee tables or admissions prose were copied into published records. Robots access was not treated as a content reuse licence.

See [the detailed audit and operating notes](data/milestone-1-official-links.md) and [the public reuse policy](data/public-catalogue-reuse.md).

## 4. Verification results

| Verification | Result |
|---|---|
| Backend full clean verification | 165 unit tests and 280 integration tests passed; no failures or skips |
| Final strengthened import/refresh regression | Targeted verification passed |
| Backend executable-bit, refresh and backup safety checks | Passed |
| Frontend formatting, ESLint and TypeScript | Passed |
| Frontend unit tests | 144 passed |
| Frontend production build | Passed |
| Desktop/mobile browser suite | 403 passed; 7 existing device-specific skips |
| Visual inspection | Desktop light and mobile dark screenshots reviewed |
| Hosted backend CI on final PR head | Passed |
| Hosted frontend CI on final PR head | Passed |

The browser suite covers public/authenticated pages, navigation, external links, accessibility, account flows, profiles and saved-program compatibility. The Windows browser runner required its own local Next server to be stopped during teardown; it subsequently exited successfully. Hosted Linux CI also passed.

## 5. Pull requests and commits

| Repository | Review | Final feature-branch commit | State at completion |
|---|---|---|---|
| Backend | [PR #39](https://github.com/YunisSadig/pathway-ai-backend/pull/39) | `376ac9727095f3444a4a490d1fd97e09ae09e580` | Open, CI green |
| Frontend | [PR #50](https://github.com/YunisSadig/pathway-ai-frontend/pull/50) | `8af28b3657295bc73f6b5ec10b73d5c0a357a7f0` | Open, CI green |

Both repositories use `feat/official-program-links`. Implementation, documentation and the additional synchronization regression are organized into focused commits.

**Production commits deployed by this work: none.** The existing Vercel integration built a PR preview. No PR was merged, no direct push to main occurred, and no production deployment, migration or data backfill was run.

## 6. Baseline preservation, migration impact and rollback

Preserved production baseline references:

- Backend: `c298a36ff40df21dcdc5e990e0896438e0215d54`
- Frontend: `0be0f0194cc73e2c1c38124fe6240163a32f95f4`

Both local main branches still pointed to these commits at implementation completion. Both feature working trees were clean before this standalone report was written.

There is **no schema migration**, new dependency, hosting service, credential change or security-check removal. Merging the code does not execute the backfill; its flags default off.

Code rollback uses reviewed reverts of the focused feature commits. No schema rollback is required. Before any future production data backfill, obtain separate explicit approval, take a verified encrypted backup, rehearse an isolated restore, and export scoped before-images of affected URLs, provenance and source relationships. Test the operation twice on that restored database before proposing production execution.

If an approved backfill later requires reversal, restore only affected URL/provenance values in a reviewed transaction and abort where later edits exist. Preserve unrelated accounts, profiles, saves and subsequent evidence. Do not overwrite a live database with an old whole-database backup. The detailed backup and scoped rollback procedure is included in [the operating notes](data/milestone-1-official-links.md).

## 7. Remaining work and approval boundary

1. Complete 51 university-page associations, including ambiguous or similarly named offerings with distinct source IDs.
2. Establish bachelor's coverage without confusing combined degrees with standalone bachelor's offerings.
3. Verify separate admissions-information pages and current/upcoming application submission routes.
4. Add permitted maintained extraction for tuition, applicant categories, scholarships, eligibility, tests, documents, selection and instructions.
5. Propose suitable sourced fields for qualification, credits, program structure and specializations; prepare a reversible migration plan before any schema proposal.
6. Define ongoing link-check behavior and student-facing pending-review/freshness details. Scheduling remains subject to explicit approval.
7. Review both PRs. Any merge, production deployment, migration or production backfill requires the owner's explicit approval.

**Delivery assessment:** the official-link vertical slice is implemented and tested. The full comprehensive program-information milestone is not yet complete. This standalone report is saved locally for review; writing it does not authorize or trigger any production action.
