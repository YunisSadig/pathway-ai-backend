# Public university discovery: what may be shown without an account

Checked 2026-11-15 against the official documentation
<https://wiki.eduuni.fi/spaces/ophPPK/pages/190612447/Koulutustarjonta+ja+Opintopolku.fi+tietomallit+ja+rajapinnat> (section "Opintopolku.fi:n (konfo) ulkoiset rajapinnat").

## What the official page says (and does not say)

- The external konfo endpoints need **no authentication**, but every call must send a caller-id header.
- They are described as suited to "showing published training offerings on your own websites" (*Sopii erityisesti jos halutaan hakea julkaistua koulutustarjontaa näkyviin omille verkkosivuille*). That is a statement of purpose.
- The page states **no licence, no attribution rule, no terms of use, no image or logo rights and no rate limits**. Linked pages (CAS, caller-id, CSRF) concern access, not reuse.

**Conclusion: reuse of the Studyinfo fields is technically open but NOT licensed in writing.** We do not claim permission was obtained. The question to the provider (`studyinfo-permission-request.md`) was asked on 2026-11-19; there is no reply yet.

## Decision: a conservative public tier

`GET /api/v1/public/universities` and `/{slug}` (no session needed; the site's proxy still adds the infrastructure key) expose only:

| Field | Why it is safe to show |
| --- | --- |
| `slug`, `name`, `city`, `countryCode`, `countryName` | identity of an institution (a fact, matched against the official registry) |
| `websiteUrl` | the institution's OFFICIAL website, read by us before it was stored (never an unread URL) |
| `verifiedOn` | our own verification date |
| `trackedPrograms` | a number we compute (how many programs we keep current); not content from the source |

The institution list follows the same listing rules as the protected catalogue (verified, kept current), so an unverified or withheld institution never appears. A test pins the exact field set; adding a field is a deliberate, reviewed change.

**Stays protected (catalogue gate: signed-in account with `catalogue_access`):** program names, degree levels, fields of study, durations, teaching languages, tuition, deadlines, requirements, application links, descriptions, and every per-program source field. **No images or logos are used.**

## Honest limits shown to visitors

The public page says the catalogue covers only the institutions actually synchronized (Finland), that a university's count is the number of programs tracked (not its whole offering), and that nothing else is shown until the reuse terms are confirmed.

## Synchronization status (unchanged by this work)

Since 2026-11-19 scheduled sync is ON (`SYNC_ENABLED=true`) with the Healthchecks.io dead-man's switch configured. The owner enabled it before the permission reply because sync only reads the public API and the catalogue stays private (`cataloguePublic=false`); public display of program details stays blocked until a written answer. The permission email was sent on 2026-11-19 (`official-api-connectors.md`). Stale data is already withheld from discovery after 45 days without a successful read.

## To widen the public tier later

Obtain the provider's written answer (licence and attribution), then add the permitted fields to `PublicUniversity` and extend the pinning test.

## Update 2026-11-15: public program tier (owner decision)

`GET /api/v1/public/programs[/{identifier}]` (no account) shows the PUBLISHED OFFERING the official Konfo external API exists to display: name, degree level, field, study mode, planned duration, teaching languages, campus city, the offering university, the official program and admissions pages and our verification date. Filters: name, field, level, country, university, language.

Still restricted (catalogue gate): tuition, intakes and deadlines, admission requirements, source records, synchronization details and the application link. The licence question stays OPEN and unanswered in writing (`studyinfo-permission-request.md`, sent 2026-11-19, awaiting reply); the public program fields are therefore a documented risk accepted by the owner, easy to narrow by editing `PublicProgramController`. A test pins the exact field set.

Email verification is separate from access: it never blocks sign-in, saving, profiles or browsing.
