package com.pathwayai.backend.refresh;

import com.pathwayai.backend.provider.studyinfo.StudyinfoAdapter;
import com.pathwayai.backend.provider.studyinfo.StudyinfoImporter;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import com.pathwayai.backend.refresh.report.MonitoringReportService;
import com.pathwayai.backend.refresh.schedule.RefreshService;
import com.pathwayai.backend.refresh.pipeline.StagedChangeService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Operations entry point, run once and then the JVM ends (start with {@code --spring.main.web-application-type=none}):
 * <pre>
 *   --app.refresh.command=sync                        the unattended pass: discover new Studyinfo programs, then refresh
 *                                                     everything that is due (use this from an external scheduler)
 *   --app.refresh.command=run                         refresh every monitor that is due, without discovering new programs
 *   --app.refresh.command=report                      print the monitoring report
 *   --app.refresh.command=reactivate [--app.refresh.id=SOURCE_ID]   put unsupported/failing/paused sources back on schedule
 *   --app.refresh.command=studyinfo-import [--app.refresh.dry-run=true] [--app.refresh.university=slug] [--app.refresh.limit=N]
 *                                                     import programs from the Studyinfo official API (reviewed university list)
 *   --app.refresh.command=studyinfo-sync              re-read every Studyinfo program now and publish or stage what changed
 *   --app.refresh.command=assign-adapter --app.refresh.id=SOURCE_ID --app.refresh.adapter=NAME [--app.refresh.university=slug]
 *                                                     read one source with a named extraction adapter (re-baselines it;
 *                                                     a university binds a source that states facts for several programs)
 *   --app.refresh.command=sync-adapter --app.refresh.adapter=NAME   re-read every active source of one adapter now
 *   --app.refresh.command=freshness-check [--app.refresh.max-age-hours=240]
 *                                                     last-success monitoring: exits non-zero unless EVERY program kept current by an
 *                                                     extractor was read successfully within that many hours (default 240 = 10 days)
 *   --app.refresh.fail-on-failures=true [--app.refresh.failure-ratio-threshold=0.5]
 *                                                     exit non-zero when more than that share of the checks failed or were
 *                                                     blocked (default 0 = any failure); a single flaky site stays visible in
 *                                                     the report (FAILING) without failing the job
 *   --app.refresh.command=review-list                list changes awaiting review
 *   --app.refresh.command=review-approve --app.refresh.id=N [--app.refresh.actor=name]
 *   --app.refresh.command=review-reject  --app.refresh.id=N [--app.refresh.reason=text] [--app.refresh.actor=name]
 * </pre>
 * There is deliberately no HTTP equivalent: no public route can register sources, trigger a crawl or publish a change.
 */
@Component
@ConditionalOnProperty(name = "app.refresh.command")
public class RefreshCli implements ApplicationRunner {

    private final RefreshService refresh;
    private final SyncOrchestrator orchestrator;
    private final MonitoringReportService report;
    private final StagedChangeService review;
    private final SourceMonitorRepository monitors;
    private final Clock clock;
    private final String command;
    private final Long id;
    private final String actor;
    private final String reason;
    private final StudyinfoImporter studyinfo;
    private final boolean dryRun;
    private final String university;
    private final Integer limit;
    private final String adapter;
    private final boolean failOnFailures;
    private final double failureRatioThreshold;
    private final long maxAgeHours;

    public RefreshCli(RefreshService refresh, SyncOrchestrator orchestrator, MonitoringReportService report,
                      StagedChangeService review, SourceMonitorRepository monitors, Clock clock, @Value("${app.refresh.command}") String command,
                      @Value("${app.refresh.id:#{null}}") Long id, @Value("${app.refresh.actor:cli}") String actor,
                      @Value("${app.refresh.reason:#{null}}") String reason, StudyinfoImporter studyinfo,
                      @Value("${app.refresh.dry-run:false}") boolean dryRun,
                      @Value("${app.refresh.university:#{null}}") String university,
                      @Value("${app.refresh.limit:#{null}}") Integer limit,
                      @Value("${app.refresh.adapter:#{null}}") String adapter,
                      @Value("${app.refresh.fail-on-failures:false}") boolean failOnFailures,
                      @Value("${app.refresh.failure-ratio-threshold:0.0}") double failureRatioThreshold,
                      @Value("${app.refresh.max-age-hours:240}") long maxAgeHours) {
        this.refresh = refresh;
        this.orchestrator = orchestrator;
        this.report = report;
        this.review = review;
        this.monitors = monitors;
        this.clock = clock;
        this.command = command;
        this.id = id;
        this.actor = actor;
        this.reason = reason;
        this.studyinfo = studyinfo;
        this.dryRun = dryRun;
        this.university = university;
        this.limit = limit;
        this.adapter = adapter;
        this.failOnFailures = failOnFailures;
        this.failureRatioThreshold = failureRatioThreshold;
        this.maxAgeHours = maxAgeHours;
    }

