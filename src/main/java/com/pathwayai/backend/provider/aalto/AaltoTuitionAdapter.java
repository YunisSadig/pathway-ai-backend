package com.pathwayai.backend.provider.aalto;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import com.pathwayai.backend.refresh.extract.ScopedExtractionAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads Aalto University's official tuition fee table (plain HTML, no API exists for it). The page states, for each
 * degree level, the fee per field group and the period it applies to ("rights to study beginning on or after
 * 1 August 2025"), and that it concerns students from outside the EU, EEA and Switzerland in programmes taught in
 * English. Each of those statements is required before a fee is produced; none is assumed.
 *
 * <p>Only tuition is read here, and a fee is produced only for a program in the reviewed mapping
 * ({@code aalto-fee-groups.json}). The applicable period is the date the page states; it is never derived from the
 * day the page was read. A table row that does not parse is left out, not repaired.
 */
@Component
public class AaltoTuitionAdapter implements ScopedExtractionAdapter {

    public static final String NAME = "aalto-tuition";
    public static final String VERSION = "1";

    record Mapping(String slug, String level, String group) {
    }

    record Table(String level, LocalDate from, Map<String, Integer> feeByGroup) {
    }

    private static final Pattern ELIGIBILITY = Pattern.compile(
            "outside the EU, EEA or Switzerland[^.]{0,120}taught in English[^.]{0,80}liable to pay tuition fees");
    private static final Pattern TABLE_HEAD = Pattern.compile(
            "(Bachelor|Master)'s programmes Tuition fees for rights to study beginning on or after (\\d{1,2}) ([A-Z][a-z]+) (\\d{4})");
    private static final Pattern ROW = Pattern.compile(
            "(Fields? of [A-Z][a-z]+(?: and [A-Z][a-z]+)?|Multidisciplinary programmes)\\*{0,3} € ?([\\d ]+?) per academic year");

    private final List<Mapping> mappings;

    public AaltoTuitionAdapter() {
        this(load());
    }

    AaltoTuitionAdapter(List<Mapping> mappings) {
        this.mappings = List.copyOf(mappings);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Map<String, List<ExtractedFact>> extractForPrograms(CanonicalPage page) {
        String text = page.text().replace(' ', ' ').replace(' ', ' ').replace('’', '\'').replaceAll("\\s+", " ");
        Map<String, List<ExtractedFact>> out = new LinkedHashMap<>();
        if (!ELIGIBILITY.matcher(text).find()) {
            return out; // the student category cannot be confirmed from this page: produce nothing
        }
        List<Table> tables = tables(text);
        for (Mapping m : mappings) {
            for (Table t : tables) {
                Integer amount = t.feeByGroup().get(m.group());
                if (!t.level().equals(m.level()) || amount == null) {
                    continue;
                }
                String key = m.slug() + "|INTERNATIONAL|PER_YEAR|" + t.from();
                out.computeIfAbsent(m.slug(), k -> new ArrayList<>()).add(ExtractedFact.of(FactType.TUITION, key, Map.of(
                        "amount", String.valueOf(amount), "currency", "EUR", "billingPeriod", "PER_YEAR",
                        "studentCategory", "INTERNATIONAL", "effectiveFrom", t.from().toString(),
                        "scopeNote", "Aalto tuition fee table, " + t.level().toLowerCase(Locale.ROOT) + " programmes, fee group "
                                + m.group() + "; students from outside the EU, EEA and Switzerland in programmes taught in English;"
                                + " rights to study beginning on or after " + t.from())));
            }
        }
        return out;
    }

    /** Every table the page states, each with the period it names. A malformed date or amount drops that table or row. */
    static List<Table> tables(String text) {
        List<Table> tables = new ArrayList<>();
        Matcher head = TABLE_HEAD.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String[]> heads = new ArrayList<>();
        while (head.find()) {
            spans.add(new int[] {head.start(), head.end()});
            heads.add(new String[] {head.group(1), head.group(2), head.group(3), head.group(4)});
        }
        for (int i = 0; i < spans.size(); i++) {
            LocalDate from;
            try {
                from = LocalDate.of(Integer.parseInt(heads.get(i)[3]), Month.valueOf(heads.get(i)[2].toUpperCase(Locale.ROOT)),
                        Integer.parseInt(heads.get(i)[1]));
            } catch (RuntimeException e) {
                continue;
            }
            int start = spans.get(i)[1];
            int end = Math.min(i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length(), start + 1200);
            Map<String, Integer> fees = new LinkedHashMap<>();
            Matcher row = ROW.matcher(text.substring(start, end));
            while (row.find()) {
                String group = group(row.group(1));
                String digits = row.group(2).replace(" ", "");
                if (group != null && digits.matches("\\d{3,6}") && fees.put(group, Integer.parseInt(digits)) != null) {
                    fees.remove(group); // the same group twice in one table: ambiguous, state nothing
                }
            }
            tables.add(new Table(heads.get(i)[0].toUpperCase(Locale.ROOT), from, fees));
        }
        return tables;
    }

    private static String group(String label) {
        return switch (label) {
            case "Field of Business" -> "BUSINESS";
            case "Fields of Art and Architecture" -> "ART_ARCHITECTURE";
            case "Field of Technology" -> "TECHNOLOGY";
            case "Multidisciplinary programmes" -> "MULTIDISCIPLINARY";
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Mapping> load() {
        try (InputStream in = AaltoTuitionAdapter.class.getResourceAsStream("/reference-data/aalto-fee-groups.json")) {
            if (in == null) {
                throw new IllegalStateException("reference-data/aalto-fee-groups.json is missing");
            }
            Map<String, Object> root = JsonMapper.builder().build().readValue(in, Map.class);
            List<Mapping> out = new ArrayList<>();
            for (Object o : (List<Object>) root.get("programs")) {
                Map<String, Object> m = (Map<String, Object>) o;
                out.add(new Mapping((String) m.get("slug"), (String) m.get("level"), (String) m.get("group")));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
