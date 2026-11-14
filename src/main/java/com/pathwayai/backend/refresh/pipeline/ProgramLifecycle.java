package com.pathwayai.backend.refresh.pipeline;

import com.pathwayai.backend.refresh.extract.PageCanonicalizer;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Discontinued programs. A program is marked DISCONTINUED only when its official source states explicitly that it is
 * no longer offered ({@code ExtractionAdapter#discontinuation}); nothing is deleted, its facts and provenance stay, and
 * public discovery hides it. If the same source later publishes the record again, the program is reinstated. Both
 * transitions are audited in {@code staged_change} (type LIFECYCLE, status AUTO_APPLIED) and the previous state is
 * recorded in {@code program_fact_history}.
 */
@Component
public class ProgramLifecycle {

    private final JdbcClient jdbc;

    public ProgramLifecycle(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns true when the program changed state. */
    public boolean discontinue(SourceMonitor m, String reason, String parserVersion, Instant now) {
        if (m.programId() == null) {
            return false;
        }
        int rows = jdbc.sql("""
                update degree_program set lifecycle_status = 'DISCONTINUED', discontinued_at = :now, updated_at = now()
                where id = :p and lifecycle_status = 'ACTIVE'""")
                .param("now", Timestamp.from(now)).param("p", m.programId()).update();
        if (rows == 0) {
            return false;
        }
        record(m, "ACTIVE", "DISCONTINUED", reason, parserVersion, now);
        return true;
    }

    /** The official source publishes the record again. */
    public boolean reinstate(SourceMonitor m, String parserVersion, Instant now) {
        if (m.programId() == null) {
            return false;
        }
        int rows = jdbc.sql("""
                update degree_program set lifecycle_status = 'ACTIVE', discontinued_at = null, updated_at = now()
                where id = :p and lifecycle_status = 'DISCONTINUED'""").param("p", m.programId()).update();
        if (rows == 0) {
            return false;
        }
        record(m, "DISCONTINUED", "ACTIVE", "the official source publishes the record again", parserVersion, now);
        return true;
    }

    private void record(SourceMonitor m, String from, String to, String reason, String parserVersion, Instant now) {
        jdbc.sql("""
                insert into program_fact_history (program_id, field_name, value, recorded_source_id, superseded_at,
                                                  superseded_by_source, note)
                values (:p, 'lifecycle', :from, :s, :now, :s, :note)""")
                .param("p", m.programId()).param("from", from).param("s", m.sourceId())
                .param("now", Timestamp.from(now)).param("note", truncate(reason, 300)).update();
        String key = PageCanonicalizer.sha256(m.sourceId() + "|LIFECYCLE|" + to + "|" + now.toEpochMilli());
        jdbc.sql("""
                insert into staged_change (source_id, university_id, program_id, fact_type, fact_key, old_value, new_value,
                    risk, status, reason, parser_version, dedupe_key, detected_at, decided_at, decided_by)
                values (:s, :u, :p, 'LIFECYCLE', '', :old, :new, 'LOW', 'AUTO_APPLIED', :reason, :pv, :dk, :now, :now, :by)
                on conflict (dedupe_key) do nothing""")
                .param("s", m.sourceId()).param("u", m.universityId()).param("p", m.programId()).param("old", from)
                .param("new", to).param("reason", truncate(reason, 500)).param("pv", parserVersion).param("dk", key)
                .param("now", Timestamp.from(now)).param("by", ChangePipeline.AUTO_ACTOR).update();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
