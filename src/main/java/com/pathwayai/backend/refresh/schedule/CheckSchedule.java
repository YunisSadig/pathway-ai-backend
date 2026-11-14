package com.pathwayai.backend.refresh.schedule;

import java.time.Duration;
import java.time.Instant;

/**
 * When a source is next checked. Starting policy: weekly for general information, programme and admissions pages,
 * tuition and scholarships; daily while a verified application deadline falls inside the configured window.
 * Failures back off (6 h, 12 h, 24 h, ...) but never beyond the normal interval, so a source is never abandoned
 * and never hammered. These are starting values, not a claim that every institution needs the same frequency.
 */
public final class CheckSchedule {

    private static final Duration FIRST_BACKOFF = Duration.ofHours(6);

    private CheckSchedule() {
    }

    public static Instant next(Instant now, int intervalDays, boolean deadlineWithinWindow, int deadlineIntervalDays,
                               int failureCount) {
        Duration normal = Duration.ofDays(deadlineWithinWindow ? Math.min(deadlineIntervalDays, intervalDays) : intervalDays);
        if (failureCount <= 0) {
            return now.plus(normal);
        }
        long factor = 1L << Math.min(failureCount - 1, 20);
        Duration backoff = FIRST_BACKOFF.multipliedBy(factor);
        return now.plus(backoff.compareTo(normal) > 0 ? normal : backoff);
    }
}
