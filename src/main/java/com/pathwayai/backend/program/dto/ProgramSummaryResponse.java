package com.pathwayai.backend.program.dto;

import com.pathwayai.backend.program.entity.DegreeLevel;

/**
 * Program row embedded in the university detail response. {@code identifier} (additive, non-breaking) is the
 * key for {@code GET /api/v1/programs/{identifier}}.
 */
public record ProgramSummaryResponse(String identifier, String slug, String name, DegreeLevel degreeLevel,
                                     String fieldOfStudy, Integer durationMonths, String teachingLanguage) {
}
