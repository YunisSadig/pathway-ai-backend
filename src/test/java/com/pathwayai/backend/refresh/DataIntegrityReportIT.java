package com.pathwayai.backend.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The read-only audit run against a hosted database after a bootstrap: it must report real violations and nothing secret. Synthetic data only. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DataIntegrityReportIT {

    @Autowired DataIntegrityReport report;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("insert into country (iso2_code, name) values ('ZI', 'Integrityland')");
        long country = jdbc.queryForObject("select id from country where iso2_code = 'ZI'", Long.class);
        long uni = jdbc.queryForObject("""
                insert into university (country_id, slug, name, verification_status, verified_on)
                values (?, 'zz-integrity-u', 'Synthetic Integrity University', 'VERIFIED', current_date) returning id""", Long.class, country);
        jdbc.update("""
                insert into degree_program (university_id, slug, name, degree_level, verification_status, verified_on)
                values (?, 'static-one', 'Synthetic Static Program', 'MASTER', 'VERIFIED', current_date)""", uni);
        jdbc.update("""
                insert into university (country_id, slug, name, verification_status, verified_on)
                values (?, 'zz-integrity-empty', 'Synthetic Empty University', 'VERIFIED', current_date)""", country);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from university where slug like 'zz-integrity-%'");
        jdbc.update("delete from country where iso2_code = 'ZI'");
    }

    @Test
    void reportsAStaticProgramAndAnEmptyUniversityAsViolations() {
        var r = report.run();
        assertThat(r.violations()).anyMatch(v -> v.contains("without any official external identifier"));
        assertThat(r.violations()).anyMatch(v -> v.contains("without field provenance"));
        assertThat(r.violations()).anyMatch(v -> v.contains("without a fact-extracting monitor"));
        assertThat(r.violations()).anyMatch(v -> v.contains("university without programs"));
    }

    @Test
    void listsTheAppliedMigrationsAndNoCredentials() {
        var r = report.run();
        assertThat(r.lines().get(0)).startsWith("flyway versions applied: 1,2,3,4,5,6,7,8");
        assertThat(String.join("\n", r.lines()).toLowerCase()).doesNotContain("password").doesNotContain("jdbc:");
        assertThat(r.violations()).noneMatch(v -> v.contains("failed migration"));
    }

    @Test
    void passesOnceTheSyntheticRowsAreGone() {
        clean();
        assertThat(report.run().violations()).doesNotContain("1 university without programs");
    }
}
