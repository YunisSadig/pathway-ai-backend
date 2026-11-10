package com.pathwayai.backend.university.repository;

import static com.pathwayai.backend.support.SyntheticFixtures.countryA;
import static com.pathwayai.backend.support.SyntheticFixtures.countryB;
import static com.pathwayai.backend.support.SyntheticFixtures.program;
import static com.pathwayai.backend.support.SyntheticFixtures.university;
import static com.pathwayai.backend.university.repository.UniversitySpecifications.fetchCountry;
import static com.pathwayai.backend.university.repository.UniversitySpecifications.inCountry;
import static com.pathwayai.backend.university.repository.UniversitySpecifications.nameContains;
import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.country.repository.CountryRepository;
import com.pathwayai.backend.program.repository.DegreeProgramRepository;
import com.pathwayai.backend.university.entity.University;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class UniversityRepositoryIT {

    @Autowired CountryRepository countries;
    @Autowired UniversityRepository universities;
    @Autowired DegreeProgramRepository programs;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void seed() {
        var a = countries.save(countryA());
        var b = countries.save(countryB());
        universities.save(university(a, "alpha-institute", "Alpha Institute", "Aville"));
        universities.save(university(a, "beta-university", "Beta University", "Bville"));
        universities.save(university(b, "gamma-college", "Gamma College", "Cville"));
        universities.save(university(b, "percent-100-uni", "100% Real_Name University", "Dville"));
        universities.flush();
    }

    private Specification<University> spec(String q, String country) {
        return Specification.where(fetchCountry()).and(nameContains(q)).and(inCountry(country));
    }

    private static PageRequest byName(int page, int size) {
        return PageRequest.of(page, size, Sort.by("name").and(Sort.by("id")));
    }

    @Test
    void searchIsCaseInsensitiveAndMatchesSubstring() {
        Page<University> result = universities.findAll(spec("UNIVERSITY", null), byName(0, 10));
        assertThat(result.getContent()).extracting(University::getSlug)
                .containsExactly("percent-100-uni", "beta-university");
    }

    @Test
    void likeWildcardsInUserInputAreTreatedLiterally() {
        assertThat(universities.findAll(spec("%", null), byName(0, 10)).getContent())
                .extracting(University::getSlug).containsExactly("percent-100-uni");
        assertThat(universities.findAll(spec("_", null), byName(0, 10)).getContent())
                .extracting(University::getSlug).containsExactly("percent-100-uni");
    }

    @Test
    void filtersByCountryCodeIgnoringCase() {
        Page<University> result = universities.findAll(spec(null, "xb"), byName(0, 10));
        assertThat(result.getContent()).extracting(University::getSlug)
                .containsExactly("percent-100-uni", "gamma-college");
    }

    @Test
    void combinesNameAndCountryFilters() {
        Page<University> result = universities.findAll(spec("college", "XB"), byName(0, 10));
        assertThat(result.getContent()).extracting(University::getSlug).containsExactly("gamma-college");
        assertThat(universities.findAll(spec("college", "XA"), byName(0, 10)).getContent()).isEmpty();
    }

    @Test
    void paginatesWithCorrectTotals() {
        Page<University> first = universities.findAll(spec(null, null), byName(0, 3));
        Page<University> second = universities.findAll(spec(null, null), byName(1, 3));
        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).hasSize(3);
        assertThat(second.getContent()).hasSize(1);
    }

    @Test
    void sortsDescending() {
        Page<University> result = universities.findAll(spec(null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "name").and(Sort.by("id"))));
        assertThat(result.getContent()).extracting(University::getName)
                .containsExactly("Gamma College", "Beta University", "Alpha Institute", "100% Real_Name University");
    }

    @Test
    void findBySlugLoadsCountryAndPrograms() {
        University alpha = universities.findBySlug("alpha-institute").orElseThrow();
        programs.saveAndFlush(program(alpha, "synthetic-bsc", "Synthetic BSc"));
        // Same transaction => same persistence context; clear it so the entity graph is really loaded.
        entityManager.clear();

        University loaded = universities.findBySlug("alpha-institute").orElseThrow();
        assertThat(loaded.getCountry().getIso2Code()).isEqualTo("XA");
        assertThat(loaded.getPrograms()).extracting("slug").contains("synthetic-bsc");
        assertThat(universities.findBySlug("does-not-exist")).isEmpty();
    }
}
