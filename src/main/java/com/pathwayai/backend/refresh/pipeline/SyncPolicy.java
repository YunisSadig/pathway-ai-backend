package com.pathwayai.backend.refresh.pipeline;

import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import java.util.Set;

/**
 * The single statement of what is published automatically and what waits for a person. The change pipeline enforces
 * it and the API reports it per field, so the two can never disagree.
 *
 * <p>A fact read from an OFFICIAL API connector is deterministic: explicit fields, validated, compared with the
 * published value. Low-impact, objective fields are then published without review (the replaced value is archived
 * in {@code program_fact_history}). Identity and classification (name, level, field), dated facts that would be
 * overwritten, tuition and requirements are never changed silently.
 */
public final class SyncPolicy {

    /** Adapters that read an official API (not a scraped page). Provenance and publication rules key off this. */
    public static boolean isOfficialApi(String adapterName) {
        return adapterName != null && adapterName.startsWith("studyinfo");
    }

    /** A different value from the official API replaces the published one automatically. */
    public static final Set<FactType> AUTO_REPLACE = Set.of(FactType.DURATION_MONTHS, FactType.LANGUAGES, FactType.CAMPUS_CITY);

    /** A value that does not exist yet (a new application round) is added automatically; an existing one is never overwritten. */
    public static final Set<FactType> AUTO_ADD = Set.of(FactType.DEADLINE);

    /** How a published field reaches the public API once its source changes. */
    public enum UpdateMode {
        /** Re-read on schedule and published without review. */
        AUTOMATIC,
        /** Re-read on schedule, but a change is staged and needs approval; the old value stays until then. */
        REVIEW_REQUIRED,
        /** New values (a new application round) are added automatically; a published value is never overwritten without review. */
        ADDITIVE_AUTOMATIC,
        /** Set once from the provider's record; never changed afterwards. */
        FIXED_AT_CREATION
    }

    public static UpdateMode modeOf(String provenanceField) {
        return switch (provenanceField) {
            case "duration_months", "languages", "campus_city" -> UpdateMode.AUTOMATIC;
            case "name", "degree_level", "academic_field" -> UpdateMode.REVIEW_REQUIRED;
            case "application_deadline" -> UpdateMode.ADDITIVE_AUTOMATIC;
            default -> UpdateMode.FIXED_AT_CREATION;
        };
    }

    /** What an official-API connector cannot supply, so the API must say it is unavailable instead of showing it. */
    public static final java.util.List<String> UNAVAILABLE_FROM_STUDYINFO =
            java.util.List.of("tuition", "scholarships", "admissionRequirements", "applicationUrl");

    private SyncPolicy() {
    }
}
