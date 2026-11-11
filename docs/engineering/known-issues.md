# Known Issues

| # | Issue | Impact | Status |
|---|---|---|---|
| 1 | Machine `JAVA_HOME` points at JDK 25; builds must override it to JDK 21 locally | Local builds use the wrong JDK unless `JAVA_HOME` is set per session | Open (environment; CI is correct) |
| 2 | Mockito prints "self-attaching ... inline-mock-maker" warning on JDK 21+ | Log noise only; will need a `-javaagent` argument on a future JDK | Open (not yet configured) |
| 3 | Maven warns `401` fetching `flyway-community-db-support` metadata from `maven.pkg.github.com` | Harmless warning; build succeeds | Open (upstream Flyway repository config) |
| 4 | No production data is seeded | `GET /api/v1/universities` returns an empty page until real, source-cited data is imported | By design |
| 5 | Programs, requirements and tuition have entities/tables but no API/services yet | Only universities are exposed | Planned |
| 6 | No authentication/authorization | Endpoints are public read-only | Planned (Spring Security intentionally excluded for now) |
| 7 | Actuator exposes only `health` and `info` | No metrics endpoint | By design |
| 8 | Frontend/backend contract mismatches (frontend untouched by design): frontend calls `/api/universities` (backend: `/api/v1/universities`); sends `countryCodes` (comma list) and `search` (backend: single `country`, `q`); expects `id`, `sourceUrl`, `checkedAt`, `verification` on `University` and lower-case `level` (backend: no `id`/provenance, upper-case `degreeLevel`); calls `/universities/{id}/programs` (backend embeds programs in the detail response) | Frontend cannot consume the API until its client/types are adapted | Open (frontend change required; see README contract) |
| 9 | Dev database is empty, so non-empty responses were verified only via synthetic Testcontainers fixtures, not the live server | Live smoke tests show `content: []` | By design until real source-cited data exists |
| 10 | No CD pipeline or Dockerfile; deployment needs a hosting choice and cost approval | Backend is not hosted | Open (see `docs/deployment.md`) |
| 11 | `country` accepts one ISO code only | Frontend multi-country filter cannot pass through as-is | Open (extend API or filter client-side) |
