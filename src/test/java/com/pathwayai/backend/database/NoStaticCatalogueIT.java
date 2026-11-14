package com.pathwayai.backend.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.IsolatedPostgresConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Static academic records must not come back. With EVERY startup importer switched on and the bundled files, a
 * fresh database ends up with exactly the three connector-backed universities, no programs, no fees, no deadlines,
 * no requirements and no monitors: programs only ever arrive through the official-API connector.
 */
@SpringBootTest(properties = {"app.reference-data.import-on-startup=true", "app.catalogue.import-on-startup=true"})
@Import(IsolatedPostgresConfiguration.class)
class NoStaticCatalogueIT {

    static final List<String> RETIRED = List.of("university-of-amsterdam", "university-of-copenhagen", "ku-leuven", "peking-university",
            "tu-wien", "utrecht-university", "university-of-tokyo", "seoul-national-university");

    @Autowired JdbcTemplate jdbc;

    long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void startingWithAllImportersEnabledCreatesOnlyTheConnectorBackedUniversitiesAndNoAcademicRecords() {
        assertThat(jdbc.queryForList("select slug from university where slug in ('university-of-helsinki','aalto-university','university-of-oulu') order by slug", String.class))
                .containsExactly("aalto-university", "university-of-helsinki", "university-of-oulu");
        for (String retired : RETIRED) {
            assertThat(count("select count(*) from university where slug = '" + retired + "'")).as(retired).isZero();
        }
        assertThat(count("select count(*) from degree_program p join university u on u.id = p.university_id where u.slug in ('university-of-helsinki','aalto-university','university-of-oulu')"))
                .as("no program arrives from a static file").isZero();
        String ours = " where university_id in (select id from university where slug in ('university-of-helsinki','aalto-university','university-of-oulu'))";
        assertThat(count("select count(*) from source_monitor" + ours)).as("no page-change or any monitor is registered by an importer").isZero();
        assertThat(count("select count(*) from academic_field where slug in ('natural-sciences','computer-and-data-science','humanities','economics')"))
                .as("taxonomy still arrives").isEqualTo(4);
    }

    @Test
    void noMigrationInsertsAcademicDataAndNoPageChangeRegistrarExists() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                String sql = Files.readString(p).toLowerCase();
                assertThat(sql).as(p.getFileName() + " must not seed data").doesNotContain("insert into degree_program")
                        .doesNotContain("insert into university").doesNotContain("insert into tuition_fee")
                        .doesNotContain("insert into program_intake").doesNotContain("insert into academic_requirement");
            }
        }
        assertThat(Files.exists(Path.of("src/main/java/com/pathwayai/backend/refresh/registry/MonitorRegistrar.java")))
                .as("the content-hash registrar recreated page-change monitors for the whole catalogue").isFalse();
    }
}
