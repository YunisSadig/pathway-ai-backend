# Merge sequence for backend PRs #1-#5

Verified 2026-11-14: all five PRs are open, `MERGEABLE`/`CLEAN`, CI (`build`) passes on each, and the ancestry is a strict
chain (each branch contains its parent; 1 + 3 + 1 + 4 + 2 commits, no duplicated commits, no divergence).

```
main <- #1 feat/backend-integration-readiness            (1 commit)
main <- #2 feat/program-first-backend                    (3 commits; contains #1's commit)
#2   <- #3 feat/historical-facts-pgadmin-guide           (1 commit)
#3   <- #4 feat/eu-asia-catalogue-and-refresh            (4 commits)
#4   <- #5 feat/studyinfo-program-connector              (2 commits)
```

Always use **merge commits** (never squash or rebase-merge): squashing a parent rewrites the commits the next PR is built on
and produces conflicts and duplicated history. Do not delete a branch until every child PR has been retargeted.

| Step | Action | Check afterwards |
|---|---|---|
| 1 | Merge #1 into `main` | PR #2 diff shows only its 3 commits |
| 2 | Merge #2 into `main` | PR #3 is still based on `feat/program-first-backend` |
| 3 | `gh pr edit 3 --base main`, then merge #3 | #4 diff shows only its 4 commits |
| 4 | `gh pr edit 4 --base main`, then merge #4 | #5 diff shows only its 2 commits |
| 5 | `gh pr edit 5 --base main`, then merge #5 | `main` equals the tip of `feat/studyinfo-program-connector` |

After **each** merge, on `main`: `git pull`; `JAVA_HOME=<JDK 21> ./mvnw clean verify` (expect all green; 323+ tests after step 5);
stop the running API first (Windows jar lock). After step 5 also confirm Flyway reports V1-V7 applied and nothing pending
(`FlywayMigrationIT`), and that CI is green on `main`.

Database: the development database already contains V1-V7 and the imported data. Merging changes no applied migration
(V7 is new and additive), so the first start of `main` against it finds nothing pending. Nothing is deleted by any step.
Pending review items 40-43 are data, not code: they are unaffected by merging and are decided separately.
