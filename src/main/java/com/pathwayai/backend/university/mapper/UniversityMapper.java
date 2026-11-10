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
        return new ProgramSummaryResponse(
                program.getSlug(),
                program.getName(),
                program.getDegreeLevel(),
                program.getFieldOfStudy(),
                program.getDurationMonths(),
                program.getTeachingLanguage());
    }
}
