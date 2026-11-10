package com.pathwayai.backend.program.dto;

import com.pathwayai.backend.program.entity.DegreeLevel;

public record ProgramSummaryResponse(String slug, String name, DegreeLevel degreeLevel, String fieldOfStudy,
                                     Integer durationMonths, String teachingLanguage) {
}
