package com.pathwayai.backend.refresh.pipeline;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractedFact.FactType;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer;
import com.pathwayai.backend.refresh.pipeline.ProgramFacts.Comparison;
import com.pathwayai.backend.refresh.pipeline.ProgramFacts.Kind;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * NORMALIZE → VALIDATE → COMPARE → STAGE → APPLY OR REVIEW. Nothing extracted from a page is published directly:
 * every proposed change is written to {@code staged_change} first.
 *
 * <p>Policy (see {@link SyncPolicy}). A fill-in of an empty, low-impact field read from structured data by a
 * deterministic adapter is published automatically. From an OFFICIAL API connector, a different duration, teaching
 * languages or campus city replaces the published value automatically (the old value is archived), and a new application
 * round is added automatically. Everything else, in particular tuition, requirements, identity (name, level, field)
 * and any change to an existing dated value, waits for review, so a badly parsed value can never replace a verified fee,
 * requirement or classification. Processing the same page twice stages nothing new
 * (the dedupe key covers source, fact and value).
 */
@Service
public class ChangePipeline {

    public static final String AUTO_ACTOR = "system:auto";
    private static final Set<FactType> LOW_RISK = Set.of(FactType.DURATION_MONTHS, FactType.LANGUAGES, FactType.CAMPUS_CITY);

    /** What one pass did, with the reasons for anything rejected. */
    public record Outcome(int autoApplied, int pendingReview, int unchanged, int alreadySeen, List<String> rejected) {
        public int rejectedCount() {
            return rejected.size();
        }
    }

    private final JdbcClient jdbc;
    private final ProgramFacts facts;
    private final Clock clock;

    public ChangePipeline(JdbcClient jdbc, ProgramFacts facts, Clock clock) {
        this.jdbc = jdbc;
        this.facts = facts;
        this.clock = clock;
    }

    @Transactional
    public Outcome process(SourceMonitor monitor, List<ExtractedFact> extracted, String parserVersion, Instant now) {
        return process(monitor, extracted, parserVersion, now, false);
    }

