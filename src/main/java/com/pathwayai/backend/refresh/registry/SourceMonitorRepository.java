package com.pathwayai.backend.refresh.registry;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes the source registry ({@code source_monitor}). Facts and their sources are never touched here. */
@Repository
public class SourceMonitorRepository {

    private static final String SELECT = """
            select m.source_id, s.url, m.university_id, m.program_id, m.domain, m.source_kind, m.extraction_method,
                   m.adapter_name, m.adapter_version, m.status, m.unsupported_reason, m.verification_status,
                   m.check_interval_days, m.last_attempt_at, m.last_success_at, m.last_change_at, m.etag,
                   m.last_modified, m.content_hash, m.hash_parser_version, m.last_result, m.failure_count,
                   m.next_check_at
            from source_monitor m join source s on s.id = m.source_id""";

    private final JdbcClient jdbc;

    public SourceMonitorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Registers a page for monitoring unless its source is already registered. Returns true if a row was added. */
    public boolean register(long sourceId, SourceMonitor.Spec spec, String verificationStatus, Instant now) {
        String host = URI.create(spec.url()).getHost().toLowerCase(Locale.ROOT);
        return jdbc.sql("""
                insert into source_monitor (source_id, university_id, program_id, domain, source_kind, supported_fields,
                    extraction_method, adapter_name, adapter_version, verification_status, check_interval_days, next_check_at)
                values (:s, :u, :p, :d, :k, :f, :m, :an, :av, :v, :i, :now)
                on conflict (source_id) do nothing""")
                .param("s", sourceId).param("u", spec.universityId()).param("p", spec.programId()).param("d", host)
                .param("k", spec.kind()).param("f", spec.supportedFields()).param("m", spec.extractionMethod())
                .param("an", spec.adapterName()).param("av", spec.adapterVersion()).param("v", verificationStatus)
                .param("i", spec.intervalDays()).param("now", Timestamp.from(now)).update() > 0;
    }

    public List<SourceMonitor> findDue(Instant now, int limit) {
        return jdbc.sql(SELECT + " where m.status in ('ACTIVE','FAILING') and m.next_check_at <= :now order by m.next_check_at limit :limit")
                .param("now", Timestamp.from(now)).param("limit", limit).query(SourceMonitorRepository::map).list();
    }

    /** Due sources read by one adapter only (used to run one provider without crawling every other monitored page). */
    public List<SourceMonitor> findDue(Instant now, int limit, String adapterName) {
        return jdbc.sql(SELECT + " where m.status in ('ACTIVE','FAILING') and m.next_check_at <= :now and m.adapter_name = :an"
                        + " order by m.next_check_at, m.source_id limit :limit")
                .param("now", Timestamp.from(now)).param("an", adapterName).param("limit", limit)
                .query(SourceMonitorRepository::map).list();
    }

    /**
     * Operator action: switches one source to an adapter and clears its stored hash, so the next check re-reads the page
     * with the new adapter as a baseline instead of reporting a phantom change.
     */
    public int assignAdapter(long sourceId, String adapterName, String adapterVersion, Long universityId, Instant now) {
        return jdbc.sql("""
                update source_monitor set extraction_method = 'ADAPTER', adapter_name = :an, adapter_version = :av,
                    university_id = coalesce(:uni, university_id),
                    content_hash = null, hash_parser_version = null, etag = null, last_modified = null,
                    status = 'ACTIVE', unsupported_reason = null, failure_count = 0, next_check_at = :now, updated_at = now()
                where source_id = :id""")
                .param("an", adapterName).param("av", adapterVersion).param("uni", universityId)
                .param("now", Timestamp.from(now)).param("id", sourceId).update();
    }

    public Optional<Long> universityIdBySlug(String slug) {
        return jdbc.sql("select id from university where slug = :s").param("s", slug).query(Long.class).optional();
    }

    /** How current the fact-extracting monitors are: the base of last-success monitoring and the freshness watchdog. */
    public record ExtractorFreshness(int total, int fresh, int neverSucceeded, int stale, int notActive, Instant newestSuccess,
                                     Instant oldestSuccess) {
    }

    /** Every monitor of a program that extracts facts (official API or structured data); {@code cutoff} is the oldest acceptable success. */
    public ExtractorFreshness extractorFreshness(Instant cutoff) {
        return jdbc.sql("""
                select count(*) as total,
                       count(*) filter (where status = 'ACTIVE' and last_success_at >= :cutoff) as fresh,
                       count(*) filter (where last_success_at is null) as never,
                       count(*) filter (where last_success_at < :cutoff) as stale,
                       count(*) filter (where status <> 'ACTIVE') as not_active,
                       max(last_success_at) as newest, min(last_success_at) as oldest
                from source_monitor
                where program_id is not null and extraction_method in ('ADAPTER', 'JSON_LD')""")
                .param("cutoff", Timestamp.from(cutoff))
                .query((rs, i) -> new ExtractorFreshness(rs.getInt("total"), rs.getInt("fresh"), rs.getInt("never"),
                        rs.getInt("stale"), rs.getInt("not_active"), instant(rs, "newest"), instant(rs, "oldest")))
                .single();
    }

