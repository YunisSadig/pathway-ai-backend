package com.pathwayai.backend.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Last-success monitoring: of the programs kept current by a fact-extracting monitor, how many were read successfully within the
 * allowed age. A page-change monitor never counts. Synthetic data only.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExtractorFreshnessIT {

    @Autowired SourceMonitorRepository monitors;
    @Autowired JdbcTemplate jdbc;

    final Instant now = Instant.now();
    long university;

    @BeforeEach
    void seed() {
        jdbc.update("insert into country (iso2_code, name) values ('ZF', 'Freshland')");
        long country = jdbc.queryForObject("select id from country where iso2_code = 'ZF'", Long.class);
        university = jdbc.queryForObject("""
                insert into university (country_id, slug, name, verification_status, verified_on)
                values (?, 'zz-fresh-u', 'Synthetic Freshness University', 'VERIFIED', current_date) returning id""", Long.class, country);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from source_check");
        jdbc.update("delete from source_monitor");
        jdbc.update("delete from university where slug = 'zz-fresh-u'");
        jdbc.update("delete from source where url like 'https://www.zz-fresh.example/%'");
        jdbc.update("delete from country where iso2_code = 'ZF'");
    }

    void monitor(String slug, String method, Instant lastSuccess, String status) {
        long program = jdbc.queryForObject("""
                insert into degree_program (university_id, slug, name, degree_level, verification_status, verified_on)
                values (?, ?, ?, 'MASTER', 'VERIFIED', current_date) returning id""", Long.class, university, slug, "Synthetic " + slug);
        long source = jdbc.queryForObject("""
                insert into source (url, title, publisher, source_type, retrieved_on)
                values (?, 'Synthetic', 'Synthetic', 'GOVERNMENT', current_date) returning id""", Long.class, "https://www.zz-fresh.example/" + slug);
        String adapter = "ADAPTER".equals(method) ? "x-extractor" : "content-hash";
        monitors.register(source, new SourceMonitor.Spec("https://www.zz-fresh.example/" + slug, university, program, "PROGRAM", "",
                method, adapter, "1", 7), "VERIFIED", now);
        jdbc.update("update source_monitor set last_success_at = ?, status = ? where source_id = ?",
                lastSuccess == null ? null : Timestamp.from(lastSuccess), status, source);
    }

    @Test
    void countsFreshStaleAndNeverSucceededExtractorMonitors() {
        monitor("fresh", "ADAPTER", now.minus(Duration.ofDays(1)), "ACTIVE");
        monitor("stale", "ADAPTER", now.minus(Duration.ofDays(20)), "ACTIVE");
        monitor("never", "ADAPTER", null, "ACTIVE");

        var f = monitors.extractorFreshness(now.minus(Duration.ofDays(10)));

        assertThat(f.total()).isEqualTo(3);
        assertThat(f.fresh()).isEqualTo(1);
        assertThat(f.stale()).isEqualTo(1);
        assertThat(f.neverSucceeded()).isEqualTo(1);
        assertThat(f.notActive()).isZero();
        assertThat(f.newestSuccess()).isAfter(now.minus(Duration.ofDays(2)));
        assertThat(f.oldestSuccess()).isBefore(now.minus(Duration.ofDays(19)));
    }

    @Test
    void aFailingMonitorIsNeverFreshEvenIfItSucceededRecently() {
        monitor("failing", "ADAPTER", now.minus(Duration.ofHours(2)), "FAILING");
        var f = monitors.extractorFreshness(now.minus(Duration.ofDays(10)));
        assertThat(f.total()).isEqualTo(1);
        assertThat(f.fresh()).isZero();
        assertThat(f.notActive()).isEqualTo(1);
    }

    @Test
    void aPageChangeMonitorDoesNotCountAsKeepingAProgramCurrent() {
        monitor("watched-only", "CONTENT_HASH", now.minus(Duration.ofHours(1)), "ACTIVE");
        var f = monitors.extractorFreshness(now.minus(Duration.ofDays(10)));
        assertThat(f.total()).as("a page-change monitor is not synchronization").isZero();
    }

    @Test
    void aTighterAgeMakesTheSameMonitorsStale() {
        monitor("fresh", "ADAPTER", now.minus(Duration.ofDays(3)), "ACTIVE");
        assertThat(monitors.extractorFreshness(now.minus(Duration.ofDays(10))).fresh()).isEqualTo(1);
        assertThat(monitors.extractorFreshness(now.minus(Duration.ofDays(1))).fresh()).isZero();
    }
}
