package com.pathwayai.backend.program.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AcademicCalendarTest {

    private static AcademicCalendar on(String date) {
        return new AcademicCalendar(Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void theAcademicYearStartsInAugust() {
        assertThat(on("2026-11-11").currentStartYear()).isEqualTo(2026);
        assertThat(on("2027-03-01").currentStartYear()).isEqualTo(2026);
        assertThat(on("2027-07-31").currentStartYear()).isEqualTo(2026);
        assertThat(on("2027-08-01").currentStartYear()).isEqualTo(2027);
    }

    @Test
    void startYearReadsTheFirstFourDigitYearOnly() {
        assertThat(AcademicCalendar.startYear("2026/2027")).isEqualTo(2026);
        assertThat(AcademicCalendar.startYear("2027")).isEqualTo(2027);
        assertThat(AcademicCalendar.startYear("Autumn 2027")).isEqualTo(2027);
        assertThat(AcademicCalendar.startYear("no year")).isEqualTo(-1);
        assertThat(AcademicCalendar.startYear(null)).isEqualTo(-1);
        assertThat(AcademicCalendar.startYear("12345")).isEqualTo(-1);
    }

    @Test
    void tuitionIsClassifiedByThePeriodTheSourceStated() {
        var c = on("2026-11-11");
        assertThat(c.tuition("2026/2027", null, null)).isEqualTo(TemporalStatus.CURRENT);
        assertThat(c.tuition("2027/2028", null, null)).isEqualTo(TemporalStatus.CURRENT);
        assertThat(c.tuition("2025/2026", null, null)).isEqualTo(TemporalStatus.HISTORICAL);
        // a start-date range: open-ended from the past is still current; one that ended is historical
        assertThat(c.tuition(null, LocalDate.of(2025, 8, 1), null)).isEqualTo(TemporalStatus.CURRENT);
        assertThat(c.tuition(null, null, LocalDate.of(2024, 8, 1))).isEqualTo(TemporalStatus.HISTORICAL);
        // nothing stated: it is never guessed
        assertThat(c.tuition(null, null, null)).isEqualTo(TemporalStatus.UNKNOWN_YEAR);
    }

    @Test
    void intakesAreClassifiedByDeadlineThenYearThenStartDate() {
        var c = on("2026-11-11");
        assertThat(c.intake(null, LocalDate.of(2027, 1, 19), null)).isEqualTo(TemporalStatus.CURRENT);
        assertThat(c.intake(null, LocalDate.of(2026, 1, 19), null)).isEqualTo(TemporalStatus.HISTORICAL);
        assertThat(c.intake("2025/2026", null, null)).isEqualTo(TemporalStatus.HISTORICAL);
        assertThat(c.intake(null, null, LocalDate.of(2027, 9, 1))).isEqualTo(TemporalStatus.CURRENT);
        assertThat(c.intake(null, null, null)).isEqualTo(TemporalStatus.UNKNOWN_YEAR);
        // a stated deadline wins over a stale academic-year label
        assertThat(c.intake("2020/2021", LocalDate.of(2027, 1, 19), null)).isEqualTo(TemporalStatus.CURRENT);
    }

    @Test
    void theSameFactChangesStatusAsTimePasses() {
        var deadline = LocalDate.of(2027, 1, 19);
        assertThat(on("2026-11-11").intake(null, deadline, null)).isEqualTo(TemporalStatus.CURRENT);
        assertThat(on("2027-01-19").intake(null, deadline, null)).isEqualTo(TemporalStatus.CURRENT);
        assertThat(on("2027-01-20").intake(null, deadline, null)).isEqualTo(TemporalStatus.HISTORICAL);
        assertThat(on("2026-11-11").tuition("2026/2027", null, null)).isEqualTo(TemporalStatus.CURRENT);
        assertThat(on("2027-08-01").tuition("2026/2027", null, null)).isEqualTo(TemporalStatus.HISTORICAL);
    }

    @Test
    void anApplyLinkIsActiveOnlyForACurrentOrUpcomingCycle() {
        var c = on("2026-11-11");
        assertThat(c.isActiveCycle("2027")).isTrue();
        assertThat(c.isActiveCycle("2026/2027")).isTrue();
        assertThat(c.isActiveCycle("2025")).isFalse();
        assertThat(c.isActiveCycle("2025/2026")).isFalse();
        assertThat(c.isActiveCycle(null)).isFalse();
        assertThat(c.isActiveCycle("spring")).isFalse();
    }
}
