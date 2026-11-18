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
                          List<Deadline> deadlines, Details details) {
    }

    /**
     * Structured facts of one REVIEWED implementation, read only from the provider's coded fields (no free text is stored).
     * Every part is null when the record does not state it. {@code feeAcademicYear} is set only when the record states an
     * autumn start term and year for the implementation (its own, or every application round of it, all agreeing): the
     * first academic year of that intake (Finnish academic years run August to July). {@code feeLiableStated}: the
     * provider's fee description names the fee-liable audience (non-EU/EEA); without it no fee is published from here.
     */
    public record Details(String degreeTitles, String credits, String studyMode, String feeAmount, String feeAcademicYear,
                          String feeStartTerm, boolean feeLiableStated, String scholarship) {
    }

    /** {@code program} is null when the record cannot be used at all. */
    public record Parsed(Program program, List<String> issues) {
    }

    private StudyinfoParser() {
    }

    /**
     * The provider states in the record's own {@code tila} that the education was archived ("arkistoitu") or deleted
     * ("poistettu"). Any other non-published state (for example the draft state "tallennettu") may be temporary and is
     * not a discontinuation, so it keeps going through the review path.
     */
    public static java.util.Optional<String> discontinuation(String json) {
        try {
            Object parsed = MAPPER.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> root && str(root.get("oid")) != null) {
                String tila = str(root.get("tila"));
                if ("arkistoitu".equals(tila) || "poistettu".equals(tila)) {
                    return java.util.Optional.of("the provider states the education is no longer offered (tila=" + tila + ")");
                }
            }
        } catch (RuntimeException e) {
            // not valid JSON: a failure, not a discontinuation
        }
        return java.util.Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public static Parsed parse(String json, FieldTaxonomy taxonomy) {
        return parse(json,taxonomy,null,false);
    }

    /** A reviewed offering may explicitly be a bachelor's-entry combined pathway; other mixed levels stay unsupported. */
    @SuppressWarnings("unchecked")
    public static Parsed parse(String json, FieldTaxonomy taxonomy,String implementationOid,boolean combinedEntry) {
        return parse(json,taxonomy,implementationOid,combinedEntry,null);
    }

    public static Parsed parse(String json, FieldTaxonomy taxonomy,String implementationOid,boolean combinedEntry,String reviewedProviderOid) {
        return parse(json,taxonomy,implementationOid,combinedEntry,reviewedProviderOid,List.of());
    }

    /**
     * Base-education requirements that are themselves a completed degree. A target asking for one of these is a
     * master's (or later) entry route; a bachelor's entry route asks for general university eligibility instead.
     */
    static final Set<String> PRIOR_DEGREE_REQUIREMENTS = Set.of("pohjakoulutusvaatimuskouta_101", "pohjakoulutusvaatimuskouta_102",
            "pohjakoulutusvaatimuskouta_103", "pohjakoulutusvaatimuskouta_116", "pohjakoulutusvaatimuskouta_117",
            "pohjakoulutusvaatimuskouta_119", "pohjakoulutusvaatimuskouta_120", "pohjakoulutusvaatimuskouta_121");
    static final String GENERAL_UNIVERSITY_ELIGIBILITY = "pohjakoulutusvaatimuskouta_123";

    /**
     * {@code reviewedTitleCodes}: for a reviewed combined pathway whose level codes name only the master's level, the
     * exact degree-title codes ({@code metadata.tutkintonimike}) a reviewer confirmed. The record is then a bachelor's
     * entry only if it still states exactly those titles AND every published target of the reviewed implementation asks
     * for general university eligibility and for no completed degree. Names are never used. Any difference leaves the
     * level unknown, so the offering goes to review instead of being relabelled.
     */
    public static Parsed parse(String json, FieldTaxonomy taxonomy,String implementationOid,boolean combinedEntry,String reviewedProviderOid,
                               List<String> reviewedTitleCodes) {
        return parse(json,taxonomy,implementationOid,combinedEntry,reviewedProviderOid,reviewedTitleCodes,null);
    }

    /**
     * {@code reviewedField}: for a reviewed offering whose education codes map to SEVERAL taxonomy fields, the one a
     * reviewer chose among them. It applies only while the record still maps to that field; otherwise the field stays
     * unknown as before. Never taken from a name.
     */
    @SuppressWarnings("unchecked")
    public static Parsed parse(String json, FieldTaxonomy taxonomy,String implementationOid,boolean combinedEntry,String reviewedProviderOid,
                               List<String> reviewedTitleCodes,String reviewedField) {
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
        if(reviewedProviderOid!=null && implementationOid!=null)
            providers=providers.stream().filter(p->reviewedProviderOid.equals(str(map(p).get("oid")))).toList();
        if (providers.size() != 1) {
            return new Parsed(null, List.of("expected exactly one providing institution, found " + providers.size()));
        }
        String orgOid = str(map(providers.get(0)).get("oid"));
        Localized orgName = localized(map(providers.get(0)).get("nimi"));

        Localized name = null;
        List<Map<String, Object>> toteutukset = new ArrayList<>();
        for (Object t : list(root.get("toteutukset"))) {
            if ("julkaistu".equals(str(map(t).get("tila")))
                    && (implementationOid==null || implementationOid.equals(str(map(t).get("oid"))))) {
                toteutukset.add(map(t));
            }
        }
        if(implementationOid!=null && toteutukset.size()!=1)
            return new Parsed(null,List.of("reviewed implementation is not uniquely published"));
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

        String level = degreeLevel(root, issues,combinedEntry);
        if(combinedEntry && implementationOid!=null && reviewedTitleCodes!=null && !reviewedTitleCodes.isEmpty()) {
            level=bachelorEntryByEvidence(root,implementationOid,reviewedTitleCodes,issues) ? "BACHELOR" : null;
        }
        String[] field = field(root, taxonomy, issues, implementationOid == null ? null : reviewedField);
        Integer months = distinctSingle(durations(toteutukset), "planned duration", issues);
        Set<String> languages = distinctSingleSet(languageSets(toteutukset), "teaching language", issues);
        String city = distinctSingle(cities(toteutukset), "campus city", issues);
        Map<String,Object> deadlineRoot=root;
        if(implementationOid!=null) {
            Set<String> rounds=new LinkedHashSet<>();
            for(Object target:list(root.get("hakukohteet"))) {
                Map<String,Object> entry=map(target);
                if(implementationOid.equals(str(entry.get("toteutusOid"))) && "julkaistu".equals(str(entry.get("tila"))))
                    rounds.add(str(entry.get("hakuOid")));
            }
            deadlineRoot=new LinkedHashMap<>(root);
            deadlineRoot.put("haut",list(root.get("haut")).stream().filter(h->rounds.contains(str(map(h).get("oid")))).toList());
            deadlineRoot.put("hakukohteet",list(root.get("hakukohteet")).stream()
                    .filter(t->implementationOid.equals(str(map(t).get("toteutusOid")))).toList());
        }
        List<Deadline> deadlines = implementationOid==null ? deadlines(deadlineRoot, issues) : routeDeadlines(deadlineRoot, issues);

        Details details = implementationOid == null || toteutukset.size() != 1 ? null
                : details(root, toteutukset.get(0), deadlineRoot, issues);
        String cleanName = INTAKE_SUFFIX.matcher(name.value()).replaceFirst("").strip();
        if (implementationOid != null) {
            cleanName = withoutRoutePrefix(cleanName, localized(root.get("nimi")));
        }
        Program p = new Program(oid, toteutusOid, orgOid, orgName == null ? null : orgName.value(), cleanName.isEmpty() ? name.value() : cleanName, name.language(),
                level, field[0], field[1], field[2], city, months,
                languages == null ? List.of() : List.copyOf(languages), deadlines, details);
        return new Parsed(p, issues);
    }

    /**
     * Some providers prefix an implementation's name with its admission route ("Rolling Admission, Digital Business ,
     * …"). Leading comma-separated segments that the education's own name does not contain are dropped; the rest is kept
     * verbatim (with a stray space before a comma removed). Nothing is dropped when the education name is unknown.
     */
    static String withoutRoutePrefix(String implementationName, Localized educationName) {
        if (educationName == null) return implementationName;
        String education = educationName.value().replaceAll("\\s+,", ",").toLowerCase(java.util.Locale.ROOT);
        String[] segments = implementationName.split(",");
        int first = 0;
        while (first < segments.length - 1 && !education.contains(segments[first].strip().toLowerCase(java.util.Locale.ROOT))) first++;
        if (first == 0) return implementationName;
        return String.join(",", java.util.Arrays.copyOfRange(segments, first, segments.length)).replaceAll("\\s+,", ",").strip();
    }

    private static final Pattern FEE_LIABLE = Pattern.compile("(?i)\\b(EU/EEA|EEA|European Economic Area)\\b");

    /** See {@link Details}. Only coded fields; the free-text descriptions are read for the audience check alone. */
    private static Details details(Map<String, Object> root, Map<String, Object> toteutus, Map<String, Object> routesRoot,
                                   List<String> issues) {
        Map<String, Object> rootMeta = map(root.get("metadata"));
        Map<String, Object> opetus = map(map(toteutus.get("metadata")).get("opetus"));
        List<String> titles = new ArrayList<>();
        for (Object t : list(rootMeta.get("tutkintonimike"))) {
            Localized n = localized(map(t).get("nimi"));
            if (n != null && "en".equals(n.language())) titles.add(n.value());
        }
        String credits = null;
        Object ects = rootMeta.get("opintojenLaajuusNumero");
        String unit = unversioned(str(map(rootMeta.get("opintojenLaajuusyksikko")).get("koodiUri")));
        if (ects instanceof Number n && n.doubleValue() > 0 && n.doubleValue() == Math.rint(n.doubleValue())
                && "opintojenlaajuusyksikko_2".equals(unit)) {
            credits = n.intValue() + " ECTS";
        }
        List<String> modes = new ArrayList<>();
        for (Object m : list(opetus.get("opetustapa"))) {
            Localized n = localized(map(m).get("nimi"));
            if (n != null && "en".equals(n.language())) modes.add(n.value());
        }
        // fee: exactly one annual tuition fee, as a whole number of euros
        String fee = null;
        List<Object> fees = list(opetus.get("maksut"));
        if (fees.size() == 1 && "lukuvuosimaksu".equals(str(map(fees.get(0)).get("maksullisuustyyppi")))
                && map(fees.get(0)).get("maksunMaara") instanceof Number amount && amount.doubleValue() > 0
                && amount.doubleValue() == Math.rint(amount.doubleValue())) {
            fee = String.valueOf(amount.longValue());
        } else if (!fees.isEmpty()) {
            issues.add("fee is not a single annual tuition fee: not imported");
        }
        Localized feeText = localized(opetus.get("maksullisuusKuvaus"));
        boolean liable = feeText != null && "en".equals(feeText.language()) && FEE_LIABLE.matcher(feeText.value()).find();
        // start term: the implementation's own statement and every application round of it must agree
        Set<String> starts = new TreeSet<>();
        String own = startTerm(map(opetus.get("koulutuksenAlkamiskausi")));
        if (own != null) starts.add(own);
        for (Object h : list(routesRoot.get("haut"))) {
            String s = startTerm(map(map(map(h).get("metadata")).get("koulutuksenAlkamiskausi")));
            if (s != null) starts.add(s);
        }
        String startTerm = starts.size() == 1 ? starts.iterator().next() : null;
        if (starts.size() > 1) {
            issues.add("the record states different start terms " + starts + ": no academic year for the fee");
        }
        String academicYear = null;
        if (startTerm != null && startTerm.startsWith("autumn ")) {
            int year = Integer.parseInt(startTerm.substring(7));
            academicYear = year + "/" + (year + 1);
        }
        // scholarship: a stated range in euros or as a share of the tuition fee
        String scholarship = null;
        Map<String, Object> grant = map(opetus.get("apuraha"));
        if (Boolean.TRUE.equals(opetus.get("onkoApuraha")) && grant.get("min") instanceof Number min && grant.get("max") instanceof Number max
                && min.doubleValue() >= 0 && max.doubleValue() >= min.doubleValue()) {
            String u = str(grant.get("yksikko"));
            String range = min.longValue() == max.longValue() ? String.valueOf(max.longValue()) : min.longValue() + "–" + max.longValue();
            if ("euro".equals(u)) scholarship = "Scholarship of €" + range.replaceAll("(\\d)(?=(\\d{3})+(\\D|$))", "$1,") + " available";
            else if ("prosentti".equals(u)) scholarship = "Tuition-fee scholarship of " + range + "% available";
        }
        return new Details(titles.isEmpty() ? null : String.join(" + ", titles), credits,
                modes.isEmpty() ? null : String.join(", ", modes), fee, academicYear, startTerm, liable, scholarship);
    }

    /** "autumn 2027" / "spring 2028" from a coded start term (kausi_s = autumn, kausi_k = spring); null otherwise. */
    private static String startTerm(Map<String, Object> start) {
        String season = unversioned(str(map(start.get("koulutuksenAlkamiskausi")).get("koodiUri")));
        String year = str(start.get("koulutuksenAlkamisvuosi"));
        if (year == null || !year.matches("\\d{4}")) return null;
        if ("kausi_s".equals(season)) return "autumn " + year;
        if ("kausi_k".equals(season)) return "spring " + year;
        return null;
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
        Details d = p.details();
        if (d != null) {
            // an annual fee is published only with its stated audience and the academic year of a stated intake
            if (d.feeAmount() != null && d.feeLiableStated() && d.feeAcademicYear() != null) {
                out.add(ExtractedFact.of(FactType.TUITION, d.feeAcademicYear() + "|INTERNATIONAL", Map.of(
                        "studentCategory", "INTERNATIONAL", "amount", d.feeAmount(), "currency", "EUR", "billingPeriod", "PER_YEAR",
                        "academicYear", d.feeAcademicYear(),
                        "scopeNote", "Annual tuition fee for fee-liable (non-EU/EEA) students; first academic year of the "
                                + d.feeStartTerm() + " intake (Studyinfo)")));
            }
            Map<String, String> structured = new java.util.LinkedHashMap<>();
            if (d.degreeTitles() != null) structured.put("qualification", d.degreeTitles());
            if (d.credits() != null) structured.put("credits", d.credits());
            if (d.studyMode() != null) structured.put("study_mode", d.studyMode());
            if (d.scholarship() != null) structured.put("scholarship", d.scholarship());
            for (var e : structured.entrySet()) {
                out.add(ExtractedFact.of(FactType.UNIVERSITY_FACT, e.getKey() + "|" + STUDYINFO_SCOPE, Map.of("field", e.getKey(),
                        "value", e.getValue(), "educationOid", p.koulutusOid(), "scope", STUDYINFO_SCOPE)));
            }
        }
        return out;
    }

    /** Scope of the structured facts this connector adds next to the university pages' own facts. */
    public static final String STUDYINFO_SCOPE = "studyinfo";

    // ---- level and field --------------------------------------------------------------------------------------

    private static String degreeLevel(Map<String, Object> root, List<String> issues,boolean combinedEntry) {
        Set<String> codes = new TreeSet<>();
        for (Object k : list(root.get("koulutuskoodienAlatJaAsteet"))) {
            for (Object c : list(map(k).get("koulutusasteKoodiUrit"))) {
                Matcher m = c instanceof String s ? LEVEL.matcher(s) : null;
                if (m != null && m.find()) {
                    codes.add(m.group(1));
                }
            }
        }
        if(combinedEntry && codes.equals(Set.of("6","7"))) return "BACHELOR";
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
    private static String[] field(Map<String, Object> root, FieldTaxonomy taxonomy, List<String> issues, String reviewedField) {
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
        if (slugs.size() > 1 && reviewedField != null && hit.containsKey(reviewedField)) {
            FieldTaxonomy.Entry e = hit.get(reviewedField);
            return new String[] {e.slug(), e.code(), e.label()};
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

    private static String unversioned(String codeUri) {
        return codeUri == null ? null : codeUri.replaceFirst("#.*$", "");
    }

    /** See {@link #parse(String, FieldTaxonomy, String, boolean, String, List)}. */
    private static boolean bachelorEntryByEvidence(Map<String, Object> root, String implementationOid, List<String> reviewedTitleCodes,
                                                   List<String> issues) {
        Set<String> titles = new TreeSet<>();
        for (Object t : list(map(root.get("metadata")).get("tutkintonimike"))) {
            titles.add(unversioned(str(map(t).get("koodiUri"))));
        }
        if (!titles.equals(new TreeSet<>(reviewedTitleCodes.stream().map(StudyinfoParser::unversioned).toList()))) {
            issues.add("degree titles " + titles + " differ from the reviewed " + reviewedTitleCodes + ": entry level needs review");
            return false;
        }
        int targets = 0;
        for (Object o : list(root.get("hakukohteet"))) {
            Map<String, Object> target = map(o);
            if (!implementationOid.equals(str(target.get("toteutusOid"))) || !"julkaistu".equals(str(target.get("tila")))) {
                continue;
            }
            targets++;
            Set<String> requirements = new TreeSet<>();
            for (Object r : list(target.get("pohjakoulutusvaatimus"))) {
                requirements.add(unversioned(str(map(r).get("koodiUri"))));
            }
            if (!requirements.contains(GENERAL_UNIVERSITY_ELIGIBILITY) || requirements.stream().anyMatch(PRIOR_DEGREE_REQUIREMENTS::contains)) {
                issues.add("application target " + str(target.get("oid")) + " does not state a bachelor's entry requirement: entry level needs review");
                return false;
            }
        }
        if (targets == 0) {
            issues.add("reviewed implementation has no published application target: entry level needs review");
        }
        return targets > 0;
    }

    /**
     * Deadlines of one reviewed implementation, one per application route. Targets of the same round that close on the
     * same date form one route labelled with the round's name (the existing label). Targets of one round that close on
     * different dates (for example two admission groups) each become a route; the label is the part of the target's own
     * published name that the other targets of the round do not share, followed by the round's name.
     */
    private static List<Deadline> routeDeadlines(Map<String, Object> root, List<String> issues) {
        Map<String, Map<String, Object>> rounds = new LinkedHashMap<>();
        for (Object h : list(root.get("haut"))) {
            if ("julkaistu".equals(str(map(h).get("tila"))) && str(map(h).get("oid")) != null) {
                rounds.put(str(map(h).get("oid")), map(h));
            }
        }
        Map<String, List<Map<String, Object>>> targetsByRound = new LinkedHashMap<>();
        Map<String, String> closing = new LinkedHashMap<>();
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
                issues.add("application target " + str(target.get("oid")) + " has no single closing date: not imported");
                continue;
            }
            closing.put(str(target.get("oid")), ends.iterator().next());
            targetsByRound.computeIfAbsent(str(target.get("hakuOid")), k -> new ArrayList<>()).add(target);
        }
        List<Deadline> out = new ArrayList<>();
        for (var e : targetsByRound.entrySet()) {
            Localized roundName = localized(rounds.get(e.getKey()).get("nimi"));
            if (roundName == null) {
                issues.add("application round " + e.getKey() + " has no usable name for a label: not imported");
                continue;
            }
            Set<String> dates = new TreeSet<>();
            e.getValue().forEach(t -> dates.add(closing.get(str(t.get("oid")))));
            if (dates.size() == 1) {
                out.add(new Deadline(e.getKey(), fitLabel(roundName.value()), dates.iterator().next()));
                continue;
            }
            for (Map<String, Object> target : e.getValue()) {
                String route = distinguishingSegment(target, e.getValue());
                if (route == null) {
                    issues.add("application target " + str(target.get("oid")) + " cannot be told apart by its published name: not imported");
                    continue;
                }
                out.add(new Deadline(str(target.get("oid")), fitLabel(route + " · " + roundName.value()),
                        closing.get(str(target.get("oid")))));
            }
        }
        Set<String> labels = new java.util.HashSet<>();
        for (Deadline d : out) {
            if (!labels.add(d.label())) {
                issues.add("two application routes shorten to the same label '" + d.label() + "': routes not imported");
                return List.of();
            }
        }
        out.sort(Comparator.comparing(Deadline::date).thenComparing(Deadline::label));
        return out;
    }

    /** Labels are at most 100 characters (the intake key). A longer published name keeps its start and its end. */
    static String fitLabel(String label) {
        return label.length() <= 100 ? label : label.substring(0, 60).strip() + " … " + label.substring(label.length() - 37).strip();
    }

    /** The single comma-separated segment of a target's published name that no other target of the same round has. */
    private static String distinguishingSegment(Map<String, Object> target, List<Map<String, Object>> sameRound) {
        Localized own = localized(target.get("nimi"));
        if (own == null) {
            return null;
        }
        Set<String> others = new LinkedHashSet<>();
        for (Map<String, Object> t : sameRound) {
            Localized n = t == target ? null : localized(t.get("nimi"));
            if (n != null) {
                for (String s : n.value().split(",")) others.add(s.strip());
            }
        }
        List<String> unique = new ArrayList<>();
        for (String s : own.value().split(",")) {
            if (!s.isBlank() && !others.contains(s.strip())) unique.add(s.strip());
        }
        return unique.size() == 1 ? unique.get(0) : null;
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
