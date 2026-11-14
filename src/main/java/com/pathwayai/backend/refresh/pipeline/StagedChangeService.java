package com.pathwayai.backend.refresh.pipeline;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import com.pathwayai.backend.refresh.pipeline.ProgramFacts.Kind;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The exception-review queue. There is intentionally no HTTP endpoint: review runs through the command line
 * ({@code app.refresh.command}), so no public route can change published facts.
 */
@Service
public class StagedChangeService {

    public record Item(long id, long sourceId, Long programId, String factType, String factKey, String oldValue,
                       String newValue, String risk, String status, String reason, String sourceUrl) {
    }

    private final JdbcClient jdbc;
    private final ProgramFacts facts;
    private final Clock clock;

    public StagedChangeService(JdbcClient jdbc, ProgramFacts facts, Clock clock) {
        this.jdbc = jdbc;
        this.facts = facts;
        this.clock = clock;
    }

    public List<Item> pending() {
        return jdbc.sql("""
                select c.id, c.source_id, c.program_id, c.fact_type, c.fact_key, c.old_value, c.new_value, c.risk,
                       c.status, c.reason, s.url
                from staged_change c join source s on s.id = c.source_id
                where c.status = 'PENDING_REVIEW' order by c.detected_at, c.id""")
                .query((rs, i) -> new Item(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11))).list();
    }

    /**
     * Publishes a reviewed change. Re-compares first: if the same period has since been filled, or now holds a
     * different value, nothing is overwritten.
     */
    @Transactional
    public void approve(long id, String actor) {
        Item item = lock(id);
        FactType type = FactType.valueOf(item.factType());
        if (type == FactType.SOURCE_CONTENT) {
            decide(id, "APPROVED", actor); // the reviewer re-read the page; no fact to publish
            return;
        }
        Map<String, String> values = ExtractedFact.parseValue(item.newValue());
        if (item.programId() == null) {
            throw new IllegalStateException("staged change " + id + " has no program");
        }
        var current = facts.compare(item.programId(), type, values);
        if (current.kind() == Kind.UNCHANGED) {
            decide(id, "SUPERSEDED", actor); // already published (for example by the reference-data import)
            return;
        }
        if (current.kind() == Kind.CONFLICT && (type == FactType.TUITION || type == FactType.DEADLINE)) {
            throw new IllegalStateException("a different " + type + " is already published for the same period ("
                    + current.existing() + "). Dated facts are never overwritten: correct it through the reference-data"
                    + " import with a reasoned retraction, then reject this item.");
        }
        facts.apply(item.programId(), item.sourceId(), type, values);
        decide(id, "APPROVED", actor);
    }

    @Transactional
    public void reject(long id, String actor, String reason) {
        lock(id);
        jdbc.sql("""
                update staged_change set status = 'REJECTED', decided_at = :now, decided_by = :by,
                    reason = coalesce(:reason, reason) where id = :id""")
                .param("now", Timestamp.from(clock.instant())).param("by", actor).param("reason", reason).param("id", id).update();
    }

    private Item lock(long id) {
        Item item = jdbc.sql("""
                select c.id, c.source_id, c.program_id, c.fact_type, c.fact_key, c.old_value, c.new_value, c.risk,
                       c.status, c.reason, s.url
                from staged_change c join source s on s.id = c.source_id where c.id = :id for update of c""")
                .param("id", id).query((rs, i) -> new Item(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getString(11))).optional()
                .orElseThrow(() -> new IllegalArgumentException("no staged change " + id));
        if (!"PENDING_REVIEW".equals(item.status())) {
            throw new IllegalStateException("staged change " + id + " is " + item.status() + ", not pending");
        }
        return item;
    }

    private void decide(long id, String status, String actor) {
        jdbc.sql("update staged_change set status = :s, decided_at = :now, decided_by = :by where id = :id")
                .param("s", status).param("now", Timestamp.from(clock.instant())).param("by", actor).param("id", id).update();
    }
}
