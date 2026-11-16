package com.pathwayai.backend.provider.universitypages;

import com.pathwayai.backend.refresh.fetch.PageFetcher;
import com.pathwayai.backend.refresh.pipeline.ProgramFacts;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Operator-only, dry-run by default. Never creates programs or changes their stable identifiers. */
@Service
public class OfficialLinkBackfill {
    private final JdbcClient jdbc;
    private final PageFetcher fetcher;
    private final ProgramFacts facts;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OfficialLinkBackfill(JdbcClient jdbc, PageFetcher fetcher, ProgramFacts facts, Clock clock,
                               PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.fetcher = fetcher;
        this.facts = facts;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    record Program(long id, String university, String name, String level, String oid, String url, long sourceId) { }
    public record Result(String oid, String university, String name, String status, String url, String reason) { }

    public List<Result> run(boolean commit) {
        return run(commit, OfficialProgramPages.REVIEWED);
    }

    List<Result> run(boolean commit, List<OfficialProgramPages.Match> reviewed) {
        List<Program> programs = jdbc.sql("""
                select p.id, u.slug, p.name, p.degree_level, e.value, p.official_program_url,
                  (select min(ps.source_id) from program_source ps where ps.program_id=p.id) as source_id
                from degree_program p join university u on u.id=p.university_id
                join program_external_id e on e.program_id=p.id and e.scheme='STUDYINFO_KOULUTUS_OID'
                where u.slug in ('aalto-university','university-of-helsinki','university-of-oulu')
                order by u.slug,p.name""")
                .query((rs, n) -> new Program(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getLong(7))).list();
        List<Result> results = new ArrayList<>();
        for (Program p : programs) {
            var match = reviewed.stream().filter(m -> m.oid().equals(p.oid())).findFirst();
            String reason = "No reviewed OID association; manual review required";
            String url = null;
            if (match.isPresent()) {
                var m = match.get();
                if (!m.university().equals(p.university()) || !m.name().equals(p.name()) || !m.level().equals(p.level())) {
                    reason = "Stored university, title or degree level differs from the reviewed association";
                } else {
                    var response = fetcher.fetch(m.url(), null, null);
                    if (response.kind() == PageFetcher.Kind.OK) {
                        try {
                            m.verify(response.finalUrl(), response.contentType(), response.body());
                            url = m.url();
                            reason = "Exact OID, institution, stored title, degree level, live heading and qualification matched";
                        } catch (IllegalArgumentException e) { reason = e.getMessage(); }
                    } else { reason = response.kind() + ": " + response.error(); }
                }
            }
            // A changed university URL is a proposal, never silently published by this backfill.
            if (p.url() != null && !OfficialProgramPages.studyinfo(p.url()) && !Objects.equals(p.url(), url)) {
                results.add(new Result(p.oid(), p.university(), p.name(), "PENDING_REVIEW", url, reason));
                continue;
            }
            if (commit) {
                String verifiedUrl = url;
                tx.executeWithoutResult(s -> publish(p, verifiedUrl));
            }
            results.add(new Result(p.oid(), p.university(), p.name(), url == null ? "UNKNOWN" : "VERIFIED", url, reason));
        }
        return List.copyOf(results);
    }

    private void publish(Program p, String url) {
        String current = jdbc.sql("select official_program_url from degree_program where id=:p for update")
                .param("p", p.id()).query((rs, n) -> rs.getString(1)).optional().orElse(null);
        if (!Objects.equals(current, p.url())) throw new IllegalStateException("Concurrent program link change; rerun preview");
        long sourceId = p.sourceId();
        var now = clock.instant();
        if (url != null) {
            sourceId = jdbc.sql("""
                    insert into source (url,title,publisher,source_type,retrieved_on)
                    values (:url,:title,:publisher,'UNIVERSITY_WEBSITE',:today)
                    on conflict (url) do update set retrieved_on=excluded.retrieved_on returning id""")
                    .param("url", url).param("title", p.name()).param("publisher", p.university())
                    .param("today", LocalDate.ofInstant(now, ZoneOffset.UTC)).query(Long.class).single();
            jdbc.sql("insert into program_source (program_id,source_id) values (:p,:s) on conflict do nothing")
                    .param("p", p.id()).param("s", sourceId).update();
            jdbc.sql("""
                    insert into source_check (source_id,checked_at,outcome,http_status,parser_version)
                    values (:s,:now,:outcome,200,:version)""")
                    .param("s", sourceId).param("now", Timestamp.from(now))
                    .param("outcome", Objects.equals(current, url) ? "UNCHANGED" : "BASELINE")
                    .param("version", OfficialProgramPages.VERSION).update();
            facts.recordProvenance(p.id(), "official_program_url", sourceId, now, OfficialProgramPages.VERSION);
        }
        if (Objects.equals(current, url)) return;
        jdbc.sql("""
                insert into program_fact_history
                (program_id,field_name,value,recorded_source_id,superseded_at,superseded_by_source,note)
                values (:p,'official_program_url',:old,:oldSource,:now,:s,
                'Official-link backfill: Studyinfo remains the data source, not the university program destination')""")
                .param("p", p.id()).param("old", current).param("oldSource", p.sourceId())
                .param("now", Timestamp.from(now)).param("s", sourceId).update();
        jdbc.sql("update degree_program set official_program_url=:url,updated_at=:now where id=:p")
                .param("url", url).param("now", Timestamp.from(now)).param("p", p.id()).update();
    }
}
