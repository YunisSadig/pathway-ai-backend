# Google sign-in: status, design and the manual steps

**Status (2026-11-15): NOT implemented.** There is no OAuth code in the backend or frontend, and no Google client exists. Email and password sign-in, sessions, password reset and email verification are implemented and tested; they are not delivered by email until SMTP is configured (`docs/deployment-sync.md`, known-issues).

## Why it was not built in the same sprint

A Google flow cannot be verified without a real Google client and a registered callback, so shipping code for it would produce an unverified security boundary. It should be built once, with the credentials in hand, and tested against Google with a test account.

## Manual steps (owner)

1. Google Cloud Console, create or choose a project, then OAuth consent screen (External, testing mode, add test users).
2. Credentials, OAuth client ID, type Web application. Authorized redirect URI: `https://<site>/api/v1/account/oauth/google/callback` (the same-origin proxy path; add `http://localhost:3100/...` for local tests).
3. Store the client ID and secret only as hosted secrets (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` on the API host). Never commit them.

## Design to implement (authorization code flow with PKCE)

- `GET /api/v1/account/oauth/google/start?next=/path`: create random `state` and a PKCE verifier, keep both server-side bound to a short-lived HttpOnly cookie, redirect to Google with `state`, `code_challenge` (S256) and `scope=openid email profile`. `next` goes through the existing `safeNext` check (relative paths only).
- Callback: reject a missing or mismatched `state`; exchange the code at Google's token endpoint over TLS with the verifier; validate the ID token (signature against Google's JWKS, `iss` accounts.google.com, `aud` our client ID, `exp`, `nonce`) before trusting any claim.
- Account rules: look up by Google `sub` first. Link to an existing password account ONLY when Google states `email_verified=true` AND the existing account's email is verified or the user proves the password; otherwise refuse and tell the user to log in with their password first. A new Google user creates an account with `email_verified_at` set (Google verified it) and no password.
- Open the same server-side session as password login. Tokens never appear in URLs, responses or logs.
- Tests: valid callback (mocked token endpoint and JWKS), bad state, bad audience/issuer/expiry, unverified email linking refused, `next` open-redirect refused, existing profile and saved items untouched.
