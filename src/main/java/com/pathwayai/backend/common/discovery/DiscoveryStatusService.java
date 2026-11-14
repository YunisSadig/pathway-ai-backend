package com.pathwayai.backend.common.discovery;

import com.pathwayai.backend.program.repository.DegreeProgramRepository;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Explains the state of public discovery, so an empty or partial catalogue is understandable instead of silent: how
 * many programs exist, how many are listed, why the rest are held back, and when a source was last read successfully.
 */
@Service
public class DiscoveryStatusService {

    public record Withheld(long notVerified, long sourcesStale, long neverChecked, long extractorFailing) {
    }

    public record Status(String state, boolean restricted, int maxSourceAgeDays, long programsTotal, long programsListed,
                         long programsWithheld, Withheld withheldBecause, Instant lastSuccessfulSourceCheck, String message) {
    }

    private final JdbcClient jdbc;
    private final DegreeProgramRepository programs;
    private final DiscoveryPolicy policy;

    public DiscoveryStatusService(JdbcClient jdbc, DegreeProgramRepository programs, DiscoveryPolicy policy) {
        this.jdbc = jdbc;
        this.programs = programs;
        this.policy = policy;
    }

    public Status status() {
        long total = programs.count();
        Instant last = jdbc.sql("select max(last_success_at) from source_monitor where program_id is not null")
                .query((rs, i) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant()).optional().orElse(null);
        if (!policy.restricts()) {
            return new Status("UNRESTRICTED", false, policy.maxSourceAgeDays(), total, total, 0, new Withheld(0, 0, 0, 0), last,
                    "Discovery restriction is switched off: every program is listed regardless of verification or source age.");
        }
        long listed = programs.count(policy.programs());
        Timestamp cutoff = Timestamp.from(policy.cutoff());
        long notVerified = count("""
                select count(*) from degree_program p join university u on u.id = p.university_id
                where p.verification_status <> 'VERIFIED' or u.verification_status <> 'VERIFIED'""", null);
        String verified = " from degree_program p join university u on u.id = p.university_id"
                + " where p.verification_status = 'VERIFIED' and u.verification_status = 'VERIFIED'";
        long neverChecked = count("select count(*)" + verified
                + " and not exists (select 1 from source_monitor m where m.program_id = p.id and m.last_success_at is not null)", null);
        String brokenExtractor = " exists (select 1 from source_monitor x where x.program_id = p.id and x.extraction_method in ('ADAPTER','JSON_LD')"
                + " and (x.status in ('FAILING','UNSUPPORTED') or x.last_success_at is null or x.last_success_at < :cutoff))";
        String hasFresh = " exists (select 1 from source_monitor m where m.program_id = p.id and m.last_success_at >= :cutoff)";
        long extractorFailing = count("select count(*)" + verified + " and" + hasFresh + " and" + brokenExtractor, cutoff);
        long stale = count("select count(*)" + verified
                + " and exists (select 1 from source_monitor m where m.program_id = p.id and m.last_success_at is not null)"
                + " and not" + hasFresh, cutoff);
        long withheld = total - listed;
        String state = total == 0 ? "EMPTY" : listed == 0 ? "STALE" : withheld > 0 ? "PARTIAL" : "FRESH";
        return new Status(state, true, policy.maxSourceAgeDays(), total, listed, withheld,
                new Withheld(notVerified, stale, neverChecked, extractorFailing), last, message(state, withheld, last));
    }

    private String message(String state, long withheld, Instant last) {
        String since = last == null ? "no source has ever been read successfully" : "the last successful source check was " + last;
        return switch (state) {
            case "FRESH" -> "Every program is verified and its sources were read successfully within " + policy.maxSourceAgeDays() + " days.";
            case "PARTIAL" -> withheld + " program(s) are held back (unverified, no successful source check within "
                    + policy.maxSourceAgeDays() + " days, or a fact-extracting source of theirs is failing); " + since + ". Held-back data is kept and returns after a successful check.";
            case "STALE" -> "No program is listed: none is verified with a source read successfully within " + policy.maxSourceAgeDays()
                    + " days; " + since + ". The refresh job may have stopped. Data is held back, not deleted.";
            default -> "There are no programs yet.";
        };
    }

    private long count(String sql, Timestamp cutoff) {
        var spec = jdbc.sql(sql);
        if (cutoff != null) {
            spec = spec.param("cutoff", cutoff);
        }
        return spec.query(Long.class).single();
    }
}
