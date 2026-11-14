# Next milestone: maintainable programs outside Finland

**Goal: programs and academic facts that stay current automatically, not more university names.** A university is added to
the active catalogue only when at least one real degree program can be retrieved *and refreshed* through a permitted
official source; three or more are preferred. Numbers (25-30 universities) are an outcome, never a target that justifies
adding an institution with no maintainable source. Institutions already in the catalogue without program sources stay
REGISTRY_ONLY or VERIFIED-identity-only and are not offered for discovery as programs.

## Admission test for any new source (all must hold before code is written)

1. **Permitted:** robots.txt allows the path; terms or licence do not forbid automated reading; a published crawl delay and rate
   limit are honoured exactly. A 403, challenge page, login or CAPTCHA means *restricted*: record it, do not bypass.
2. **Machine-readable first:** official API, then dataset or feed, then JSON-LD/structured data, then a narrowly scoped HTML or
   PDF extractor for that one site. Browser automation only if nothing simpler works and it is permitted.
3. **Explicit facts only:** the source states the fact (and its period) in the response; nothing is inferred from reading
   dates, adjacent headings or a pattern. Missing stays unknown.
4. **Reuse position recorded:** licence or terms, attribution, and whether a persistent refreshed copy is allowed (the
   Studyinfo lesson: "open data" without a licence name still needs written confirmation).
5. **A tested refresh path:** recorded fixtures, a controlled-change test on PostgreSQL, a repeat-read test that writes nothing,
   and one live read of the real service before trusting the fixtures.

## Work per source (reuse what exists)

`ExtractionAdapter` / `ScopedExtractionAdapter` + a reviewed mapping file + the existing pipeline (normalise, validate, compare,
stage or apply, provenance, history) + `assign-adapter`/`sync-adapter` + discovery freshness. A provider adds: discovery of
offerings, a pure parser with an `issues` list, a reviewed code/field map, an importer for new programs (operator-run), and
documentation of what it cannot supply.

## Candidate order (to be confirmed against the admission test, not assumed)

| Region | Direction to investigate | Why it may qualify | Known unknowns |
|---|---|---|---|
| Other EU countries | National study portals and registers with open endpoints (for example the Danish, Dutch and Czech higher-education registers and portals) | National agencies publish program lists with ids; same shape as Studyinfo | Per-country licence, whether an API exists, language/field codes to map |
| USA | College Scorecard and IPEDS open datasets (federal, public domain) for program lists, degrees awarded and costs | Official, documented, licensed as open government data | They give institution/program (CIP) and cost data, not live deadlines or requirements; cadence is annual |
| Canada | Provincial and national open-data catalogues; university program pages with structured data | Some provinces publish program inventories | No single national program API known; likely per-university extractors |
| China | Ministry of Education published lists (institutions, majors) and official English admissions pages for international programs | Official lists exist | Language, terms and access restrictions must be checked first; high chance of "restricted" |
| Japan | MEXT/JASSO official lists; university English program pages | Official institution and program lists | API availability unknown; pages mostly HTML/PDF |
| South Korea | Official portals (for example the national higher-education information service) and university English program pages | Government-run information service | Terms and API availability unknown |

Each row is a hypothesis. The first deliverable for a region is a short written finding (permitted? machine-readable? what
facts? licence?) from a handful of requests, **before** any importer. If a region fails the test, it stays out and the report
says so.

## Facts the next connectors must cover (gaps left by Studyinfo)

Tuition with applicant category and stated period; language-proficiency and admission requirements with their cycle;
application deadlines tied to a stated intake; official application link; scholarships where an official source exists. Each
fact keeps source URL, verification time, period, mechanism and freshness; ambiguous changes are staged and the old value
is not shown as verified current while pending (known issue 42: needs an additive per-fact flag).

## Definition of done for each new university

1. A permitted source with a recorded reuse position.
2. At least one (preferably three or more) real program imported with provider ids and field-level provenance.
3. A repeat read that writes nothing; a controlled change that stages, validates and persists; an outage that changes nothing.
4. The monitor is registered and due, discovery shows the program as fresh, and `GET /api/v1/discovery/status` is `FRESH` or
   explains why not.
5. No unverified or invented value; unknown stays null.
