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