    /** Operator action: makes the active sources of one adapter due now. Returns how many. */
    public int markDue(String adapterName, Instant now) {
        return jdbc.sql("""
                update source_monitor set next_check_at = :now, updated_at = now()
                where adapter_name = :an and status in ('ACTIVE', 'FAILING')""")
                .param("now", Timestamp.from(now)).param("an", adapterName).update();
    }

    public Optional<SourceMonitor> findBySourceId(long sourceId) {
        return jdbc.sql(SELECT + " where m.source_id = :id").param("id", sourceId).query(SourceMonitorRepository::map).optional();
    }

    /**
     * Operator action: puts UNSUPPORTED, FAILING or PAUSED sources back on schedule (all of them, or one). Returns how
     * many were reactivated. Use it after a site changes its access rules or a crawler defect has been fixed.
     */
    public int reactivate(Long sourceId, Instant now) {
        String base = """
                update source_monitor set status = 'ACTIVE', unsupported_reason = null, failure_count = 0,
                    next_check_at = :now, updated_at = now() where status in ('UNSUPPORTED', 'FAILING', 'PAUSED')""";
        return sourceId == null
                ? jdbc.sql(base).param("now", Timestamp.from(now)).update()
                : jdbc.sql(base + " and source_id = :id").param("now", Timestamp.from(now)).param("id", sourceId).update();
    }

    public List<String> activeDomains() {
        return jdbc.sql("select distinct domain from source_monitor where status <> 'PAUSED'").query(String.class).list();
    }

    /** Persists everything a check can change. The identity columns (source, domain, kind) are never rewritten. */
    public void saveState(SourceMonitor m) {
        jdbc.sql("""
                update source_monitor set status = :status, unsupported_reason = :reason,
                    extraction_method = :method, adapter_name = :an, adapter_version = :av,
                    last_attempt_at = :attempt, last_success_at = :success, last_change_at = :change,
                    etag = :etag, last_modified = :lm, content_hash = :hash, hash_parser_version = :hv,
                    last_result = :result, failure_count = :failures, next_check_at = :next, updated_at = now()
                where source_id = :id""")
                .param("status", m.status()).param("reason", m.unsupportedReason())
                .param("method", m.extractionMethod()).param("an", m.adapterName()).param("av", m.adapterVersion())
                .param("attempt", ts(m.lastAttemptAt())).param("success", ts(m.lastSuccessAt()))
                .param("change", ts(m.lastChangeAt())).param("etag", m.etag()).param("lm", m.lastModified())
                .param("hash", m.contentHash()).param("hv", m.hashParserVersion()).param("result", m.lastResult())
                .param("failures", m.failureCount()).param("next", ts(m.nextCheckAt())).param("id", m.sourceId()).update();
    }

    public void logCheck(long sourceId, Instant at, String outcome, Integer httpStatus, String hash, long durationMs,
                         String parserVersion, String error) {
        jdbc.sql("""
                insert into source_check (source_id, checked_at, outcome, http_status, content_hash, duration_ms, parser_version, error)
                values (:s, :at, :o, :h, :c, :d, :v, :e)""")
                .param("s", sourceId).param("at", Timestamp.from(at)).param("o", outcome).param("h", httpStatus)
                .param("c", hash).param("d", (int) Math.min(durationMs, Integer.MAX_VALUE)).param("v", parserVersion)
                .param("e", error == null ? null : error.substring(0, Math.min(error.length(), 500))).update();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static SourceMonitor map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SourceMonitor(rs.getLong("source_id"), rs.getString("url"), (Long) rs.getObject("university_id"),
                (Long) rs.getObject("program_id"), rs.getString("domain"), rs.getString("source_kind"),
                rs.getString("extraction_method"), rs.getString("adapter_name"), rs.getString("adapter_version"),
                rs.getString("status"), rs.getString("unsupported_reason"), rs.getString("verification_status"),
                rs.getInt("check_interval_days"), instant(rs, "last_attempt_at"), instant(rs, "last_success_at"),
                instant(rs, "last_change_at"), rs.getString("etag"), rs.getString("last_modified"),
                rs.getString("content_hash"), rs.getString("hash_parser_version"), rs.getString("last_result"),
                rs.getInt("failure_count"), instant(rs, "next_check_at"));
    }
}
