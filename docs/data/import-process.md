> **SUPERSEDED (2026-11-14).** The static reference-data import and the content-hash registrar described below were removed:
> the active catalogue holds no hand-maintained academic records. Current policy and procedure: [sync-policy.md](sync-policy.md),
> connectors: [official-api-connectors.md](official-api-connectors.md). This page is kept as history of how the catalogue was first built.

# Import process

Two idempotent importers feed the database. Neither deletes anything. Schema is Flyway; data is data (see ADR-11).

| Importer | File | Enable at startup | Owns |
| -------- | ---- | ----------------- | ---- |
| `UniversityCatalogueImporter` | `reference-data/universities.json` | `IMPORT_UNIVERSITY_CATALOGUE=true` | institution identity: names, websites, identifiers, aliases, verification, identity sources |
| `ReferenceDataImporter` | `reference-data/programs.json` | `IMPORT_REFERENCE_DATA=true` | programs and their facts: tuition, requirements, intakes, sources, retractions |

Run either against another file with `UNIVERSITY_CATALOGUE_LOCATION` / `REFERENCE_DATA_LOCATION` (any Spring resource, e.g. `file:/path/x.json`). A failed import stops the application, so a bad file is never half-applied and ignored.

## Before importing into a database that matters

```
docker exec pathway-ai-postgres pg_dump -U pathway -d pathway_ai -Fc > pathway_ai_before_import.dump
```

Restore with `pg_restore` into a scratch database to check it first. Keep dumps outside the repository.

## How an institution record is resolved (no duplicates)

For each record in `universities.json`, in order:

1. **External identifier**: any `(scheme, value)` pair (SCHAC home organisation, Erasmus code, ECHE number, PIC) already owned by a university identifies it, even if the name or slug changed.
2. **Slug**.
3. **Name or alias within the same country** (case-insensitive; the record's name, aliases and native name against existing names and aliases).

Ambiguity is an error, not a guess: external ids that belong to two universities, a slug and external ids pointing at different rows, or aliases matching several universities abort the whole import.

For an **existing** row the importer keeps the slug, name, city and website that are already there and fills only empty columns. Verification only moves up (UNVERIFIED, REGISTRY_ONLY, VERIFIED). New external ids, aliases and source links are added. Sources are keyed by URL; an existing source keeps its title and only receives a newer read date.

## Idempotency, demonstrated

- Integration tests (`UniversityCatalogueImporterIT`, PostgreSQL via Testcontainers): a second run of the same file reports zero changes; rename and alias cases do not duplicate; curated values are never overwritten; verification never goes down; an invalid file writes nothing; a database conflict aborts the run.
- Real database: the first import created 45 universities (3 existing ones matched and filled in place) and 28 new countries; a second import after the file gained 31 admissions/catalogue source pages changed **62 rows (31 sources + 31 links)** and created **no** university.

## Validation (the whole file, before any write)

Slugs are lower-case words joined by single hyphens (the program identifier `<university>--<program>` relies on `--` never appearing in a slug); ISO country codes declared; https URLs only; a REGISTRY_ONLY institution must carry no website URLs; a VERIFIED one must have a website and an `UNIVERSITY_WEBSITE` source for exactly that URL; external ids unique across the file; source types valid. A database check also forbids a website on a REGISTRY_ONLY row.

## Adding or correcting an institution

1. Find it in the EU HEI registry (`/api/public/v1/country/{iso}/hei`) and note its SCHAC id. Outside the EU, use the official site.
2. Open the official home page (and admissions / programme-catalogue pages). Record the URLs only if they load with the right title.
3. Add the record with `verificationStatus` `VERIFIED` (site read) or `REGISTRY_ONLY` (not readable; no URLs), cite every page in `sources`, run the importer, then `app.refresh.command=register` so the new pages are monitored.

## Adding programs

Read the program's official page. Add the program, its cited source(s) and only the facts the page states to `programs.json` (unknown stays absent). `ReferenceDataFileTest` guards the file. Never copy a value from a page you did not read. Dated facts follow the year rule in CLAUDE.md: store a year or period only when the source prints it; a new year is a new row.

## Known limits

- The importers add and update; removing a wrong record needs an explicit reasoned `retractions` entry (programs) or manual SQL (institutions).
- `institution_type` is never set by the importer (not verifiable from the sources used).
