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
 * Pausing Poland (REFRESH_EXCLUDED_ADAPTERS=polon-courses,pl-university-page): the scheduled run and the freshness watchdog skip those
 * monitors (their programs stay as imported), while Finland's monitors are read as before. An explicit single-adapter read still
 * works. Synthetic data only.
 */
@SpringBootTest(properties = "app.refresh.excluded-adapters=polon-courses,pl-university-page")
@Import(TestcontainersConfiguration.class)
class ExcludedAdapterIT {

    @Autowired SourceMonitorRepository monitors;
    @Autowired JdbcTemplate jdbc;

    final Instant now = Instant.now();
    long university;

    @BeforeEach
    void seed() {
        jdbc.update("insert into country (iso2_code, name) values ('ZX', 'Excludedland')");
        long country = jdbc.queryForObject("select id from country where iso2_code = 'ZX'", Long.class);
        university = jdbc.queryForObject("""
                insert into university (country_id, slug, name, verification_status, verified_on)
                values (?, 'zz-excl-u', 'Synthetic Exclusion University', 'VERIFIED', current_date) returning id""", Long.class, country);
        monitor("finland-like", "studyinfo-external");
        monitor("poland-like", "polon-courses");
        monitor("poland-pages-like", "pl-university-page");
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from source_check");
        jdbc.update("delete from source_monitor");
        jdbc.update("delete from university where slug = 'zz-excl-u'");
        jdbc.update("delete from source where url like 'https://www.zz-excl.example/%'");
        jdbc.update("delete from country where iso2_code = 'ZX'");
    }

    void monitor(String slug, String adapter) {
        long program = jdbc.queryForObject("""
                insert into degree_program (university_id, slug, name, degree_level, verification_status, verified_on)
                values (?, ?, ?, 'MASTER', 'VERIFIED', current_date) returning id""", Long.class, university, slug, "Synthetic " + slug);
        long source = jdbc.queryForObject("""
                insert into source (url, title, publisher, source_type, retrieved_on)
                values (?, 'Synthetic', 'Synthetic', 'GOVERNMENT', current_date) returning id""", Long.class, "https://www.zz-excl.example/" + slug);
        monitors.register(source, new SourceMonitor.Spec("https://www.zz-excl.example/" + slug, university, program, "PROGRAM", "",
                "ADAPTER", adapter, "1", 7), "VERIFIED", now);
        jdbc.update("update source_monitor set last_success_at = ?, next_check_at = ? where source_id = ?",
                Timestamp.from(now.minus(Duration.ofDays(30))), Timestamp.from(now.minus(Duration.ofHours(1))), source);
    }

    @Test
    void theScheduledSelectionSkipsPolandButReadsFinland() {
        var due = monitors.findDue(now, 100);
        assertThat(due).extracting(SourceMonitor::adapterName).containsExactly("studyinfo-external");
    }

    @Test
    void anExplicitSingleAdapterReadStillSelectsPoland() {
        assertThat(monitors.findDue(now, 100, "polon-courses")).extracting(SourceMonitor::adapterName).containsExactly("polon-courses");
    }

    @Test
    void theFreshnessWatchdogDoesNotCountPolandSoItCannotTurnFinlandsRunRed() {
        var f = monitors.extractorFreshness(now.minus(Duration.ofDays(10)));
        assertThat(f.total()).isEqualTo(1); // only the Finland-like monitor (30 days old: stale), the paused Poland ones are not counted
        assertThat(f.stale()).isEqualTo(1);
    }
}
