package com.pathwayai.backend.refresh.registry;

import com.pathwayai.backend.refresh.RefreshProperties;
import com.pathwayai.backend.refresh.fetch.ApprovedHosts;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers the official pages the platform already cites. No source row is invented: only URLs that already exist
 * in {@code source} are monitored (the catalogue import stored the pages it read, with their real titles). Re-running
 * is idempotent. Facts are never modified here.
 *
 * <ul>
 *   <li>a VERIFIED university's website, admissions page and programme catalogue;</li>
 *   <li>the EU HEI registry record that confirmed its identity;</li>
 *   <li>every page that backs a program, tuition fee, requirement or intake.</li>
 * </ul>
 */
@Service
public class MonitorRegistrar {

    private final JdbcClient jdbc;
    private final SourceMonitorRepository monitors;
    private final RefreshProperties props;
    private final ApprovedHosts approvedHosts;

    public MonitorRegistrar(JdbcClient jdbc, SourceMonitorRepository monitors, RefreshProperties props,
                            ApprovedHosts approvedHosts) {
        this.jdbc = jdbc;
        this.monitors = monitors;
        this.props = props;
        this.approvedHosts = approvedHosts;
    }

    @Transactional
    public Map<String, Integer> registerAll(Instant now) {
        Map<String, Integer> report = new LinkedHashMap<>();
        registerUniversities(now, report);
        registerPrograms(now, report);
        approvedHosts.refresh();
        return report;
    }

    private void registerUniversities(Instant now, Map<String, Integer> report) {
        List<Map<String, Object>> universities = jdbc.sql("""
                select id, website_url, admissions_url, programme_catalogue_url from university
                where verification_status = 'VERIFIED'""").query().listOfRows();
        for (Map<String, Object> u : universities) {
            long id = ((Number) u.get("id")).longValue();
            add(id, null, (String) u.get("website_url"), "UNIVERSITY_INFO", "VERIFIED", now, report);
            add(id, null, (String) u.get("admissions_url"), "ADMISSIONS", "VERIFIED", now, report);
            add(id, null, (String) u.get("programme_catalogue_url"), "PROGRAM_CATALOGUE", "VERIFIED", now, report);
        }
        // the registry record behind each identity (changes to an institution's registered facts)
        for (Map<String, Object> r : jdbc.sql("""
                select us.university_id, s.id source_id, s.url from university_source us join source s on s.id = us.source_id
                where s.url like 'https://hei.api.uni-foundation.eu/%'""").query().listOfRows()) {
            registerExisting(((Number) r.get("source_id")).longValue(), (String) r.get("url"),
                    ((Number) r.get("university_id")).longValue(), null, "UNIVERSITY_INFO", "VERIFIED", now, report);
        }
    }

    private void registerPrograms(Instant now, Map<String, Integer> report) {
        String sourcesOfPrograms = """
                select source_id, program_id from program_source
                union select source_id, program_id from tuition_fee
                union select source_id, program_id from academic_requirement
                union select source_id, program_id from program_intake""";
        List<Map<String, Object>> rows = jdbc.sql("""
                select s.id source_id, s.url, count(distinct x.program_id) programs, min(x.program_id) program_id,
                       min(p.university_id) university_id, bool_and(p.verification_status = 'VERIFIED') verified
                from (""" + sourcesOfPrograms + """
                ) x join source s on s.id = x.source_id join degree_program p on p.id = x.program_id
                group by s.id, s.url""").query().listOfRows();
        for (Map<String, Object> r : rows) {
            boolean single = ((Number) r.get("programs")).intValue() == 1;
            String url = (String) r.get("url");
            registerExisting(((Number) r.get("source_id")).longValue(), url,
                    single ? ((Number) r.get("university_id")).longValue() : null,
                    single ? ((Number) r.get("program_id")).longValue() : null, kindOf(url),
                    Boolean.TRUE.equals(r.get("verified")) ? "VERIFIED" : "UNVERIFIED", now, report);
        }
    }

    private void add(long universityId, Long programId, String url, String kind, String verification, Instant now,
                     Map<String, Integer> report) {
        if (url == null) {
            return;
        }
        Optional<Long> source = jdbc.sql("""
                select s.id from source s join university_source us on us.source_id = s.id
                where us.university_id = :u and s.url = :url""").param("u", universityId).param("url", url)
                .query(Long.class).optional();
        if (source.isEmpty()) {
            bump(report, "skippedNoSourceRow");
            return;
        }
        registerExisting(source.get(), url, universityId, programId, kind, verification, now, report);
    }

    private void registerExisting(long sourceId, String url, Long universityId, Long programId, String kind,
                                  String verification, Instant now, Map<String, Integer> report) {
        if (!url.startsWith("https://")) {
            bump(report, "skippedNotHttps");
            return;
        }
        var spec = SourceMonitor.Spec.hashOnly(url, universityId, programId, kind, props.generalIntervalDays());
        bump(report, monitors.register(sourceId, spec, verification, now) ? "registered" : "alreadyRegistered");
    }

    /** Tuition and admissions pages are named by their URL; anything else describing a program is a PROGRAM page. */
    static String kindOf(String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        if (u.contains("tuition") || u.contains("fees")) {
            return "TUITION";
        }
        if (u.contains("scholarship")) {
            return "SCHOLARSHIP";
        }
        if (u.contains("deadline")) {
            return "DEADLINES";
        }
        if (u.contains("admission") || u.contains("apply") || u.contains("application")) {
            return "ADMISSIONS";
        }
        return "PROGRAM";
    }

    private static void bump(Map<String, Integer> m, String k) {
        m.merge(k, 1, Integer::sum);
    }
}
