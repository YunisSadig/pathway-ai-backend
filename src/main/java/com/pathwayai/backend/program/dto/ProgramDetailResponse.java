package com.pathwayai.backend.program.dto;

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
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /api/v1/programs/{identifier}}. Every changing fact carries the official source it came from
 * (its {@code retrievedOn} is the date it was checked). Empty lists mean "nothing known", not "none exist".
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
        String officialApplicationUrl,
        Verification verification,
        List<SourceRef> sources,
        List<Intake> intakes,
        List<Tuition> tuition,
        List<Requirement> requirements) {

    public record SourceRef(String url, String title, String publisher, SourceType type, LocalDate retrievedOn) {
    }

    public record Intake(String label, String academicYear, LocalDate startDate, LocalDate applicationDeadline,
                         StudentCategory applicantCategory, SourceRef source) {
    }

    public record Tuition(String academicYear, StudentCategory studentCategory, BigDecimal amount, String currency,
                          BillingPeriod billingPeriod, SourceRef source) {
    }

    public record Requirement(String applicationCycle, RequirementType type, String description,
                              BigDecimal minimumValue, String valueUnit, SourceRef source) {
    }
}
