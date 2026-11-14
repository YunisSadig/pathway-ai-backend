# Official sources

What the catalogue and the monitoring system read, under what conditions, and what could not be read.

## Source hierarchy

1. **Official university websites**: the authority for names, websites, admissions, programmes, fees and deadlines.
2. **EU HEI registry (European University Foundation HEI API)**: used for **institutional identity only** (active status, SCHAC home organisation, Erasmus code, ECHE number, PIC, registered name and city).
3. Government and accreditation portals: tried for Bulgaria, Cyprus and Luxembourg; blocked or unreachable (see `catalogue-coverage.md`).

Third-party rankings and aggregators are not used and are not sources.

## EU HEI registry

- Endpoints used: `GET https://hei.api.uni-foundation.eu/api/public/v1/country/{iso}/hei` (one call per EU country) and the per-institution record `/api/public/v1/schac/{schac}/hei` (cited as each institution's source).
- Access: public, no key. The documentation page lists the endpoints and the JSON:API format. **No explicit licence or reuse statement was found on the site**, so the data is used only to match and identify institutions, attributed in `source` (publisher "European University Foundation HEI API"), and never presented as admissions information. If the operator publishes terms that restrict this, the import can be re-run from official sites alone.
- Coverage: all 27 EU countries (1,738 French and 1,748 Spanish records, 7 for Luxembourg, ...). It returns nothing for China, Japan and South Korea.
- Limits observed: `website_url` is empty for every record; the `city` field is sometimes wrong (Helsinki's says "Helsingin Yliopisto") and sometimes lists two spellings ("Wien | Vienna"); names are ASCII-folded (diacritics removed). Hence websites, cities and native names are taken from or checked against official sources.
- All 45 EU targets matched exactly by SCHAC identifier and were active (`status: true`). The registry record is also a monitored source: a change to an institution's registered facts is raised for review.

## Official websites

Reading rules applied to every domain:

- One GET per page with an identifying user agent (`PathwayAIBot/0.1 (+repository URL)`), at most one request per host every 3 seconds, `robots.txt` honoured (RFC 9309), https only.
- No CAPTCHA solving, no login, no scripted browser, no proxies, no user-agent spoofing. A bot-protection page is recorded as "blocked" and the source is not retried automatically.
- Only the minimum is kept: the URL, the page title and the date read (in `source`), a content hash, and extracted facts. Page bodies are not stored.

## Domain access findings (2026-11-11)

| Finding | Domains |
| ------- | ------- |
| Read normally (home pages of 39 institutions) | univie.ac.at, tuwien.at, ugent.be, unizg.hr, cuni.cz, vut.cz, ku.dk, au.dk, ut.ee, taltech.ee, helsinki.fi, aalto.fi, sorbonne-universite.fr, universite-paris-saclay.fr, tum.de, uni-heidelberg.de, uoa.gr, elte.hu, bme.hu, tcd.ie, unibo.it, unipd.it, lu.lv, vu.lt, ktu.edu, um.edu.mt, uva.nl, uu.nl, uni.lodz.pl, p.lodz.pl, up.pt, ubbcluj.ro, stuba.sk, uni-lj.si, uab.cat, lunduniversity.lu.se, pku.edu.cn, u-tokyo.ac.jp, snu.ac.kr |
| Cloudflare or CloudFront challenge / 403 | ucy.ac.cy, ucd.ie, ulisboa.pt, ub.edu, uu.se, unibuc.ro, uni.lu (HTTP 202 challenge) |
| Connection timeouts or failures | kuleuven.be, uni-sofia.bg |
| `robots.txt` returns a server error | elte.hu (its two monitored pages stay FAILING and are retried with back-off; per RFC 9309 the crawler assumes "disallow" while robots.txt is unavailable) |
| Oversized page for the default cap | unipd.it home page is 2.03 MB (the cap is now 4 MB) |

`robots.txt` of every readable host was fetched; none disallowed `/` for generic crawlers.

## Parsers and what they can read

| Adapter | Reads | Does not read |
| ------- | ----- | ------------- |
| `jsonld-program` | schema.org `Course` / `EducationalOccupationalProgram`: `timeToComplete` (whole months) and `inLanguage` (ISO 639 codes) | tuition, deadlines, requirements; language names (never mapped to codes by guessing); durations in days or weeks |
| `content-hash` | nothing: detects change and raises a review item | everything |
| `content-watch` | nothing: records change in the audit trail only (home pages) | everything |

There is no generic HTML scraper by design. Tuition, deadline and requirement extraction needs a reviewed, per-site adapter written for that site's real markup; none exists yet, so those facts reach the database only through the reference-data import after a human reads the page.
