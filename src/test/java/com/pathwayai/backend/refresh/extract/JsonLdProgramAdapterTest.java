package com.pathwayai.backend.refresh.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonLdProgramAdapterTest {

    final JsonLdProgramAdapter adapter = new JsonLdProgramAdapter();

    List<ExtractedFact> extract(String... jsonLdBlocks) {
        return adapter.extract(new CanonicalPage("t", "text", "hash", List.of(jsonLdBlocks)));
    }

    @Test
    void readsDurationAndLanguagesFromACourseBlock() {
        var facts = extract("{\"@context\":\"https://schema.org\",\"@type\":\"Course\",\"timeToComplete\":\"P2Y\",\"inLanguage\":[\"en\",\"fi\"]}");
        assertThat(facts).extracting(ExtractedFact::type).containsExactly(FactType.DURATION_MONTHS, FactType.LANGUAGES);
        assertThat(facts.get(0).values()).containsEntry("months", "24");
        assertThat(facts.get(1).values()).containsEntry("codes", "en,fi");
    }

    @Test
    void understandsMonthsAndMixedDurationsAndGraphs() {
        assertThat(extract("{\"@type\":\"EducationalOccupationalProgram\",\"timeToComplete\":\"P18M\"}").get(0).values())
                .containsEntry("months", "18");
        assertThat(extract("{\"@type\":\"Course\",\"timeToComplete\":\"P1Y6M\"}").get(0).values()).containsEntry("months", "18");
        assertThat(extract("{\"@graph\":[{\"@type\":\"Organization\"},{\"@type\":\"Course\",\"timeToComplete\":\"P3Y\"}]}").get(0).values())
                .containsEntry("months", "36");
    }

    @Test
    void daysAndWeeksAreNotConvertedToMonthsByGuessing() {
        assertThat(extract("{\"@type\":\"Course\",\"timeToComplete\":\"P90D\"}")).isEmpty();
        assertThat(extract("{\"@type\":\"Course\",\"timeToComplete\":\"P1Y10D\"}")).isEmpty();
        assertThat(extract("{\"@type\":\"Course\",\"timeToComplete\":\"two years\"}")).isEmpty();
        assertThat(extract("{\"@type\":\"Course\",\"timeToComplete\":\"P0Y\"}")).isEmpty();
    }

    @Test
    void aLanguageNameIsNotMappedToACode() {
        assertThat(extract("{\"@type\":\"Course\",\"inLanguage\":\"English\"}")).isEmpty();
        var facts = extract("{\"@type\":\"Course\",\"inLanguage\":[\"en-GB\",\"Finnish\"]}");
        assertThat(facts.get(0).values()).containsEntry("codes", "en");
    }

    @Test
    void ignoresOtherTypesMalformedJsonAndNoBlocks() {
        assertThat(extract("{\"@type\":\"Organization\",\"timeToComplete\":\"P2Y\"}")).isEmpty();
        assertThat(extract("{not json")).isEmpty();
        assertThat(extract()).isEmpty();
    }

    @Test
    void neverReadsTuitionDeadlinesOrRequirements() {
        var facts = extract("{\"@type\":\"Course\",\"offers\":{\"price\":\"9000\",\"priceCurrency\":\"EUR\"},\"timeToComplete\":\"P2Y\"}");
        assertThat(facts).extracting(ExtractedFact::type).containsOnly(FactType.DURATION_MONTHS);
    }
}
