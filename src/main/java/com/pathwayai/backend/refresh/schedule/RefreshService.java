package com.pathwayai.backend.refresh.schedule;

import com.pathwayai.backend.refresh.RefreshProperties;
import com.pathwayai.backend.refresh.extract.ContentHashAdapter;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractionAdapter;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import com.pathwayai.backend.refresh.fetch.PageFetcher;
import com.pathwayai.backend.refresh.pipeline.ChangePipeline;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FETCH → EXTRACT → NORMALIZE → VALIDATE → COMPARE → STAGE → APPLY OR REVIEW → AUDIT for every source that is due.
 *
 * <p>Each source is processed in its own transaction, so one failing page cannot affect another. A failed fetch,
 * a challenge page, a parse error or a rollback never touches published facts: the last verified record stays,
 * the failure is logged in {@code source_check}, and the source's freshness fields tell the truth about it. A
 * temporary HTTP error is never read as "the programme was discontinued".
 */
@Service
public class RefreshService {

    private static final Logger log = LoggerFactory.getLogger(RefreshService.class);
    private static final int MAX_BATCHES_PER_RUN = 20;

    /** Counts for one run, by what happened to each checked source. */
    public record RunSummary(int checked, int baseline, int unchanged, int notModified, int changed, int failed,
                             int blocked, int rateLimited, int autoApplied, int pendingReview, int rejectedFacts) {
        static RunSummary merge(RunSummary a, RunSummary b) {
            return new RunSummary(a.checked + b.checked, a.baseline + b.baseline, a.unchanged + b.unchanged,
                    a.notModified + b.notModified, a.changed + b.changed, a.failed + b.failed, a.blocked + b.blocked,
                    a.rateLimited + b.rateLimited, a.autoApplied + b.autoApplied, a.pendingReview + b.pendingReview,
                    a.rejectedFacts + b.rejectedFacts);
        }

        static final RunSummary EMPTY = new RunSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private final SourceMonitorRepository monitors;
    private final PageFetcher fetcher;
    private final PageCanonicalizer canonicalizer;
    private final Map<String, ExtractionAdapter> adapters;
    private final ChangePipeline pipeline;
    private final RefreshProperties props;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public RefreshService(SourceMonitorRepository monitors, PageFetcher fetcher, PageCanonicalizer canonicalizer,
                          List<ExtractionAdapter> adapterList, ChangePipeline pipeline, RefreshProperties props,
                          JdbcClient jdbc, PlatformTransactionManager txManager) {
        this.monitors = monitors;
        this.fetcher = fetcher;
        this.canonicalizer = canonicalizer;
        this.adapters = adapterList.stream().collect(Collectors.toMap(ExtractionAdapter::name, Function.identity()));
        this.pipeline = pipeline;
        this.props = props;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Checks every due source, batch by batch, up to a bounded number of batches per run. */
    public RunSummary runAllDue(Instant now) {
        RunSummary total = RunSummary.EMPTY;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            RunSummary batch = runDue(now);
            total = RunSummary.merge(total, batch);
            if (batch.checked() < props.batchSize()) {
                break;
            }
        }
        return total;
    }

    /** One batch: up to {@code batchSize} sources whose next check is due. */
    public RunSummary runDue(Instant now) {
        RunSummary summary = RunSummary.EMPTY;
        for (SourceMonitor m : monitors.findDue(now, props.batchSize())) {
            RunSummary one;
            try {
                one = tx.execute(status -> checkOne(m, now));
            } catch (RuntimeException e) {
                log.warn("Check of source {} failed and was rolled back: {}", m.sourceId(), e.toString());
                one = tx.execute(status -> recordFailure(m, now, "processing error: " + e.getMessage()));
            }
            summary = RunSummary.merge(summary, one);
        }
        return summary;
    }

    private RunSummary checkOne(SourceMonitor m, Instant now) {
        PageFetcher.Result r = fetcher.fetch(m.url(), m.etag(), m.lastModified());
        boolean window = deadlineWithinWindow(m, now);
        switch (r.kind()) {
            case NOT_MODIFIED -> {
                save(m.with(f -> {
                    success(f, now);
                    f.lastResult = "NOT_MODIFIED";
                    f.nextCheckAt = CheckSchedule.next(now, m.checkIntervalDays(), window, props.deadlineIntervalDays(), 0);
                }));
                monitors.logCheck(m.sourceId(), now, "NOT_MODIFIED", r.httpStatus(), m.contentHash(), r.durationMs(), m.hashParserVersion(), null);
                return count("notModified");
            }
            case BLOCKED -> {
                save(m.with(f -> {
                    f.status = SourceMonitor.UNSUPPORTED;
                    f.unsupportedReason = truncate(r.error(), 300);
                    f.lastAttemptAt = now;
                    f.lastResult = "BLOCKED";
                }));
                monitors.logCheck(m.sourceId(), now, "BLOCKED", r.httpStatus() == 0 ? null : r.httpStatus(), null, r.durationMs(), null, r.error());
                return count("blocked");
            }
            case RATE_LIMITED -> {
                Instant retryAt = now.plus(r.retryAfter() == null ? Duration.ofHours(1) : r.retryAfter());
                save(m.with(f -> {
                    f.lastAttemptAt = now;
                    f.lastResult = "RATE_LIMITED";
                    f.nextCheckAt = retryAt;
                }));
                monitors.logCheck(m.sourceId(), now, "RATE_LIMITED", r.httpStatus(), null, r.durationMs(), null, r.error());
                return count("rateLimited");
            }
            case FAILED -> {
                return recordFailure(m, now, r.error(), r.httpStatus() == 0 ? null : r.httpStatus(), r.durationMs());
            }
            default -> {
                return handleContent(m, r, now, window);
            }
        }
    }

    private RunSummary handleContent(SourceMonitor m, PageFetcher.Result r, Instant now, boolean window) {
        CanonicalPage page = canonicalizer.canonicalize(r.body(), r.contentType());
        ExtractionAdapter adapter = adapters.getOrDefault(m.adapterName(), adapters.get(ContentHashAdapter.NAME));
        String parser = PageCanonicalizer.VERSION + "+" + adapter.name() + "@" + adapter.version();

        boolean baseline = m.contentHash() == null;
        boolean parserChanged = !baseline && !parser.equals(m.hashParserVersion());
        boolean changed = !baseline && !parserChanged && !page.hash().equals(m.contentHash());
        String outcome = baseline || parserChanged ? "BASELINE" : changed ? "CHANGED" : "UNCHANGED";

        ChangePipeline.Outcome result = new ChangePipeline.Outcome(0, 0, 0, 0, List.of());
        if (baseline || parserChanged || changed) {
            List<ExtractedFact> facts = adapter.extract(page);
            result = pipeline.process(m, facts, parser, now);
            if (changed && facts.isEmpty() && adapter.raisesReviewItems()) {
                if (pipeline.stageContentChange(m, page.hash(), parser, now)) {
                    result = new ChangePipeline.Outcome(0, 1, 0, 0, List.of());
                }
            }
        }
        String note = result.rejectedCount() > 0 ? "rejected facts: " + String.join(" | ", result.rejected()) : null;
        save(m.with(f -> {
            success(f, now);
            f.etag = r.etag();
            f.lastModified = r.lastModified();
            f.contentHash = page.hash();
            f.hashParserVersion = parser;
            f.lastResult = outcome;
            if ("CHANGED".equals(outcome)) {
                f.lastChangeAt = now;
            }
            f.nextCheckAt = CheckSchedule.next(now, m.checkIntervalDays(), window, props.deadlineIntervalDays(), 0);
        }));
        monitors.logCheck(m.sourceId(), now, outcome, r.httpStatus(), page.hash(), r.durationMs(), parser, note);
        RunSummary base = count(changed ? "changed" : baseline || parserChanged ? "baseline" : "unchanged");
        return new RunSummary(base.checked(), base.baseline(), base.unchanged(), 0, base.changed(), 0, 0, 0,
                result.autoApplied(), result.pendingReview(), result.rejectedCount());
    }

    private RunSummary recordFailure(SourceMonitor m, Instant now, String error) {
        return recordFailure(m, now, error, null, 0);
    }

    /** A failure keeps the last verified data and only updates freshness and the audit trail. */
    private RunSummary recordFailure(SourceMonitor m, Instant now, String error, Integer http, long durationMs) {
        int failures = m.failureCount() + 1;
        boolean window = deadlineWithinWindow(m, now);
        save(m.with(f -> {
            f.failureCount = failures;
            f.lastAttemptAt = now;
            f.lastResult = "FAILED";
            if (failures >= props.failureThreshold() && SourceMonitor.ACTIVE.equals(f.status)) {
                f.status = SourceMonitor.FAILING;
            }
            f.nextCheckAt = CheckSchedule.next(now, m.checkIntervalDays(), window, props.deadlineIntervalDays(), failures);
        }));
        monitors.logCheck(m.sourceId(), now, "FAILED", http, null, durationMs, null, error);
        return count("failed");
    }

    private void success(SourceMonitor.Fields f, Instant now) {
        f.lastAttemptAt = now;
        f.lastSuccessAt = now;
        f.failureCount = 0;
        f.status = SourceMonitor.ACTIVE;
        f.unsupportedReason = null;
    }

    private void save(SourceMonitor updated) {
        monitors.saveState(updated);
    }

    /** True while a verified application deadline for this source's programme falls inside the daily-check window. */
    boolean deadlineWithinWindow(SourceMonitor m, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return Boolean.TRUE.equals(jdbc.sql("""
                select exists (select 1 from program_intake i
                    where (i.program_id = :p or i.source_id = :s)
                      and i.application_deadline between :from and :to)""")
                .param("p", m.programId()).param("s", m.sourceId()).param("from", today)
                .param("to", today.plusDays(props.deadlineWindowDays())).query(Boolean.class).single());
    }

    private static RunSummary count(String what) {
        return new RunSummary(1, what.equals("baseline") ? 1 : 0, what.equals("unchanged") ? 1 : 0,
                what.equals("notModified") ? 1 : 0, what.equals("changed") ? 1 : 0, what.equals("failed") ? 1 : 0,
                what.equals("blocked") ? 1 : 0, what.equals("rateLimited") ? 1 : 0, 0, 0, 0);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