    /**
     * {@code initialImport} is true only when an operator-run import has just created the program: every fact the
     * official source states for a still-empty field is then published (and audited in staged_change). It never
     * overrides a value that is already there: any difference is still staged for review.
     */
    @Transactional
    public Outcome process(SourceMonitor monitor, List<ExtractedFact> extracted, String parserVersion, Instant now,
                           boolean initialImport) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<String> rejected = new ArrayList<>();
        // NORMALIZE
        List<ExtractedFact> normalized = extracted.stream().map(FactNormalizer::normalize).toList();
        // contradictions inside one extraction: two different values for the same fact are both untrustworthy
        Map<String, Set<String>> valuesByFact = new LinkedHashMap<>();
        for (ExtractedFact f : normalized) {
            valuesByFact.computeIfAbsent(f.type() + "|" + f.key(), k -> new java.util.HashSet<>()).add(f.canonicalValue());
        }
        int autoApplied = 0, pending = 0, unchanged = 0, seen = 0;
        for (ExtractedFact f : normalized) {
            String id = f.type() + (f.key().isEmpty() ? "" : "[" + f.key() + "]");
            if (valuesByFact.get(f.type() + "|" + f.key()).size() > 1) {
                rejected.add(id + ": the page states contradictory values");
                continue;
            }
            // VALIDATE
            List<String> errors = FactValidator.validate(f, monitor.programId() != null, today);
            if (!errors.isEmpty()) {
                rejected.add(id + ": " + String.join("; ", errors));
                continue;
            }
            // COMPARE
            Comparison cmp = f.type() == FactType.SOURCE_CONTENT ? new Comparison(Kind.NEW, null)
                    : facts.compare(monitor.programId(), f.type(), f.values());
            if (cmp.kind() == Kind.UNCHANGED) {
                unchanged++;
                continue;
            }
            // STAGE + APPLY OR REVIEW
            boolean official = SyncPolicy.isOfficialApi(monitor.adapterName()) && "ADAPTER".equals(monitor.extractionMethod());
            boolean fill = cmp.kind() == Kind.NEW
                    && (initialImport || LOW_RISK.contains(f.type()) && !"CONTENT_HASH".equals(monitor.extractionMethod())
                    || official && SyncPolicy.AUTO_ADD.contains(f.type()));
            boolean replace = cmp.kind() == Kind.CONFLICT && official && SyncPolicy.AUTO_REPLACE.contains(f.type());
            boolean auto = fill || replace;
            String reason = replace
                    ? "official API now states a different value (was " + cmp.existing() + "): published automatically, old value archived"
                    : cmp.kind() == Kind.CONFLICT
                    ? "differs from the published value (" + cmp.existing() + "): review before replacing"
                    : auto ? (initialImport ? "initial import of a new program from the official source"
                            : official && SyncPolicy.AUTO_ADD.contains(f.type()) ? "new " + f.type() + " stated by the official API"
                            : "empty field filled from structured data") : "new " + f.type() + " read from a monitored page: review before publishing";
            // an automatic replacement may legitimately return to a value held before, so its audit row is keyed by time too
            Long stagedId = stage(monitor, f, cmp.existing(), auto ? "LOW" : "HIGH", auto ? "AUTO_APPLIED" : "PENDING_REVIEW",
                    reason, parserVersion, now, auto, replace ? cmp.existing() + "@" + now.toEpochMilli() : "");
            if (stagedId == null) {
                seen++;
                continue;
            }
            if (auto) {
                facts.apply(monitor.programId(), monitor.sourceId(), f.type(), f.values(), now, parserVersion);
                autoApplied++;
            } else {
                pending++;
            }
        }
        return new Outcome(autoApplied, pending, unchanged, seen, rejected);
    }

    /** A page changed but no adapter can say what: ask a human to look, once per distinct content. */
    @Transactional
    public boolean stageContentChange(SourceMonitor monitor, String contentHash, String parserVersion, Instant now) {
        ExtractedFact f = ExtractedFact.of(FactType.SOURCE_CONTENT, "", Map.of("contentHash", contentHash));
        Long id = stage(monitor, f, monitor.contentHash(), "LOW", "PENDING_REVIEW",
                "page content changed and no adapter extracts facts from it: re-read the page", parserVersion, now, false, "");
        if (id != null) {
            jdbc.sql("""
                    update staged_change set status = 'SUPERSEDED', decided_at = :now, decided_by = :by
                    where source_id = :s and fact_type = 'SOURCE_CONTENT' and status = 'PENDING_REVIEW' and id <> :id""")
                    .param("now", Timestamp.from(now)).param("by", AUTO_ACTOR).param("s", monitor.sourceId()).param("id", id).update();
        }
        return id != null;
    }

    /** Inserts the staged row; returns its id, or null if this exact change was already staged earlier. */
    private Long stage(SourceMonitor m, ExtractedFact f, String oldValue, String risk, String status, String reason,
                       String parserVersion, Instant now, boolean decided, String dedupeSalt) {
        String newValue = f.canonicalValue();
        String key = PageCanonicalizer.sha256(m.sourceId() + "|" + f.type() + "|" + f.key() + "|" + newValue
                + (dedupeSalt.isEmpty() ? "" : "|" + dedupeSalt));
        return jdbc.sql("""
                insert into staged_change (source_id, university_id, program_id, fact_type, fact_key, old_value, new_value,
                    risk, status, reason, parser_version, dedupe_key, detected_at, decided_at, decided_by)
                values (:s, :u, :p, :t, :k, :old, :new, :risk, :status, :reason, :pv, :dk, :now, :decidedAt, :by)
                on conflict (dedupe_key) do nothing returning id""")
                .param("s", m.sourceId()).param("u", m.universityId()).param("p", m.programId()).param("t", f.type().name())
                .param("k", f.key()).param("old", oldValue).param("new", newValue).param("risk", risk).param("status", status)
                .param("reason", reason).param("pv", parserVersion).param("dk", key).param("now", Timestamp.from(now))
                .param("decidedAt", decided ? Timestamp.from(now) : null).param("by", decided ? AUTO_ACTOR : null)
                .query(Long.class).optional().orElse(null);
    }
}
