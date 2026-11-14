package com.pathwayai.backend.program.dto;

import com.pathwayai.backend.program.entity.DegreeLevel;
import com.pathwayai.backend.program.entity.StudyMode;
import com.pathwayai.backend.program.entity.VerificationStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * One row of {@code GET /api/v1/programs}. Unknown values are {@code null} (or an empty list), never
 * placeholders.
 *
 * <ul>
 *   <li>{@code officialAdmissionsUrl}: an official admissions-information page. Show it as information.</li>
 *   <li>{@code officialApplicationUrl}: an ACTIVE apply link: only present when the program is VERIFIED and the
 *       link was verified for a current or upcoming cycle. A link for an older cycle is never returned here.</li>
 * </ul>
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
        String officialAdmissionsUrl,
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
