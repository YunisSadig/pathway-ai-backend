package com.pathwayai.backend.program.service;

import com.pathwayai.backend.common.discovery.DiscoveryPolicy;
import com.pathwayai.backend.program.dto.ProgramDetailResponse.FieldSync;
import com.pathwayai.backend.program.dto.ProgramDetailResponse.Synchronization;
import com.pathwayai.backend.refresh.pipeline.SyncPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Builds the {@code synchronization} block of a program: which working extractor maintains it, when it last read its
 * source, whether that is within the freshness window, and, per field, whether a change is published automatically or
 * waits for review. A fact no connector maintains is listed as not synchronized instead of being shown as current.
 */
@Component
public class ProgramSyncInfo {

    private final JdbcClient jdbc;
    private final DiscoveryPolicy policy;

    public ProgramSyncInfo(JdbcClient jdbc, DiscoveryPolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    public Synchronization forProgram(long programId) {
        List<Map<String, Object>> monitors = jdbc.sql("""
                select adapter_name, extraction_method, status, last_success_at, next_check_at from source_monitor
                where program_id = :p and extraction_method in ('ADAPTER', 'JSON_LD')
                order by last_success_at desc nulls last limit 1""").param("p", programId).query().listOfRows();
        if (monitors.isEmpty()) {
            return new Synchronization("NOT_SYNCHRONIZED", null, null, null, null, false, policy.maxSourceAgeDays(), List.of(),
                    List.of("everything: no working extractor maintains this program"));
        }
        Map<String, Object> m = monitors.get(0);
        String adapter = (String) m.get("adapter_name");
        Instant last = instant(m.get("last_success_at"));
        boolean fresh = "ACTIVE".equals(m.get("status")) && last != null && !last.isBefore(policy.cutoff());
        List<FieldSync> fields = new ArrayList<>(jdbc.sql("""
                select field_name, verified_on from program_field_provenance where program_id = :p order by field_name""")
                .param("p", programId)
                .query((rs, i) -> new FieldSync(rs.getString("field_name"),
                        SyncPolicy.modeOf(rs.getString("field_name")).name(),
                        rs.getDate("verified_on") == null ? null : rs.getDate("verified_on").toLocalDate())).list());
        boolean hasDeadline = jdbc.sql("""
                select exists (select 1 from program_intake i join source_monitor x on x.source_id = i.source_id
                               where i.program_id = :p and x.extraction_method in ('ADAPTER', 'JSON_LD'))""")
                .param("p", programId).query(Boolean.class).single();
        if (hasDeadline) {
            fields.add(new FieldSync("application_deadline", SyncPolicy.modeOf("application_deadline").name(), null));
        }
        List<String> unavailable = SyncPolicy.isOfficialApi(adapter) ? SyncPolicy.UNAVAILABLE_FROM_STUDYINFO : List.of();
        return new Synchronization("AUTOMATIC", adapter, SyncPolicy.isOfficialApi(adapter) ? "OFFICIAL_API" : "STRUCTURED_DATA",
                last, instant(m.get("next_check_at")), fresh, policy.maxSourceAgeDays(), fields, unavailable);
    }

    private static Instant instant(Object o) {
        return o instanceof java.sql.Timestamp t ? t.toInstant()
                : o instanceof java.time.OffsetDateTime odt ? odt.toInstant() : null;
    }
}
