package com.pathwayai.backend.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pathwayai.backend.TestcontainersConfiguration;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The cleanup procedure (scripts/db/retire-unsynchronised-catalogue.sql) against PostgreSQL with synthetic rows only.
 * Every scenario runs inside ONE transaction that is rolled back at the end, so no test leaves data behind and the
 * procedure's own commit/rollback semantics are exercised with savepoints: a dry run keeps everything, a failed
 * safety check keeps everything, and a successful run archives before it deletes.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogueRetirementIT {

    static final String SCRIPT;

    static {
        try {
            SCRIPT = Files.readString(Path.of("scripts/db/retire-unsynchronised-catalogue.sql"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired SourceMonitorRepository monitors;

    TransactionTemplate tx;
    long country;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    /** Runs the scenario in a transaction that is always rolled back. */
    void inTransaction(Runnable scenario) {
        tx.executeWithoutResult(status -> {
            seed();
            scenario.run();
            status.setRollbackOnly();
        });
    }

    long university(String slug) {
        return jdbc.queryForObject("""
                insert into university (country_id, slug, name, verification_status, verified_on)
                values (?, ?, ?, 'VERIFIED', current_date) returning id""", Long.class, country, slug, "Synthetic " + slug);
    }

    long program(long university, String slug) {
        return jdbc.queryForObject("""
                insert into degree_program (university_id, slug, name, degree_level, verification_status, verified_on)
                values (?, ?, ?, 'MASTER', 'VERIFIED', current_date) returning id""", Long.class, university, slug, "Synthetic " + slug);
    }

    long source(String url) {
        return jdbc.queryForObject("""
                insert into source (url, title, publisher, source_type, retrieved_on)
                values (?, 'Synthetic', 'Synthetic', 'UNIVERSITY_WEBSITE', current_date) returning id""", Long.class, url);
    }

    void monitor(long source, long university, Long program, String method, String adapter) {
        monitors.register(source, new SourceMonitor.Spec("https://www.zz-retire.example/" + source, university, program, "PROGRAM", "",
                method, adapter, "1", 7), "VERIFIED", Instant.now());
    }

    long keepUni, dropUni, keepProgram, staticProgram, droppedUniProgram;

    void seed() {
        jdbc.update("insert into country (iso2_code, name) values ('ZY', 'Retireland')");
        country = jdbc.queryForObject("select id from country where iso2_code = 'ZY'", Long.class);
        keepUni = university("zz-retire-keep");
        dropUni = university("zz-retire-drop");
        keepProgram = program(keepUni, "synced");
        staticProgram = program(keepUni, "static");          // same university, but nothing keeps it current
        droppedUniProgram = program(dropUni, "static-too");

        // the kept program: a fact-extracting official-API monitor
        long api = source("https://www.zz-retire.example/api/synced");
        jdbc.update("insert into program_source (program_id, source_id) values (?, ?)", keepProgram, api);
        monitor(api, keepUni, keepProgram, "ADAPTER", "studyinfo-external");
        jdbc.update("insert into program_language (program_id, language_code) values (?, 'en')", keepProgram);

        // static records with page-change monitors, a fee, a deadline, a language and a staged change
        long page = source("https://www.zz-retire.example/page/static");
        jdbc.update("insert into program_source (program_id, source_id) values (?, ?)", staticProgram, page);
        monitor(page, keepUni, staticProgram, "CONTENT_HASH", "content-hash");
        jdbc.update("""
                insert into tuition_fee (program_id, source_id, academic_year, student_category, amount, currency, billing_period)
                values (?, ?, '2099/2100', 'INTERNATIONAL', 12345, 'EUR', 'PER_YEAR')""", staticProgram, page);
        jdbc.update("insert into program_intake (program_id, source_id, label, application_deadline) values (?, ?, 'Round', '2099-01-01')", staticProgram, page);
        jdbc.update("insert into program_language (program_id, language_code) values (?, 'en')", staticProgram);
        jdbc.update("""
                insert into staged_change (source_id, university_id, program_id, fact_type, fact_key, old_value, new_value, risk, status,
                    reason, parser_version, dedupe_key, detected_at)
                values (?, ?, ?, 'DURATION_MONTHS', '', null, '24', 'HIGH', 'PENDING_REVIEW', 'x', 'p', repeat('a', 64), now())""",
                page, keepUni, staticProgram);
        long dropPage = source("https://www.zz-retire.example/page/dropped");
        jdbc.update("insert into university_source (university_id, source_id) values (?, ?)", dropUni, dropPage);
        monitor(dropPage, dropUni, null, "CONTENT_HASH", "content-watch");
        // a source nothing refers to
        source("https://www.zz-retire.example/page/orphan");
    }

    void runScript(String batch, Consumer<JdbcTemplate> settings) {
        jdbc.queryForObject("select set_config('cleanup.batch', ?, true)", String.class, batch);
        jdbc.queryForObject("select set_config('cleanup.expected_database', current_database(), true)", String.class);
        // the test database carries no development marker; the scenarios that are not about the marker opt out explicitly
        jdbc.queryForObject("select set_config('cleanup.allow_unmarked_database', 'true', true)", String.class);
        settings.accept(jdbc);
        jdbc.execute(SCRIPT);
    }

    long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void keepsOnlyProgramsWithAWorkingExtractorAndTheirUniversitiesAndArchivesTheRest() {
        inTransaction(() -> {
            long flywayBefore = count("select count(*) from flyway_schema_history");
            long countriesBefore = count("select count(*) from country");
            runScript("it-batch-1", j -> { });

            assertThat(count("select count(*) from degree_program where slug = 'synced'")).isEqualTo(1);
            assertThat(count("select count(*) from degree_program where slug in ('static', 'static-too')")).isZero();
            assertThat(count("select count(*) from university where slug = 'zz-retire-keep'")).isEqualTo(1);
            assertThat(count("select count(*) from university where slug = 'zz-retire-drop'")).isZero();
            assertThat(count("select count(*) from source_monitor where extraction_method = 'CONTENT_HASH'")).as("page-change monitors do not count").isZero();
            assertThat(count("select count(*) from source_monitor where adapter_name = 'studyinfo-external'")).isEqualTo(1);
            assertThat(count("select count(*) from tuition_fee where program_id = " + staticProgram)).isZero();
            assertThat(count("select count(*) from program_language where program_id = " + keepProgram)).as("the kept program keeps its facts").isEqualTo(1);
            assertThat(count("select count(*) from source where url = 'https://www.zz-retire.example/page/orphan'")).isZero();
            assertThat(count("select count(*) from source where url = 'https://www.zz-retire.example/api/synced'")).isEqualTo(1);

            // never touched
            assertThat(count("select count(*) from flyway_schema_history")).isEqualTo(flywayBefore);
            assertThat(count("select count(*) from country")).isEqualTo(countriesBefore);

            // archived BEFORE deleting, with the dependent facts inside the snapshot
            assertThat(jdbc.queryForObject("""
                    select snapshot->'tuition'->0->>'amount' from catalogue_archive
                    where batch = 'it-batch-1' and entity_type = 'PROGRAM' and entity_key = 'zz-retire-keep--static'""", String.class))
                    .isEqualTo("12345.00");
            assertThat(jdbc.queryForObject("""
                    select snapshot->'intakes'->0->>'application_deadline' from catalogue_archive
                    where batch = 'it-batch-1' and entity_key = 'zz-retire-keep--static'""", String.class)).isEqualTo("2099-01-01");
            assertThat(jdbc.queryForObject("""
                    select snapshot->'stagedChanges'->0->>'status' from catalogue_archive
                    where batch = 'it-batch-1' and entity_key = 'zz-retire-keep--static'""", String.class)).isEqualTo("PENDING_REVIEW");
            assertThat(count("select count(*) from catalogue_archive where batch = 'it-batch-1' and entity_type = 'UNIVERSITY' and entity_key = 'zz-retire-drop'")).isEqualTo(1);
            assertThat(count("select count(*) from catalogue_archive where batch = 'it-batch-1' and entity_type = 'SOURCE_MONITOR' and entity_key like 'https://www.zz-retire.example/page/%'")).isEqualTo(2);
            assertThat(count("select count(*) from catalogue_archive where batch = 'it-batch-1' and entity_type = 'SOURCE' and entity_key = 'https://www.zz-retire.example/page/orphan'")).isEqualTo(1);
        });
    }

    @Test
    void aDryRunRollsEverythingBackIncludingTheArchive() {
        inTransaction(() -> {
            jdbc.execute("savepoint dry_run");
            runScript("it-batch-dry", j -> { });
            assertThat(count("select count(*) from degree_program where slug = 'static'")).isZero();
            jdbc.execute("rollback to savepoint dry_run");

            assertThat(count("select count(*) from degree_program where slug in ('synced', 'static', 'static-too')")).isEqualTo(3);
            assertThat(count("select count(*) from university where slug like 'zz-retire-%'")).isEqualTo(2);
            assertThat(count("select count(*) from tuition_fee where program_id = " + staticProgram)).isEqualTo(1);
            assertThat(count("select count(*) from catalogue_archive where batch = 'it-batch-dry'")).isZero();
        });
    }

    @Test
    void aFailedSafetyCheckAbortsAndLeavesEveryRowInPlace() {
        inTransaction(() -> {
            jdbc.execute("savepoint wrong_count");
            assertThatThrownBy(() -> runScript("it-batch-bad", j -> j.queryForObject("select set_config('cleanup.expect_programs', '999', true)", String.class)))
                    .hasMessageContaining("verification failed: ");
            jdbc.execute("rollback to savepoint wrong_count");
            assertThat(count("select count(*) from degree_program where slug in ('synced', 'static', 'static-too')")).isEqualTo(3);
            assertThat(count("select count(*) from catalogue_archive where batch = 'it-batch-bad'")).isZero();
        });
    }

    @Test
    void refusesToRunOnADatabaseThatIsNotMarkedAsTheDevelopmentDatabase() {
        inTransaction(() -> {
            jdbc.execute("savepoint unmarked");
            // same database name as expected, no development marker: exactly what a production database would look like
            assertThatThrownBy(() -> runScript("it-batch-unmarked",
                    j -> j.queryForObject("select set_config('cleanup.allow_unmarked_database', 'false', true)", String.class)))
                    .hasMessageContaining("is not marked as the development database");
            jdbc.execute("rollback to savepoint unmarked");
            assertThat(count("select count(*) from degree_program where slug = 'static'")).as("nothing was removed").isEqualTo(1);
            assertThat(count("select count(*) from catalogue_archive where batch = 'it-batch-unmarked'")).isZero();
        });
    }

    @Test
    void runsOnceThePersonWhoOwnsTheDevelopmentDatabaseHasMarkedIt() {
        inTransaction(() -> {
            String db = jdbc.queryForObject("select current_database()", String.class);
            jdbc.execute("COMMENT ON DATABASE \"" + db + "\" IS 'pathway-ai:development'");
            runScript("it-batch-marked", j -> j.queryForObject("select set_config('cleanup.allow_unmarked_database', 'false', true)", String.class));
            assertThat(count("select count(*) from degree_program where slug = 'static'")).isZero();
            assertThat(count("select count(*) from catalogue_archive where batch = 'it-batch-marked'")).isPositive();
        });
    }

    @Test
    void refusesToRunAgainstADifferentDatabaseName() {
        inTransaction(() -> {
            jdbc.execute("savepoint wrong_db");
            assertThatThrownBy(() -> runScript("it-batch-db", j -> j.queryForObject("select set_config('cleanup.expected_database', 'some_production_db', true)", String.class)))
                    .hasMessageContaining("refusing to run: connected to database");
            jdbc.execute("rollback to savepoint wrong_db");
            assertThat(count("select count(*) from degree_program where slug = 'static'")).isEqualTo(1);
        });
    }

    @Test
    void aBatchNameCannotBeReusedAndASecondRunWithNothingLeftChangesNothing() {
        inTransaction(() -> {
            runScript("it-batch-a", j -> { });
            long programs = count("select count(*) from degree_program");
            long archived = count("select count(*) from catalogue_archive");
            runScript("it-batch-b", j -> { });
            assertThat(count("select count(*) from degree_program")).as("idempotent").isEqualTo(programs);
            assertThat(count("select count(*) from catalogue_archive")).isEqualTo(archived);
            jdbc.execute("savepoint reuse");
            assertThatThrownBy(() -> runScript("it-batch-a", j -> { })).hasMessageContaining("archive batch it-batch-a already exists");
            jdbc.execute("rollback to savepoint reuse");
        });
    }
}
