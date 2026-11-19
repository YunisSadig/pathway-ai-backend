# Google sign-in ("Continue with Google")

**Status:** IMPLEMENTED and TESTED with a stubbed Google (backend `GoogleAuthIT`, `GoogleIdentityValidationTest`; frontend `google.spec.ts`, proxy unit tests). VERIFIED IN PRODUCTION on 2026-11-19: the owner signed in with a real Google account several times (first and repeat sign-in both worked). Until both variables are set the button is not shown and the endpoints answer with a redirect to `/login?error=google_unavailable`.

## How it works

- The button is a plain link to `/api/v1/account/oauth/google/start` (through the site's own proxy, so the session cookie stays on the site's origin). The backend creates `state`, `nonce` and a PKCE verifier, keeps them in the browser's server-side session, and redirects to Google (`scope=openid email profile`, `code_challenge_method=S256`).
- Google redirects the browser back to `/api/v1/account/oauth/google/callback`. The pending login is consumed on first use (a replay finds nothing) and expires after 10 minutes. The code is exchanged server to server with the client secret and the verifier; the identity token is checked for signature (Google's published keys), issuer, audience (our client id), expiry and nonce.
- The proxy follows redirects only for these two routes, and only passes a `Location` that points at this site or at Google's authorization endpoint.
- Accounts: a returning person is found by Google's stable `sub` (table `external_identity`, unique per provider and subject). A new person gets one account with no password (`password_hash` is NULL, never a made-up value) and a verified email. A Google address that already belongs to a password account is NEVER merged by email: the verified Google identity is parked in the visitor's own server-side session (10 minutes, single use) and the visitor lands on `/login/link-google`, where entering the existing Pathway AI password ONCE links that Google subject to THAT account (`POST /api/v1/account/oauth/google/confirm-link`; five wrong passwords or ten minutes end it, throttled per address and per account, a fresh session id opens on success). Afterwards Google signs in directly and the password keeps working. A subject that belongs to another account, or an account that already has a Google identity, is refused and nothing changes. Linking from Settings, Account & Security remains. An address Google did not verify creates nothing and never starts linking. Any unexpected failure in the callback is a redirect to `/login?error=google_failed` (the exception is logged without the code or tokens), never a raw 500.
- **Before production use:** migration V12 must be applied to Neon (workflow "Source refresh", mode `migrate`), see mistakes 46.
- Google-only accounts have no password login, no "change password", no reset link; deleting such an account from the site is not offered yet (it asks for a password) and is a known limitation.
- Google's profile picture is not used; an uploaded Pathway AI avatar is never touched.

## Exact values (nothing here is a secret)

- **Production authorized redirect URI:** `https://pathway-ai-frontend-roan.vercel.app/api/v1/account/oauth/google/callback` (the site origin from the repository variable `STAGING_SITE_URL`, plus the path). If a custom domain is added later, register that domain's URI as well and set `SITE_URL` to it.
- **Local development redirect URI:** `http://localhost:3100/api/v1/account/oauth/google/callback` (the port the e2e server uses; use `http://localhost:3000/...` if you run `npm run dev` on 3000, and set `SITE_URL` to the same origin when running the API locally).
- **Authorized JavaScript origins:** none are needed (the authorization code flow runs on the server).
- **Variables on the API host (Render):** `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` (secret), and `SITE_URL` which MUST equal the site origin above exactly (it builds the redirect URI and the pages the person lands on). No frontend variable is needed.

## Google Cloud steps (owner)

1. console.cloud.google.com: create or choose a project.
2. APIs & Services, OAuth consent screen: User type External; app name Pathway AI; support email; scopes `openid`, `email`, `profile` only; keep it in Testing and add your disposable Google account as a test user (publish later when ready).
3. Credentials, Create credentials, OAuth client ID, type Web application. Add the production and local redirect URIs above exactly. Copy the client ID and secret.
4. Render, the API service, Environment: add `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`; check `SITE_URL`; redeploy. Never paste the secret anywhere else.
5. Check `GET <site>/api/v1/account/config`: `"googleLoginAvailable": true`.

## Real end-to-end procedure (disposable Google account)

1. Open `/signup`, press Continue with Google, choose the disposable account: expect to land on `/dashboard?welcome=1` signed in, with the Google name in the menu.
2. Complete the first profile steps (onboarding), then log out.
3. Log in again with Google: same account, same profile, same saved items; no second account (check `external_identity` has one row for the subject).
4. Create a password account with a different address, then in Settings, Account & Security, connect the same Google account: expect "already used by another account".
5. Try Google with an address that has a password account: expect the "already exists" message and no session.
6. Cancel at Google's screen: expect "Google sign-in was cancelled" on `/login`.
7. Delete the disposable accounts afterwards (a Google-only account must be removed by the owner in the database, `delete from user_account where email_normalized = '...'`; the identity goes with it).
