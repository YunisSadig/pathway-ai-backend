package com.pathwayai.backend.common.discovery;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Public discovery lists only VERIFIED programs of VERIFIED universities whose sources were read successfully
 * recently; everything else is held back, not deleted. Synthetic data only.
 */
@SpringBootTest(properties = {"app.discovery.restrict-to-verified-fresh=true", "app.discovery.max-source-age-days=45"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DiscoveryVisibilityIT {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SourceMonitorRepository monitors;

    @BeforeEach
    void seed() {
        jdbc.update("insert into country (iso2_code, name) values ('ZD', 'Discoverland')");
        long country = jdbc.queryForObject("select id from country where iso2_code = 'ZD'", Long.class);
        long good = university(country, "zz-disc-u", "VERIFIED");
        long unverified = university(country, "zz-disc-unv", "UNVERIFIED");
        Instant now = Instant.now();
        program(good, "fresh", "VERIFIED", now.minus(2, ChronoUnit.DAYS));
        program(good, "stale", "VERIFIED", now.minus(90, ChronoUnit.DAYS));
        program(good, "unverified-program", "UNVERIFIED", now.minus(1, ChronoUnit.DAYS));
        program(good, "never-checked", "VERIFIED", null);
        program(unverified, "at-unverified-university", "VERIFIED", now.minus(1, ChronoUnit.DAYS));
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from source_check");
        jdbc.update("delete from source_monitor where domain = 'www.zz-disc.example'");
        jdbc.update("delete from university where slug like 'zz-disc-%'");
        jdbc.update("delete from source where url like 'https://www.zz-disc.example/%'");
        jdbc.update("delete from country where iso2_code = 'ZD'");
    }

    long university(long country, String slug, String status) {
        return jdbc.queryForObject("""
                insert into university (country_id, slug, name, verification_status, verified_on)
                values (?, ?, ?, ?, case when ? = 'UNVERIFIED' then null else current_date end) returning id""",
                Long.class, country, slug, "Synthetic " + slug, status, status);
    }

    void program(long university, String slug, String status, Instant lastSuccess) {
        long id = jdbc.queryForObject("""
                insert into degree_program (university_id, slug, name, degree_level, verification_status, verified_on)
                values (?, ?, ?, 'MASTER', ?, case when ? = 'VERIFIED' then current_date else null end) returning id""",
                Long.class, university, slug, "Synthetic " + slug, status, status);
        if (lastSuccess != null) {
            long source = jdbc.queryForObject("""
                    insert into source (url, title, publisher, source_type, retrieved_on)
                    values (?, 'Synthetic', 'Synthetic', 'UNIVERSITY_WEBSITE', current_date) returning id""",
                    Long.class, "https://www.zz-disc.example/" + slug);
            monitors.register(source, new SourceMonitor.Spec("https://www.zz-disc.example/" + slug, university, id, "PROGRAM", "",
                    "CONTENT_HASH", "content-hash", "1", 7), "VERIFIED", Instant.now());
            jdbc.update("update source_monitor set last_success_at = ? where source_id = ?", java.sql.Timestamp.from(lastSuccess), source);
        }
    }

    @Test
    void onlyVerifiedFreshProgramsOfVerifiedUniversitiesAreListed() throws Exception {
        mockMvc.perform(get("/api/v1/programs").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.identifier =~ /zz-disc.*/)]", hasSize(1)))
                .andExpect(jsonPath("$.content[?(@.identifier == 'zz-disc-u--fresh')]", hasSize(1)));
    }

    @Test
    void aHeldBackProgramIsNotReachableByItsIdentifierEither() throws Exception {
        mockMvc.perform(get("/api/v1/programs/zz-disc-u--fresh")).andExpect(status().isOk());
        for (String hidden : new String[] {"zz-disc-u--stale", "zz-disc-u--unverified-program", "zz-disc-u--never-checked",
                "zz-disc-unv--at-unverified-university"}) {
            mockMvc.perform(get("/api/v1/programs/" + hidden)).andExpect(status().isNotFound());
        }
    }

    @Test
    void anUnverifiedUniversityIsNotListedButItsDataIsNotDeleted() throws Exception {
        mockMvc.perform(get("/api/v1/universities").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.slug == 'zz-disc-u')]", hasSize(1)))
                .andExpect(jsonPath("$.content[?(@.slug == 'zz-disc-unv')]", hasSize(0)));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from degree_program where slug like 'at-unverified%'", Long.class)).isEqualTo(1);
    }

    @Test
    void aProgramReturnsToDiscoveryAsSoonAsItsSourceIsReadSuccessfullyAgain() throws Exception {
        mockMvc.perform(get("/api/v1/programs/zz-disc-u--stale")).andExpect(status().isNotFound());
        jdbc.update("update source_monitor set last_success_at = now() where program_id = (select id from degree_program where slug = 'stale')");
        mockMvc.perform(get("/api/v1/programs/zz-disc-u--stale")).andExpect(status().isOk());
    }
}
