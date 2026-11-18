# Site performance: findings, changes and hosting limits (2026-11-18)

Request path: Browser → Vercel (Next.js page + `/api/v1/*` proxy route) → Render Free (Spring Boot, Frankfurt) → Neon (PostgreSQL).

## Measured baseline (production, signed-out visitor, before frontend PR #52)

| Measure | Warm backend |
|---|---|
| `/` usable (heading) | 0.7 s |
| `/universities` first university card | **2.5 s** |
| "Log in" visible | 1.2–1.5 s |
| Request order on `/universities` | `/account/me` (1.2 s) → only then `/public/programs` ×2 (2.3 s) |
| Public API caching | `private, max-age=300`: never shared, every visitor hit Render |

Cold backend (recorded 2026-11-15): first answer after 17 idle minutes **84.6 s** direct, 108.7 s through the site. The
`/universities` skeleton waited for `/account/me` first, so a visitor saw skeletons for up to ~2 minutes.

Root causes (code, not only hosting):
1. `useCatalogueMode` returned `"loading"` until `/account/me` answered; the catalogue rendered nothing until then.
2. An account-service error flipped visitors into the protected catalogue (401) instead of the public one.
3. The proxy rewrote every non-account `Cache-Control` to `private`, so the CDN could not serve public data.
4. The header showed an empty placeholder until `/account/me`, and "Log in" for a signed-in user whose backend was asleep.

## Changes (frontend PR #52, merged `f9cbb89`)

- Public catalogue renders immediately; it switches to the full catalogue only when the session confirms access.
- `/api/v1/public/*` is fetched **without** the session cookie; successful GETs are marked
  `public, max-age=60, s-maxage=300, stale-while-revalidate=86400`. Vercel's CDN serves them (`X-Vercel-Cache: HIT`) and
  refreshes them in the background, so a sleeping backend does not delay public pages once an answer was cached.
  Account (`no-store`) and protected catalogue answers (`no-store`/`private`) are unchanged — verified live.
- Header: Log in / Sign up at once for visitors; "Restoring your account…" for a browser that had a session (localStorage
  holds only `"1"`, no token or identity; cleared on a confirmed 401 or logout, never on a service error).

## After (production, warm backend, repeated runs)

| Measure | Before | After |
|---|---|---|
| `/universities` first contentful paint | (probe bug) | 0.46–0.54 s |
| `/universities` first university card | 2.5 s | **0.86 s** |
| "Log in" visible | 1.2–1.5 s | 0.6 s |
| Public data source | Render (MISS) | Vercel CDN (HIT, ~0.7 s) |

Cold-backend "after" measurement: see the release log below (taken after ≥16 idle minutes).

## What free hosting can and cannot guarantee

- **Render Free** stops the service after 15 idle minutes; the next request waits for container start + JVM + Spring
  (~85 s measured). No code change removes that for **signed-in** pages: `/account/me`, profiles, saves and the protected
  catalogue must reach the backend. The UI now keeps working and says "Restoring your account…" meanwhile.
- **Public pages** no longer depend on it once the CDN has an answer: served stale for up to a day while one background
  request revalidates. The very first visitor after a CDN eviction or a new public URL (new filter combination) still waits.
- **Neon Free** suspends compute after 5 idle minutes; resume is ~1–5 s, small next to Render.
- No artificial keep-alive traffic is used (owner requirement). The existing inactive keep-warm proposal stays inactive.

## Options (not implemented; owner decision, costs not incurred)

1. Stay on Render Free (current): public pages fast via CDN; signed-in first request after idle ~85 s.
2. Always-on backend: Render Starter (paid) keeps the instance running — removes cold starts entirely.
3. Faster JVM start on the same plan: Spring Boot CDS / AOT cache (training run at image build). Needs a database at
   build time or a training profile without schema validation; estimated tens of percent of the Spring part only.
   Container start and Render's spin-up are not affected.
4. Other free hosts with sleeping instances have the same class of limitation; moving providers is not proposed.
