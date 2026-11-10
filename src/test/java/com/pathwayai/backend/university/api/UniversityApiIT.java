package com.pathwayai.backend.university.api;

import static com.pathwayai.backend.support.SyntheticFixtures.countryA;
import static com.pathwayai.backend.support.SyntheticFixtures.countryB;
import static com.pathwayai.backend.support.SyntheticFixtures.program;
import static com.pathwayai.backend.support.SyntheticFixtures.university;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.country.repository.CountryRepository;
import com.pathwayai.backend.program.repository.DegreeProgramRepository;
import com.pathwayai.backend.university.repository.UniversityRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Full stack (controller -> service -> JPA -> PostgreSQL). @Transactional rolls the synthetic data back. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class UniversityApiIT {

    @Autowired MockMvc mockMvc;
    @Autowired CountryRepository countries;
    @Autowired UniversityRepository universities;
    @Autowired DegreeProgramRepository programs;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void seed() {
        var a = countries.save(countryA());
        var b = countries.save(countryB());
        universities.save(university(a, "alpha-institute", "Alpha Institute", "Aville"));
        var beta = universities.save(university(a, "beta-university", "Beta University", "Bville"));
        universities.save(university(b, "gamma-college", "Gamma College", "Cville"));
        programs.save(program(beta, "synthetic-bsc", "Synthetic BSc"));
        universities.flush();
        programs.flush();
        // Requests run in this test's transaction; clear so reads hit the database, not stale entities.
        entityManager.clear();
    }

    @Test
    void listsAllSortedByNameByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/universities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].slug").value("alpha-institute"))
                .andExpect(jsonPath("$.content[2].slug").value("gamma-college"))
                .andExpect(jsonPath("$.content[0].countryName").value("Synthetic Land A"));
    }

    @Test
    void searchesByNameCaseInsensitively() throws Exception {
        mockMvc.perform(get("/api/v1/universities").param("q", "UNIVERSITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("beta-university"));
    }

    @Test
    void filtersByCountry() throws Exception {
        mockMvc.perform(get("/api/v1/universities").param("country", "xb"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].countryCode").value("XB"));
    }

    @Test
    void unknownCountryYieldsEmptyPageNotError() throws Exception {
        mockMvc.perform(get("/api/v1/universities").param("country", "ZZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void paginatesAndSortsDescending() throws Exception {
        mockMvc.perform(get("/api/v1/universities")
                        .param("size", "2").param("page", "1").param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("alpha-institute"))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void sortsByCity() throws Exception {
        mockMvc.perform(get("/api/v1/universities").param("sort", "city").param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].city").value("Cville"));
    }

    @Test
    void detailIncludesCountryAndPrograms() throws Exception {
        mockMvc.perform(get("/api/v1/universities/beta-university"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("XA"))
                .andExpect(jsonPath("$.programs[0].slug").value("synthetic-bsc"))
                .andExpect(jsonPath("$.programs[0].degreeLevel").value("BACHELOR"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void missingUniversityReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/universities/not-there"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void invalidParametersReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/universities").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("size"));
        mockMvc.perform(get("/api/v1/universities").param("sort", "password"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void actuatorHealthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
