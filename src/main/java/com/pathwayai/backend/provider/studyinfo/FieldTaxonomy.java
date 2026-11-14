package com.pathwayai.backend.provider.studyinfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps the national field-of-education codes Studyinfo publishes (Statistics Finland's classification, which follows
 * ISCED-F) to the Pathway AI taxonomy. The mapping is a reviewed file ({@code reference-data/studyinfo-field-map.json}):
 * each entry states the code, the official English label seen in the API response and the taxonomy slug. A code that
 * is not in the file yields no field at all; it is never guessed from a program name or a nearby code.
 */
@Component
public class FieldTaxonomy {

    public record Entry(String code, String slug, String label) {
    }

    private final Map<String, Entry> byCode;

    public FieldTaxonomy() {
        this(load());
    }

    FieldTaxonomy(Map<String, Entry> byCode) {
        this.byCode = Map.copyOf(byCode);
    }

    public static FieldTaxonomy of(Map<String, Entry> byCode) {
        return new FieldTaxonomy(byCode);
    }

    /** Looks a code up exactly (a 4-digit detailed code and a 3-digit narrow code are separate keys). */
    public Optional<Entry> lookup(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Entry> load() {
        try (InputStream in = FieldTaxonomy.class.getResourceAsStream("/reference-data/studyinfo-field-map.json")) {
            if (in == null) {
                throw new IllegalStateException("reference-data/studyinfo-field-map.json is missing");
            }
            Map<String, Object> root = JsonMapper.builder().build().readValue(in, Map.class);
            Map<String, Entry> out = new HashMap<>();
            for (Object o : (List<Object>) root.get("mappings")) {
                Map<String, Object> m = (Map<String, Object>) o;
                String code = (String) m.get("code");
                if (out.put(code, new Entry(code, (String) m.get("slug"), (String) m.get("label"))) != null) {
                    throw new IllegalStateException("duplicate field code in studyinfo-field-map.json: " + code);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
