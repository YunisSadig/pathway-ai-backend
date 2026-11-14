package com.pathwayai.backend.refresh.extract;

import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * A fact an adapter read from a page, before any validation. Values are strings exactly as extracted (normalised
 * later); {@code key} identifies which fact it is within its type (for example a tuition period and applicant
 * category) so that a re-extraction of the same fact is recognised.
 */
public record ExtractedFact(FactType type, String key, Map<String, String> values) {

    public enum FactType {
        /** Informational: the page changed and no adapter could say what. */
        SOURCE_CONTENT,
        DURATION_MONTHS,
        LANGUAGES,
        TUITION,
        DEADLINE,
        REQUIREMENT
    }

    public ExtractedFact {
        values = Map.copyOf(new TreeMap<>(values));
    }

    public static ExtractedFact of(FactType type, String key, Map<String, String> values) {
        return new ExtractedFact(type, key, values);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A stable, order-independent JSON rendering used for storage, comparison and de-duplication. */
    public String canonicalValue() {
        return JSON.writeValueAsString(new TreeMap<>(values));
    }

    /** Reads back what {@link #canonicalValue()} wrote. */
    @SuppressWarnings("unchecked")
    public static Map<String, String> parseValue(String canonical) {
        return JSON.readValue(canonical, Map.class);
    }
}
