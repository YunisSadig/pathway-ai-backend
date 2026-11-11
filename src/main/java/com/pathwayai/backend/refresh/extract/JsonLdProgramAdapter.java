package com.pathwayai.backend.refresh.extract;

import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads schema.org structured data embedded in an official programme page: a {@code Course} or
 * {@code EducationalOccupationalProgram} with {@code timeToComplete} (an ISO-8601 duration such as P2Y or P24M) and
 * {@code inLanguage} (ISO 639 codes). Structured data states a fact explicitly, so reading it is deterministic.
 * It never reads tuition, deadlines or requirements: those need a reviewed, site-specific adapter.
 * A page without such a block, or a value that is not an unambiguous code or whole-month duration, yields no fact.
 */
@Component
public class JsonLdProgramAdapter implements ExtractionAdapter {

    public static final String NAME = "jsonld-program";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Set<String> PROGRAM_TYPES = Set.of("Course", "EducationalOccupationalProgram");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public List<ExtractedFact> extract(CanonicalPage page) {
        List<ExtractedFact> facts = new ArrayList<>();
        for (String block : page.jsonLd()) {
            Object parsed;
            try {
                parsed = MAPPER.readValue(block, Object.class);
            } catch (RuntimeException e) {
                continue; // malformed structured data is ignored, never repaired
            }
            for (Map<?, ?> node : programNodes(parsed)) {
                duration(node).ifPresent(m -> facts.add(
                        ExtractedFact.of(FactType.DURATION_MONTHS, "", Map.of("months", String.valueOf(m)))));
                String languages = languages(node.get("inLanguage"));
                if (!languages.isEmpty()) {
                    facts.add(ExtractedFact.of(FactType.LANGUAGES, "", Map.of("codes", languages)));
                }
            }
        }
        return facts;
    }

    private static List<Map<?, ?>> programNodes(Object parsed) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (parsed instanceof List<?> list) {
            list.forEach(o -> out.addAll(programNodes(o)));
        } else if (parsed instanceof Map<?, ?> map) {
            if (map.get("@graph") != null) {
                out.addAll(programNodes(map.get("@graph")));
            }
            Object type = map.get("@type");
            boolean isProgram = type instanceof String s ? PROGRAM_TYPES.contains(s)
                    : type instanceof List<?> l && l.stream().anyMatch(t -> t instanceof String s && PROGRAM_TYPES.contains(s));
            if (isProgram) {
                out.add(map);
            }
        }
        return out;
    }

    private static java.util.Optional<Integer> duration(Map<?, ?> node) {
        Object v = node.get("timeToComplete");
        if (!(v instanceof String s) || !s.startsWith("P")) {
            return java.util.Optional.empty();
        }
        try {
            Period p = Period.parse(s);
            if (p.getDays() != 0 || p.isNegative() || p.isZero()) {
                return java.util.Optional.empty(); // days or weeks do not convert to months without guessing
            }
            long months = p.toTotalMonths();
            return months >= 1 && months <= 120 ? java.util.Optional.of((int) months) : java.util.Optional.empty();
        } catch (DateTimeParseException e) {
            return java.util.Optional.empty();
        }
    }

    private static String languages(Object v) {
        Set<String> codes = new LinkedHashSet<>();
        if (v instanceof String s) {
            collect(s, codes);
        } else if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    collect(s, codes);
                } else if (o instanceof Map<?, ?> m && m.get("name") instanceof String s) {
                    collect(s, codes);
                }
            }
        }
        return String.join(",", codes);
    }

    /** Only an explicit ISO 639 code is accepted. A language *name* is not mapped to a code by guessing. */
    private static void collect(String raw, Set<String> codes) {
        String code = raw.strip().toLowerCase(java.util.Locale.ROOT);
        int dash = code.indexOf('-');
        if (dash > 0) {
            code = code.substring(0, dash);
        }
        if (code.matches("[a-z]{2,3}")) {
            codes.add(code);
        }
    }
}
