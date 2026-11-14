package com.pathwayai.backend.refresh.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CheckScheduleTest {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void generalInformationIsCheckedWeekly() {
        assertThat(CheckSchedule.next(NOW, 7, false, 1, 0)).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void anApproachingVerifiedDeadlineMakesTheCheckDaily() {
        assertThat(CheckSchedule.next(NOW, 7, true, 1, 0)).isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void theDailyWindowNeverLengthensAShorterConfiguredInterval() {
        assertThat(CheckSchedule.next(NOW, 1, true, 3, 0)).isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void failuresBackOffExponentiallyButNeverBeyondTheNormalInterval() {
        assertThat(CheckSchedule.next(NOW, 7, false, 1, 1)).isEqualTo(NOW.plus(Duration.ofHours(6)));
        assertThat(CheckSchedule.next(NOW, 7, false, 1, 2)).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(CheckSchedule.next(NOW, 7, false, 1, 3)).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(CheckSchedule.next(NOW, 7, false, 1, 50)).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void aFailingSourceInsideTheDeadlineWindowRetriesNoLaterThanDaily() {
        assertThat(CheckSchedule.next(NOW, 7, true, 1, 9)).isEqualTo(NOW.plus(Duration.ofDays(1)));
    }
}
