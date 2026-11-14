package com.pathwayai.backend.program.dto;

import com.pathwayai.backend.program.calendar.TemporalStatus;
import com.pathwayai.backend.program.dto.ProgramListItemResponse.FieldRef;
import com.pathwayai.backend.program.dto.ProgramListItemResponse.UniversityRef;
import com.pathwayai.backend.program.dto.ProgramListItemResponse.Verification;
import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.entity.StudyMode;
import com.pathwayai.backend.requirement.entity.RequirementType;
import com.pathwayai.backend.source.entity.SourceType;
import com.pathwayai.backend.tuition.entity.BillingPeriod;
import com.pathwayai.backend.tuition.entity.StudentCategory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /api/v1/programs/{identifier}}. Every changing fact carries the official source it came from
 * (its {@code retrievedOn} is the date it was read) and a {@code temporalStatus} describing the period the
 * source attached to it. A {@code HISTORICAL} value must never be shown as current; {@code UNKNOWN_YEAR} means
 * the source stated no period. Empty lists mean "nothing known", not "none exist".
 */
public record ProgramDetailResponse(
        String identifier,
        String slug,
        String name,
        DegreeLevel degreeLevel,
        FieldRef field,
        StudyMode studyMode,
        Integer durationMonths,
        List<String> teachingLanguages,
        String campusCity,
        UniversityRef university,
        String officialProgramUrl,
        String officialAdmissionsUrl,
        String officialApplicationUrl,
        Verification verification,
        List<SourceRef> sources,
        List<Intake> intakes,
        List<Tuition> tuition,
        List<Requirement> requirements,
        Synchronization synchronization) {

    public ProgramDetailResponse withSynchronization(Synchronization s) {
        return new ProgramDetailResponse(identifier, slug, name, degreeLevel, field, studyMode, durationMonths, teachingLanguages,
                campusCity, university, officialProgramUrl, officialAdmissionsUrl, officialApplicationUrl, verification, sources,
                intakes, tuition, requirements, s);
    }

    /**
     * How this program is kept current. {@code status} is AUTOMATIC when a working extractor of an official source maintains
     * it, NOT_SYNCHRONIZED otherwise. Per field, {@code updateMode} says whether a change at the source is published
     * automatically (AUTOMATIC), added automatically but never overwritten (ADDITIVE_AUTOMATIC), held for approval
     * (REVIEW_REQUIRED, the old value stays shown until then) or fixed when the program was created (FIXED_AT_CREATION).
     * {@code notSynchronized} lists facts no connector maintains for this program: they are omitted, not guessed.
     */
    public record Synchronization(String status, String connector, String sourceKind, Instant lastSuccessfulSyncAt,
                                  Instant nextCheckDueAt, boolean fresh, int freshnessWindowDays, List<FieldSync> fields,
                                  List<String> notSynchronized) {
    }

    public record FieldSync(String field, String updateMode, LocalDate verifiedOn) {
    }

    public record SourceRef(String url, String title, String publisher, SourceType type, LocalDate retrievedOn) {
    }

    public record Intake(String label, String academicYear, LocalDate startDate, LocalDate applicationDeadline,
                         StudentCategory applicantCategory, TemporalStatus temporalStatus, SourceRef source) {
    }

    /**
     * {@code academicYear} is only set when the source prints one; otherwise the period may be a start-date range
     * ({@code effectiveFrom}/{@code effectiveTo}, inclusive, either end open) or unknown. {@code scopeNote} is the
     * source's own wording.
     */
    public record Tuition(String academicYear, LocalDate effectiveFrom, LocalDate effectiveTo, String scopeNote,
                          StudentCategory studentCategory, BigDecimal amount, String currency,
                          BillingPeriod billingPeriod, TemporalStatus temporalStatus, SourceRef source) {
    }

    public record Requirement(String applicationCycle, RequirementType type, String description,
                              BigDecimal minimumValue, String valueUnit, TemporalStatus temporalStatus,
                              SourceRef source) {
    }
}
