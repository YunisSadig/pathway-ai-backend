package com.pathwayai.backend.provider.studyinfo;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one record of the Studyinfo (Opintopolku) official API, {@code /konfo-backend/external/koulutus/{oid}} with
 * implementations, application targets and application rounds included. Pure and deterministic: it returns only what
 * the record states. A value that is absent, ambiguous (several implementations disagree) or not in a reviewed code
 * table is left out and reported in {@code issues}; it is never inferred, and nothing here touches the database.
 */
public final class StudyinfoParser {

    public static final String SCHEME_KOULUTUS = "STUDYINFO_KOULUTUS_OID";
    public static final String SCHEME_TOTEUTUS = "STUDYINFO_TOTEUTUS_OID";
    public static final String SCHEME_ORGANISATION = "STUDYINFO_ORG_OID";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<String> NAME_LANGUAGES = List.of("en", "fi", "sv");
    private static final Pattern LEVEL = Pattern.compile("koulutusastetaso1_(\\d+)(#\\d+)?$");
    private static final Pattern FIELD3 = Pattern.compile("koulutusalataso3_(\\d+)(#\\d+)?$");
    private static final Pattern FIELD2 = Pattern.compile("koulutusalataso2_(\\d+)(#\\d+)?$");
    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})(T.*)?$");
    /** A trailing intake term such as ", Autumn 2027" is when the program starts, not what it is called. */
    private static final Pattern INTAKE_SUFFIX = Pattern.compile("(?i)[,\\s]+(autumn|fall|spring|summer|winter)\\s+\\d{4}$");
    /** The provider's own English name of the instruction language -> ISO 639 code. Anything else is left out. */
    private static final Map<String, String> LANGUAGE = Map.of("english", "en", "finnish", "fi", "swedish", "sv",
            "englanti", "en", "suomi", "fi", "ruotsi", "sv");

    public record Deadline(String hakuOid, String label, String date) {
    }

    public record Program(String koulutusOid, String toteutusOid, String organisationOid, String organisationName,
                          String name, String nameLanguage, String degreeLevel, String fieldSlug, String fieldCode,
                          String fieldLabel, String campusCity, Integer durationMonths, List<String> languages,
                          List<Deadline> deadlines) {
    }

    /** {@code program} is null when the record cannot be used at all. */
    public record Parsed(Program program, List<String> issues) {
    }

    private StudyinfoParser() {
    }

    @SuppressWarnings("unchecked")
    public static Parsed parse(String json, FieldTaxonomy taxonomy) {
        List<String> issues = new ArrayList<>();
        Map<String, Object> root;
        try {
            Object parsed = MAPPER.readValue(json, Object.class);
            if (!(parsed instanceof Map<?, ?>)) {
                return new Parsed(null, List.of("response is not a JSON object"));
            }
            root = (Map<String, Object>) parsed;
        } catch (RuntimeException e) {
            return new Parsed(null, List.of("response is not valid JSON"));
        }
        String oid = str(root.get("oid"));
        if (oid == null) {
            return new Parsed(null, List.of("record has no oid"));
        }
        if (!"julkaistu".equals(str(root.get("tila")))) {
            return new Parsed(null, List.of("record is not published (tila=" + str(root.get("tila")) + ")"));
        }
        if (!"yo".equals(str(root.get("koulutustyyppi")))) {
            return new Parsed(null, List.of("not a university degree record (koulutustyyppi=" + str(root.get("koulutustyyppi")) + ")"));
        }
        List<Object> providers = list(root.get("tarjoajat"));
        if (providers.size() != 1) {
            return new Parsed(null, List.of("expected exactly one providing institution, found " + providers.size()));
        }
        String orgOid = str(map(providers.get(0)).get("oid"));
        Localized orgName = localized(map(providers.get(0)).get("nimi"));

        Localized name = null;
        List<Map<String, Object>> toteutukset = new ArrayList<>();
        for (Object t : list(root.get("toteutukset"))) {
            if ("julkaistu".equals(str(map(t).get("tila")))) {
                toteutukset.add(map(t));
            }
        }
        String toteutusOid = null;
        if (toteutukset.size() == 1) {
            toteutusOid = str(toteutukset.get(0).get("oid"));
            name = localized(toteutukset.get(0).get("nimi"));
        } else if (toteutukset.size() > 1) {
            issues.add(toteutukset.size() + " published implementations: no single program page, duration, language or city");
        }
        if (name == null) {
            name = localized(root.get("nimi"));
        }
        if (name == null) {
            return new Parsed(null, List.of("record has no name"));
        }

        String level = degreeLevel(root, issues);
        String[] field = field(root, taxonomy, issues);
        Integer months = distinctSingle(durations(toteutukset), "planned duration", issues);
        Set<String> languages = distinctSingleSet(languageSets(toteutukset), "teaching language", issues);
        String city = distinctSingle(cities(toteutukset), "campus city", issues);
        List<Deadline> deadlines = deadlines(root, issues);

        String cleanName = INTAKE_SUFFIX.matcher(name.value()).replaceFirst("").strip();
        Program p = new Program(oid, toteutusOid, orgOid, orgName == null ? null : orgName.value(), cleanName.isEmpty() ? name.value() : cleanName, name.language(),
                level, field[0], field[1], field[2], city, months,
                languages == null ? List.of() : List.copyOf(languages), deadlines);
        return new Parsed(p, issues);
    }

    /** The facts the program states, as pipeline input. Empty parts are simply absent. */
    public static List<ExtractedFact> toFacts(Program p) {
        List<ExtractedFact> out = new ArrayList<>();
        out.add(ExtractedFact.of(FactType.PROGRAM_NAME, "", Map.of("name", p.name())));
        if (p.degreeLevel() != null) {
            out.add(ExtractedFact.of(FactType.DEGREE_LEVEL, "", Map.of("level", p.degreeLevel())));
        }
        if (p.fieldSlug() != null) {
            out.add(ExtractedFact.of(FactType.ACADEMIC_FIELD, "", Map.of("field", p.fieldSlug())));
        }
        if (p.campusCity() != null) {
            out.add(ExtractedFact.of(FactType.CAMPUS_CITY, "", Map.of("city", p.campusCity())));
        }
        if (p.durationMonths() != null) {
            out.add(ExtractedFact.of(FactType.DURATION_MONTHS, "", Map.of("months", String.valueOf(p.durationMonths()))));
        }
        if (!p.languages().isEmpty()) {
            out.add(ExtractedFact.of(FactType.LANGUAGES, "", Map.of("codes", String.join(",", new TreeSet<>(p.languages())))));
        }
        for (Deadline d : p.deadlines()) {
            out.add(ExtractedFact.of(FactType.DEADLINE, d.label(), Map.of("label", d.label(), "applicationDeadline", d.date())));
        }
        return out;
    }

    // ---- level and field --------------------------------------------------------------------------------------

    private static String degreeLevel(Map<String, Object> root, List<String> issues) {
        Set<String> codes = new TreeSet<>();
        for (Object k : list(root.get("koulutuskoodienAlatJaAsteet"))) {
            for (Object c : list(map(k).get("koulutusasteKoodiUrit"))) {
                Matcher m = c instanceof String s ? LEVEL.matcher(s) : null;
                if (m != null && m.find()) {
                    codes.add(m.group(1));
                }
            }
        }
        if (codes.size() != 1) {
            issues.add("degree level is not stated unambiguously (level codes " + codes + ")");
            return null;
        }
        return switch (codes.iterator().next()) {
            case "6" -> "BACHELOR";
            case "7" -> "MASTER";
            default -> {
                issues.add("degree level code " + codes + " is not a bachelor's or master's level");
                yield null;
            }
        };
    }

    /** {slug, code, label}; all null when the code is absent, ambiguous or not in the reviewed mapping. */
    @SuppressWarnings("unchecked")
    private static String[] field(Map<String, Object> root, FieldTaxonomy taxonomy, List<String> issues) {
        Set<String> slugs = new LinkedHashSet<>();
        Map<String, FieldTaxonomy.Entry> hit = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        for (Object k : list(root.get("koulutuskoodienAlatJaAsteet"))) {
            String d3 = null, d2 = null;
            for (Object c : list(map(k).get("koulutusalaKoodiUrit"))) {
                if (c instanceof String s) {
                    Matcher m3 = FIELD3.matcher(s);
                    Matcher m2 = FIELD2.matcher(s);
                    if (m3.find()) {
                        d3 = m3.group(1);
                    } else if (m2.find()) {
                        d2 = m2.group(1);
                    }
                }
            }
            var entry = d3 == null ? java.util.Optional.<FieldTaxonomy.Entry>empty() : taxonomy.lookup(d3);
            if (entry.isEmpty() && d2 != null) {
                entry = taxonomy.lookup(d2);
            }
            if (entry.isPresent()) {
                slugs.add(entry.get().slug());
                hit.put(entry.get().slug(), entry.get());
            } else {
                unmapped.add(d3 != null ? d3 : d2);
            }
        }
        if (slugs.size() == 1) {
            FieldTaxonomy.Entry e = hit.values().iterator().next();
            return new String[] {e.slug(), e.code(), e.label()};
        }
        issues.add(slugs.isEmpty() ? "field of education not in the reviewed mapping (codes " + unmapped + ")"
                : "field of education maps to several taxonomy fields " + slugs);
        return new String[] {null, null, null};
    }

    // ---- implementations ----------------------------------------------------------------------------------------

    private static List<Integer> durations(List<Map<String, Object>> toteutukset) {
        List<Integer> out = new ArrayList<>();
        for (Map<String, Object> t : toteutukset) {
            Map<String, Object> opetus = map(map(t.get("metadata")).get("opetus"));
            int years = number(opetus.get("suunniteltuKestoVuodet"));
            int months = number(opetus.get("suunniteltuKestoKuukaudet"));
            int total = years * 12 + months;
            if (total > 0) {
                out.add(total);
            }
        }
        return out;
    }

    private static List<Set<String>> languageSets(List<Map<String, Object>> toteutukset) {
        List<Set<String>> out = new ArrayList<>();
        for (Map<String, Object> t : toteutukset) {
            Set<String> codes = new TreeSet<>();
            boolean complete = true;
            List<Object> entries = list(map(map(t.get("metadata")).get("opetus")).get("opetuskieli"));
            for (Object e : entries) {
                Map<String, Object> nimi = map(map(e).get("nimi"));
                String label = str(nimi.get("en")) != null ? str(nimi.get("en")) : str(nimi.get("fi"));
                String iso = label == null ? null : LANGUAGE.get(label.toLowerCase(java.util.Locale.ROOT).strip());
                if (iso == null) {
                    complete = false; // a language outside the reviewed table: do not publish a partial list
                } else {
                    codes.add(iso);
                }
            }
            if (complete && !codes.isEmpty()) {
                out.add(codes);
            }
        }
        return out;
    }

    private static List<String> cities(List<Map<String, Object>> toteutukset) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> t : toteutukset) {
            for (Object p : list(t.get("tarjoajat"))) {
                Map<String, Object> nimi = map(map(map(p).get("paikkakunta")).get("nimi"));
                String city = str(nimi.get("en")) != null ? str(nimi.get("en")) : str(nimi.get("fi"));
                if (city != null) {
                    out.add(city);
                }
            }
        }
        return out;
    }

    private static <T> T distinctSingle(List<T> values, String what, List<String> issues) {
        Set<T> distinct = new LinkedHashSet<>(values);
        if (distinct.size() == 1) {
            return distinct.iterator().next();
        }
        if (distinct.size() > 1) {
            issues.add("implementations disagree on " + what + ": " + distinct);
        }
        return null;
    }

    private static Set<String> distinctSingleSet(List<Set<String>> values, String what, List<String> issues) {
        Set<Set<String>> distinct = new LinkedHashSet<>(values);
        if (distinct.size() == 1) {
            return distinct.iterator().next();
        }
        if (distinct.size() > 1) {
            issues.add("implementations disagree on " + what + ": " + distinct);
        }
        return null;
    }

    // ---- application rounds -------------------------------------------------------------------------------------

    private static List<Deadline> deadlines(Map<String, Object> root, List<String> issues) {
        Map<String, Map<String, Object>> rounds = new LinkedHashMap<>();
        for (Object h : list(root.get("haut"))) {
            if ("julkaistu".equals(str(map(h).get("tila"))) && str(map(h).get("oid")) != null) {
                rounds.put(str(map(h).get("oid")), map(h));
            }
        }
        Map<String, Set<String>> datesByRound = new LinkedHashMap<>();
        for (Object o : list(root.get("hakukohteet"))) {
            Map<String, Object> target = map(o);
            Map<String, Object> round = rounds.get(str(target.get("hakuOid")));
            if (round == null || !"julkaistu".equals(str(target.get("tila")))) {
                continue;
            }
            List<Object> windows = Boolean.TRUE.equals(target.get("kaytetaanHaunAikataulua"))
                    ? list(round.get("hakuajat")) : list(target.get("hakuajat"));
            Set<String> ends = new TreeSet<>();
            for (Object w : windows) {
                String end = str(map(w).get("paattyy"));
                Matcher m = end == null ? null : ISO_DATE.matcher(end);
                if (m != null && m.matches()) {
                    ends.add(m.group(1));
                }
            }
            if (windows.size() != 1 || ends.size() != 1) {
                issues.add("application round " + str(target.get("hakuOid")) + " has no single closing date: not imported");
                datesByRound.computeIfAbsent(str(target.get("hakuOid")), k -> new TreeSet<>()).add("");
                continue;
            }
            datesByRound.computeIfAbsent(str(target.get("hakuOid")), k -> new TreeSet<>()).addAll(ends);
        }
        List<Deadline> out = new ArrayList<>();
        for (var e : datesByRound.entrySet()) {
            if (e.getValue().size() != 1 || e.getValue().contains("")) {
                if (!e.getValue().contains("")) {
                    issues.add("application targets of round " + e.getKey() + " disagree on the closing date: not imported");
                }
                continue;
            }
            Localized label = localized(rounds.get(e.getKey()).get("nimi"));
            if (label == null || label.value().length() > 100) {
                issues.add("application round " + e.getKey() + " has no usable name for a label: not imported");
                continue;
            }
            out.add(new Deadline(e.getKey(), label.value(), e.getValue().iterator().next()));
        }
        out.sort(Comparator.comparing(Deadline::date).thenComparing(Deadline::label));
        return out;
    }

    // ---- small helpers over the untyped JSON tree -----------------------------------------------------------------

    private record Localized(String value, String language) {
    }

    private static Localized localized(Object o) {
        Map<String, Object> m = map(o);
        for (String lang : NAME_LANGUAGES) {
            String v = str(m.get(lang));
            if (v != null) {
                return new Localized(v.replaceAll("\\s+", " "), lang);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static String str(Object o) {
        return o instanceof String s && !s.isBlank() ? s.strip() : null;
    }

    private static int number(Object o) {
        return o instanceof Number n && n.intValue() > 0 ? n.intValue() : 0;
    }
}
