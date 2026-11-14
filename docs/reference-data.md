# Reference data (retired as a source of academic facts)

Until 2026-11-14 a versioned JSON file (`programs.json`) held hand-collected programs, tuition and requirements. **That is
no longer how data gets in.** Static records are not part of the active catalogue (policy: [data/sync-policy.md](data/sync-policy.md)):
a fact is published only if a working extractor keeps it current.

What is left in `src/main/resources/reference-data/`:

| File | Purpose |
|---|---|
| `taxonomy.json` | countries and the academic-field taxonomy only (no programs, universities, sources, fees, deadlines or requirements) |
| `universities.json` | the connector-backed universities (identity, registry ids, sources) |
| `studyinfo.json`, `studyinfo-field-map.json` | the Studyinfo connector's university list and reviewed field-code map |
| `aalto-fee-groups.json` | reviewed fee-group mapping for the Aalto tuition extractor (empty: no retained program has a monitor for it) |

The old procedure, its validators and the legacy records are preserved only in git history and in `catalogue_archive` (written by
`scripts/db/retire-unsynchronised-catalogue.sql`). `IMPORT_REFERENCE_DATA=true` still loads the taxonomy; a file that carries academic
records is refused. To add data, add a connector: [data/official-api-connectors.md](data/official-api-connectors.md).
