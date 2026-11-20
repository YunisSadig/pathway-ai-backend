package com.pathwayai.backend.provider.polon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pathwayai.backend.provider.studyinfo.FieldTaxonomy;
import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Recorded POL-on responses (2026-11-20); no network. Nothing here is a university fee or deadline: POL-on does not state any. */
class PolonParserTest {
    static final FieldTaxonomy TAXONOMY = new FieldTaxonomy();
    static final PolonSources SOURCES = new PolonSources();

    static String fixture(String courseUuid) throws IOException {
        return new String(new ClassPathResource("polon/" + courseUuid + ".json").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    static PolonSources.Candidate first(String key) {
        return SOURCES.candidates().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void everyRecordedCandidateParsesToTheReviewedLevelAndInstance() throws IOException {
        for (var c : SOURCES.candidates()) {
            var p = PolonParser.parse(fixture(c.courseUuid()), c, TAXONOMY);
            assertThat(p.degreeLevel()).as(c.key()).isEqualTo(c.entryLevel());
            assertThat(p.instanceUuid()).as(c.key()).isEqualTo(c.courseInstanceUuid());
            assertThat(p.name()).as(c.key()).isEqualTo(PolonParser.englishName(c.polonName())).doesNotContain("/");
            assertThat(p.semesters()).as(c.key()).isBetween(1, 12);
            var types = PolonParser.toFacts(p).stream().map(f -> f.type()).toList();
            assertThat(types).as(c.key()).contains(FactType.PROGRAM_NAME, FactType.DEGREE_LEVEL, FactType.DURATION_MONTHS, FactType.LANGUAGES)
                    .doesNotContain(FactType.TUITION, FactType.DEADLINE, FactType.REQUIREMENT);
        }
    }

    @Test
    void aghComputerScienceStatesWhatThePortalStates() throws IOException {
        var c = first("akademia-computer-science-bachelor");
        var p = PolonParser.parse(fixture(c.courseUuid()), c, TAXONOMY);
        assertThat(p.name()).isEqualTo("Computer Science");
        assertThat(p.semesters()).isEqualTo(7);
        assertThat(p.ects()).isEqualTo(210);
        assertThat(p.startDate()).isEqualTo("2019-10-01");
        assertThat(PolonParser.toFacts(p)).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(FactType.DURATION_MONTHS);
            assertThat(f.values()).containsEntry("months", "42");
        });
    }

    @Test
    void aBilingualPolOnNameIsStoredAsItsEnglishPartOnlyAndOtherNamesAreKeptAsStated() throws IOException {
        assertThat(PolonParser.englishName("Sztuczna inteligencja/Artificial Intelligence")).isEqualTo("Artificial Intelligence");
        assertThat(PolonParser.englishName("Computer Science")).isEqualTo("Computer Science");
        assertThat(PolonParser.englishName("A/B/C")).isEqualTo("A/B/C");
        assertThat(PolonParser.englishName("Informatyka/")).isEqualTo("Informatyka/");
        var c = first("politechnika-sztuczna-inteligencja-artificial-intelligence-bachelor");
        assertThat(PolonParser.parse(fixture(c.courseUuid()), c, TAXONOMY).name()).isEqualTo("Artificial Intelligence");
    }

    @Test
    void aLevelThatDiffersFromTheReviewedOneIsRefused() throws IOException {
        var c = first("akademia-computer-science-bachelor");
        var wrong = new PolonSources.Candidate(c.key(), c.university(), c.courseUuid(), c.courseInstanceUuid(), "MASTER",
                c.programUrl(), c.feesUrl(), true, c.polonName(), c.officialDomain(), c.pageMatch(), c.feeStrategy(), c.feeRow());
        assertThatThrownBy(() -> PolonParser.parse(fixture(c.courseUuid()), wrong, TAXONOMY)).hasMessageContaining("differs");
    }

    @Test
    void aMissingInstanceAnEmptyAnswerAndAForeignCourseAreRefusedNeverEmpty() throws IOException {
        var c = first("akademia-computer-science-bachelor");
        var other = new PolonSources.Candidate(c.key(), c.university(), c.courseUuid(), "00000000-0000-0000-0000-000000000000",
                c.entryLevel(), c.programUrl(), c.feesUrl(), true, c.polonName(), c.officialDomain(), c.pageMatch(), c.feeStrategy(), c.feeRow());
        assertThatThrownBy(() -> PolonParser.parse(fixture(c.courseUuid()), other, TAXONOMY)).hasMessageContaining("no longer listed");
        assertThatThrownBy(() -> PolonParser.parse("{\"results\":[]}", c, TAXONOMY)).hasMessageContaining("0 courses");
        assertThatThrownBy(() -> PolonParser.parse("{}", c, TAXONOMY)).hasMessageContaining("0 courses");
        assertThatThrownBy(() -> PolonParser.parse(fixture(first("politechnika-computer-science-bachelor").courseUuid()), c, TAXONOMY))
                .hasMessageContaining("another course");
    }

    @Test
    void discontinuationIsOnlyEverPolOnsExplicitStatus() throws IOException {
        var c = first("akademia-computer-science-bachelor");
        String active = fixture(c.courseUuid());
        assertThat(PolonParser.discontinuation(active, c)).isEmpty();
        assertThat(PolonParser.discontinuation("{\"results\":[]}", c)).isEmpty(); // an empty answer is a failure, not a closure
        assertThat(PolonParser.discontinuation("{\"results\":5}", c)).isEmpty();
        String closed = active.replaceFirst("\"currentStatusCode\":\"3\"", "\"currentStatusCode\":\"4\"").replaceFirst("\"currentStatusName\":\"prowadzone\"", "\"currentStatusName\":\"likwidowane\"");
        assertThat(PolonParser.discontinuation(closed, c)).hasValueSatisfying(r -> assertThat(r).contains("likwidowane"));
        assertThatThrownBy(() -> PolonParser.parse(closed, c, TAXONOMY)).hasMessageContaining("not active");
    }

    @Test
    void anUnmappedIscedCodeLeavesTheFieldUnstatedNeverGuessed() {
        assertThat(PolonParser.field("9999", TAXONOMY)).isEmpty();
        assertThat(PolonParser.field("09", TAXONOMY)).isEmpty();
    }
}
