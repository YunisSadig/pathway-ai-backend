package com.pathwayai.backend.refresh.pipeline;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * NORMALIZE step: makes equivalent spellings identical and nothing else. It never repairs a value that is
 * ambiguous: {@code 20,000} could be twenty thousand or twenty, so it is left as is and the validator rejects it.
 */
public final class FactNormalizer {

    private static final Set<String> UPPER = Set.of("currency", "billingPeriod", "studentCategory", "applicantCategory");

    private FactNormalizer() {
    }

    public static ExtractedFact normalize(ExtractedFact fact) {
        Map<String, String> out = new HashMap<>();
        fact.values().forEach((k, v) -> {
            String value = v == null ? "" : v.replace(' ', ' ').replace(' ', ' ').strip().replaceAll("\\s+", " ");
            if (UPPER.contains(k)) {
                value = value.toUpperCase(Locale.ROOT).replace(' ', '_');
            }
            if (k.equals("amount")) {
                String compact = value.replace(" ", "");
                if (compact.matches("\\d+(\\.\\d{1,2})?")) {
                    value = new java.math.BigDecimal(compact).stripTrailingZeros().toPlainString();
                }
            }
            if (k.equals("codes")) {
                value = value.toLowerCase(Locale.ROOT).replace(" ", "");
            }
            out.put(k, value);
        });
        return ExtractedFact.of(fact.type(), fact.key().strip(), out);
    }
}
