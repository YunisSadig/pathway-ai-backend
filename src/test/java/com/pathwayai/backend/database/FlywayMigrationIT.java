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
        assertThat(current.getVersion().getVersion()).isEqualTo("1");
        assertThat(current.getState().isApplied()).isTrue();
    }

    @Test
    void coreTablesExist() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("country", "university", "degree_program", "academic_requirement",
                "tuition_fee", "source", "flyway_schema_history");
    }

    @Test
    void expectedIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where schemaname = 'public'", String.class);
        assertThat(indexes).contains("idx_university_country_id", "idx_university_name_lower",
                "idx_program_university_id", "idx_requirement_program_cycle", "uq_university_slug",
                "uq_tuition_program_year_category");
    }

    @Test
    void foreignKeysConnectTheModel() {
        Integer fkCount = jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints "
                        + "where constraint_type = 'FOREIGN KEY' and table_schema = 'public'", Integer.class);
        // university->country, program->university, requirement->(program, source), tuition->(program, source)
        assertThat(fkCount).isEqualTo(6);
    }
}