    @Override
    public void run(ApplicationArguments args) {
        switch (command) {
            case "sync" -> {
                var result = orchestrator.runAll(clock.instant());
                if (result.discovery() != null) {
                    var d = result.discovery();
                    System.out.println("Discovery: created " + d.created() + ", already imported " + d.alreadyImported()
                            + ", skipped " + d.skipped() + ", failed " + d.failed());
                }
                finish("Refresh run: ", result.refresh());
            }
            case "freshness-check" -> {
                var f = monitors.extractorFreshness(clock.instant().minus(java.time.Duration.ofHours(maxAgeHours)));
                System.out.println("Freshness (max age " + maxAgeHours + " h): " + f.fresh() + " of " + f.total()
                        + " extractor-maintained program(s) are fresh; never succeeded " + f.neverSucceeded() + ", stale " + f.stale()
                        + ", not active " + f.notActive() + "; newest success " + f.newestSuccess() + ", oldest " + f.oldestSuccess());
                if (f.total() == 0 || f.fresh() < f.total()) {
                    throw new IllegalStateException("synchronization is not current: " + (f.total() - f.fresh()) + " of " + f.total()
                            + " extractor-maintained program(s) were not read successfully within " + maxAgeHours + " hours"
                            + (f.total() == 0 ? " (there are none)" : ""));
                }
            }
            case "run" -> finish("Refresh run: ", refresh.runAllDue(clock.instant()));
            case "report" -> System.out.println(report.generate(clock.instant()).render());
            case "reactivate" -> System.out.println("Reactivated sources: " + monitors.reactivate(id, clock.instant()));
            case "studyinfo-import" -> {
                var summary = studyinfo.run(dryRun, university, limit);
                summary.notes().forEach(n -> System.out.println("  " + n));
                System.out.println((dryRun ? "DRY RUN (nothing written): " : "") + "Studyinfo import: " + summary.universities()
                        + " universities, created " + summary.created() + ", already imported " + summary.alreadyImported()
                        + ", skipped " + summary.skipped() + ", failed " + summary.failed());
            }
            case "studyinfo-sync" -> {
                int due = monitors.markDue(StudyinfoAdapter.NAME, clock.instant());
                System.out.println("Studyinfo programs made due: " + due);
                finish("Refresh run: ", refresh.runDueForAdapter(StudyinfoAdapter.NAME, clock.instant()));
            }
            case "assign-adapter" -> {
                if (adapter == null || !refresh.hasAdapter(adapter)) {
                    throw new IllegalArgumentException("--app.refresh.adapter must name a registered adapter, got: " + adapter);
                }
                Long uni = null;
                if (university != null) {
                    uni = monitors.universityIdBySlug(university)
                            .orElseThrow(() -> new IllegalArgumentException("no university with slug " + university));
                }
                System.out.println("Source " + requireId() + " now read by " + adapter + ": "
                        + monitors.assignAdapter(requireId(), adapter, "1", uni, clock.instant()) + " row(s)");
            }
            case "sync-adapter" -> {
                if (adapter == null || !refresh.hasAdapter(adapter)) {
                    throw new IllegalArgumentException("--app.refresh.adapter must name a registered adapter, got: " + adapter);
                }
                System.out.println("Sources made due: " + monitors.markDue(adapter, clock.instant()));
                finish("Refresh run: ", refresh.runDueForAdapter(adapter, clock.instant()));
            }
            case "review-list" -> {
                var items = review.pending();
                System.out.println(items.size() + " change(s) awaiting review");
                items.forEach(i -> System.out.printf("#%d %s%s program=%s risk=%s%n    old: %s%n    new: %s%n    why: %s%n    source: %s%n",
                        i.id(), i.factType(), i.factKey().isEmpty() ? "" : "[" + i.factKey() + "]", i.programId(), i.risk(),
                        i.oldValue(), i.newValue(), i.reason(), i.sourceUrl()));
            }
            case "review-approve" -> {
                review.approve(requireId(), actor);
                System.out.println("Approved #" + id);
            }
            case "review-reject" -> {
                review.reject(requireId(), actor, reason);
                System.out.println("Rejected #" + id);
            }
            default -> throw new IllegalArgumentException("unknown app.refresh.command: " + command);
        }
    }

    /** Prints the summary and, when asked, turns failed checks into a failing exit code so a scheduler can alert. */
    private void finish(String label, RefreshService.RunSummary summary) {
        System.out.println(label + summary);
        int bad = summary.failed() + summary.blocked();
        if (failOnFailures && summary.checked() > 0 && (double) bad / summary.checked() > failureRatioThreshold) {
            throw new IllegalStateException("refresh finished with failures: " + bad + " of " + summary.checked()
                    + " checks failed or were blocked (threshold " + failureRatioThreshold + "), rejectedFacts=" + summary.rejectedFacts());
        }
    }

    private long requireId() {
        if (id == null) {
            throw new IllegalArgumentException("--app.refresh.id=N is required for " + command);
        }
        return id;
    }
}
