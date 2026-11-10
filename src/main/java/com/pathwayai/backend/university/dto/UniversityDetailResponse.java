package com.pathwayai.backend.university.dto;

import com.pathwayai.backend.program.dto.ProgramSummaryResponse;
import java.util.List;

public record UniversityDetailResponse(String slug, String name, String city, String countryCode,
                                       String countryName, String websiteUrl, String description,
                                       List<ProgramSummaryResponse> programs) {
}
