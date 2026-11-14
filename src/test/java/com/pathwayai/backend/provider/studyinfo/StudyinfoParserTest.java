package com.pathwayai.backend.provider.studyinfo;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import com.pathwayai.backend.refresh.pipeline.FactNormalizer;
import com.pathwayai.backend.refresh.pipeline.FactValidator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parser against a real, recorded Studyinfo response (see src/test/resources/studyinfo/README.md). Every variant
 * below is derived from it in code and is a controlled modification, never something the provider published.
 */
class StudyinfoParserTest {

    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final FieldTaxonomy TAXONOMY = FieldTaxonomy.of(Map.of(
            "061", new FieldTaxonomy.Entry("061", "computer-and-data-science", "Information and Communication Technologies (ICT)")));

    static String recorded() {
        try (InputStream in = StudyinfoParserTest.class.getResourceAsStream("/studyinfo/oulu-computer-science-engineering-master.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static String modified(Consumer<Map<String, Object>> change) {
        Map<String, Object> root = MAPPER.readValue(recorded(), Map.class);
        change.accept(root);
        return MAPPER.writeValueAsString(root);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> first(Map<String, Object> root, String listKey) {
        return (Map<String, Object>) ((List<Object>) root.get(listKey)).get(0);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> opetus(Map<String, Object> toteutus) {
        return (Map<String, Object>) ((Map<String, Object>) toteutus.get("metadata")).get("opetus");
    }

    static StudyinfoParser.Parsed parse(String json) {
        return StudyinfoParser.parse(json, TAXONOMY);
    }

    @Test
    void readsEveryStatedFactFromARealRecord() {
        var parsed = parse(recorded());
        assertThat(parsed.issues()).isEmpty();
        var p = parsed.program();
        assertThat(p.koulutusOid()).isEqualTo("1.2.246.562.13.00000000000000000973");
        assertThat(p.toteutusOid()).isEqualTo("1.2.246.562.17.00000000000000001637");
        assertThat(p.organisationName()).isEqualTo("University of Oulu");
        assertThat(p.organisationOid()).isEqualTo("1.2.246.562.10.38417217288");
        assertThat(p.name()).isEqualTo("Computer Science and Engineering, Master of Science (Technology) (2 years)");
        assertThat(p.nameLanguage()).isEqualTo("en");
        assertThat(p.degreeLevel()).isEqualTo("MASTER");
        assertThat(p.fieldSlug()).isEqualTo("computer-and-data-science");
        assertThat(p.fieldCode()).isEqualTo("061");
        assertThat(p.durationMonths()).isEqualTo(24);
        assertThat(p.languages()).containsExactly("en");
        assertThat(p.campusCity()).isEqualTo("Oulu");
        assertThat(p.deadlines()).singleElement().satisfies(d -> {
            assertThat(d.label()).isEqualTo("Joint Application to Higher Education, Spring 2027");
            assertThat(d.date()).isEqualTo("2027-01-21");
        });
    }

    @Test
    void everyFactItProducesPassesTheExistingValidator() {
        var facts = StudyinfoParser.toFacts(parse(recorded()).program());
        assertThat(facts).extracting(ExtractedFact::type).containsExactlyInAnyOrder(FactType.PROGRAM_NAME, FactType.DEGREE_LEVEL,
                FactType.ACADEMIC_FIELD, FactType.CAMPUS_CITY, FactType.DURATION_MONTHS, FactType.LANGUAGES, FactType.DEADLINE);
        for (ExtractedFact f : facts) {
            assertThat(FactValidator.validate(FactNormalizer.normalize(f), true, LocalDate.of(2026, 11, 14)))
                    .as(f.type().name()).isEmpty();
        }
    }

    @Test
    void parsingTheSameRecordTwiceGivesIdenticalFacts() {
        assertThat(StudyinfoParser.toFacts(parse(recorded()).program())).isEqualTo(StudyinfoParser.toFacts(parse(recorded()).program()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFieldCodeOutsideTheReviewedMappingYieldsNoFieldInsteadOfAGuess() {
        var parsed = StudyinfoParser.parse(recorded(), FieldTaxonomy.of(Map.of()));
        assertThat(parsed.program().fieldSlug()).isNull();
        assertThat(parsed.issues()).anyMatch(i -> i.contains("not in the reviewed mapping"));
        assertThat(StudyinfoParser.toFacts(parsed.program())).extracting(ExtractedFact::type).doesNotContain(FactType.ACADEMIC_FIELD);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aChangedFieldCodeIsParsedToTheNewTaxonomyField() {
        var taxonomy = FieldTaxonomy.of(Map.of(
                "061", new FieldTaxonomy.Entry("061", "computer-and-data-science", "ICT"),
                "054", new FieldTaxonomy.Entry("054", "mathematics-and-statistics", "Mathematics and statistics")));
        String json = modified(root -> {
            Map<String, Object> k = first(root, "koulutuskoodienAlatJaAsteet");
            k.put("koulutusalaKoodiUrit", List.of("kansallinenkoulutusluokitus2016koulutusalataso1_05",
                    "kansallinenkoulutusluokitus2016koulutusalataso2_054"));
        });
        assertThat(StudyinfoParser.parse(json, taxonomy).program().fieldSlug()).isEqualTo("mathematics-and-statistics");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bachelorAndMasterLevelsComeFromTheOfficialLevelCodes() {
        for (var e : Map.of("6", "BACHELOR", "7", "MASTER").entrySet()) {
            String json = modified(root -> first(root, "koulutuskoodienAlatJaAsteet").put("koulutusasteKoodiUrit",
                    List.of("kansallinenkoulutusluokitus2016koulutusastetaso1_" + e.getKey())));
            assertThat(parse(json).program().degreeLevel()).isEqualTo(e.getValue());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void doctoralOrAmbiguousLevelsAreNeverGuessed() {
        String doctoral = modified(root -> first(root, "koulutuskoodienAlatJaAsteet").put("koulutusasteKoodiUrit",
                List.of("kansallinenkoulutusluokitus2016koulutusastetaso1_8")));
        assertThat(parse(doctoral).program().degreeLevel()).isNull();
        String both = modified(root -> first(root, "koulutuskoodienAlatJaAsteet").put("koulutusasteKoodiUrit",
                List.of("kansallinenkoulutusluokitus2016koulutusastetaso1_6", "kansallinenkoulutusluokitus2016koulutusastetaso1_7")));
        var parsed = parse(both);
        assertThat(parsed.program().degreeLevel()).isNull();
        assertThat(parsed.issues()).anyMatch(i -> i.contains("not stated unambiguously"));
    }

    @Test
    void anUnpublishedOrNonUniversityRecordIsRefused() {
        assertThat(parse(modified(r -> r.put("tila", "arkistoitu"))).program()).isNull();
        assertThat(parse(modified(r -> r.put("koulutustyyppi", "amk"))).program()).isNull();
        assertThat(parse("not json").program()).isNull();
        assertThat(parse("[]").program()).isNull();
    }

    @Test
    void durationIsWholeMonthsFromYearsAndMonthsAndAbsentWhenNotStated() {
        assertThat(parse(modified(r -> {
            opetus(first(r, "toteutukset")).put("suunniteltuKestoVuodet", 1);
            opetus(first(r, "toteutukset")).put("suunniteltuKestoKuukaudet", 6);
        })).program().durationMonths()).isEqualTo(18);
        assertThat(parse(modified(r -> {
            opetus(first(r, "toteutukset")).put("suunniteltuKestoVuodet", 0);
            opetus(first(r, "toteutukset")).put("suunniteltuKestoKuukaudet", 0);
        })).program().durationMonths()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void implementationsThatDisagreeProduceNoGuessedValue() {
        String json = modified(r -> {
            Map<String, Object> second = MAPPER.readValue(MAPPER.writeValueAsString(first(r, "toteutukset")), Map.class);
            second.put("oid", "1.2.246.562.17.00000000000000009999");
            opetus(second).put("suunniteltuKestoVuodet", 3);
            ((List<Object>) r.get("toteutukset")).add(second);
        });
        var parsed = parse(json);
        assertThat(parsed.program().durationMonths()).isNull();
        assertThat(parsed.program().toteutusOid()).isNull();
        assertThat(parsed.issues()).anyMatch(i -> i.contains("published implementations"));
    }

    @Test
    void aTeachingLanguageOutsideTheReviewedTableOmitsTheWholeLanguageList() {
        String json = modified(r -> opetus(first(r, "toteutukset")).put("opetuskieli", List.of(
                Map.of("koodiUri", "oppilaitoksenopetuskieli_4#2", "nimi", Map.of("en", "English")),
                Map.of("koodiUri", "oppilaitoksenopetuskieli_9#1", "nimi", Map.of("en", "Other language")))));
        assertThat(parse(json).program().languages()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anApplicationRoundWithoutASingleClosingDateIsNotImported() {
        String rolling = modified(r -> {
            ((Map<String, Object>) ((List<Object>) first(r, "haut").get("hakuajat")).get(0)).put("paattyy", null);
        });
        var parsed = parse(rolling);
        assertThat(parsed.program().deadlines()).isEmpty();
        assertThat(parsed.issues()).anyMatch(i -> i.contains("no single closing date"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRoundThatIsNotPublishedContributesNoDeadline() {
        String json = modified(r -> first(r, "haut").put("tila", "tallennettu"));
        assertThat(parse(json).program().deadlines()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTrailingIntakeTermIsNotPartOfTheProgramName() {
        for (String suffix : new String[] {", Autumn 2027", " Spring 2028", ", fall 2026"}) {
            String json = modified(r -> first(r, "toteutukset").put("nimi", Map.of("en", "Master's Programme in Data Science" + suffix)));
            assertThat(parse(json).program().name()).isEqualTo("Master's Programme in Data Science");
        }
        // a year inside the name, or an intake word that is not trailing, is left alone
        String inside = modified(r -> first(r, "toteutukset").put("nimi", Map.of("en", "Spring 2027 Seminar Programme")));
        assertThat(parse(inside).program().name()).isEqualTo("Spring 2027 Seminar Programme");
    }

    @Test
    void slugsDropApostrophesInsteadOfSplittingTheWord() {
        assertThat(StudyinfoImporter.slugify("Master's Programme in Data Science")).isEqualTo("masters-programme-in-data-science");
        assertThat(StudyinfoImporter.slugify("Master’s Programme in Data Science")).isEqualTo("masters-programme-in-data-science");
    }

    @Test
    void slugsAreLowerCaseHyphenatedAndNeverContainDoubleHyphens() {
        // parenthesised qualifiers such as "(2 years)" are dropped from the slug, not from the stored official name
        assertThat(StudyinfoImporter.slugify("Computer Science and Engineering, Master of Science (Technology) (2 years)"))
                .isEqualTo("computer-science-and-engineering-master-of-science");
        assertThat(StudyinfoImporter.slugify("Master’s Programme in Data Science -- Ålesund")).doesNotContain("--").matches("[a-z0-9]+(-[a-z0-9]+)*");
        assertThat(StudyinfoImporter.slugify("!!!")).isEqualTo("program");
    }
}
