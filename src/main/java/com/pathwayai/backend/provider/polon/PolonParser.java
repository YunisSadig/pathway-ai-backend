package com.pathwayai.backend.provider.polon;

import com.pathwayai.backend.provider.studyinfo.FieldTaxonomy;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one POL-on course record (the ministry's open data, {@code /opendata/polon/courses}). It states identity, level,
 * ISCED field, and per instance the teaching language, number of semesters, ECTS, form, start date and status. It does
 * NOT state tuition, requirements, deadlines or a programme page: those stay unknown here. A response that does not have
 * the expected shape, or does not describe exactly the reviewed course and instance, is refused (never an empty result).
 */
public final class PolonParser {
    public static final String SCHEME_COURSE = "POLON_COURSE";
    public static final String SCHEME_INSTANCE = "POLON_COURSE_INSTANCE";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One reviewed English-language instance of a POL-on course. */
    public record Program(String courseUuid, String instanceUuid, String courseCode, String name, String degreeLevel,
                          int semesters, Integer ects, String form, String startDate, String institution, String city,
                          String iscedCode, String iscedName, String fieldSlug, String lastRefresh) { }

    private PolonParser() { }

    public static Program parse(String body, PolonSources.Candidate c, FieldTaxonomy taxonomy) {
        JsonNode root = JSON.readTree(body);
        JsonNode results = root.path("results");
        if (!results.isArray() || results.size() != 1)
            throw new IllegalStateException("POL-on answered with " + (results.isArray() ? results.size() : 0) + " courses for " + c.courseUuid());
        JsonNode course = results.get(0);
        if (!c.courseUuid().equals(course.path("courseUuid").asText()))
            throw new IllegalStateException("POL-on returned another course than " + c.courseUuid());
        if (!"3".equals(course.path("currentStatusCode").asText()) || !course.path("liquidationDate").isNull())
            throw new IllegalStateException("POL-on states the course is not active (" + course.path("currentStatusName").asText() + ")");
        String level = switch (course.path("levelName").asText()) {
            case "pierwszego stopnia" -> "BACHELOR";
            case "drugiego stopnia" -> "MASTER";
            default -> throw new IllegalStateException("Unsupported POL-on level: " + course.path("levelName").asText());
        };
        if (!level.equals(c.entryLevel())) throw new IllegalStateException("POL-on level " + level + " differs from the reviewed " + c.entryLevel());
        JsonNode instance = null;
        for (JsonNode i : course.path("courseInstances"))
            if (c.courseInstanceUuid().equals(i.path("courseInstanceUuid").asText())) instance = i;
        if (instance == null) throw new IllegalStateException("The reviewed instance is no longer listed by POL-on: " + c.courseInstanceUuid());
        if (!"eng".equals(instance.path("languageCode").asText()))
            throw new IllegalStateException("The reviewed instance is not taught in English any more");
        if (!"3".equals(instance.path("statusCode").asText()) || !instance.path("liquidationDate").isNull())
            throw new IllegalStateException("POL-on states the instance is not active (" + instance.path("statusName").asText() + ")");
        String name = englishName(instance.path("courseName").asText());
        int semesters = Integer.parseInt(instance.path("numberOfSemesters").asText());
        if (name.isBlank() || semesters <= 0) throw new IllegalStateException("Name or number of semesters missing for " + c.courseUuid());
        String ects = instance.path("ects").asText("");
        String isced = course.path("iscedCode").asText("");
        return new Program(c.courseUuid(), c.courseInstanceUuid(), course.path("courseCode").asText(), name, level, semesters,
                ects.isBlank() ? null : Integer.valueOf(ects), instance.path("formName").asText(),
                instance.path("educationStartDate").asText(null), course.path("leadingInstitutionName").asText(),
                course.path("leadingInstitutionCity").asText(), isced, course.path("iscedName").asText(),
                field(isced, taxonomy).map(FieldTaxonomy.Entry::slug).orElse(null), course.path("lastRefresh").asText());
    }

    /**
     * POL-on sometimes states an English instance's name in both languages as "Polish name/English name". The English part is
     * used then (exactly one "/", both parts non-blank); any other name is kept exactly as stated.
     */
    static String englishName(String stated) {
        int i = stated.indexOf('/');
        if (i > 0 && i == stated.lastIndexOf('/') && !stated.substring(i + 1).isBlank()) return stated.substring(i + 1).strip();
        return stated.strip();
    }

    /** ISCED-F code exactly as POL-on states it; only if the reviewed field map has the detailed or its narrow code. */
    static Optional<FieldTaxonomy.Entry> field(String isced, FieldTaxonomy taxonomy) {
        if (isced.length() < 3 || isced.equals("9999")) return Optional.empty();
        var detailed = taxonomy.lookup(isced);
        return detailed.isPresent() ? detailed : taxonomy.lookup(isced.substring(0, 3));
    }

    /** Facts POL-on states; a fact it does not state is simply absent. A semester is six months (POL-on states semesters). */
    public static List<ExtractedFact> toFacts(Program p) {
        List<ExtractedFact> out = new ArrayList<>();
        out.add(ExtractedFact.of(FactType.PROGRAM_NAME, "", Map.of("name", p.name())));
        out.add(ExtractedFact.of(FactType.DEGREE_LEVEL, "", Map.of("level", p.degreeLevel())));
        if (p.fieldSlug() != null) out.add(ExtractedFact.of(FactType.ACADEMIC_FIELD, "", Map.of("field", p.fieldSlug())));
        out.add(ExtractedFact.of(FactType.DURATION_MONTHS, "", Map.of("months", String.valueOf(p.semesters() * 6))));
        out.add(ExtractedFact.of(FactType.LANGUAGES, "", Map.of("codes", "en")));
        return out;
    }

    /** Only POL-on's own statement that the course or the instance is no longer run counts; an error never does. */
    public static Optional<String> discontinuation(String body, PolonSources.Candidate c) {
        JsonNode results = JSON.readTree(body).path("results");
        if (!results.isArray() || results.size() != 1) return Optional.empty();
        JsonNode course = results.get(0);
        if (!"3".equals(course.path("currentStatusCode").asText()) || !course.path("liquidationDate").isNull())
            return Optional.of("POL-on: course status " + course.path("currentStatusName").asText());
        for (JsonNode i : course.path("courseInstances"))
            if (c.courseInstanceUuid().equals(i.path("courseInstanceUuid").asText())
                    && (!"3".equals(i.path("statusCode").asText()) || !i.path("liquidationDate").isNull()))
                return Optional.of("POL-on: instance status " + i.path("statusName").asText());
        return Optional.empty();
    }
}
