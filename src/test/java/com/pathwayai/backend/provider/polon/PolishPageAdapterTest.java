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
        assertThat(PolishPageAdapter.extract(page("agh-programme.html"), agh.programUrl(), agh.programUrl(), agh))
                .noneMatch(f -> "official_program_url".equals(f.values().get("field")));
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

    @Test
    void lodzFeeIsPerSemesterInPlnForTheEnglishFullTimeRowOfTheProgrammesCycleOnly() throws IOException {
        for (var key : java.util.List.of("politechnika-computer-science-bachelor", "politechnika-information-technology-bachelor",
                "politechnika-computer-science-and-information-technology-master")) {
            var c = candidate(key);
            var fee = one(PolishPageAdapter.extract(page("tul-fees.html"), c.feesUrl(), c.feesUrl(), c), FactType.TUITION, null);
            assertThat(fee.values()).as(key).containsEntry("amount", "9000").containsEntry("currency", "PLN")
                    .containsEntry("billingPeriod", "PER_SEMESTER").containsEntry("academicYear", "2026/2027").containsEntry("studentCategory", "INTERNATIONAL");
        }
        // the Polish-language "Informatyka" rows (5900 PLN) are a different programme and never match an English name
        var c = candidate("politechnika-computer-science-bachelor");
        var wrongCycle = new PolonSources.Candidate(c.key(), c.university(), c.courseUuid(), c.courseInstanceUuid(), "MASTER", c.programUrl(), c.feesUrl(),
                true, c.polonName(), c.officialDomain(), c.pageMatch(), c.feeStrategy(), c.feeRow());
        assertThat(PolishPageAdapter.extract(page("tul-fees.html"), c.feesUrl(), c.feesUrl(), wrongCycle)).noneMatch(f -> f.type() == FactType.TUITION);
    }

    @Test
    void aghProgrammeBlockGivesDurationAndAnUndatedRecruitmentWindowOnlyForTheExactlyNamedAccordionItem() throws IOException {
        var c = candidate("akademia-computer-science-bachelor");
        var facts = PolishPageAdapter.extract(page("agh-programme.html"), c.programUrl(), c.programUrl(), c);
        assertThat(one(facts, FactType.UNIVERSITY_FACT, "duration_structure").values()).containsEntry("value", "7 semesters (3,5 years)");
        var window = one(facts, FactType.UNIVERSITY_FACT, "application_period").values();
        assertThat(window.get("value")).contains("June - September").contains("October 1").contains("no year");
        assertThat(window).doesNotContainKey("academicYear");
    }

    @Test
    void warsawIrkPageStatesDegreeCampusAdmissionEnglishRequirementAndDatedDeadlines() throws IOException {
        var c = candidate("uniwersytet-machine-learning-master");
        var facts = PolishPageAdapter.extract(page("uw-irk-ml-programme.html"), c.programUrl(), c.programUrl(), c);
        assertThat(one(facts, FactType.UNIVERSITY_FACT, "qualification").values()).containsEntry("value", "Magister (MSc)");
        assertThat(one(facts, FactType.UNIVERSITY_FACT, "study_mode").values()).containsEntry("value", "Full-time");
        assertThat(one(facts, FactType.UNIVERSITY_FACT, "campus").values().get("value")).contains("Ochota Campus");
        assertThat(one(facts, FactType.UNIVERSITY_FACT, "selection").values().get("value")).contains("45%");
        assertThat(one(facts, FactType.UNIVERSITY_FACT, "language_tests").values().get("value")).contains("B2");
        var deadlines = one(facts, FactType.UNIVERSITY_FACT, "application_period").values();
        assertThat(deadlines.get("value")).contains("1st of July, 2026").contains("31st of July, 2026");
        assertThat(deadlines).containsEntry("academicYear", "2026-2027");
        assertThat(com.pathwayai.backend.provider.universitypages.UniversityProgramAdapter.periodEnd(deadlines.get("value")))
                .isEqualTo(java.time.LocalDate.of(2026, 7, 31));
    }

    @Test
    void sghProgrammePageGivesModeDurationAndTheTuitionSentenceWithoutInventingAYear() throws IOException {
        var c = candidate("szkola-international-business-master");
        var facts = PolishPageAdapter.extract(page("sgh-im-programme.html"), c.programUrl(), c.programUrl(), c);
        assertThat(one(facts, FactType.UNIVERSITY_FACT, "study_mode").values()).containsEntry("value", "Full-time");
        assertThat(one(facts, FactType.UNIVERSITY_FACT, "duration_structure").values()).containsEntry("value", "2 years (4 semesters)");
        var fee = one(facts, FactType.UNIVERSITY_FACT, "tuition_scope").values();
        assertThat(fee.get("value")).contains("2400 EUR").contains("no academic year");
        assertThat(fee).doesNotContainKey("academicYear");
    }

    @Test
    void linkMonitorOnlyVerifiesAPageThatStillIsTheReviewedProgramme() throws IOException {
        var ml = candidate("uniwersytet-machine-learning-master");
        assertThat(PolishLinkMonitor.stillTheProgramme(page("uw-irk-ml-programme.html"), ml.programUrl(), ml.programUrl(), ml)).isTrue();
        assertThat(PolishLinkMonitor.stillTheProgramme("<html><body><h1>Page not found</h1></body></html>", ml.programUrl(), ml.programUrl(), ml))
                .as("an HTTP 200 that is another page proves nothing").isFalse();
        assertThat(PolishLinkMonitor.stillTheProgramme(page("uw-irk-ml-programme.html"), "https://evil.example/x", ml.programUrl(), ml)).isFalse();
    }

    static java.util.List<ExtractedFact> read(String key, String url, String fixture) throws IOException {
        var c = candidate(key);
        return PolishPageAdapter.extract(page(fixture), url, url, c);
    }

    @Test
    void everyAdmissionsPageIsStoredOnlyWhenItStillCarriesTheReviewedTitle() throws IOException {
        var pages = java.util.Map.of(
                "akademia-computer-science-bachelor", "agh-recruitment.html",
                "politechnika-computer-science-bachelor", "tul-enroll.html",
                "politechnika-sztuczna-inteligencja-artificial-intelligence-bachelor", "put-admission.html",
                "uniwersytet-machine-learning-master", "uw-admissions.html",
                "szkola-international-business-master", "sgh-admission.html",
                "uniwersytet-master-in-international-business-master", "pueb-masters-2026-11-21.html");
        for (var e : pages.entrySet()) {
            var c = candidate(e.getKey());
            var fact = one(read(e.getKey(), c.admissionsUrl(), e.getValue()), FactType.UNIVERSITY_FACT, "official_admissions_url");
            assertThat(fact.values()).as(e.getKey()).containsEntry("value", c.admissionsUrl());
            assertThat(PolonSources.owned(c.admissionsUrl(), c.university())).isTrue();
            assertThat(PolishPageAdapter.extract("<html><head><title>Page not found</title></head><body>x</body></html>", c.admissionsUrl(), c.admissionsUrl(), c))
                    .as("another page proves nothing: " + e.getKey()).noneMatch(f -> "official_admissions_url".equals(f.values().get("field")));
        }
        assertThat(candidate("uniwersytet-international-business-bachelor").admissionsUrl()).as("UEK is unsupported").isNull();
    }

    @Test
    void aghBachelorRequirementsComeFromTheBachelorPagesWithTheirOwnSourceAndYear() throws IOException {
        var c = candidate("akademia-computer-science-bachelor");
        var base = "https://www.international.agh.edu.pl/en/studies/recruitment/";
        var period = one(read(c.key(), base + "deadlines-bachelor-studies", "agh-deadlines-bachelor.html"), FactType.UNIVERSITY_FACT, "application_period").values();
        assertThat(period.get("value")).contains("Recruitment for the academic year 2027/2028 will start in June 2027");
        assertThat(period).containsEntry("academicYear", "2027-2028");
        var rules = read(c.key(), base + "recruitment-rules-bachelor", "agh-rules-bachelor.html");
        assertThat(one(rules, FactType.UNIVERSITY_FACT, "selection").values().get("value")).contains("300 points").contains("Bachelor (first-cycle)");
        assertThat(one(rules, FactType.UNIVERSITY_FACT, "language_tests").values().get("value")).contains("You must provide the language document");
        assertThat(one(read(c.key(), base + "required-documents-bachelor", "agh-documents-bachelor.html"), FactType.UNIVERSITY_FACT, "documents").values().get("value"))
                .contains("secondary school-leaving certificate").doesNotContain("Master");
        // the level guard: a page that is not the bachelor page yields nothing
        assertThat(read(c.key(), base + "deadlines-bachelor-studies", "agh-recruitment.html")).noneMatch(f -> "application_period".equals(f.values().get("field")));
    }

    @Test
    void lodzEnglishRequirementAppliesToEveryProgrammeButTheEntranceExamOnlyToBachelors() throws IOException {
        for (var key : java.util.List.of("politechnika-computer-science-bachelor", "politechnika-computer-science-and-information-technology-master")) {
            var lang = one(read(key, "https://apply.p.lodz.pl/en/enrollment/enroll/language-requirements", "tul-language.html"), FactType.UNIVERSITY_FACT, "language_tests");
            assertThat(lang.values().get("value")).as(key).startsWith("You can apply for programmes taught in English if you know English at least at the B2 level");
        }
        var exams = read("politechnika-computer-science-bachelor", "https://apply.p.lodz.pl/en/enrollment/enroll/exams", "tul-exams.html");
        assertThat(one(exams, FactType.UNIVERSITY_FACT, "entrance_tests").values().get("value")).contains("minimum score of 60%");
        assertThat(read("politechnika-computer-science-and-information-technology-master", "https://apply.p.lodz.pl/en/enrollment/enroll/exams", "tul-exams.html"))
                .as("the first-cycle exam is not stated for a master's programme").noneMatch(f -> "entrance_tests".equals(f.values().get("field")));
    }

    @Test
    void poznanUebMastersPageGivesDocumentsAndEnglishRequirementForTheMastersProgramme() throws IOException {
        var c = candidate("uniwersytet-master-in-international-business-master");
        var facts = read(c.key(), c.programUrl(), "pueb-masters-2026-11-21.html");
        assertThat(one(facts, FactType.UNIVERSITY_FACT, "documents").values().get("value")).startsWith("a signed application").contains("diploma");
        assertThat(one(facts, FactType.UNIVERSITY_FACT, "language_tests").values().get("value")).startsWith("Accepted documents certifying knowledge of English");
    }
}
