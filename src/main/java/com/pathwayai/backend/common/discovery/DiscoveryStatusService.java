package com.pathwayai.backend.common.discovery;

import com.pathwayai.backend.program.repository.DegreeProgramRepository;
import com.pathwayai.backend.refresh.RefreshProperties;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Explains the state of public discovery, so an empty or partial catalogue is understandable instead of silent: how
 * many programs exist, how many are listed, why the rest are held back, when an extractor last read its source
 * successfully, and whether an in-process refresh schedule is switched on.
 */
@Service
public class DiscoveryStatusService {

    /**
     * Why programs are held back. A program can appear under more than one reason. {@code noExtractor} counts programs that
     * have no official-API or structured-data monitor at all (a page-change monitor does not make a program eligible).
     */
    public record Withheld(long notVerified, long sourcesStale, long neverChecked, long extractorFailing, long noExtractor,
                           long discontinued) {
    }

    /** {@code inProcessEnabled} is only a statement about this application instance; it proves nothing about a hosted job. */
    public record Scheduler(boolean inProcessEnabled, String cron, Instant lastAttemptAt) {
    }

    public record Status(String state, boolean restricted, int maxSourceAgeDays, long programsTotal, long programsListed,
                         long programsWithheld, Withheld withheldBecause, Instant lastSuccessfulSourceCheck, Scheduler scheduler,
                         String message) {
    }

    private static final String EXTRACTOR = "x.extraction_method in ('ADAPTER','JSON_LD')";

    private final JdbcClient jdbc;
    private final DegreeProgramRepository programs;
    private final DiscoveryPolicy policy;
    private final RefreshProperties refreshProperties;

    public DiscoveryStatusService(JdbcClient jdbc, DegreeProgramRepository programs, DiscoveryPolicy policy,
                                  RefreshProperties refreshProperties) {
        this.jdbc = jdbc;
        this.programs = programs;
        this.policy = policy;
        this.refreshProperties = refreshProperties;
    }

    public Status status() {
        long total = programs.count();
        Instant last = instant("select max(last_success_at) from source_monitor where program_id is not null and extraction_method in ('ADAPTER','JSON_LD')");
        Scheduler scheduler = new Scheduler(refreshProperties.scheduler().enabled(), refreshProperties.scheduler().cron(),
                instant("select max(last_attempt_at) from source_monitor where program_id is not null"));
        if (!policy.restricts()) {
            return new Status("UNRESTRICTED", false, policy.maxSourceAgeDays(), total, total, 0, new Withheld(0, 0, 0, 0, 0, 0), last,
                    scheduler, "Discovery restriction is switched off: every program is listed regardless of verification or source age.");
        }
        long listed = programs.count(policy.programs());
        Timestamp cutoff = Timestamp.from(policy.cutoff());
        long notVerified = count("""
                select count(*) from degree_program p join university u on u.id = p.university_id
                where p.verification_status <> 'VERIFIED' or u.verification_status <> 'VERIFIED'""", null);
        long discontinued = count("select count(*) from degree_program p where p.lifecycle_status = 'DISCONTINUED'", null);
        String live = " from degree_program p join university u on u.id = p.university_id"
                + " where p.verification_status = 'VERIFIED' and u.verification_status = 'VERIFIED' and p.lifecycle_status = 'ACTIVE'";
        String hasExtractor = " exists (select 1 from source_monitor x where x.program_id = p.id and " + EXTRACTOR + ")";
        String freshExtractor = " exists (select 1 from source_monitor x where x.program_id = p.id and " + EXTRACTOR
                + " and x.status = 'ACTIVE' and x.last_success_at >= :cutoff)";
        String brokenExtractor = " exists (select 1 from source_monitor x where x.program_id = p.id and " + EXTRACTOR
                + " and (x.status in ('FAILING','UNSUPPORTED') or x.last_success_at is null or x.last_success_at < :cutoff))";
        long noExtractor = count("select count(*)" + live + " and not" + hasExtractor, null);
        long neverChecked = count("select count(*)" + live + " and" + hasExtractor
                + " and not exists (select 1 from source_monitor x where x.program_id = p.id and " + EXTRACTOR + " and x.last_success_at is not null)", null);
        long extractorFailing = count("select count(*)" + live + " and" + freshExtractor + " and" + brokenExtractor, cutoff);
        long stale = count("select count(*)" + live + " and exists (select 1 from source_monitor x where x.program_id = p.id and "
                + EXTRACTOR + " and x.last_success_at is not null) and not" + freshExtractor, cutoff);
        long withheld = total - listed;
        String state = total == 0 ? "EMPTY" : listed == 0 ? "STALE" : withheld > 0 ? "PARTIAL" : "FRESH";
        return new Status(state, true, policy.maxSourceAgeDays(), total, listed, withheld,
                new Withheld(notVerified, stale, neverChecked, extractorFailing, noExtractor, discontinued), last, scheduler,
                message(state, withheld, last));
    }

    private Instant instant(String sql) {
        return jdbc.sql(sql).query((rs, i) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant()).optional().orElse(null);
    }

    private String message(String state, long withheld, Instant last) {
        String since = last == null ? "no extractor has ever read its source successfully" : "the last successful extractor run was " + last;
        return switch (state) {
            case "FRESH" -> "Every program is verified, active and kept current by a working extractor that read its source within "
                    + policy.maxSourceAgeDays() + " days.";
            case "PARTIAL" -> withheld + " program(s) are held back (unverified, discontinued, no working extractor, or the extractor has not read"
                    + " its source successfully within " + policy.maxSourceAgeDays() + " days); " + since
                    + ". Held-back data is kept and returns after a successful extractor run.";
            case "STALE" -> "No program is listed: none is kept current by a working extractor that read its source within "
                    + policy.maxSourceAgeDays() + " days; " + since + ". The refresh job may have stopped. Data is held back, not deleted.";
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
