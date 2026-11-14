package com.pathwayai.backend.refresh;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read-only audit of the database the application is connected to, used by {@code --app.refresh.command=verify-data} after a hosted
 * bootstrap or sync. It prints only counts and fingerprints (never connection details or credentials) and lists every violated invariant:
 * a program that no official-API record keeps current, a program without field provenance or without a fact-extracting monitor, an
 * official identifier shared by two programs, a university without programs, or a migration that failed.
 */
@Component
public class DataIntegrityReport {

    public record Result(List<String> lines, List<String> violations) {}

    private final JdbcClient jdbc;

    public DataIntegrityReport(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private String text(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }

    public Result run() {
        var lines = new ArrayList<String>();
        var bad = new ArrayList<String>();

        lines.add("flyway versions applied: " + String.join(",", jdbc.sql(
                "select version from flyway_schema_history where success and version is not null order by installed_rank")
                .query(String.class).list()));
        long failedMigrations = count("select count(*) from flyway_schema_history where not success");
        if (failedMigrations > 0) {
            bad.add(failedMigrations + " failed migration(s)");
        }

        lines.add("universities: " + count("select count(*) from university")
                + ", programs: " + count("select count(*) from degree_program")
                + ", academic fields: " + count("select count(*) from academic_field")
                + ", countries: " + count("select count(*) from country"));
        for (var row : jdbc.sql("""
                select u.slug as slug, count(p.id) as programs from university u left join degree_program p on p.university_id = u.id
                group by u.slug order by u.slug""").query((rs, n) -> rs.getString("slug") + "=" + rs.getLong("programs")).list()) {
            lines.add("  programs per university: " + row);
        }
        lines.add("external identifiers: " + count("select count(*) from program_external_id")
                + ", field provenance rows: " + count("select count(*) from program_field_provenance")
                + ", fact history rows: " + count("select count(*) from program_fact_history")
                + ", staged changes: " + count("select count(*) from staged_change"));
        lines.add("monitors: " + String.join(", ", jdbc.sql(
                "select extraction_method || '/' || adapter_name || '/' || status || '=' || count(*) from source_monitor "
                        + "group by extraction_method, adapter_name, status order by 1").query(String.class).list()));

        // unchanged between two syncs of unchanged source data (only source_monitor.last_success_at moves)
        lines.add("write fingerprint: max(program.updated_at)=" + text("select coalesce(max(updated_at)::text, '-') from degree_program")
                + ", max(provenance.observed_at)=" + text("select coalesce(max(observed_at)::text, '-') from program_field_provenance")
                + ", max(university.updated_at)=" + text("select coalesce(max(updated_at)::text, '-') from university"));

        check(bad, "program(s) without any official external identifier (static record?)",
                "select count(*) from degree_program p where not exists (select 1 from program_external_id e where e.program_id = p.id)");
        check(bad, "program(s) without field provenance",
                "select count(*) from degree_program p where not exists (select 1 from program_field_provenance f where f.program_id = p.id)");
        check(bad, "program(s) without a fact-extracting monitor (ADAPTER or JSON_LD)",
                "select count(*) from degree_program p where not exists (select 1 from source_monitor m where m.program_id = p.id "
                        + "and m.extraction_method in ('ADAPTER', 'JSON_LD'))");
        check(bad, "CONTENT_HASH monitor(s) registered", "select count(*) from source_monitor where extraction_method = 'CONTENT_HASH'");
        check(bad, "program(s) with more than one identifier of the same scheme",
                "select count(*) from (select program_id, scheme from program_external_id group by program_id, scheme having count(*) > 1) x");
        check(bad, "duplicate program name within a university",
                "select count(*) from (select university_id, lower(name) from degree_program group by university_id, lower(name) having count(*) > 1) x");
        check(bad, "university without programs",
                "select count(*) from university u where not exists (select 1 from degree_program p where p.university_id = u.id)");
        return new Result(lines, bad);
    }

    private void check(List<String> bad, String what, String sql) {
        long n = count(sql);
        if (n > 0) {
            bad.add(n + " " + what);
        }
    }
}
