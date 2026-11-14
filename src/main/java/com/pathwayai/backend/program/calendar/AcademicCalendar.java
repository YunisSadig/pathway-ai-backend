package com.pathwayai.backend.program.calendar;

import java.time.Clock;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Classifies year-specific facts against the clock. An academic year is taken to start on 1 August, so in
 * November 2026 the current academic year is 2026/2027 and 2027/2028 is upcoming. Pure functions of the facts
 * and the clock: no database access.
 */
@Component
public class AcademicCalendar {

    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(\\d{4})(?!\\d)");

    private final Clock clock;

    public AcademicCalendar(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Start year of the academic year that contains today (2026 for 2026-11-11 and for 2027-03-01 it is 2026). */
    public int currentStartYear() {
        LocalDate d = today();
        return d.getMonthValue() >= 8 ? d.getYear() : d.getYear() - 1;
    }

    /** First four-digit year in a label such as "2026/2027" or "2027"; -1 when there is none. */
    static int startYear(String label) {
        if (label == null) {
            return -1;
        }
        Matcher m = YEAR.matcher(label);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private TemporalStatus byYearLabel(String label) {
        int year = startYear(label);
        if (year < 0) {
            return TemporalStatus.UNKNOWN_YEAR;
        }
        return year >= currentStartYear() ? TemporalStatus.CURRENT : TemporalStatus.HISTORICAL;
    }

    /** Tuition: the printed academic year wins; otherwise the start-date range the source states. */
    public TemporalStatus tuition(String academicYear, LocalDate effectiveFrom, LocalDate effectiveTo) {
        if (academicYear != null) {
            return byYearLabel(academicYear);
        }
        if (effectiveTo != null && effectiveTo.isBefore(today())) {
            return TemporalStatus.HISTORICAL;
        }
        if (effectiveFrom != null || effectiveTo != null) {
            return TemporalStatus.CURRENT;
        }
        return TemporalStatus.UNKNOWN_YEAR;
    }

    /** Intake: a stated deadline decides (it carries its own year); then the academic year; then the start date. */
    public TemporalStatus intake(String academicYear, LocalDate applicationDeadline, LocalDate startDate) {
        if (applicationDeadline != null) {
            return applicationDeadline.isBefore(today()) ? TemporalStatus.HISTORICAL : TemporalStatus.CURRENT;
        }
        if (academicYear != null) {
            return byYearLabel(academicYear);
        }
        if (startDate != null) {
            return startDate.isBefore(today()) ? TemporalStatus.HISTORICAL : TemporalStatus.CURRENT;
        }
        return TemporalStatus.UNKNOWN_YEAR;
    }

    /** Requirement: classified by its application cycle label. */
    public TemporalStatus requirement(String applicationCycle) {
        return byYearLabel(applicationCycle);
    }

    /** True when an application link verified for this cycle may be presented as an active Apply link. */
    public boolean isActiveCycle(String cycle) {
        return byYearLabel(cycle) == TemporalStatus.CURRENT;
    }
}
