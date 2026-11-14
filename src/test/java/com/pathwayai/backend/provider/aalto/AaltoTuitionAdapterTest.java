package com.pathwayai.backend.provider.aalto;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import com.pathwayai.backend.refresh.pipeline.FactNormalizer;
import com.pathwayai.backend.refresh.pipeline.FactValidator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Against a real recorded copy of Aalto's tuition page; every variant is derived from it in code. */
class AaltoTuitionAdapterTest {

    static final List<AaltoTuitionAdapter.Mapping> MAPPINGS = List.of(
            new AaltoTuitionAdapter.Mapping("architecture-msc", "MASTER", "ART_ARCHITECTURE"),
            new AaltoTuitionAdapter.Mapping("mechanical-engineering-msc", "MASTER", "TECHNOLOGY"),
            new AaltoTuitionAdapter.Mapping("bachelor-tech", "BACHELOR", "TECHNOLOGY"));
    final AaltoTuitionAdapter adapter = new AaltoTuitionAdapter(MAPPINGS);

    static String html() {
        try (InputStream in = AaltoTuitionAdapterTest.class.getResourceAsStream("/aalto/tuition-fees.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Replaces only the first occurrence (the Technology row of the master table precedes the Multidisciplinary one). */
    static String first(String text, String target, String replacement) {
        int i = text.indexOf(target);
        if (i < 0) {
            throw new IllegalStateException("fixture does not contain: " + target);
        }
        return text.substring(0, i) + replacement + text.substring(i + target.length());
    }

    static CanonicalPage page(String html) {
        return new PageCanonicalizer().canonicalize(html, "text/html");
    }

    Map<String, List<ExtractedFact>> read(String html) {
        return adapter.extractForPrograms(page(html));
    }

    static Map<String, String> only(List<ExtractedFact> facts) {
        assertThat(facts).hasSize(1);
        return facts.get(0).values();
    }

    @Test
    void readsTheStatedFeePerFieldGroupWithTheStatedPeriod() {
        var facts = read(html());
        assertThat(facts).containsOnlyKeys("architecture-msc", "mechanical-engineering-msc", "bachelor-tech");
        var arch = only(facts.get("architecture-msc"));
        assertThat(arch).containsEntry("amount", "20000").containsEntry("currency", "EUR").containsEntry("billingPeriod", "PER_YEAR")
                .containsEntry("studentCategory", "INTERNATIONAL").containsEntry("effectiveFrom", "2025-08-01");
        assertThat(only(facts.get("mechanical-engineering-msc"))).containsEntry("amount", "17000").containsEntry("effectiveFrom", "2025-08-01");
        assertThat(only(facts.get("bachelor-tech"))).containsEntry("amount", "12000");
        assertThat(arch.get("scopeNote")).contains("outside the EU, EEA and Switzerland").contains("rights to study beginning on or after 2025-08-01");
    }

    @Test
    void theApplicablePeriodIsTheStatedDateNeverTheReadingDate() {
        assertThat(read(html()).values().stream().flatMap(List::stream))
                .allSatisfy(f -> assertThat(f.values().get("effectiveFrom")).isEqualTo("2025-08-01"))
                .allSatisfy(f -> assertThat(f.values()).doesNotContainKey("effectiveTo"))
                .allSatisfy(f -> assertThat(f.values()).doesNotContainKey("academicYear"));
    }

    @Test
    void everyFactPassesTheExistingTuitionValidation() {
        for (var list : read(html()).values()) {
            for (ExtractedFact f : list) {
                assertThat(FactValidator.validate(FactNormalizer.normalize(f), true, LocalDate.of(2026, 11, 14))).isEmpty();
            }
        }
    }

    @Test
    void aProgramThatIsNotInTheReviewedMappingGetsNoFee() {
        assertThat(new AaltoTuitionAdapter(List.of()).extractForPrograms(page(html()))).isEmpty();
    }

    @Test
    void aChangedFeeIsParsedAsTheNewAmount() {
        var facts = read(first(html(), "€17 000 per academic year", "€18 500 per academic year"));
        assertThat(only(facts.get("mechanical-engineering-msc"))).containsEntry("amount", "18500");
        assertThat(only(facts.get("architecture-msc"))).containsEntry("amount", "20000");
    }

    @Test
    void aNewlyStatedPeriodIsAdditionalNotAReplacement() {
        String withNewPeriod = html().replace("</main>", "<div><table><tr><td><h4>Master’s programmes</h4></td><td><h4>"
                + "Tuition fees for rights to study beginning on or after 1 August 2026</h4></td></tr>"
                + "<tr><td>Field of Technology</td><td>€18 000 per academic year</td></tr></table></div></main>");
        var mech = read(withNewPeriod).get("mechanical-engineering-msc");
        assertThat(mech).extracting(f -> f.values().get("effectiveFrom")).containsExactlyInAnyOrder("2025-08-01", "2026-08-01");
        assertThat(mech).extracting(ExtractedFact::key).doesNotHaveDuplicates();
    }

    @Test
    void withoutTheStatementWhoPaysNoFeeIsProduced() {
        String stripped = html().replace("outside the EU, EEA or Switzerland", "from somewhere");
        assertThat(read(stripped)).isEmpty();
    }

    @Test
    void anUnparsableDateOrAmountIsLeftOutNotRepaired() {
        assertThat(read(html().replace("1 August 2025", "1 Augsut 2025"))).isEmpty();
        var oddAmount = read(first(html(), "€17 000 per academic year", "€17,5 per academic year"));
        assertThat(oddAmount).doesNotContainKey("mechanical-engineering-msc");
        assertThat(oddAmount).containsKey("architecture-msc");
    }

    @Test
    void theSameFieldGroupTwiceInOneTableIsAmbiguousAndStatesNothing() {
        // a second, different fee for the same group directly after the master's one
        String twice = first(html(), "€17 000 per academic year</td>",
                "€17 000 per academic year</td></tr><tr><td>Field of Technology</td><td>€19 000 per academic year</td>");
        var facts = read(twice);
        assertThat(facts).doesNotContainKey("mechanical-engineering-msc");
    }

    @Test
    void parsingTwiceGivesIdenticalFacts() {
        assertThat(read(html())).isEqualTo(read(html()));
    }
}
