package com.pathwayai.backend.provider.polon;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Recorded official pages (2026-11-20); no network. A fee is extracted only where the page states it unambiguously. */
class PolishPageAdapterTest {
    static final PolonSources SOURCES = new PolonSources();

    static String page(String name) throws IOException {
        return new String(new ClassPathResource("polon/pages/" + name).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    static PolonSources.Candidate candidate(String key) {
        return SOURCES.candidates().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    static ExtractedFact one(java.util.List<ExtractedFact> facts, FactType type, String field) {
        return facts.stream().filter(f -> f.type() == type && (field == null || field.equals(f.values().get("field")))).findFirst().orElseThrow();
    }

    @Test
    void aghFeeIsASemesterFeeInEuroForTheBachelorColumnWithThePrintedAcademicYear() throws IOException {
        var c = candidate("akademia-computer-science-bachelor");
        var facts = PolishPageAdapter.extract(page("agh-fees.html"), c.feesUrl(), c.feesUrl(), c);
        var fee = one(facts, FactType.TUITION, null);
        assertThat(fee.values()).containsEntry("amount", "3000").containsEntry("currency", "EUR").containsEntry("billingPeriod", "PER_SEMESTER")
                .containsEntry("studentCategory", "INTERNATIONAL").containsEntry("academicYear", "2026/2027");
        assertThat(one(facts, FactType.UNIVERSITY_FACT, "financial_url").values()).containsEntry("value", c.feesUrl());
    }

    @Test
    void aProgrammesOwnPageIsStoredAsItsOfficialLinkButAnOfferListIsNot() throws IOException {
        var ml = candidate("uniwersytet-machine-learning-master");
        var facts = PolishPageAdapter.extract(page("uw-irk-ml-programme.html"), ml.programUrl(), ml.programUrl(), ml);
        assertThat(one(facts, FactType.UNIVERSITY_FACT, "official_program_url").values()).containsEntry("value", ml.programUrl());
        // AGH's programme page is the bachelor offer list (h1 "Education offer - Bachelor studies"): not the programme's page
        var agh = candidate("akademia-computer-science-bachelor");
        assertThat(PolishPageAdapter.extract(page("agh-programme.html"), agh.programUrl(), agh.programUrl(), agh)).isEmpty();
        assertThat(PolishPageAdapter.extract("<html><body><h1>Another page</h1></body></html>", ml.programUrl(), ml.programUrl(), ml))
                .as("a page that does not name the programme proves nothing").isEmpty();
        assertThat(PolishPageAdapter.extract("<html><body><h1>Machine Learning</h1><h1>Second</h1></body></html>", ml.programUrl(), ml.programUrl(), ml))
                .as("exactly one h1").isEmpty();
    }

    @Test
    void aPageOnAnotherDomainOrAnUnregisteredUrlYieldsNothing() throws IOException {
        var c = candidate("akademia-computer-science-bachelor");
        assertThat(PolishPageAdapter.extract(page("agh-fees.html"), "https://evil.example/fees", c.feesUrl(), c)).isEmpty();
        assertThat(PolishPageAdapter.extract(page("agh-fees.html"), c.feesUrl(), "https://www.international.agh.edu.pl/other", c)).isEmpty();
    }

    @Test
    void aMasterCandidateNeverReadsTheBachelorColumn() throws IOException {
        var c = candidate("akademia-computer-science-bachelor");
        var master = new PolonSources.Candidate(c.key(), c.university(), c.courseUuid(), c.courseInstanceUuid(), "MASTER", c.programUrl(), c.feesUrl(),
                true, c.polonName(), c.officialDomain(), c.pageMatch(), c.feeStrategy(), c.feeRow());
        // Computer Science has no master fee on the page ("not available"), so nothing is stated
        assertThat(PolishPageAdapter.extract(page("agh-fees.html"), c.feesUrl(), c.feesUrl(), master)).noneMatch(f -> f.type() == FactType.TUITION);
    }

    @Test
    void poznanUtFeeIsKeptWordForWordWithTheYearItsHeadingPrints() throws IOException {
        for (var entry : java.util.Map.of("politechnika-sztuczna-inteligencja-artificial-intelligence-bachelor", "put-first-cycle-fees.html",
                "politechnika-sztuczna-inteligencja-artificial-intelligence-master", "put-second-cycle-fees.html").entrySet()) {
            var c = candidate(entry.getKey());
            var facts = PolishPageAdapter.extract(page(entry.getValue()), c.feesUrl(), c.feesUrl(), c);
            var fee = one(facts, FactType.UNIVERSITY_FACT, "tuition_scope");
            assertThat(fee.values().get("value")).as(entry.getKey()).contains("EUR").contains("PLN").contains("semester");
            assertThat(fee.values()).containsEntry("academicYear", "2026-2027"); // the column format of university facts
        }
    }

    @Test
    void poznanUebFeeIsCalledCurrentByThePageSoNoYearIsStored() throws IOException {
        var c = candidate("uniwersytet-master-in-international-business-master");
        var facts = PolishPageAdapter.extract(page("uep-masters.html"), c.programUrl(), c.programUrl(), c);
        var fee = one(facts, FactType.UNIVERSITY_FACT, "tuition_scope");
        assertThat(fee.values().get("value")).contains("7 350 PLN per semester").contains("current");
        assertThat(fee.values()).doesNotContainKey("academicYear");
        assertThat(facts).as("the masters landing page is not the programme's page").noneMatch(f -> "official_program_url".equals(f.values().get("field")));
        assertThat(one(facts, FactType.UNIVERSITY_FACT, "financial_url").values()).containsEntry("value", c.programUrl());
    }

    @Test
    void everyEmittedUrlIsOwnedByTheUniversityAndEveryFactTypeIsSupported() throws IOException {
        var c = candidate("akademia-computer-science-bachelor");
        for (var f : PolishPageAdapter.extract(page("agh-fees.html"), c.feesUrl(), c.feesUrl(), c)) {
            assertThat(f.type()).isIn(FactType.UNIVERSITY_FACT, FactType.TUITION);
            if (f.type() == FactType.UNIVERSITY_FACT && f.values().get("field").endsWith("url"))
                assertThat(PolonSources.owned(f.values().get("value"), c.university())).isTrue();
        }
        assertThat(PolonSources.ownedByAny("https://www.international.agh.edu.pl/x")).isTrue();
        assertThat(PolonSources.ownedByAny("http://www.international.agh.edu.pl/x")).isFalse();
        assertThat(PolonSources.ownedByAny("https://agh.edu.pl.evil.example/x")).isFalse();
    }
}
