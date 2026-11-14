package com.pathwayai.backend.refresh;

import com.pathwayai.backend.provider.studyinfo.StudyinfoImporter;
import com.pathwayai.backend.refresh.schedule.RefreshService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One unattended synchronization pass: OFFICIAL SOURCE -> FETCH -> EXTRACT -> VALIDATE -> DATABASE.
 *
 * <ol>
 *   <li><b>Discover</b> new programs the official API lists for the reviewed universities (within each university's quota).
 *       A program already carrying the provider's id is skipped without a request, so this is cheap and idempotent.</li>
 *   <li><b>Refresh</b> every monitor that is due: re-read the record, compare field by field, publish deterministic
 *       changes, stage the rest for review, hide programs the source says are discontinued.</li>
 * </ol>
 * Used by {@code --app.refresh.command=sync} (for an external scheduler) and by the optional in-process scheduler.
 */
@Service
public class SyncOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SyncOrchestrator.class);

    public record Result(StudyinfoImporter.Summary discovery, RefreshService.RunSummary refresh) {
    }

    private final StudyinfoImporter importer;
    private final RefreshService refresh;

    public SyncOrchestrator(StudyinfoImporter importer, RefreshService refresh) {
        this.importer = importer;
        this.refresh = refresh;
    }

    public Result runAll(Instant now) {
        StudyinfoImporter.Summary discovery = null;
        try {
            discovery = importer.run(false, null, null);
            discovery.notes().forEach(n -> log.info("Studyinfo discovery: {}", n));
        } catch (RuntimeException e) {
            // discovering new programs must never stop the refresh of the ones we already maintain
            log.warn("Studyinfo discovery failed, refreshing existing programs anyway: {}", e.toString());
        }
        return new Result(discovery, refresh.runAllDue(now));
    }
}
