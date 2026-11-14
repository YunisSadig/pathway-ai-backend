package com.pathwayai.backend.program.api;

import static com.pathwayai.backend.support.ProgramFixtures.describe;
import static com.pathwayai.backend.support.ProgramFixtures.field;
import static com.pathwayai.backend.support.ProgramFixtures.program;
import static com.pathwayai.backend.support.ProgramFixtures.verified;
import static com.pathwayai.backend.support.ProgramFixtures.withAdmissions;
import static com.pathwayai.backend.support.SyntheticFixtures.countryA;
import static com.pathwayai.backend.support.SyntheticFixtures.countryB;
import static com.pathwayai.backend.support.SyntheticFixtures.source;
import static com.pathwayai.backend.support.SyntheticFixtures.university;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.country.repository.CountryRepository;
import com.pathwayai.backend.field.repository.AcademicFieldRepository;
import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.entity.ProgramIntake;
import com.pathwayai.backend.program.entity.StudyMode;
import com.pathwayai.backend.program.repository.DegreeProgramRepository;
import com.pathwayai.backend.program.repository.ProgramIntakeRepository;
import com.pathwayai.backend.requirement.entity.AcademicRequirement;
import com.pathwayai.backend.requirement.entity.RequirementType;
import com.pathwayai.backend.requirement.repository.AcademicRequirementRepository;
import com.pathwayai.backend.source.repository.SourceRepository;
import com.pathwayai.backend.tuition.entity.BillingPeriod;
import com.pathwayai.backend.tuition.entity.StudentCategory;
import com.pathwayai.backend.tuition.entity.TuitionFee;
import com.pathwayai.backend.tuition.repository.TuitionFeeRepository;
import com.pathwayai.backend.university.repository.UniversityRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full stack (controller -> service -> JPA -> PostgreSQL) for the program resources, with synthetic data only.
 *
 * <pre>
 * alpha-institute (XA): alpha--computing (BSc, field cs, en, VERIFIED + application URL, 1000 EUR intl/yr)
 *                       alpha--physics   (MSc, field stem, en+de, 5000 EUR intl/yr, 700 EUR domestic/yr)
 * beta-university (XA): beta--literature (MSc, field humanities, de, UNVERIFIED, no tuition)
 *                       beta--100-match  (DIPLOMA, no field, name contains % and _)
 * gamma-college   (XB): gamma--computing (BSc, field cs, en, 800 USD intl/yr) - same program slug as alpha
 * fields: stem > cs ; humanities
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ProgramApiIT {

    @Autowired MockMvc mockMvc;
    @Autowired CountryRepository countries;
    @Autowired UniversityRepository universities;
    @Autowired DegreeProgramRepository programs;
    @Autowired AcademicFieldRepository fields;
    @Autowired SourceRepository sources;
    @Autowired TuitionFeeRepository tuitionFees;
    @Autowired ProgramIntakeRepository intakes;
    @Autowired AcademicRequirementRepository requirements;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void seed() {
        var a = countries.save(countryA());
        var b = countries.save(countryB());
        var alpha = universities.save(university(a, "alpha-institute", "Alpha Institute", "Aville"));
        var beta = universities.save(university(a, "beta-university", "Beta University", "Bville"));
        var gamma = universities.save(university(b, "gamma-college", "Gamma College", "Cville"));
        var stem = fields.save(field(null, "stem", "Synthetic STEM"));
        var cs = fields.save(field(stem, "cs", "Synthetic Computing Science"));
        var humanities = fields.save(field(null, "humanities", "Synthetic Humanities"));
        var src = sources.save(source("program-source"));

        var computing = programs.save(verified(
                program(alpha, "computing", "Synthetic Computing", DegreeLevel.BACHELOR, cs, "en"),
                "https://example.invalid/apply/computing"));
        describe(computing, StudyMode.FULL_TIME, 36, "https://example.invalid/programs/computing");
        computing.getSources().add(src);
        var physics = programs.save(program(alpha, "physics", "Synthetic Physics", DegreeLevel.MASTER, stem,
                "en", "de"));
        programs.save(program(beta, "literature", "Synthetic Literature", DegreeLevel.MASTER, humanities, "de"));
        programs.save(program(beta, "100-match", "Synthetic 100%_Match", DegreeLevel.DIPLOMA, null));
        var gamma1 = programs.save(program(gamma, "computing", "Synthetic Computing", DegreeLevel.BACHELOR, cs,
                "en"));

        tuitionFees.save(new TuitionFee(computing, src, "2099/2100", StudentCategory.INTERNATIONAL,
                new BigDecimal("1000.00"), "EUR", BillingPeriod.PER_YEAR));
        tuitionFees.save(new TuitionFee(physics, src, "2099/2100", StudentCategory.INTERNATIONAL,
                new BigDecimal("5000.00"), "EUR", BillingPeriod.PER_YEAR));
        tuitionFees.save(new TuitionFee(physics, src, "2099/2100", StudentCategory.DOMESTIC,
                new BigDecimal("700.00"), "EUR", BillingPeriod.PER_YEAR));
        tuitionFees.save(new TuitionFee(gamma1, src, "2099/2100", StudentCategory.INTERNATIONAL,
                new BigDecimal("800.00"), "USD", BillingPeriod.PER_YEAR));
        intakes.save(new ProgramIntake(computing, src, "Autumn", "2099/2100", null,
                LocalDate.of(2099, 1, 15), null));
        requirements.save(new AcademicRequirement(computing, src, "2099", RequirementType.MIN_GPA,
                "Synthetic minimum grade", new BigDecimal("3.00"), "GPA"));

        // Year-specific facts of every kind: historical, unknown period, start-date range.
        tuitionFees.save(new TuitionFee(programs.findByUniversitySlugAndSlug("beta-university", "100-match")
                .orElseThrow(), src, "2000/2001", StudentCategory.INTERNATIONAL, new BigDecimal("1.00"), "EUR",
                BillingPeriod.PER_YEAR));
        tuitionFees.save(new TuitionFee(programs.findByUniversitySlugAndSlug("beta-university", "100-match")
                .orElseThrow(), src, null, StudentCategory.DOMESTIC, new BigDecimal("2.00"), "EUR",
                BillingPeriod.PER_YEAR, null, null, "Synthetic: the source states no period"));
        tuitionFees.save(new TuitionFee(gamma1, src, null, StudentCategory.DOMESTIC, new BigDecimal("700.00"),
                "USD", BillingPeriod.PER_YEAR, LocalDate.of(2000, 1, 1), null, "Synthetic: rights from 2000-01-01"));
        intakes.save(new ProgramIntake(computing, src, "Old autumn", "2000/2001", null,
                LocalDate.of(2000, 1, 15), null));
        requirements.save(new AcademicRequirement(computing, src, "2000", RequirementType.MIN_GPA,
                "Synthetic old minimum grade", new BigDecimal("2.00"), "GPA"));
        // gamma: its application link was verified for an OLD cycle; the admissions page is informational
        verified(gamma1, "https://example.invalid/apply/old-cycle", "2000");
        withAdmissions(gamma1, "https://example.invalid/admissions/gamma");
        withAdmissions(computing, "https://example.invalid/admissions/computing");

        entityManager.flush();
        // Requests run in this test's transaction; clear so reads hit the database, not stale entities.
        entityManager.clear();
    }

    // ---------- list: basics, filters ----------

    @Test
    void listsAllProgramsSortedByNameWithUniversityAndStableKeys() throws Exception {
        mockMvc.perform(get("/api/v1/programs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.keys()").value(containsInAnyOrder(
                        "content", "number", "size", "totalElements", "totalPages")))
                .andExpect(jsonPath("$.content[0].keys()").value(containsInAnyOrder(
                        "identifier", "slug", "name", "degreeLevel", "field", "studyMode", "durationMonths",
                        "teachingLanguages", "campusCity", "university", "officialProgramUrl",
                        "officialAdmissionsUrl", "officialApplicationUrl", "verification")))
                .andExpect(jsonPath("$.content[0].university.keys()").value(containsInAnyOrder(
                        "slug", "name", "city", "countryCode", "countryName")));
    }

    @Test
    void sameProgramSlugInTwoUniversitiesHasDistinctIdentifiers() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("q", "synthetic computing").param("sort", "university"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].identifier").value("alpha-institute--computing"))
                .andExpect(jsonPath("$.content[1].identifier").value("gamma-college--computing"));
    }

    @Test
    void filtersByDegreeLevelCaseInsensitively() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("level", "bachelor"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].degreeLevel").value(containsInAnyOrder("BACHELOR", "BACHELOR")));
        mockMvc.perform(get("/api/v1/programs").param("level", "MASTER"))
                .andExpect(jsonPath("$.totalElements").value(2));
        // Existing non bachelor/master levels stay supported.
        mockMvc.perform(get("/api/v1/programs").param("level", "diploma"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("100-match"));
        mockMvc.perform(get("/api/v1/programs").param("level", "doctorate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void filtersByAcademicFieldIncludingChildFields() throws Exception {
        // "stem" matches its own programs and those of its child field "cs".
        mockMvc.perform(get("/api/v1/programs").param("field", "stem"))
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(get("/api/v1/programs").param("field", "cs"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/programs").param("field", "humanities"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].field.name").value("Synthetic Humanities"));
        mockMvc.perform(get("/api/v1/programs").param("field", "unknown-field"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void filtersByCountryUniversityAndLanguage() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("country", "xb"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].university.countryCode").value("XB"));
        mockMvc.perform(get("/api/v1/programs").param("university", "beta-university"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/programs").param("language", "de"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/programs").param("language", "EN"))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void combinesFilters() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("level", "bachelor").param("country", "XA")
                        .param("field", "stem").param("language", "en"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].identifier").value("alpha-institute--computing"));
    }

    @Test
    void nameSearchIsCaseInsensitiveAndTreatsWildcardsLiterally() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("q", "PHYSICS"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/programs").param("q", "100%_"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("100-match"));
        mockMvc.perform(get("/api/v1/programs").param("q", "%"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ---------- tuition filter ----------

    @Test
    void tuitionFilterComparesOnlySameCurrencyAndExcludesUnknownTuition() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("tuitionMax", "1000").param("tuitionCurrency", "eur"))
                .andExpect(jsonPath("$.totalElements").value(2)) // computing 1000 EUR and physics domestic 700 EUR
                .andExpect(jsonPath("$.content[*].identifier").value(containsInAnyOrder(
                        "alpha-institute--computing", "alpha-institute--physics")));
        mockMvc.perform(get("/api/v1/programs").param("tuitionMax", "999999").param("tuitionCurrency", "USD"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].identifier").value("gamma-college--computing"));
        // Unknown tuition (beta programs) is never treated as "free", whatever the limit.
        mockMvc.perform(get("/api/v1/programs").param("tuitionMax", "999999").param("tuitionCurrency", "EUR"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void tuitionFilterCanTargetAnApplicantCategory() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("tuitionMax", "1000").param("tuitionCurrency", "EUR")
                        .param("tuitionCategory", "international"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].identifier").value("alpha-institute--computing"));
        mockMvc.perform(get("/api/v1/programs").param("tuitionMax", "1000").param("tuitionCurrency", "EUR")
                        .param("tuitionCategory", "domestic"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].identifier").value("alpha-institute--physics"));
    }

    @Test
    void tuitionParametersMustBeConsistent() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("tuitionMax", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("tuitionCurrency"));
        mockMvc.perform(get("/api/v1/programs").param("tuitionCurrency", "EUR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("tuitionMax"));
        mockMvc.perform(get("/api/v1/programs").param("tuitionCategory", "domestic"))
                .andExpect(status().isBadRequest());
    }

    // ---------- pagination and sorting ----------

    @Test
    void paginatesWithStableTotals() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("size", "2").param("page", "0"))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(0));
        mockMvc.perform(get("/api/v1/programs").param("size", "2").param("page", "2"))
                .andExpect(jsonPath("$.content", hasSize(1)));
        mockMvc.perform(get("/api/v1/programs").param("size", "2").param("page", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void sortsByNameUniversityAndLevel() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("sort", "name").param("direction", "desc"))
                .andExpect(jsonPath("$.content[0].name").value("Synthetic Physics"));
        mockMvc.perform(get("/api/v1/programs").param("sort", "university").param("direction", "desc"))
                .andExpect(jsonPath("$.content[0].university.slug").value("gamma-college"));
        mockMvc.perform(get("/api/v1/programs").param("sort", "level"))
                .andExpect(jsonPath("$.content[0].degreeLevel").value("BACHELOR"));
    }

    // ---------- detail ----------

    @Test
    void detailReturnsFactsEachWithItsOfficialSource() throws Exception {
        mockMvc.perform(get("/api/v1/programs/alpha-institute--computing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys()").value(containsInAnyOrder(
                        "identifier", "slug", "name", "degreeLevel", "field", "studyMode", "durationMonths",
                        "teachingLanguages", "campusCity", "university", "officialProgramUrl",
                        "officialAdmissionsUrl", "officialApplicationUrl", "verification", "sources", "intakes", "tuition",
                        "requirements")))
                .andExpect(jsonPath("$.name").value("Synthetic Computing"))
                .andExpect(jsonPath("$.degreeLevel").value("BACHELOR"))
                .andExpect(jsonPath("$.studyMode").value("FULL_TIME"))
                .andExpect(jsonPath("$.durationMonths").value(36))
                .andExpect(jsonPath("$.teachingLanguages[0]").value("en"))
                .andExpect(jsonPath("$.university.slug").value("alpha-institute"))
                .andExpect(jsonPath("$.field.slug").value("cs"))
                .andExpect(jsonPath("$.officialProgramUrl").value("https://example.invalid/programs/computing"))
                .andExpect(jsonPath("$.officialApplicationUrl").value("https://example.invalid/apply/computing"))
                .andExpect(jsonPath("$.verification.status").value("VERIFIED"))
                .andExpect(jsonPath("$.verification.verifiedOn").value("2026-01-15"))
                .andExpect(jsonPath("$.sources", hasSize(1)))
                .andExpect(jsonPath("$.intakes[0].label").value("Autumn"))
                .andExpect(jsonPath("$.intakes[0].applicationDeadline").value("2099-01-15"))
                .andExpect(jsonPath("$.intakes[0].startDate").value(nullValue()))
                .andExpect(jsonPath("$.intakes[0].source.url").value("https://example.invalid/program-source"))
                .andExpect(jsonPath("$.tuition[0].amount").value(1000.0))
                .andExpect(jsonPath("$.tuition[0].currency").value("EUR"))
                .andExpect(jsonPath("$.tuition[0].billingPeriod").value("PER_YEAR"))
                .andExpect(jsonPath("$.tuition[0].source.retrievedOn").value("2026-01-01"))
                .andExpect(jsonPath("$.requirements[0].type").value("MIN_GPA"))
                .andExpect(jsonPath("$.requirements[0].minimumValue").value(3.0))
                .andExpect(jsonPath("$.requirements[0].source.title").value("Synthetic source program-source"));
    }

    @Test
    void missingDataStaysUnknown() throws Exception {
        mockMvc.perform(get("/api/v1/programs/beta-university--literature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studyMode").value(nullValue()))
                .andExpect(jsonPath("$.durationMonths").value(nullValue()))
                .andExpect(jsonPath("$.campusCity").value(nullValue()))
                .andExpect(jsonPath("$.officialProgramUrl").value(nullValue()))
                .andExpect(jsonPath("$.officialApplicationUrl").value(nullValue()))
                .andExpect(jsonPath("$.verification.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.verification.verifiedOn").value(nullValue()))
                .andExpect(jsonPath("$.sources", hasSize(0)))
                .andExpect(jsonPath("$.intakes", hasSize(0)))
                .andExpect(jsonPath("$.tuition", hasSize(0)))
                .andExpect(jsonPath("$.requirements", hasSize(0)));
        // A program without a field reports null, not a placeholder.
        mockMvc.perform(get("/api/v1/programs/beta-university--100-match"))
                .andExpect(jsonPath("$.field").value(nullValue()))
                .andExpect(jsonPath("$.teachingLanguages", hasSize(0)));
    }

    @Test
    void applicationLinkIsOnlyEverPresentForVerifiedPrograms() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.officialApplicationUrl != null)].verification.status")
                        .value(containsInAnyOrder("VERIFIED")));
    }

    @Test
    void unknownIdentifierIs404() throws Exception {
        mockMvc.perform(get("/api/v1/programs/alpha-institute--does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Program not found: alpha-institute--does-not-exist"));
        mockMvc.perform(get("/api/v1/programs/no-such-university--computing"))
                .andExpect(status().isNotFound());
        // Program slugs are scoped per university: beta has no "computing".
        mockMvc.perform(get("/api/v1/programs/beta-university--computing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedIdentifierIs400() throws Exception {
        for (String bad : new String[] {"computing", "alpha-institute-computing", "Alpha--Computing",
                "alpha--", "--computing", "a---b"}) {
            mockMvc.perform(get("/api/v1/programs/" + bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.violations[0].field").value("identifier"));
        }
    }

    // ---------- invalid parameters ----------

    @Test
    void invalidListParametersAreRejectedWithViolations() throws Exception {
        String[][] bad = {
                {"level", "phd"}, {"size", "0"}, {"size", "101"}, {"page", "-1"}, {"sort", "ranking"},
                {"direction", "sideways"}, {"country", "XXX"}, {"field", "Not A Slug"},
                {"university", "UPPER"}, {"language", "english"}, {"tuitionMax", "-1"},
                {"tuitionCurrency", "EURO"}, {"tuitionCategory", "alien"}, {"q", "x".repeat(101)}};
        for (String[] p : bad) {
            mockMvc.perform(get("/api/v1/programs").param(p[0], p[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.violations[0].field").value(p[0]));
        }
        mockMvc.perform(get("/api/v1/programs").param("size", "abc"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/programs").param("tuitionMax", "cheap").param("tuitionCurrency", "EUR"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyResultIsEmptyPageNotError() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("q", "zzz-no-match"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void programsAreReadOnly() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/programs").contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/programs/alpha-institute--computing"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---------- historical vs current facts ----------

    @Test
    void everyYearSpecificFactSaysWhetherItIsCurrentHistoricalOrOfUnknownPeriod() throws Exception {
        mockMvc.perform(get("/api/v1/programs/alpha-institute--computing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tuition[?(@.academicYear == '2099/2100')].temporalStatus")
                        .value(containsInAnyOrder("CURRENT")))
                .andExpect(jsonPath("$.intakes[?(@.label == 'Autumn')].temporalStatus")
                        .value(containsInAnyOrder("CURRENT")))
                .andExpect(jsonPath("$.intakes[?(@.label == 'Old autumn')].temporalStatus")
                        .value(containsInAnyOrder("HISTORICAL")))
                .andExpect(jsonPath("$.requirements[?(@.applicationCycle == '2099')].temporalStatus")
                        .value(containsInAnyOrder("CURRENT")))
                .andExpect(jsonPath("$.requirements[?(@.applicationCycle == '2000')].temporalStatus")
                        .value(containsInAnyOrder("HISTORICAL")));
        mockMvc.perform(get("/api/v1/programs/beta-university--100-match"))
                .andExpect(jsonPath("$.tuition", hasSize(2)))
                .andExpect(jsonPath("$.tuition[?(@.academicYear == '2000/2001')].temporalStatus")
                        .value(containsInAnyOrder("HISTORICAL")))
                // no year, no range: the API says so and does not pick a year
                .andExpect(jsonPath("$.tuition[?(@.academicYear == null)].temporalStatus")
                        .value(containsInAnyOrder("UNKNOWN_YEAR")))
                .andExpect(jsonPath("$.tuition[?(@.academicYear == null)].scopeNote")
                        .value(containsInAnyOrder("Synthetic: the source states no period")));
        mockMvc.perform(get("/api/v1/programs/gamma-college--computing"))
                .andExpect(jsonPath("$.tuition[?(@.effectiveFrom == '2000-01-01')].temporalStatus")
                        .value(containsInAnyOrder("CURRENT")))
                .andExpect(jsonPath("$.tuition[?(@.effectiveFrom == '2000-01-01')].academicYear")
                        .value(containsInAnyOrder(nullValue())));
    }

    @Test
    void priceFiltersIgnoreHistoricalAndUnknownPeriodFees() throws Exception {
        // 100-match has only a HISTORICAL (1 EUR) and an UNKNOWN_YEAR (2 EUR) fee: neither may match.
        mockMvc.perform(get("/api/v1/programs").param("tuitionMax", "10").param("tuitionCurrency", "EUR"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/programs").param("tuitionMax", "999999").param("tuitionCurrency", "EUR"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void anApplyLinkForAnOldCycleIsNeverPresentedAsActive() throws Exception {
        // computing: verified for cycle 2099 -> active Apply link; gamma: verified for cycle 2000 -> withheld.
        mockMvc.perform(get("/api/v1/programs/alpha-institute--computing"))
                .andExpect(jsonPath("$.officialApplicationUrl").value("https://example.invalid/apply/computing"))
                .andExpect(jsonPath("$.officialAdmissionsUrl").value("https://example.invalid/admissions/computing"));
        mockMvc.perform(get("/api/v1/programs/gamma-college--computing"))
                .andExpect(jsonPath("$.officialApplicationUrl").value(nullValue()))
                // the informational admissions page is still offered, clearly separate from an Apply link
                .andExpect(jsonPath("$.officialAdmissionsUrl").value("https://example.invalid/admissions/gamma"))
                .andExpect(jsonPath("$.verification.status").value("VERIFIED"));
        mockMvc.perform(get("/api/v1/programs").param("country", "XB"))
                .andExpect(jsonPath("$.content[0].officialApplicationUrl").value(nullValue()))
                .andExpect(jsonPath("$.content[0].officialAdmissionsUrl").value("https://example.invalid/admissions/gamma"));
    }

    // ---------- university API regression ----------

    @Test
    void universityEndpointsStillWorkAndListProgramsWithTheirField() throws Exception {
        mockMvc.perform(get("/api/v1/universities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(get("/api/v1/universities/alpha-institute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programs", hasSize(2)))
                .andExpect(jsonPath("$.programs[0].identifier").value("alpha-institute--computing"))
                // The normalised field name wins over the legacy free-text column.
                .andExpect(jsonPath("$.programs[0].fieldOfStudy").value("Synthetic Computing Science"))
                .andExpect(jsonPath("$.programs[0].degreeLevel").value("BACHELOR"));
        // A program without a normalised field falls back to the legacy column (null here).
        mockMvc.perform(get("/api/v1/universities/beta-university"))
                .andExpect(jsonPath("$.programs[0].fieldOfStudy").value(nullValue()));
    }
}
