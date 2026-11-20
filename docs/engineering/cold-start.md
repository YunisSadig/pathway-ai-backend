# Cold start: what was measured and what is not known

**Measured (2026-11-15):** after an idle period the first API request through the site took longer than 120 s (my probe gave up at 120 s), the next took 6 s, then 0.5 s. Earlier measurements: 84.6 s directly and 108.7 s through the site. Warm API calls answer in 0.1 to 1 s; static pages in about 0.26 s.

**Cause:** the free hosting tier stops the API when idle and starts the Java application again on the next request (JVM start, Spring context, database pool, migrations check). The 120 s figure is therefore the tail of that start, not a bug in a request.

**Not verified:** the hosting provider's startup logs were not available to me (no access token in this environment), so the split of the start time between JVM, Spring and the database connection is unknown. To see it, open the service logs on the host right after a cold start and compare the first log line time with "Started PathwayAiBackendApplication in N seconds".

**Mitigations in the code:** the browser waits up to 140 s with clear messages (`apiTimeoutMs`), the sign-in and data screens show a slow-start notice, and since 2026-11-15 the session re-reads itself in the background (3, 8, 20, 45 and 75 s, and when the browser goes online), so a refresh during a cold start no longer leaves the user looking logged out.

An UptimeRobot check on `/actuator/info` every 5 minutes (deployment-status.md row 8) keeps the backend awake in practice.

**Optional, needs the owner's approval (not used):** `docs/engineering/keep-warm.workflow.yml.example`, a ping every 10 minutes between 09:00 and 23:00 Asia/Baku. Activate: copy it to `.github/workflows/keep-warm.yml`, commit, then set the repository variable `KEEP_WARM` to `true`. Stop: set `KEEP_WARM` to anything else. Check: Actions, "Keep API warm", each run's summary shows the status and time of the ping. It lowers the number of cold starts in those hours; it cannot guarantee none.
