package com.pathwayai.backend.refresh.report;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The internal monitoring report: catalogue coverage and the health of the refresh pipeline. Read-only. Exposed only
 * through the command line ({@code app.refresh.command=report}); there is no public endpoint and no control surface.
 */
@Service
public class MonitoringReportService {

    public record CountryRow(String iso, int universities, int verified, int programs) {
    }

    public record Report(Instant generatedAt, int universities, int verifiedUniversities, int registryOnlyUniversities,
                         int programs, List<CountryRow> countries, int programsWithRequirements, int programsWithTuition,
                         int programsWithCurrentDeadline, int monitoredSources, Map<String, Integer> monitorsByStatus,
                         int successfulChecks7d, int failedChecks7d, int changedPages7d, Map<String, Integer> checksByOutcome7d,
                         int autoAppliedChanges, int changesAwaitingReview, Map<String, Integer> changesByStatus,
                         int neverChecked, int staleSources, List<String> staleOrUnresolved) {

        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("PATHWAY AI MONITORING REPORT ").append(generatedAt).append('\n');
            sb.append(String.format("Universities: %d (verified %d, registry-only %d)  Programs: %d%n", universities,
                    verifiedUniversities, registryOnlyUniversities, programs));
            sb.append("By country (universities / verified / programs):\n");
            for (CountryRow c : countries) {
                sb.append(String.format("  %s  %2d  %2d  %3d%n", c.iso(), c.universities(), c.verified(), c.programs()));
            }
            sb.append(String.format("Programs with a sourced requirement: %d, with tuition: %d, with a deadline today or later: %d%n",
                    programsWithRequirements, programsWithTuition, programsWithCurrentDeadline));
            sb.append(String.format("Monitored sources: %d %s%n", monitoredSources, monitorsByStatus));
            sb.append(String.format("Checks in the last 7 days: %d successful, %d failed or blocked, %d changed pages %s%n",
                    successfulChecks7d, failedChecks7d, changedPages7d, checksByOutcome7d));
            sb.append(String.format("Changes: %d applied automatically, %d awaiting review %s%n", autoAppliedChanges,
                    changesAwaitingReview, changesByStatus));
            sb.append(String.format("Never checked: %d. Stale (no successful check in twice the interval): %d%n", neverChecked, staleSources));
            if (!staleOrUnresolved.isEmpty()) {
                sb.append("Stale or unresolved sources (first ").append(staleOrUnresolved.size()).append("):\n");
                staleOrUnresolved.forEach(s -> sb.append("  ").append(s).append('\n'));
            }
            return sb.toString();
        }
    }

    private final JdbcClient jdbc;

    public MonitoringReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Report generate(Instant now) {
        Timestamp since = Timestamp.from(now.minus(Duration.ofDays(7)));
        Map<String, Integer> checks = grouped("select outcome, count(*) from source_check where checked_at >= :since group by outcome", since);
        int success = sum(checks, "BASELINE", "UNCHANGED", "NOT_MODIFIED", "CHANGED");
        int failed = sum(checks, "FAILED", "BLOCKED");
        Map<String, Integer> changes = grouped("select status, count(*) from staged_change group by status", null);
        List<CountryRow> countries = jdbc.sql("""
                select c.iso2_code,
                       count(distinct u.id) universities,
                       count(distinct u.id) filter (where u.verification_status = 'VERIFIED') verified,
                       count(distinct p.id) programs
                from country c join university u on u.country_id = c.id left join degree_program p on p.university_id = u.id
                group by c.iso2_code order by c.iso2_code""")
                .query((rs, i) -> new CountryRow(rs.getString(1).trim(), rs.getInt(2), rs.getInt(3), rs.getInt(4))).list();
        List<String> stale = jdbc.sql("""
                select s.url || ' [' || m.status || ', ' || coalesce(m.last_result, 'never checked') || ']'
                from source_monitor m join source s on s.id = m.source_id
                where m.status in ('UNSUPPORTED', 'FAILING')
                   or (m.status = 'ACTIVE' and m.last_success_at is not null
                       and m.last_success_at < :stale)
                order by m.status, s.url limit 10""")
                .param("stale", Timestamp.from(now.minus(Duration.ofDays(14)))).query(String.class).list();
        return new Report(now,
                one("select count(*) from university"),
                one("select count(*) from university where verification_status = 'VERIFIED'"),
                one("select count(*) from university where verification_status = 'REGISTRY_ONLY'"),
                one("select count(*) from degree_program"), countries,
                one("select count(distinct program_id) from academic_requirement"),
                one("select count(distinct program_id) from tuition_fee"),
                one("select count(distinct program_id) from program_intake where application_deadline >= current_date"),
                one("select count(*) from source_monitor"),
                grouped("select status, count(*) from source_monitor group by status", null),
                success, failed, checks.getOrDefault("CHANGED", 0), checks,
                changes.getOrDefault("AUTO_APPLIED", 0), changes.getOrDefault("PENDING_REVIEW", 0), changes,
                one("select count(*) from source_monitor where last_attempt_at is null and status in ('ACTIVE','FAILING')"),
                jdbc.sql("""
                        select count(*) from source_monitor where status in ('ACTIVE','FAILING') and last_success_at is not null
                          and last_success_at < :stale""").param("stale", Timestamp.from(now.minus(Duration.ofDays(14))))
                        .query(Integer.class).single(),
                stale);
    }

    private int one(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private Map<String, Integer> grouped(String sql, Timestamp since) {
        var spec = jdbc.sql(sql);
        if (since != null) {
            spec = spec.param("since", since);
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        spec.query((rs, i) -> {
            out.put(rs.getString(1), rs.getInt(2));
            return null;
        }).list();
        return out;
    }

    private static int sum(Map<String, Integer> m, String... keys) {
        int total = 0;
        for (String k : keys) {
            total += m.getOrDefault(k, 0);
        }
        return total;
    }
}
