# Adding a university: the connector lifecycle

An institution is never published because its name exists in the database. A program becomes visible only when it is verified, its
source states its facts, and a fact-extracting monitor of its own is active and fresh (`DiscoveryPolicy`). The frontend has no catalogue:
it renders whatever the API returns, so a new university needs **no frontend change**.

Do the steps in order; each has a checkable result.

1. **Authoritative provider.** Prefer an official API (Studyinfo is the model). Record the provider, its terms and its contact in `official-api-connectors.md`.
2. **Reuse conditions.** Licence, attribution, persistent copies, commercial use, rate limits, robots.txt. If unknown: ask in writing (`studyinfo-permission-request.md` is the template) and keep the data in protected staging. Public launch stays blocked until answered.
3. **Adapter.** A small class in `com.pathwayai.backend.provider.<provider>` behind the existing fetch boundary (https, approved hosts, public IPs, robots.txt, spacing, size cap, bounded retries). Never bypass a challenge page, CAPTCHA, login or 403: mark the source UNSUPPORTED.
4. **Response schema validation.** Reject a response that does not have the expected shape; a malformed response is a failed check, never an empty result.
5. **Stable external identifiers.** `program_external_id (scheme, value)` is globally unique, so a re-import finds the program it created.
6. **Field extraction with provenance.** One `program_field_provenance` row per synchronized field (source, mechanism, observed_at, verified_on, academic year only when the source states it). Unknown stays null.
7. **Policy per field.** Declare each field in `SyncPolicy`: AUTOMATIC (deterministic official-API facts), ADDITIVE_AUTOMATIC (new dated rounds), REVIEW_REQUIRED (name, level, field, tuition, requirements, changed dated facts).
8. **Tests with recorded fixtures** (`FakeTransport`; never live sites): repeat retrieval (idempotent), change detection, source failure, discontinuation, malformed and delayed responses. Run the new crawler path once against the real service before trusting the fixtures (mistakes 18, 19, 35).
9. **Register the refresh schedule**: a fact-extracting monitor (`ADAPTER` or `JSON_LD`) per program; `CONTENT_HASH` never qualifies.
10. **Add the institution** to `reference-data/universities.json` only when the connector exists (`UniversityCatalogueFileTest` compares the two).
11. **Hosted proof.** Merge, run workflow `bootstrap`/`sync`, then `verify` (all invariants hold, counts as expected) and `freshness`.
12. **API proof.** `GET /api/v1/programs?...` on the hosted API returns the new programs (`verify-deployment` workflow / script).
13. **Frontend proof.** The new university appears in the list and detail pages without a rebuild (cache bound: 300 s).
14. **Regression monitoring.** The watchdog (`freshness`) covers every extractor-maintained program automatically; a failing extractor withholds its programs.

Scale notes: lists are paginated and indexed; the per-host crawl delay (Studyinfo: 30 s) dominates sync time (about 30 s per record), so dozens of universities need the weekly cadence, not the daily one; the 120-minute workflow limit bounds a single run, so very large catalogues need sharded runs (`--app.refresh.university=slug`).
