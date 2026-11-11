package com.pathwayai.backend.program.calendar;

/**
 * How a year-specific fact relates to today. It describes the period the SOURCE attached to the fact, never the
 * date the page was read.
 *
 * <ul>
 *   <li>{@code CURRENT}: the period is the present or an upcoming academic cycle, as published.</li>
 *   <li>{@code HISTORICAL}: the period has ended. It is kept for context and must not be shown as current.</li>
 *   <li>{@code UNKNOWN_YEAR}: the source states no period, so nothing can be said about when it applies.</li>
 * </ul>
 */
public enum TemporalStatus {
    CURRENT, HISTORICAL, UNKNOWN_YEAR
}
