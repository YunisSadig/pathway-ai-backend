package com.pathwayai.backend.refresh.schedule;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-process scheduler, OFF by default ({@code app.refresh.scheduler.enabled=true} to switch on). It is only suitable
 * for a server that is guaranteed to be running; a laptop that sleeps or a free host that suspends the app will
 * silently skip runs. For unattended monitoring run {@code app.refresh.command=run} from an external scheduler
 * (see docs/data/automated-refresh.md). Both paths are safe to repeat: due-ness is stored in the database.
 */
@Component
@ConditionalOnProperty(name = "app.refresh.scheduler.enabled", havingValue = "true")
public class RefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshScheduler.class);

    private final RefreshService refresh;
    private final Clock clock;

    public RefreshScheduler(RefreshService refresh, Clock clock) {
        this.refresh = refresh;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.refresh.scheduler.cron:0 0 5 * * *}", zone = "UTC")
    public void run() {
        var summary = refresh.runAllDue(clock.instant());
        log.info("Scheduled source refresh finished: {}", summary);
    }
}
