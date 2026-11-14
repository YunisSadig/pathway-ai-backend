package com.pathwayai.backend.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.TestcontainersConfiguration;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationIT {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void allMigrationsAreAppliedAndNoneArePending() {
        assertThat(flyway.info().pending()).isEmpty();
        MigrationInfo current = flyway.info().current();
        assertThat(current).isNotNull();
        assertThat(current.getVersion().getVersion()).isEqualTo("8");
        assertThat(flyway.info().applied()).extracting(m -> m.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(current.getState().isApplied()).isTrue();
    }

    @Test
    void coreTablesExist() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("country", "university", "degree_program", "academic_requirement",
                "tuition_fee", "source", "flyway_schema_history", "academic_field", "program_language",
                "program_source", "program_intake", "university_external_id", "university_alias",
                "university_source", "source_monitor", "source_check", "staged_change");
    }

    @Test
    void expectedIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where schemaname = 'public'", String.class);
        assertThat(indexes).contains("idx_university_country_id", "idx_university_name_lower",
                "idx_program_university_id", "idx_requirement_program_cycle", "uq_university_slug",
                "uq_tuition_program_year_category", "idx_program_academic_field_id", "idx_program_degree_level",
                "idx_program_language_code", "uq_intake_natural",
                "uq_academic_field_slug", "uq_tuition_without_year");
        // V3 removed an index that measurement showed is never used by the contains-style name search.
        assertThat(indexes).doesNotContain("idx_program_name_lower");
    }

    @Test
    void foreignKeysConnectTheModel() {
        Integer fkCount = jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints "
                        + "where constraint_type = 'FOREIGN KEY' and table_schema = 'public'", Integer.class);
        // V1: university->country, program->university, requirement->(program, source), tuition->(program, source)
        // V2: field->parent, program->field, language->program, program_source->(program, source),
        //     intake->(program, source)
        // V5: external_id->university, alias->university, university_source->(university, source)
        // V6: source_monitor->(source, university, program), source_check->source,
        //     staged_change->(source, university, program)
        // V7: program_external_id->program, program_field_provenance->(program, source),
        //     program_fact_history->(program, source, source)
        assertThat(fkCount).isEqualTo(13 + 4 + 7 + 6);
    }
}
