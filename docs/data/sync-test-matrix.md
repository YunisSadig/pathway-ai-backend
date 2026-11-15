# Synchronization test matrix

Automated (CI) unless marked. Files: `provider/studyinfo/StudyinfoConnectorIT`, `StudyinfoParserTest`, `FieldTaxonomyFileTest`, `refresh/RefreshServiceIT`, `refresh/ExtractorFreshnessIT`, `account/PublicCatalogueIT`, `account/CatalogueGateIT`, `common/discovery/DiscoveryVisibilityIT`.

| Scenario | Covered by |
| --- | --- |
| Initial import (record, provenance, monitor) | `StudyinfoConnectorIT.importCreatesTheProgram...` |
| Idempotent reimport, nothing rewritten | `importingTwiceCreatesNothingAndRewritesNothing`, `refreshingAnUnchangedRecord...` |
| New program found after the first import | `theSyncPassDiscoversANewProgramThenRepeatingItChangesNothing` |
| Field filled from an official code when unknown | `anUnknownFieldIsFilledAutomatically...` |
| Field change goes to review, history kept | `aControlledAcademicFieldChange...` |
| Duration/language/campus change automatic, archived | `aChangedDurationIsPublishedAutomatically...` |
| New application round added, changed closing date reviewed | `aNewApplicationRound...`, `aChangedClosingDate...` |
| Discontinued and reinstated | `aRecordTheProviderArchives...` |
| Rename keeps identifier, links, provider id, no duplicate | `renamingAProgramKeepsItsIdentifier...` |
| Another institution never imported | `aRecordWhoseInstitutionDiffers...`, `aJointProgram...`, `aRecordNamingAnotherInstitution...` |
| Source outage, 429, access denied, robots, SSRF, malformed | `aProviderOutage...`, `RefreshServiceIT` (rate limit, denied, robots, internal address, invalid data) |
| Draft records publish nothing | `aDraftStateRecord...` |
| Public API field allow-list, protected data closed | `PublicCatalogueIT` |
| Saved programs resolve through the public tier | `PublicCatalogueIT`, `CatalogueGateIT` |
| Mapping consistent with the taxonomy | `FieldTaxonomyFileTest` |
| Freshness watchdog | `ExtractorFreshnessIT` |
| Program page and filters (mocked API), desktop and mobile | frontend `public-discovery.spec.ts`, `programs.spec.ts` |
| Live site visitor and account journey | frontend `tests/live/smoke.spec.ts` (post-deploy) |
| Updated facts visible on the page after a sync | **partly automated**: the API answers from the database with a 300 s revalidating cache (`ApiCacheConfig`); a real sync-to-page run is verified manually on production, not in CI |
| Real Studyinfo discovery up to the 30-per-university cap | **not automated** (live service, about 30 s per record): run `sync` from the workflow and read the report |
