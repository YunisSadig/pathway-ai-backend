package com.pathwayai.backend.university.mapper;

import com.pathwayai.backend.program.dto.ProgramSummaryResponse;
import com.pathwayai.backend.program.entity.DegreeProgram;
import com.pathwayai.backend.university.dto.UniversityDetailResponse;
import com.pathwayai.backend.university.dto.UniversitySummaryResponse;
import com.pathwayai.backend.university.entity.University;
import java.util.Comparator;
import org.springframework.stereotype.Component;

@Component
public class UniversityMapper {

    public UniversitySummaryResponse toSummary(University university) {
        return new UniversitySummaryResponse(
                university.getSlug(),
                university.getName(),
                university.getCity(),
                university.getCountry().getIso2Code(),
                university.getCountry().getName(),
                university.getWebsiteUrl());
    }

    public UniversityDetailResponse toDetail(University university) {
        return new UniversityDetailResponse(
                university.getSlug(),
                university.getName(),
                university.getCity(),
                university.getCountry().getIso2Code(),
                university.getCountry().getName(),
                university.getWebsiteUrl(),
                university.getDescription(),
                university.getPrograms().stream()
                        .sorted(Comparator.comparing(DegreeProgram::getName))
                        .map(this::toProgramSummary)
                        .toList());
    }

    private ProgramSummaryResponse toProgramSummary(DegreeProgram program) {
        // The normalised academic field wins; the legacy free-text column is only a fallback.
        String field = program.getAcademicField() != null
                ? program.getAcademicField().getName()
                : program.getFieldOfStudy();
        return new ProgramSummaryResponse(
                program.getIdentifier(),
                program.getSlug(),
                program.getName(),
                program.getDegreeLevel(),
                field,
                program.getDurationMonths(),
                program.getTeachingLanguage());
    }
}
