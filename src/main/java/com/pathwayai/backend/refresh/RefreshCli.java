package com.pathwayai.backend.refresh;

import com.pathwayai.backend.provider.studyinfo.StudyinfoAdapter;
import com.pathwayai.backend.provider.studyinfo.StudyinfoImporter;
import com.pathwayai.backend.refresh.registry.MonitorRegistrar;
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
 *   --app.refresh.command=register                    register the official pages already cited as monitored sources
 *   --app.refresh.command=run                         check every source that is due (use this from an external scheduler)
 *   --app.refresh.command=report                      print the monitoring report
 *   --app.refresh.command=reactivate [--app.refresh.id=SOURCE_ID]   put unsupported/failing/paused sources back on schedule
 *   --app.refresh.command=studyinfo-import [--app.refresh.dry-run=true] [--app.refresh.university=slug] [--app.refresh.limit=N]
 *                                                     import programs from the Studyinfo official API (reviewed university list)
 *   --app.refresh.command=studyinfo-sync              re-read every Studyinfo program now and publish or stage what changed
 *   --app.refresh.command=assign-adapter --app.refresh.id=SOURCE_ID --app.refresh.adapter=NAME
 *                                                     read one source with a named extraction adapter (re-baselines it)
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
    private final MonitorRegistrar registrar;
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

    public RefreshCli(RefreshService refresh, MonitorRegistrar registrar, MonitoringReportService report,
                      StagedChangeService review, SourceMonitorRepository monitors, Clock clock, @Value("${app.refresh.command}") String command,
                      @Value("${app.refresh.id:#{null}}") Long id, @Value("${app.refresh.actor:cli}") String actor,
                      @Value("${app.refresh.reason:#{null}}") String reason, StudyinfoImporter studyinfo,
                      @Value("${app.refresh.dry-run:false}") boolean dryRun,
                      @Value("${app.refresh.university:#{null}}") String university,
                      @Value("${app.refresh.limit:#{null}}") Integer limit,
                      @Value("${app.refresh.adapter:#{null}}") String adapter) {
        this.refresh = refresh;
        this.registrar = registrar;
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
    }

    @Override
    public void run(ApplicationArguments args) {
        switch (command) {
            case "register" -> System.out.println("Registered sources: " + registrar.registerAll(clock.instant()));
            case "run" -> System.out.println("Refresh run: " + refresh.runAllDue(clock.instant()));
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
                System.out.println("Refresh run: " + refresh.runDueForAdapter(StudyinfoAdapter.NAME, clock.instant()));
            }
            case "assign-adapter" -> {
                if (adapter == null || !refresh.hasAdapter(adapter)) {
                    throw new IllegalArgumentException("--app.refresh.adapter must name a registered adapter, got: " + adapter);
                }
                System.out.println("Source " + requireId() + " now read by " + adapter + ": "
                        + monitors.assignAdapter(requireId(), adapter, "1", clock.instant()) + " row(s)");
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

    private long requireId() {
        if (id == null) {
            throw new IllegalArgumentException("--app.refresh.id=N is required for " + command);
        }
        return id;
    }
}
