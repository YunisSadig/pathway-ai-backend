# Known Issues

| # | Issue | Impact | Status |
|---|---|---|---|
| 1 | Machine `JAVA_HOME` points at JDK 25; builds must override it to JDK 21 locally | Local builds use the wrong JDK unless `JAVA_HOME` is set per session | Open (environment; CI is correct) |
| 2 | Mockito prints "self-attaching ... inline-mock-maker" warning on JDK 21+ | Log noise only; will need a `-javaagent` argument on a future JDK | Open (not yet configured) |
| 3 | Maven warns `401` fetching `flyway-community-db-support` metadata from `maven.pkg.github.com` | Harmless warning; build succeeds | Open (upstream Flyway repository config) |
| 4 | No production data is seeded by migrations | The API is empty until `IMPORT_REFERENCE_DATA=true` loads the bundled, source-cited batch (8 programs) | By design (see `docs/reference-data.md`) |
| 5 | ~~Programs, requirements and tuition had no API~~ | Programs, tuition, intakes and requirements are exposed read-only via `/api/v1/programs` | Resolved in Milestone 3A |
| 6 | No authentication/authorization | Endpoints are public read-only | Planned (Spring Security intentionally excluded for now) |
| 7 | Actuator exposes only `health` and `info` | No metrics endpoint | By design |
| 8 | Frontend/backend contract mismatches (frontend untouched by design): frontend calls `/api/universities` (backend: `/api/v1/universities`); sends `countryCodes` (comma list) and `search` (backend: single `country`, `q`); expects `id`, `sourceUrl`, `checkedAt`, `verification` on `University` and lower-case `level` (backend: no `id`/provenance, upper-case `degreeLevel`); calls `/universities/{id}/programs` (backend embeds programs in the detail response) | Frontend cannot consume the API until its client/types are adapted | Open (frontend change required; see README contract) |
| 9 | Dev database is empty, so non-empty responses were verified only via synthetic Testcontainers fixtures, not the live server | Live smoke tests show `content: []` | By design until real source-cited data exists |
| 10 | No CD pipeline or Dockerfile; deployment needs a hosting choice and cost approval | Backend is not hosted | Open (see `docs/deployment.md`) |
| 11 | `country` accepts one ISO code only | Frontend multi-country filter cannot pass through as-is | Open (extend API or filter client-side) |
| 12 | The data batch is small (8 programs, 3 institutions, FI and NL) and was read from public pages on 2026-11-11 | Not a representative catalogue; dates and fees will go stale | Open: needs a refresh process and more sources |
| 13 | Tuition academic year `2027/2028` is inferred from the admission round shown beside the fee; applicant category `INTERNATIONAL` means non-EU/EEA for these records | Possible mislabel if a university changes its wording | Documented in `docs/reference-data.md` |
| 14 | Several official pages block automated clients (HTTP 403) or give no direct application form URL (only the national portal) | Those programs have no `officialApplicationUrl`, or only the university admissions page | Open: verify manually, then add |
| 15 | The importer never deletes and there is no admin UI/API | A wrong record needs a corrected file (updates in place) or manual SQL | Planned (needs ADMIN authorization, a later milestone) |
| 16 | Field filter supports two taxonomy levels; name search is a sequential scan | Fine at the measured 20k rows (about 5.5 ms); deeper trees or 10^5+ programs need a recursive query or `pg_trgm` | Open (measured, see README performance) |
| 17 | `idx_program_level_field` (V1) shows 0 scans because the normalised `academic_field_id` replaced `field_of_study` for filtering | Small write cost | Open: candidate for a later migration to drop it |
| 18 | Offset pagination: page 500 of 20k rows takes about 87 ms | Acceptable for UI paging; no keyset pagination | By design for now |
| 19 | No scholarships in the backend; no student profiles, auth or AI matching | Out of scope for this milestone | Planned |
| 20 | `university.city` is nullable (Aalto has none) although the README documented a string | Clients must accept `null` | README fixed; the frontend already tolerates it |
