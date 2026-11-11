# Pathway AI: MVP product specification

**Status:** permanent product-direction decision. It describes where the product is going, not what exists today. Today's actual state is in the README. Features are delivered incrementally, each with tests and a pull request, and without rewriting working code.

This file is kept in sync between `pathway-ai-frontend` and `pathway-ai-backend` (same content in `docs/product/MVP_SPEC.md` in each). The two repositories stay separate.

## 1. Product definition

Pathway AI is a **global, program-first** university and scholarship discovery platform.

- It supports **all academic disciplines** and **both bachelor's and master's degrees** (other levels must remain possible).
- It is **not** limited to Computer Science or IT, to European universities, or to master's programs. No code, copy, schema or test may assume otherwise.
- Students can browse publicly without registering.

## 2. Core concepts

1. **University**: an institution that offers many degree programs.
2. **Degree program**: a first-class entity, not a child label of a university. It has its own requirements, tuition information, teaching languages, duration, deadlines, intake periods and official application link.
3. **Scholarship**: an independent entity with structured eligibility, related to programs, universities, countries or academic fields.
4. **Source**: every changing institutional fact points to a verifiable official source with a timestamp and a verification status.
5. **Student profile** (registered users only): qualifications, grades, language proficiency, desired field and degree, budget, destinations and other preferences.

## 3. Data integrity rules (apply everywhere)

- Changing facts (tuition, requirements, deadlines, intakes, scholarship terms) must carry: official source URL, `checkedAt` timestamp, verification status, and what the fact applies to (degree level, intake, citizenship category, academic year).
- Unknown stays unknown. Never display a placeholder, `0`, or an estimate as fact.
- Never fabricate records. Test fixtures are obviously synthetic and live only in tests.
- **No acceptance probabilities**, scores that imply them, or rankings invented by Pathway AI.
- **Application buttons** appear only when a verified official university or scholarship application URL exists. They link to that page and nowhere else.

## 4. Discovery and recommendation

- Public browsing: search and filter programs (field, degree level, country, language, tuition, intake) and universities; scholarships by eligibility.
- **Personalised discovery recommends programs first, then shows the universities that offer them.**
- Eligibility evaluation for a student and a program returns three explicit groups, never a single opaque score:
  - **Verified matches**: requirement is verified and the profile satisfies it.
  - **Unmet requirements**: requirement is verified and the profile does not satisfy it.
  - **Missing information**: the requirement or the profile value is unknown, so no conclusion is drawn.
- Every recommendation explains which facts it used and links their sources.

## 5. Accounts and authorization

- Roles: **USER** and **ADMIN**, minimal.
- Registered USER: maintain a profile, save programs, compare options, receive recommendations.
- ADMIN: edit institutional content. **All authorization is enforced on the server.** The frontend may hide controls, but hiding is never the protection.
- Public browsing never requires an account.

## 6. Future AI assistance

Source-grounded retrieval and explainable matching only. The assistant may state an institutional requirement only if it is retrieved from a stored, sourced record, and must cite it. It must say "unknown" instead of guessing.

## 7. Extensibility requirements

The schema and API must allow new countries, fields of study, academic years, intakes and degree levels **without a redesign** (reference data and versioned, year-scoped facts rather than hard-coded enums or per-country tables). The frontend must therefore not hard-code fields, countries served or degree levels beyond static navigation helpers.

## 8. Current state versus target (as of this spec)

| Area                                         | Today                                                                                                                       | Target                                           |
| -------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| Universities                                 | Backend lists universities; frontend `/universities` is live (search, country, sort, paging)                                | Programs-first discovery with sources            |
| Programs                                     | **Backend (Milestone 3A):** `GET /api/v1/programs` and `/{identifier}`, filters, taxonomy, official URLs, verification; frontend not yet. `DIPLOMA`/`CERTIFICATE` kept | First-class, searchable, sourced entities        |
| Requirements/tuition                         | **Backend (3A):** tuition, intakes/deadlines and requirements are exposed on program detail, each with its source          | Exposed with source and verification status      |
| Scholarships                                 | None (frontend shows an honest empty state)                                                                                 | Independent entities with structured eligibility |
| Accounts / roles                             | None                                                                                                                        | USER and ADMIN, server-authorised                |
| Profiles, saves, comparison, recommendations | None                                                                                                                        | Registered features, explainable                 |
| Data                                         | 8 source-cited programs (3 institutions, FI and NL, 8 fields) importable via the idempotent importer                        | Sourced records across fields and countries      |

## 9. Suggested delivery order (each step = tests + PR, preserving what works)

1. Backend: expose programs as first-class API resources (list, filter, detail) with source and verification fields; frontend: programs discovery alongside universities.
2. Backend: requirements, tuition, deadlines, intakes with sources; frontend: program detail with verified/unknown presentation and official application links.
3. Backend: scholarships and relationships; frontend: scholarship discovery.
4. Backend: minimal authentication, USER/ADMIN roles, admin content editing; frontend: sign-in.
5. Student profile, saved programs, comparison.
6. Eligibility evaluation (verified / unmet / missing), then recommendations.
7. Source-grounded AI assistance.

Order may change, but no step may weaken a rule in section 3.

## 10. Working agreements

- Frontend and backend remain separate repositories. A change needed in the other repository is written up as a task, not made from here.
- The API contract is owned by the backend and pinned by its tests. Frontend types mirror it exactly.
- Existing scope rules in each repository's `CLAUDE.md` (for example the frontend's four-page limit and "no auth yet") stay in force until the pull request that implements the relevant step changes them deliberately, together with their tests and docs.
- Do not add features beyond the step being delivered.
