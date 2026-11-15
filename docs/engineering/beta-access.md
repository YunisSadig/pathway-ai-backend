# Granting private-beta catalogue access to one account

Program details stay behind the catalogue gate (`catalogue_access`, `CATALOGUE_PUBLIC=false`) until the Studyinfo reuse terms are confirmed
in writing (`docs/data/public-catalogue-reuse.md`). This procedure opens the gate for ONE account you own. It does not change any public
permission, never touches a password or session, and never picks an account by guess.

Only the account owner knows which email they registered with. Do not infer it from the git author, a display name or the newest row.

1. Open the Neon SQL editor for the production database (the project's own console; do not paste credentials anywhere else).
2. Find the account by the exact email you signed up with and confirm it is the only match:

   ```sql
   SELECT id, email, role, catalogue_access, email_verified_at, created_at
   FROM user_account
   WHERE email_normalized = lower(btrim('YOUR-EXACT-EMAIL'));
   ```

   Expect exactly one row. Zero rows means you used another address: stop, do not try similar ones. Check that `created_at` matches when you registered.
3. Grant access to that row by `id`, with the email repeated as a second guard:

   ```sql
   UPDATE user_account
   SET catalogue_access = true, updated_at = now()
   WHERE id = 'ID-FROM-STEP-2' AND email_normalized = lower(btrim('YOUR-EXACT-EMAIL'));
   ```

   It must report `UPDATE 1`. Any other count: stop and re-check step 2.
4. The server checks `catalogue_access` in the database on every request, so no restart or re-login is needed. Reload the site: the
   Universities page switches to the full catalogue and `/api/v1/programs` answers 200 for your session only. A second, ordinary account must still
   get 403 there (that is the check that nothing public changed).
5. To revoke: the same `UPDATE` with `catalogue_access = false`.

Never run `UPDATE user_account SET catalogue_access = true` without the `WHERE id = ...` clause, and never set `CATALOGUE_PUBLIC=true`
for this purpose (it opens the catalogue to everyone).
