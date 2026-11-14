package com.pathwayai.backend.program.repository;

import static com.pathwayai.backend.support.ProgramFixtures.field;
import static com.pathwayai.backend.support.ProgramFixtures.program;
import static com.pathwayai.backend.support.SyntheticFixtures.countryA;
import static com.pathwayai.backend.support.SyntheticFixtures.source;
import static com.pathwayai.backend.support.SyntheticFixtures.university;
import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.country.repository.CountryRepository;
import com.pathwayai.backend.field.repository.AcademicFieldRepository;
import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.service.ProgramSearchCriteria;
import com.pathwayai.backend.program.service.ProgramService;
import com.pathwayai.backend.source.repository.SourceRepository;
import com.pathwayai.backend.university.repository.UniversityRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * Measures the number of SQL statements, so N+1 regressions fail a test instead of being guessed at.
 * The count must NOT grow with the number of rows on the page.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(TestcontainersConfiguration.class)
@Transactional
class ProgramQueryCountIT {

    @Autowired ProgramService service;
    @Autowired CountryRepository countries;
    @Autowired UniversityRepository universities;
    @Autowired DegreeProgramRepository programs;
    @Autowired AcademicFieldRepository fields;
    @Autowired SourceRepository sources;
    @Autowired EntityManager entityManager;

    Statistics stats;

    @BeforeEach
    void seed() {
        var country = countries.save(countryA());
        var parent = fields.save(field(null, "p", "Parent"));
        var child = fields.save(field(parent, "c", "Child"));
        var src = sources.save(source("qc"));
        for (int u = 0; u < 5; u++) {
            var uni = universities.save(university(country, "uni-" + u, "Uni " + u, "City"));
            for (int p = 0; p < 6; p++) {
                var prog = programs.save(program(uni, "prog-" + p, "Program " + u + "-" + p,
                        DegreeLevel.MASTER, p % 2 == 0 ? child : parent, "en", "de"));
                prog.getSources().add(src);
            }
        }
        entityManager.flush();
        entityManager.clear();
        stats = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    private long statementsForListOfSize(int size) {
        entityManager.clear();
        stats.clear();
        var page = service.search(ProgramSearchCriteria.none(),
                PageRequest.of(0, size, Sort.by("university.name", "name", "id")));
        assertThat(page.content()).hasSize(size);
        // Touch the lazily batched collection data the mapper already read: it must be loaded, not N queries.
        assertThat(page.content().get(0).teachingLanguages()).containsExactly("de", "en");
        return stats.getPrepareStatementCount();
    }

    @Test
    void listQueryCountDoesNotGrowWithPageSize() {
        long small = statementsForListOfSize(3);
        long large = statementsForListOfSize(25);
        System.out.println("MEASURED program list statements: size=3 -> " + small + ", size=25 -> " + large);
        assertThat(large).as("statements must not depend on rows per page").isEqualTo(small);
        // page query + count query + one batched language query
        assertThat(large).isLessThanOrEqualTo(4);
    }

    @Test
    void filteredListKeepsTheSameBoundedQueryCount() {
        entityManager.clear();
        stats.clear();
        var page = service.search(new ProgramSearchCriteria(null, "p", DegreeLevel.MASTER, "XA", null, "en", null,
                null, null), PageRequest.of(0, 20, Sort.by("name", "id")));
        assertThat(page.totalElements()).isEqualTo(30);
        System.out.println("MEASURED filtered program list statements: " + stats.getPrepareStatementCount());
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(4);
    }

    @Test
    void detailQueryCountIsConstant() {
        entityManager.clear();
        stats.clear();
        var detail = service.getByIdentifier("uni-0--prog-0");
        assertThat(detail.sources()).hasSize(1);
        System.out.println("MEASURED program detail statements: " + stats.getPrepareStatementCount());
        // program+university+country+field in one query, then languages, sources, intakes, tuition, requirements
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(8);
    }
}
