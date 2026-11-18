# Checkpoint 2026-11-18 (resume here)

## Live now (verified)
- Finland 20 in production: 20 programs (10 bachelor's-entry / 10 master's), 7 universities; 37/37 official links VERIFIED.
- Frontend `7cc95f0` (Vercel) and backend `c1a3a08` (Render) are deployed; "Verify deployment" passed on both merge commits.
- Production Neon is at **V13**. V14 is NOT applied; no production data operation has run for PR #44 (see "Next").

## Merged since the Finland release (all CI green, code only)
| PR | What |
|---|---|
| frontend #52 `f9cbb89` | public catalogue never waits for the session; `/api/v1/public/*` is CDN-cached (s-maxage 300, stale-while-revalidate 1 day) and fetched without the session cookie; header shows Log in at once / "Restoring your account…" for a returning browser |
| backend #44 `c1a3a08` | Studyinfo structured facts for the curated offerings (annual fee with audience + stated autumn-intake year, degree titles, ECTS, teaching mode, scholarship range), field code 050 + reviewed field for Tampere, route-prefix-free names, **automatic adoption of a verified new-cycle implementation**, migration **V14**, `review-list/approve/reject` workflow modes, `docs/engineering/performance.md`, `docs/engineering/europe-expansion-plan.md` |
| frontend #53 `7cc95f0` | one fact per source (university page leads), no empty study-track row |

Tests: backend `clean verify` 185 unit + 297 integration; frontend 158 unit + Playwright 409 (7 known skips); live smoke 14/14.

## Performance results (production, signed-out; details in `docs/engineering/performance.md`)
- Warm `/universities` first card 2.5 s → 0.86 s; "Log in" 1.2–1.5 s → 0.6 s; public data now Vercel HIT.
- Cold backend (≥17 idle min): first card 4.3 s (before: up to ~2 min); Render itself still wakes in ~103 s (free-tier limit, signed-in pages still wait).
- Open: Spring CDS/AOT start-up (modest, Spring part only), keep-warm or paid always-on host = owner decisions. Slow-page follow-up from the owner's report is addressed for public pages only.

## Next (each needs the owner's explicit go-ahead; nothing here has run)
1. Production rollout of #44's data: Curated catalogue rehearsal (read-only on Neon; proves V14 + re-import on a restored copy) → `migrate` (V14) → `curated-preview` (new fingerprint) → `curated-apply` with that fingerprint → `review-list` and `review-approve` the LUT Digital Business rename. Expected: 0 new programs, 20 preserved, 12 dated fees (2027/2028), ~80 new facts, no deletions (local scratch re-import matched this).
2. Owner actions for unattended sync: create a free Healthchecks.io check, add `HEALTHCHECK_PING_URL`, set `SYNC_ENABLED=true` (currently unset: nothing refreshes by itself).
3. Close superseded PRs #39 (backend) and #50 (frontend).
4. Send the Studyinfo reuse/licence email (still unsent).

## Poland / Czech Republic (research only; nothing imported, no code)
- Poland: POL-on courses open data `https://radon.nauka.gov.pl/opendata/polon/courses` — unauthenticated JSON, 11,671 courses, token pagination, per course instances with language (`eng`), form, ECTS, start date, status; stable UUIDs; **no tuition, no admissions pages, no deadlines**; licence/terms of reuse not yet confirmed. Local raw download + analysis: `C:\Users\Lenova\pathway-ai-research\poland-2026-11-18\` (397 English-taught running IT/business bachelor+master courses; candidate shortlist not yet chosen). Old RAD-on course services were retired in favour of this API (RAD-on help notice).
- Czech Republic: no programme-level open API found. MŠMT register `regvssp.msmt.cz/registrvssp/` is a web form (no export seen); `data.msmt.cz` unreachable; Study in Czechia `studyin.gov.cz/study-programmes` (340 pages, filters for language/level/tuition via site AJAX endpoints; robots.txt empty). Terms/licence and a safe machine-readable route still to be established before any selection.
- Next: confirm POL-on terms of use, choose Poland's 20, investigate studyin.gov.cz permitted extraction, then propose both lists for approval.

## Local state
- Working trees on `main`; untracked `docs/MILESTONE_1_REPORT.md` (old Codex report, backed up in `C:\Users\Lenova\pathway-ai-wip-backup\2026-11-17\`).
- Scratch Postgres container `pathway-local-rehearsal` (port 55433) stopped; the dev database `pathway-ai-postgres` (5433) untouched.
