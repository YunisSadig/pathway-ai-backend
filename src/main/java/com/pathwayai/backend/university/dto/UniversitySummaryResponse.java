package com.pathwayai.backend.university.dto;

public record UniversitySummaryResponse(String slug, String name, String city, String countryCode,
                                        String countryName, String websiteUrl) {
}
