package com.pathwayai.backend.program.dto;

import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.entity.StudyMode;
import com.pathwayai.backend.program.entity.VerificationStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * One row of {@code GET /api/v1/programs}. Unknown values are {@code null} (or an empty list), never
 * placeholders. The application URL is only present for a VERIFIED program.
 */
public record ProgramListItemResponse(
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
        Verification verification) {

    public record UniversityRef(String slug, String name, String city, String countryCode, String countryName) {
    }

    public record FieldRef(String slug, String name) {
    }

    /** {@code verifiedOn} is the date the record was last checked against its official sources. */
    public record Verification(VerificationStatus status, LocalDate verifiedOn) {
    }
}
