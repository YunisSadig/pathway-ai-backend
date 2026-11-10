package com.pathwayai.backend.database;

import static com.pathwayai.backend.support.SyntheticFixtures.countryA;
import static com.pathwayai.backend.support.SyntheticFixtures.program;
import static com.pathwayai.backend.support.SyntheticFixtures.source;
import static com.pathwayai.backend.support.SyntheticFixtures.university;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.country.entity.Country;
import com.pathwayai.backend.country.repository.CountryRepository;
import com.pathwayai.backend.program.entity.DegreeProgram;
import com.pathwayai.backend.program.repository.DegreeProgramRepository;
import com.pathwayai.backend.source.entity.Source;
import com.pathwayai.backend.source.repository.SourceRepository;
import com.pathwayai.backend.tuition.entity.BillingPeriod;
import com.pathwayai.backend.tuition.entity.StudentCategory;
import com.pathwayai.backend.tuition.entity.TuitionFee;
import com.pathwayai.backend.tuition.repository.TuitionFeeRepository;
import com.pathwayai.backend.university.entity.University;
import com.pathwayai.backend.university.repository.UniversityRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/** Verifies that the Flyway schema (not Hibernate) enforces integrity, and that the JPA mapping validates. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class SchemaConstraintsIT {

    @Autowired CountryRepository countries;
    @Autowired UniversityRepository universities;
    @Autowired DegreeProgramRepository programs;
    @Autowired SourceRepository sources;
    @Autowired TuitionFeeRepository tuitionFees;

    Country country;
    University uni;
    DegreeProgram program;

    @BeforeEach
    void setUp() {
        country = countries.saveAndFlush(countryA());
        uni = universities.saveAndFlush(university(country, "synthetic-uni", "Synthetic University", "Testville"));
        program = programs.saveAndFlush(program(uni, "synthetic-bsc", "Synthetic BSc"));
    }

    @Test
    void persistsAndSetsTimestamps() {
        assertThat(uni.getId()).isNotNull();
        assertThat(uni.getCreatedAt()).isNotNull();
        assertThat(country.getIso2Code()).isEqualTo("XA");
    }

    @Test
    void duplicateCountryCodeIsRejected() {
        assertThatThrownBy(() -> countries.saveAndFlush(new Country("XA", "Another Name")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lowercaseCountryCodeIsRejectedByCheckConstraint() {
        assertThatThrownBy(() -> countries.saveAndFlush(new Country("xc", "Lowercase Land")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateUniversitySlugIsRejected() {
        assertThatThrownBy(() -> universities.saveAndFlush(university(country, "synthetic-uni", "Other", "X")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidSlugFormatIsRejected() {
        assertThatThrownBy(() -> universities.saveAndFlush(university(country, "Bad Slug!", "Other", "X")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameProgramSlugInSameUniversityIsRejectedButAllowedElsewhere() {
        assertThatThrownBy(() -> programs.saveAndFlush(program(uni, "synthetic-bsc", "Duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameProgramSlugInDifferentUniversityIsAllowed() {
        University other = universities.saveAndFlush(university(country, "other-uni", "Other University", "Elsewhere"));
        DegreeProgram sameSlug = programs.saveAndFlush(program(other, "synthetic-bsc", "Synthetic BSc"));
        assertThat(sameSlug.getId()).isNotNull();
    }

    @Test
    void universityRequiresExistingCountry() {
        Country unsaved = new Country("XZ", "Never Saved");
        assertThatThrownBy(() -> universities.saveAndFlush(university(unsaved, "orphan", "Orphan", "X")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void tuitionIsUniquePerProgramYearAndCategory() {
        Source src = sources.saveAndFlush(source("tuition"));
        tuitionFees.saveAndFlush(fee(src, "2026/2027", "100.00"));

        assertThatThrownBy(() -> tuitionFees.saveAndFlush(fee(src, "2026/2027", "200.00")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // Separate test: after a constraint violation PostgreSQL aborts the surrounding transaction.
    @Test
    void tuitionForAnotherYearIsAllowed() {
        Source src = sources.saveAndFlush(source("tuition-years"));
        tuitionFees.saveAndFlush(fee(src, "2026/2027", "100.00"));
        assertThat(tuitionFees.saveAndFlush(fee(src, "2027/2028", "200.00")).getId()).isNotNull();
    }

    @Test
    void tuitionWithoutSourceIsRejected() {
        assertThatThrownBy(() -> tuitionFees.saveAndFlush(fee(null, "2026/2027", "100.00")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void negativeTuitionIsRejected() {
        Source src = sources.saveAndFlush(source("negative"));
        assertThatThrownBy(() -> tuitionFees.saveAndFlush(fee(src, "2026/2027", "-1.00")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateSourceUrlIsRejected() {
        sources.saveAndFlush(source("dup"));
        assertThatThrownBy(() -> sources.saveAndFlush(source("dup")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private TuitionFee fee(Source src, String year, String amount) {
        return new TuitionFee(program, src, year, StudentCategory.INTERNATIONAL, new BigDecimal(amount),
                "XXX", BillingPeriod.PER_YEAR);
    }
}
